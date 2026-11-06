/*
 * 文件：MerchantNotificationOutbox.java
 * 用途：O8 通知发件箱实体：开店各节点、店铺状态变更、审核 SLA 超时、证件到期提醒的统一落库记录。
 * 维护注意：
 * 1. biz_type/biz_id/channel/template_code 四元组唯一（uk_notification_event）＝“每节点只发一次”的兜底；
 *    重复插入由服务层捕获唯一冲突视为已通知，不得吞其它唯一冲突。
 * 2. status 语义：PENDING-待发送（SMS 通道启用后的排队态）；SENT-已送达（INAPP 落库即送达）；
 *    FAILED-重试超限终态；SKIPPED-通道禁用跳过（本期 SMS 默认禁用）。
 * 3. payloadJson 严禁包含密码、证件号、银行卡号；服务层对 password 类键 fail-fast。
 * 4. recipient 约定：申请人手机号 / MERCHANT:{店铺ID}（商家站内）/ ROLE:{角色码}（运营站内）。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_notification_outbox")
public class MerchantNotificationOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ONBOARDING_SUBMITTED/ONBOARDING_SUPPLEMENT/ONBOARDING_REJECTED/ONBOARDING_APPROVED/ONBOARDING_OPENED
     *  /LICENSE_EXPIRING/REVIEW_SLA_BREACH/STORE_STATUS_CHANGED。 */
    private String bizType;

    /** 业务事件ID：开店节点=开店审计行ID，店铺状态=店铺状态审计行ID，SLA=申请ID。 */
    private Long bizId;

    /** INAPP/SMS。 */
    private String channel;

    private String templateCode;

    /** 申请人手机号 / MERCHANT:{店铺ID} / ROLE:{角色码}。 */
    private String recipient;

    /** 通知内容快照；不含密码/证件/银行卡。 */
    private String payloadJson;

    /** PENDING/SENT/FAILED/SKIPPED。 */
    private String status;

    private Integer attempts;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
