package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.Complaint;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {

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
                .eq(Complaint::getOrderId, orderId));
    }

    default Optional<Complaint> findByOrderSn(String orderSn) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderSn, orderSn)));
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
}
