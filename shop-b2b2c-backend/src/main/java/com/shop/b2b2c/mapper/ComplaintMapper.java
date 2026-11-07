/*
 * 文件：ComplaintMapper.java
 * 用途：Complaint 领域持久化：按订单/店铺/状态查询，商家与超管分页列表（商家列表不返回 buyer_id）。
 * 维护注意：
 *  1. 投诉流转必须校验订单归属与参与方，不能跨店读取举证信息；普通 save 不是并发控制。
 *  2. `findActiveByOrderId` 只统计未终结工单，用于阻止同一订单重复提交；终态为 RESOLVED/REJECTED。
 *  3. 商家列表使用 select 指定列，避免把 buyer_id 等买家联系方式下发给非平台角色。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.b2b2c.entity.Complaint;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {

    Set<String> TERMINAL_STATUSES = Set.of(Complaint.STATUS_RESOLVED, Complaint.STATUS_REJECTED);

    default Optional<Complaint> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Complaint save(Complaint entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<Complaint> findByMerchantIdOrderByCreatedAtDesc(Long merchantId) {
        return selectList(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getMerchantId, merchantId)
                .orderByDesc(Complaint::getCreatedAt));
    }

    default List<Complaint> findByBuyerIdOrderByCreatedAtDesc(String buyerId) {
        return selectList(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getBuyerId, buyerId)
                .orderByDesc(Complaint::getCreatedAt));
    }

    default List<Complaint> findByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderId, orderId)
                .orderByDesc(Complaint::getCreatedAt));
    }

    default Optional<Complaint> findByOrderSn(String orderSn) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderSn, orderSn)
                .orderByDesc(Complaint::getCreatedAt)
                .last("LIMIT 1")));
    }

    default List<Complaint> findByStatusOrderByCreatedAtDesc(String status) {
        return selectList(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getStatus, status)
                .orderByDesc(Complaint::getCreatedAt));
    }

    default List<Complaint> findAllByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<Complaint>()
                .orderByDesc(Complaint::getCreatedAt));
    }

    /** 同一订单未终结的投诉数量（PENDING/MERCHANT_REPLIED/ARBITRATING）。 */
    default long countActiveByOrderId(Long orderId) {
        Long count = selectCount(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderId, orderId)
                .notIn(Complaint::getStatus, TERMINAL_STATUSES));
        return count == null ? 0L : count;
    }

    /** 商家/超管分页列表；merchantId 为空表示全平台。不返回 buyer_id。 */
    default IPage<Complaint> pageForOperator(Page<Complaint> page, Long merchantId, String status) {
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(merchantId != null, Complaint::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), Complaint::getStatus, status)
                .orderByDesc(Complaint::getCreatedAt);
        wrapper.select(Complaint::getId, Complaint::getOrderId, Complaint::getOrderSn,
                Complaint::getMerchantId, Complaint::getComplaintType, Complaint::getContent,
                Complaint::getEvidenceImages, Complaint::getMerchantReply, Complaint::getMerchantReplyTime,
                Complaint::getAdminArbitration, Complaint::getAdminArbitrationTime, Complaint::getStatus,
                Complaint::getRefundDecision, Complaint::getRefundAmount, Complaint::getCreatedAt,
                Complaint::getUpdatedAt);
        return selectPage(page, wrapper);
    }
}
