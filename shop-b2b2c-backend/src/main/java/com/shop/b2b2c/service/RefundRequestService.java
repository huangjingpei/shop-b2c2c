/*
 * 文件：RefundRequestService.java
 * 用途：F05 买家退款申请闭环：买家申请 → 商家审批（同意执行原路退款 / 拒绝）→ 买家撤回。
 * 维护注意：
 *  1. 买家身份凭 orderSn+contact+queryToken 校验，复用 BuyerOrderQueryService 的凭证匹配规则；不依赖登录态。
 *  2. 可退余额 = pay_amount − 已发起(PROCESSING)+已成功(SUCCESS)退款累计，避免超额退款；全额退款时订单最终由 trade_refund 流转置 REFUNDED 并回退库存。
 *  3. 审批同意直接调用 RefundService.applyOrderRefund 走渠道原路退回，申请单置 APPROVED；拒绝/撤回为终态，不生成 trade_refund。
 *  4. 同一订单同一时间只允许一个 PENDING 申请，避免重复审批。
 */
package com.shop.b2b2c.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.BuyerLookupRequest;
import com.shop.b2b2c.dto.RefundRequestDTOs;
import com.shop.b2b2c.entity.RefundRequest;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.RefundRequestMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.payment.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RefundRequestService {

    private static final Set<String> REFUNDABLE_STATUSES = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final Set<String> NON_REFUNDABLE_REFUND_STATUSES = Set.of("FAILED");

    private final RefundRequestMapper requests;
    private final TradeOrderMapper orders;
    private final TradeRefundMapper refunds;
    private final RefundService refundService;

    @Transactional
    public RefundRequest apply(RefundRequestDTOs.BuyerApplyRequest req) {
        TradeOrder order = orders.findByOrderSnAndBuyerContact(req.orderSn(), req.contact())
                .orElseThrow(() -> new BusinessException(404, "订单不存在或联系信息不匹配"));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, "查询凭证无效");
        }
        if (!REFUNDABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new BusinessException(409, "当前订单状态不支持申请退款");
        }
        BigDecimal amount = req.refundAmount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new BusinessException(400, "退款金额不合法");
        }
        BigDecimal refundable = refundableAmount(order);
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException(400, "退款金额超过可退余额 " + refundable.toPlainString());
        }
        // 同一订单只允许一个待审批申请
        Long pending = requests.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RefundRequest>()
                .eq(RefundRequest::getOrderId, order.getId())
                .eq(RefundRequest::getStatus, RefundRequest.STATUS_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException(409, "该订单已有待审批的退款申请，请等待商家处理");
        }
        RefundRequest rr = new RefundRequest();
        rr.setOrderId(order.getId());
        rr.setOrderSn(order.getOrderSn());
        rr.setMerchantId(order.getMerchantId());
        rr.setBuyerContact(req.contact());
        rr.setRefundAmount(amount);
        rr.setReason(req.reason().trim());
        rr.setStatus(RefundRequest.STATUS_PENDING);
        rr.setAppliedAt(LocalDateTime.now());
        requests.insert(rr);
        return rr;
    }

    @Transactional
    public RefundRequest approve(Long requestId, Long merchantId, Long operatorId, String operatorRole) {
        RefundRequest rr = requireMine(requestId, merchantId);
        if (!RefundRequest.STATUS_PENDING.equals(rr.getStatus())) {
            throw new BusinessException(409, "该申请已处理，无法重复审批");
        }
        // 二次校验可退余额（期间可能有其他退款）
        TradeOrder order = orders.selectById(rr.getOrderId());
        if (order == null) throw new BusinessException(404, "订单不存在");
        if (rr.getRefundAmount().compareTo(refundableAmount(order)) > 0) {
            throw new BusinessException(400, "可退余额不足，请让买家重新申请");
        }
        refundService.applyOrderRefund(rr.getMerchantId(), rr.getOrderSn(),
                new com.shop.b2b2c.dto.RefundDTOs.RefundRequest(rr.getRefundAmount(), rr.getReason()),
                "APPROVE-" + String.format("%032d", rr.getId()), operatorId);
        rr.setStatus(RefundRequest.STATUS_APPROVED);
        rr.setDecidedAt(LocalDateTime.now());
        rr.setDecidedBy(operatorId);
        rr.setDecidedRole(operatorRole);
        requests.updateById(rr);
        return rr;
    }

    @Transactional
    public RefundRequest reject(Long requestId, Long merchantId, Long operatorId, String operatorRole, String rejectReason) {
        RefundRequest rr = requireMine(requestId, merchantId);
        if (!RefundRequest.STATUS_PENDING.equals(rr.getStatus())) {
            throw new BusinessException(409, "该申请已处理，无法重复审批");
        }
        rr.setStatus(RefundRequest.STATUS_REJECTED);
        rr.setRejectReason(rejectReason == null ? null : rejectReason.trim());
        rr.setDecidedAt(LocalDateTime.now());
        rr.setDecidedBy(operatorId);
        rr.setDecidedRole(operatorRole);
        requests.updateById(rr);
        return rr;
    }

    @Transactional
    public RefundRequest cancel(Long requestId, String orderSn, String contact, String queryToken) {
        RefundRequest rr = requests.selectById(requestId);
        if (rr == null) throw new BusinessException(404, "退款申请不存在");
        TradeOrder order = orders.selectById(rr.getOrderId());
        if (order == null || !order.getOrderSn().equals(orderSn) || !order.getBuyerContact().equals(contact)
                || !BuyerLookupRequest.matches(order.getQueryToken(), queryToken)) {
            throw new BusinessException(400, "订单信息或凭证无效");
        }
        if (!RefundRequest.STATUS_PENDING.equals(rr.getStatus())) {
            throw new BusinessException(409, "该申请已处理，无法撤回");
        }
        rr.setStatus(RefundRequest.STATUS_CANCELLED);
        rr.setDecidedAt(LocalDateTime.now());
        requests.updateById(rr);
        return rr;
    }

    public IPage<RefundRequest> listForMerchant(Long merchantId, String status, int page, int size) {
        Page<RefundRequest> p = Page.of(page, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RefundRequest> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RefundRequest>()
                        .eq(merchantId != null, RefundRequest::getMerchantId, merchantId)
                        .eq(status != null && !status.isBlank(), RefundRequest::getStatus, status)
                        .orderByDesc(RefundRequest::getCreatedAt);
        // 列表不返回 buyer_contact 等敏感字段：select 指定列。
        wrapper.select(RefundRequest::getId, RefundRequest::getOrderId, RefundRequest::getOrderSn,
                RefundRequest::getMerchantId, RefundRequest::getRefundAmount, RefundRequest::getReason,
                RefundRequest::getStatus, RefundRequest::getRejectReason, RefundRequest::getAppliedAt,
                RefundRequest::getDecidedAt, RefundRequest::getDecidedBy, RefundRequest::getDecidedRole,
                RefundRequest::getCreatedAt, RefundRequest::getUpdatedAt);
        return requests.selectPage(p, wrapper);
    }

    public List<RefundRequest> listForBuyer(String orderSn, String contact, String queryToken) {
        TradeOrder order = orders.findByOrderSnAndBuyerContact(orderSn, contact)
                .orElseThrow(() -> new BusinessException(404, "订单不存在或联系信息不匹配"));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), queryToken)) {
            throw new BusinessException(400, "查询凭证无效");
        }
        return requests.findByOrderId(order.getId());
    }

    private RefundRequest requireMine(Long requestId, Long merchantId) {
        RefundRequest rr = requests.selectById(requestId);
        if (rr == null) throw new BusinessException(404, "退款申请不存在");
        // merchantId 为空表示超管，可跨店处理；否则必须为本店申请。
        if (merchantId != null && !rr.getMerchantId().equals(merchantId)) {
            throw new BusinessException(403, "无权处理该退款申请");
        }
        return rr;
    }

    /** 可退余额 = 实付 − 已发起+已成功退款累计（FAILED 不占额度）。 */
    private BigDecimal refundableAmount(TradeOrder order) {
        BigDecimal paid = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        List<TradeRefund> list = refunds.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getOrderId, order.getId())
                .notIn(TradeRefund::getStatus, NON_REFUNDABLE_REFUND_STATUSES));
        BigDecimal locked = list.stream()
                .map(r -> r.getRefundAmount() == null ? BigDecimal.ZERO : r.getRefundAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return paid.subtract(locked).max(BigDecimal.ZERO);
    }
}
