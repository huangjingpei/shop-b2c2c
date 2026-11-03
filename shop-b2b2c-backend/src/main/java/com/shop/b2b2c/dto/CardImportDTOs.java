/*
 * 文件：CardImportDTOs.java
 * 用途：批次游标分页和成员状态响应白名单。
 * 维护注意：批次明细仅返回卡ID、当前状态和订单号，不返回卡密正文；状态可能随发卡/作废变化，关联关系保持原导入批次。游标对应最后一条已显示记录。
 */
package com.shop.b2b2c.dto;
import java.util.List;
public final class CardImportDTOs {
    private CardImportDTOs() {}
    public record Item(Long cardId, Integer status, String orderSn) {}
    public record Page<T>(List<T> items, Long nextBeforeId) {}
}
