/*
 * 文件：RefundRequestMapper.java
 * 用途：F05 退款申请单 Mapper。提供按店铺/状态分页、按订单查申请、按 ID+商家锁定查询。
 * 维护注意：列表只返回非敏感字段，buyer_contact 不在列表接口返回；金额比较由服务层完成。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.b2b2c.entity.RefundRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RefundRequestMapper extends BaseMapper<RefundRequest> {

    @Select({
            "<script>",
            "SELECT id, order_id AS orderId, order_sn AS orderSn, merchant_id AS merchantId,",
            " refund_amount AS refundAmount, reason, status, reject_reason AS rejectReason,",
            " applied_at AS appliedAt, decided_at AS decidedAt, decided_by AS decidedBy, decided_role AS decidedRole,",
            " created_at AS createdAt, updated_at AS updatedAt",
            " FROM refund_request",
            " WHERE merchant_id = #{merchantId}",
            " <if test='status != null and status != \"\"'> AND status = #{status} </if>",
            " ORDER BY created_at DESC",
            "</script>"
    })
    IPage<RefundRequest> pageByMerchant(Page<RefundRequest> page,
                                        @Param("merchantId") Long merchantId,
                                        @Param("status") String status);

    @Select("SELECT id, order_id AS orderId, order_sn AS orderSn, merchant_id AS merchantId,"
            + " refund_amount AS refundAmount, reason, status, reject_reason AS rejectReason,"
            + " applied_at AS appliedAt, decided_at AS decidedAt, decided_by AS decidedBy, decided_role AS decidedRole,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM refund_request WHERE order_id = #{orderId} ORDER BY created_at DESC")
    List<RefundRequest> findByOrderId(@Param("orderId") Long orderId);
}
