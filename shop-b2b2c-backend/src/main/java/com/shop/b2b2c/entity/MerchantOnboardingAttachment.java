/*
 * 文件：MerchantOnboardingAttachment.java
 * 用途：商家入驻证件附件的元数据记录；文件本体保存在应用私有目录，不在公开静态目录。
 * 维护注意：
 * 1. fileKey 是相对 attachment-dir 的随机文件名，禁止使用用户上传的原始文件名，防止路径穿越与覆盖；
 * 2. sha256 仅用于完整性校验与重复排查，不保存文件内容；
 * 3. 附件只能通过平台超管接口下载，不得向公开入驻接口回传文件本体或私有目录路径。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_onboarding_attachment")
public class MerchantOnboardingAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;

    /** ID_CARD_FRONT / ID_CARD_BACK / LICENSE / FACE_VIDEO / OTHER。 */
    private String kind;

    private String fileKey;

    private String contentType;

    private Long sizeBytes;

    private String sha256;

    private LocalDateTime createdAt;
}
