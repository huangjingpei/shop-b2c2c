/*
 * 文件：ComplaintService.java
 * 用途：F06 投诉协商与平台仲裁闭环：买家提交（举证）→ 商家答复 → 买家申请平台介入 → 超管裁决（可联动原路退款）。
 * 维护注意：
 *  1. 买家身份凭 orderSn+contact+queryToken 校验，复用 BuyerLookupRequest 的凭证匹配；不依赖登录态，queryToken 不入库。
 *  2. 状态机只允许 PENDING→MERCHANT_REPLIED→ARBITRATING→RESOLVED/REJECTED；终态不可再答复或介入，全部流转集中在本服务。
 *  3. 每次流转写 complaint_event 追加快照（操作人、说明、举证），不提供更新/删除，保证操作记录不可覆盖。
 *  4. 裁决退款复用 RefundService.applyOrderRefund 原路退回；幂等键 COMPLAINT-{工单ID} 32 位零填充，
 *     金额不得超过可退余额（实付 − 未失败退款累计），渠道失败时整体事务回滚，工单不得先标 RESOLVED。
 *  5. 商家答复允许在 PENDING/MERCHANT_REPLIED 多次追加，只保留最新文本，历史文本进入事件表。
 */
package com.shop.b2b2c.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.BuyerLookupRequest;
import com.shop.b2b2c.dto.ComplaintDTOs;
import com.shop.b2b2c.dto.RefundDTOs;
import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.entity.ComplaintEvent;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.ComplaintEventMapper;
import com.shop.b2b2c.mapper.ComplaintMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.payment.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private static final Set<String> COMPLAINT_TYPES = Set.of("QUALITY", "FAKE", "SERVICE_NOT_DELIVERED", "OTHER");
    private static final Set<String> REFUNDABLE_ORDER_STATUSES = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final Set<String> NON_REFUNDABLE_REFUND_STATUSES = Set.of("FAILED");
    private static final int MAX_EVIDENCE = 5;
    private static final int MAX_EVIDENCE_URL_LENGTH = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ComplaintMapper complaints;
    private final ComplaintEventMapper events;
    private final TradeOrderMapper orders;
    private final TradeRefundMapper refunds;
    private final RefundService refundService;
    private final MerchantNotificationService notificationService;

    @Transactional
    public Complaint submit(ComplaintDTOs.BuyerSubmitRequest req) {
        TradeOrder order = orders.findByOrderSnAndBuyerContact(req.orderSn(), req.contact())
                .orElseThrow(() -> new BusinessException(404, "订单不存在或联系信息不匹配"));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, "查询凭证无效");
        }
        if (!REFUNDABLE_ORDER_STATUSES.contains(order.getOrderStatus())) {
            throw new BusinessException(409, "当前订单状态不支持发起投诉");
        }
        // F07：已完成订单超过售后受理期后不再接受新投诉；未完成/缺完成时间不拦截。
        if (!com.shop.b2b2c.common.OrderLifecycle.withinAfterSale(order, java.time.LocalDateTime.now())) {
            throw new BusinessException(409, "订单已超过售后受理期，无法发起投诉");
        }
        String type = req.complaintType().trim().toUpperCase(Locale.ROOT);
        if (!COMPLAINT_TYPES.contains(type)) {
            throw new BusinessException(400, "投诉类型不合法");
        }
        String content = req.content().trim();
        if (content.length() < 5) {
            throw new BusinessException(400, "投诉说明至少需要 5 个字");
        }
        String evidence = evidenceJson(req.evidenceImages());
        if (complaints.countActiveByOrderId(order.getId()) > 0) {
            throw new BusinessException(409, "该订单已有处理中的投诉，请等待商家答复或申请平台介入");
        }
        LocalDateTime now = LocalDateTime.now();
        Complaint c = new Complaint();
        c.setOrderId(order.getId());
        c.setOrderSn(order.getOrderSn());
        c.setBuyerId(req.contact());
        c.setMerchantId(order.getMerchantId());
        c.setComplaintType(type);
        c.setContent(content);
        c.setEvidenceImages(evidence);
        c.setStatus(Complaint.STATUS_PENDING);
        c.setRefundDecision(Complaint.DECISION_NONE);
        c.setRefundAmount(BigDecimal.ZERO);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        complaints.insert(c);
        events.insert(event(c, ComplaintEvent.TYPE_SUBMITTED, ComplaintEvent.ACTOR_BUYER, null, null, content, evidence, now));
        notificationService.notify(MerchantNotificationService.BIZ_COMPLAINT_SUBMITTED, c.getId(),
                MerchantNotificationService.CHANNEL_INAPP, "COMPLAINT_SUBMITTED",
                MerchantNotificationService.merchantRecipient(c.getMerchantId()),
                Map.of("orderSn", c.getOrderSn(), "complaintType", type));
        return c;
    }

    @Transactional
    public Complaint reply(Long id, Long merchantId, Long operatorId, String operatorName,
                           ComplaintDTOs.MerchantReplyRequest req) {
        Complaint c = requireMine(id, merchantId);
        if (!Complaint.STATUS_PENDING.equals(c.getStatus())
                && !Complaint.STATUS_MERCHANT_REPLIED.equals(c.getStatus())) {
            throw new BusinessException(409, "当前工单状态不接受商家答复");
        }
        String reply = req.reply().trim();
        String evidence = evidenceJson(req.evidenceImages());
        LocalDateTime now = LocalDateTime.now();
        c.setMerchantReply(reply);
        c.setMerchantReplyTime(now);
        c.setStatus(Complaint.STATUS_MERCHANT_REPLIED);
        c.setUpdatedAt(now);
        complaints.updateById(c);
        events.insert(event(c, ComplaintEvent.TYPE_MERCHANT_REPLIED, ComplaintEvent.ACTOR_MERCHANT,
                operatorId, operatorName, reply, evidence, now));
        return c;
    }

    @Transactional
    public Complaint escalate(Long id, ComplaintDTOs.BuyerEscalateRequest req) {
        Complaint c = complaints.selectById(id);
        if (c == null) throw new BusinessException(404, "投诉工单不存在");
        TradeOrder order = orders.selectById(c.getOrderId());
        if (order == null || !order.getOrderSn().equals(req.orderSn())
                || !order.getBuyerContact().equals(req.contact())
                || !BuyerLookupRequest.matches(order.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, "订单信息或凭证无效");
        }
        if (!Complaint.STATUS_PENDING.equals(c.getStatus())
                && !Complaint.STATUS_MERCHANT_REPLIED.equals(c.getStatus())) {
            throw new BusinessException(409, "当前工单状态不支持申请平台介入");
        }
        LocalDateTime now = LocalDateTime.now();
        c.setStatus(Complaint.STATUS_ARBITRATING);
        c.setUpdatedAt(now);
        complaints.updateById(c);
        String note = req.note() == null ? null : req.note().trim();
        events.insert(event(c, ComplaintEvent.TYPE_ESCALATED, ComplaintEvent.ACTOR_BUYER, null, null, note, null, now));
        notificationService.notify(MerchantNotificationService.BIZ_COMPLAINT_ESCALATED, c.getId(),
                MerchantNotificationService.CHANNEL_INAPP, "COMPLAINT_ESCALATED",
                MerchantNotificationService.roleRecipient("SUPERADMIN"),
                Map.of("orderSn", c.getOrderSn()));
        return c;
    }

    @Transactional
    public Complaint arbitrate(Long id, Long operatorId, ComplaintDTOs.ArbitrateRequest req) {
        Complaint c = complaints.selectById(id);
        if (c == null) throw new BusinessException(404, "投诉工单不存在");
        if (!Complaint.STATUS_ARBITRATING.equals(c.getStatus())) {
            throw new BusinessException(409, "工单尚未进入平台仲裁阶段");
        }
        String decision = req.decision().trim().toUpperCase(Locale.ROOT);
        if (!"RESOLVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new BusinessException(400, "裁决结果只能为 RESOLVE 或 REJECT");
        }
        String arbitration = req.arbitration().trim();
        BigDecimal amount = req.refundAmount() == null ? BigDecimal.ZERO : req.refundAmount();
        if (amount.signum() < 0 || amount.scale() > 2) {
            throw new BusinessException(400, "裁决退款金额不合法");
        }
        if ("REJECT".equals(decision) && amount.signum() > 0) {
            throw new BusinessException(400, "驳回裁决不能同时判定退款");
        }
        TradeOrder order = orders.selectById(c.getOrderId());
        if (order == null) throw new BusinessException(404, "订单不存在");
        String refundDecision = Complaint.DECISION_NONE;
        String status = Complaint.STATUS_REJECTED;
        if ("RESOLVE".equals(decision)) {
            status = Complaint.STATUS_RESOLVED;
            if (amount.signum() > 0) {
                if (!REFUNDABLE_ORDER_STATUSES.contains(order.getOrderStatus())) {
                    throw new BusinessException(409, "当前订单状态不支持退款");
                }
                BigDecimal refundable = refundableAmount(order);
                if (amount.compareTo(refundable) > 0) {
                    throw new BusinessException(400, "裁决退款金额超过可退余额 " + refundable.toPlainString());
                }
                String reason = ("投诉仲裁退款：" + arbitration);
                if (reason.length() > 256) reason = reason.substring(0, 256);
                refundService.applyOrderRefund(c.getMerchantId(), c.getOrderSn(),
                        new RefundDTOs.RefundRequest(amount, reason),
                        "COMPLAINT-" + String.format("%032d", c.getId()), operatorId);
                refundDecision = amount.compareTo(refundable) == 0
                        ? Complaint.DECISION_FULL_REFUND : Complaint.DECISION_PARTIAL_REFUND;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        c.setStatus(status);
        c.setAdminArbitration(arbitration);
        c.setAdminArbitrationTime(now);
        c.setRefundDecision(refundDecision);
        c.setRefundAmount(amount);
        c.setUpdatedAt(now);
        complaints.updateById(c);
        events.insert(event(c, "RESOLVE".equals(decision) ? ComplaintEvent.TYPE_RESOLVED : ComplaintEvent.TYPE_REJECTED,
                ComplaintEvent.ACTOR_SUPERADMIN, operatorId, null, arbitration, null, now));
        notificationService.notify(MerchantNotificationService.BIZ_COMPLAINT_DECIDED, c.getId(),
                MerchantNotificationService.CHANNEL_INAPP, "COMPLAINT_DECIDED",
                MerchantNotificationService.merchantRecipient(c.getMerchantId()),
                Map.of("orderSn", c.getOrderSn(), "status", status,
                        "refundAmount", amount.toPlainString()));
        return c;
    }

    public List<Complaint> listForBuyer(String orderSn, String contact, String queryToken) {
        TradeOrder order = orders.findByOrderSnAndBuyerContact(orderSn, contact)
                .orElseThrow(() -> new BusinessException(404, "订单不存在或联系信息不匹配"));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), queryToken)) {
            throw new BusinessException(400, "查询凭证无效");
        }
        return complaints.findByOrderId(order.getId());
    }

    public IPage<Complaint> listForOperator(Long merchantId, String status, int page, int size) {
        return complaints.pageForOperator(Page.of(page, size), merchantId, status);
    }

    public ComplaintDTOs.ComplaintDetail detailForBuyer(Long id, String orderSn, String contact, String queryToken) {
        Complaint c = complaints.selectById(id);
        if (c == null) throw new BusinessException(404, "投诉工单不存在");
        TradeOrder order = orders.selectById(c.getOrderId());
        if (order == null || !order.getOrderSn().equals(orderSn) || !order.getBuyerContact().equals(contact)
                || !BuyerLookupRequest.matches(order.getQueryToken(), queryToken)) {
            throw new BusinessException(400, "订单信息或凭证无效");
        }
        return detail(c);
    }

    public ComplaintDTOs.ComplaintDetail detailForMerchant(Long id, Long merchantId) {
        Complaint c = requireMine(id, merchantId);
        // 商家无需买家联系方式；列表与详情同口径不返回 buyer_id。
        c.setBuyerId(null);
        return detail(c);
    }

    public ComplaintDTOs.ComplaintDetail detailForAdmin(Long id) {
        Complaint c = complaints.selectById(id);
        if (c == null) throw new BusinessException(404, "投诉工单不存在");
        return detail(c);
    }

    private ComplaintDTOs.ComplaintDetail detail(Complaint c) {
        return new ComplaintDTOs.ComplaintDetail(c, events.findByComplaintIdOrderByCreatedAt(c.getId()));
    }

    private Complaint requireMine(Long id, Long merchantId) {
        Complaint c = complaints.selectById(id);
        if (c == null) throw new BusinessException(404, "投诉工单不存在");
        // merchantId 为空表示超管，可跨店；否则必须为本店工单。
        if (merchantId != null && !c.getMerchantId().equals(merchantId)) {
            throw new BusinessException(403, "无权处理该投诉工单");
        }
        return c;
    }

    private ComplaintEvent event(Complaint c, String type, String actorRole, Long actorId,
                                 String actorName, String note, String evidence, LocalDateTime now) {
        ComplaintEvent e = new ComplaintEvent();
        e.setComplaintId(c.getId());
        e.setEventType(type);
        e.setStatusAfter(c.getStatus());
        e.setActorRole(actorRole);
        e.setActorId(actorId);
        e.setActorName(actorName);
        e.setNote(note);
        e.setEvidenceImages(evidence);
        e.setCreatedAt(now);
        return e;
    }

    /** 举证 URL 校验 + JSON 序列化；空列表落 NULL，避免 H2/MySQL JSON 列对字符串二次编码。 */
    private String evidenceJson(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        if (urls.size() > MAX_EVIDENCE) {
            throw new BusinessException(400, "举证图片最多 " + MAX_EVIDENCE + " 条");
        }
        List<String> cleaned = new ArrayList<>();
        for (String url : urls) {
            String value = url == null ? "" : url.trim();
            if (value.length() > MAX_EVIDENCE_URL_LENGTH || value.chars().anyMatch(ch -> Character.isWhitespace((char) ch))
                    || !(value.startsWith("https://") || value.startsWith("http://"))) {
                throw new BusinessException(400, "举证图片只接受 http/https 链接，且不超过 "
                        + MAX_EVIDENCE_URL_LENGTH + " 字符");
            }
            cleaned.add(value);
        }
        try {
            return JSON.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new BusinessException(500, "举证内容序列化失败");
        }
    }

    /** 可退余额 = 实付 − 已发起+已成功退款累计（FAILED 不占额度），与 F05 退款申请同口径。 */
    private BigDecimal refundableAmount(TradeOrder order) {
        BigDecimal paid = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        List<TradeRefund> list = refunds.selectList(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getOrderId, order.getId())
                .notIn(TradeRefund::getStatus, NON_REFUNDABLE_REFUND_STATUSES));
        BigDecimal locked = list.stream()
                .map(r -> r.getRefundAmount() == null ? BigDecimal.ZERO : r.getRefundAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return paid.subtract(locked).max(BigDecimal.ZERO);
    }
}
