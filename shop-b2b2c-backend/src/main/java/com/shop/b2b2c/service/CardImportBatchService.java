/*
 * 文件：CardImportBatchService.java
 * 用途：本店成功导入批次、无卡密正文成员的有界游标查询。
 * 维护注意：商户来自已校验会话，详情先校验批次归属，外店/缺失均404；只读新批次，不为旧导入生成虚假操作人。状态为查询时状态，分页不是全库一致性快照。
 */
package com.shop.b2b2c.service;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.CardImportDTOs.*;
import com.shop.b2b2c.entity.CardImportBatch;
import com.shop.b2b2c.mapper.CardImportBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class CardImportBatchService {
    private final CardImportBatchMapper batches;
    public Page<CardImportBatch> list(Long merchant, Long sku, Long before, int limit) {
        validate(merchant, before, limit);
        if (sku != null && sku < 1) throw new BusinessException(400, "SKU编号不合法");
        var rows = batches.list(merchant, sku, before, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new Page<>(items, rows.size() > limit ? items.get(items.size() - 1).getId() : null);
    }
    public Page<Item> members(Long merchant, Long batch, Long before, int limit) {
        validate(merchant, before, limit);
        if (batch == null || batch < 1) throw new BusinessException(400, "批次编号不合法");
        if (batches.find(merchant, batch) == null) throw new BusinessException(404, "批次不存在或不属于本店");
        var rows = batches.members(merchant, batch, before, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new Page<>(items, rows.size() > limit ? items.get(items.size() - 1).cardId() : null);
    }
    private void validate(Long merchant, Long before, int limit) {
        if (merchant == null || merchant < 1) throw new BusinessException(403, "当前账号未绑定店铺");
        if ((before != null && before < 1) || limit < 1 || limit > 100) throw new BusinessException(400, "批次分页参数不合法");
    }
}
