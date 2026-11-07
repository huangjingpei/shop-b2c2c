/*
 * 文件：CardConsistencyRow.java
 * 用途：F12 存量一致性核对的单行快照：卡密商品SKU的账面库存、在库未售/已售/已作废卡数、预占中数量与异常指纹计数。
 * 维护注意：数据来自只读聚合SQL，不含卡密正文/指纹；consistent由服务端按stockQuantity==availableCards填充，
 *           异常指纹(fingerprintUnavailable>0)不参与consistent判定，必须单列提示人工处置；本模型不支持自动修复漂移。
 *           全部Long字段按JacksonConfig序列化为JSON字符串（含数量字段），前端须按字符串处理，禁止用===与number比较。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

@Data
public class CardConsistencyRow {
    /** SKU编号。 */
    private Long skuId;
    /** 所属卡密商品编号。 */
    private Long productId;
    /** SKU账面可售库存（预占不扣账面，支付履约时才扣）。 */
    private Long stockQuantity;
    /** 在库未售卡数（status=0）。 */
    private Long availableCards;
    /** 已售卡数（status=1）。 */
    private Long soldCards;
    /** 已作废卡数（status=2）。 */
    private Long voidedCards;
    /** 当前HELD预占数量，仅展示参考，不参与一致性判定。 */
    private Long heldQuantity;
    /** 缺少明文或解密失败、无法参与导入去重的异常卡数。 */
    private Long fingerprintUnavailable;
    /** 服务端填充：账面库存与在库未售卡数是否一致；不一致须人工核对，禁止用导入/作废强行平账。 */
    private Boolean consistent;
}
