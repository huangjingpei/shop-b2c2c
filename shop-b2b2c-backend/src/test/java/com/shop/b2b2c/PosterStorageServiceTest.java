/*
 * 文件：PosterStorageServiceTest.java
 * 用途：代付海报落盘/读取的路径契约、文件名白名单、覆盖写入与越界拒绝回归。
 * 维护注意：使用临时目录与固定字节，不连接数据库或 Redis；只验证文件存储边界，不代表真实磁盘故障、共享目录或多实例部署已验收。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.service.PosterStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PosterStorageServiceTest {

    @TempDir
    Path tempDir;

    private PosterStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new PosterStorageService();
        ReflectionTestUtils.setField(storage, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(storage, "publicPrefix", "/api/v1/peer-pay/poster/image");
    }

    @Test
    void storesPngUnderHashedFileNameAndReturnsPublicPath() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        String path = storage.store("token-abc", png);
        assertTrue(path.matches("/api/v1/peer-pay/poster/image/[0-9a-f]{64}\\.png"), path);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        assertTrue(Files.isRegularFile(tempDir.resolve(fileName)));
        assertArrayEquals(png, storage.read(fileName));
        // 同一凭证重复生成覆盖同名文件，不产生第二份路径。
        assertEquals(path, storage.store("token-abc", png));
    }

    @Test
    void differentTokensDoNotShareFiles() {
        String first = storage.store("token-a", new byte[] {1});
        String second = storage.store("token-b", new byte[] {2});
        assertNotEquals(first, second);
    }

    @Test
    void invalidFileNamesAndMissingFilesAreNotFoundWithoutEchoingPath() {
        Set<String> invalid = new HashSet<>();
        invalid.add(null);
        invalid.add("");
        invalid.add("abc.png");
        invalid.add("../" + "a".repeat(64) + ".png");
        invalid.add("A".repeat(64) + ".png");
        invalid.add("a".repeat(64) + ".jpg");
        invalid.add("a".repeat(64) + ".png/../" + "b".repeat(64) + ".png");
        for (String name : invalid) {
            BusinessException ex = assertThrows(BusinessException.class, () -> storage.read(name));
            assertEquals(404, ex.getCode());
            assertFalse(ex.getMessage().contains("a".repeat(8)));
        }
        // 合法格式但文件不存在同样 404。
        BusinessException missing = assertThrows(BusinessException.class,
                () -> storage.read("a".repeat(64) + ".png"));
        assertEquals(404, missing.getCode());
    }

    @Test
    void blankTokenAndEmptyContentAreNotStored() {
        BusinessException blank = assertThrows(BusinessException.class, () -> storage.store(" ", new byte[] {1}));
        assertEquals(400, blank.getCode());
        assertNull(storage.store("token-empty", new byte[0]));
        assertNull(storage.store("token-null", null));
    }
}
