/*
 * 文件：PaymentCallbackAudit.java
 * 用途：F01 支付回调审计实体：逐条记录渠道异步通知的受理结果、拒绝原因与报文指纹。
 * 维护注意：
 *  1. 本表是审计与排障证据，不是资金账本；不得凭本表推断到账或履约，到账事实仍以 payment_event 为准。
 *  2. payload_sha256 为规范化输入的 SHA-256 指纹，只用于同源重试与排障关联；不得保存回调正文、签名、证书或买家身份。
 *  3. 本表不设外键：验签/解析失败的拒绝回调、订单不存在的回调也必须留痕，order_sn 与 pay_trade_no 允许为空。
 *  4. 只增不改：应用层只插入，不更新或删除既有审计行；数据清理仅由运维按留存策略执行。
 *  5. outcome 取值 ACCEPTED/REJECTED/ERROR；processing_result 只在受理成功时写支付事件状态（SUCCESS/DUPLICATE_PAYMENT 等）。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_callback_audit")
public class PaymentCallbackAudit {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 回调渠道 WECHAT/ALIPAY；未知或缺失时写 UNKNOWN。 */
    private String channel;
    /** 回调声明的内部订单号；验签/解析失败或未提供时为空。 */
    private String orderSn;
    /** 渠道流水号；验签/解析失败时通常为空。 */
    private String payTradeNo;
    /** 渠道侧商户号，仅作审计比对，不能替代平台内部 merchant_id。 */
    private String channelMerchantId;
    /** ACCEPTED 受理成功 / REJECTED 验签、归属或资金要素拒绝 / ERROR 未预期异常。 */
    private String outcome;
    /** 受理成功后的支付事件状态；拒绝或异常时为空。 */
    private String processingResult;
    /** 拒绝/异常原因摘要，写入前截断，不得包含报文正文与凭据。 */
    private String reason;
    /** 规范化回调输入的 SHA-256（64 位小写十六进制）。 */
    private String payloadSha256;
    /** 从进入网关到写入审计的耗时（毫秒）。 */
    private Integer durationMs;

    private LocalDateTime createdAt;
}
