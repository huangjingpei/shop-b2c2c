/*
 * 文件：OnboardingAttachmentServiceTest.java
 * 用途：S3 入驻证件附件服务的隔离用例：魔数识别、大小上限、私有目录路径穿越防护与落盘命名。
 * 维护注意：
 * 1. 使用临时目录与 MockMultipartFile，不启动 Spring、不连接数据库、不写生产私有目录。
 * 2. 断言只针对“随机文件名 + 根目录内”这一安全契约；若改为保留原始文件名或允许 ../ 路径，本用例必须失败。
 * 3. 真实文件备份、Nginx 静态暴露与超管下载链路仍由用户手动验证，Mockito 无法覆盖。
 * 4. 大小上限通过 ReflectionTestUtils 注入，避免依赖真实 application.yml。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantOnboardingAttachment;
import com.shop.b2b2c.mapper.MerchantOnboardingAttachmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OnboardingAttachmentServiceTest {

    private final MerchantOnboardingAttachmentMapper mapper = mock(MerchantOnboardingAttachmentMapper.class);

    @TempDir
    Path tempDir;

    private OnboardingAttachmentService service(long maxAttachmentBytes, long maxVideoBytes) {
        OnboardingAttachmentService service = new OnboardingAttachmentService(mapper);
        ReflectionTestUtils.setField(service, "attachmentDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxAttachmentBytes", maxAttachmentBytes);
        ReflectionTestUtils.setField(service, "maxVideoBytes", maxVideoBytes);
        return service;
    }

    private static MockMultipartFile png(String originalFilename) {
        byte[] content = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0};
        return new MockMultipartFile("file", originalFilename, "image/png", content);
    }

    @Test
    void storesUnderPrivateDirWithRandomNameAndMetadataOnly() throws Exception {
        OnboardingAttachmentService service = service(5 * 1024 * 1024, 20 * 1024 * 1024);

        MerchantOnboardingAttachment attachment =
                service.store(9L, "ID_CARD_FRONT", png("身份证人像面.png"));

        assertEquals("ID_CARD_FRONT", attachment.getKind());
        assertEquals("image/png", attachment.getContentType());
        assertTrue(attachment.getFileKey().startsWith("9/"));
        // 禁止保留用户原始文件名，避免路径穿越与覆盖。
        assertFalse(attachment.getFileKey().contains("身份证"));
        Path stored = tempDir.resolve(attachment.getFileKey());
        assertTrue(Files.isRegularFile(stored));
        assertTrue(stored.normalize().startsWith(tempDir.toAbsolutePath().normalize()));
        verify(mapper).insert(any(MerchantOnboardingAttachment.class));
    }

    @Test
    void rejectsDisguisedExecutableByMagicNumber() {
        OnboardingAttachmentService service = service(5 * 1024 * 1024, 20 * 1024 * 1024);
        MockMultipartFile exe = new MockMultipartFile("file", "id-card.png", "image/png",
                new byte[]{'M', 'Z', 0, 0, 0, 0, 0, 0});

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.store(9L, "ID_CARD_FRONT", exe));

        assertEquals(400, error.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void selfieVideoUsesDedicatedSizeLimit() {
        OnboardingAttachmentService service = service(5 * 1024 * 1024, 64);
        byte[] content = new byte[65];
        content[0] = (byte) 0x1A;
        content[1] = 0x45;
        content[2] = (byte) 0xDF;
        content[3] = (byte) 0xA3;
        MockMultipartFile video = new MockMultipartFile("file", "selfie.webm", "video/webm", content);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.store(9L, "FACE_VIDEO", video));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("实人认证视频"));
        verify(mapper, never()).insert(any());
    }

    @Test
    void loadRejectsPathTraversalOutsideAttachmentDir() {
        OnboardingAttachmentService service = service(5 * 1024 * 1024, 20 * 1024 * 1024);
        MerchantOnboardingAttachment attachment = new MerchantOnboardingAttachment();
        attachment.setFileKey("../outside.png");

        BusinessException error = assertThrows(BusinessException.class, () -> service.load(attachment));

        assertEquals(404, error.getCode());
    }

    @Test
    void loadRejectsUnknownKindValue() {
        OnboardingAttachmentService service = service(5 * 1024 * 1024, 20 * 1024 * 1024);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.store(9L, "ID_CARD", png("front.png")));

        assertEquals(400, error.getCode());
    }
}
