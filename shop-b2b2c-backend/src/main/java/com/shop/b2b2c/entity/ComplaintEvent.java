/*
 * 文件：ComplaintEvent.java
 * 用途：F06 投诉工单的追加式操作记录（提交/答复/介入/裁决），用于满足“不可覆盖的操作记录”要求。
 * 维护注意：只允许 INSERT，不提供更新或删除接口；actor_id 为登录用户ID，买家匿名操作时为 null，
 * actor_role 标记 BUYER/MERCHANT_ADMIN/SUPERADMIN。note/evidence_images 保存当次快照，含买家举证，不对外公开。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("complaint_event")
public class ComplaintEvent {

    public static final String TYPE_SUBMITTED = "SUBMITTED";
    public static final String TYPE_MERCHANT_REPLIED = "MERCHANT_REPLIED";
    public static final String TYPE_ESCALATED = "ESCALATED";
    public static final String TYPE_RESOLVED = "RESOLVED";
    public static final String TYPE_REJECTED = "REJECTED";

    public static final String ACTOR_BUYER = "BUYER";
    public static final String ACTOR_MERCHANT = "MERCHANT_ADMIN";
    public static final String ACTOR_SUPERADMIN = "SUPERADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long complaintId;
    private String eventType;
    private String statusAfter;
    private String actorRole;
    private Long actorId;
    private String actorName;
    private String note;
    private String evidenceImages;
    private LocalDateTime createdAt;
}
