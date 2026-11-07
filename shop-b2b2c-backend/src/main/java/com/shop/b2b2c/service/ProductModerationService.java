/*
 * 文件：ProductModerationService.java
 * 用途：F11 平台商品违规处置服务：违规下架(TAKEDOWN)与审核恢复(RESTORE)的事务编排、参数校验与审计落库。
 * 维护注意：
 *  1. 违规下架允许从 status 0(已下架) 或 1(在售) 发起（0 时防止商家自行重新上架）；恢复仅允许从 2(违规下架)。
 *  2. RESTORE 恢复目标状态 = 最近一次 TAKEDOWN 审计的 from_status（撤销处置回到原状态），无记录兜底 0(已下架)。
 *  3. 状态更新走带条件 CAS（0 行→409），审计与状态更新必须在同一事务；平台处置不套用商家 HELD 预占闸门，
 *     违规处置须即时生效，已付款订单照常履约。
 *  4. 商家在 status=2 期间仍可编辑资料以便整改（编辑接口不禁 status=2），但商家上下架接口已拒绝 status=2；
 *     公开列表/详情已按 status=1 过滤，下架即对买家不可见。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.ProductModerationDTOs;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductViolationAudit;
import com.shop.b2b2c.mapper.ProductModerationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductModerationService {

    /** 恢复时缺省审核备注（审计 reason 非空约束要求）。 */
    public static final String DEFAULT_RESTORE_NOTE = "平台审核通过，恢复商品";

    private final ProductModerationMapper moderation;

    /** 管理端商品分页查询（join 店铺名）。 */
    public Map<String, Object> list(Long merchantId, Integer status, String keyword, int page, int size) {
        if (merchantId != null && merchantId <= 0) {
            throw new BusinessException(400, "店铺编号不正确");
        }
        if (status != null && status != 0 && status != 1 && status != 2) {
            throw new BusinessException(400, "商品状态只能是 0、1 或 2");
        }
        if (page < 1) {
            throw new BusinessException(400, "page 必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(400, "size 必须在 1 到 100 之间");
        }
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && kw.isEmpty()) {
            kw = null;
        }
        if (kw != null && kw.length() > 64) {
            throw new BusinessException(400, "关键词最长 64 个字符");
        }
        List<ProductModerationDTOs.AdminProductRow> rows =
                moderation.listAdminProducts(merchantId, status, kw, (page - 1) * size, size);
        long total = moderation.countAdminProducts(merchantId, status, kw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /** 违规下架：从 0/1 → 2，写处置原因与下架时间，同事务落审计。 */
    @Transactional(rollbackFor = Exception.class)
    public Product takedown(Long id, String reason, Long operatorId) {
        String text = requireReason(reason);
        Product product = requireProduct(id);
        int from = product.getStatus() == null ? -1 : product.getStatus();
        if (from == 2) {
            throw new BusinessException(409, "商品已处于违规下架状态");
        }
        if (from != 0 && from != 1) {
            throw new BusinessException(409, "商品当前状态不允许违规下架");
        }
        if (moderation.takedownCas(id, from, text) == 0) {
            throw new BusinessException(409, "商品状态已变化，请刷新后重试");
        }
        ProductViolationAudit audit = audit(id, product.getMerchantId(), "TAKEDOWN", from, 2, text, operatorId);
        moderation.insertAudit(audit);
        return moderation.findById(id);
    }

    /** 审核恢复：从 2 → 最近一次 TAKEDOWN 的 from_status（无记录兜底 0），清空处置快照，同事务落审计。 */
    @Transactional(rollbackFor = Exception.class)
    public Product restore(Long id, String note, Long operatorId) {
        String text = optionalNote(note, DEFAULT_RESTORE_NOTE);
        Product product = requireProduct(id);
        if (product.getStatus() == null || product.getStatus() != 2) {
            throw new BusinessException(409, "商品不是违规下架状态，无需恢复");
        }
        int to = 0;
        ProductViolationAudit latest = moderation.latestTakedown(id);
        if (latest != null && latest.getFromStatus() != null
                && (latest.getFromStatus() == 0 || latest.getFromStatus() == 1)) {
            to = latest.getFromStatus();
        }
        if (moderation.restoreCas(id, to) == 0) {
            throw new BusinessException(409, "商品状态已变化，请刷新后重试");
        }
        ProductViolationAudit audit = audit(id, product.getMerchantId(), "RESTORE", 2, to, text, operatorId);
        moderation.insertAudit(audit);
        return moderation.findById(id);
    }

    /** 商品处置记录（按时间倒序）。 */
    public List<ProductViolationAudit> audits(Long id, int limit) {
        requireProduct(id);
        if (limit < 1 || limit > 200) {
            throw new BusinessException(400, "limit 必须在 1 到 200 之间");
        }
        return moderation.listAudits(id, limit);
    }

    private Product requireProduct(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "商品编号不正确");
        }
        Product product = moderation.findById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        return product;
    }

    private String requireReason(String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > 255) {
            throw new BusinessException(400, "处置原因必填，最长 255 个字符");
        }
        return text;
    }

    private String optionalNote(String note, String fallback) {
        String text = note == null ? "" : note.trim();
        if (text.isEmpty()) {
            return fallback;
        }
        if (text.length() > 255) {
            throw new BusinessException(400, "审核备注最长 255 个字符");
        }
        return text;
    }

    private ProductViolationAudit audit(Long productId, Long merchantId, String action,
                                        int from, int to, String reason, Long operatorId) {
        ProductViolationAudit audit = new ProductViolationAudit();
        audit.setProductId(productId);
        audit.setMerchantId(merchantId);
        audit.setAction(action);
        audit.setFromStatus(from);
        audit.setToStatus(to);
        audit.setReason(reason);
        audit.setOperatorId(operatorId);
        return audit;
    }
}
