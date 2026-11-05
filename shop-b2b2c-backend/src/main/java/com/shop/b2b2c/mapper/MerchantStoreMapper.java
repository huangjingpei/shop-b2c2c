/*
 * 文件：MerchantStoreMapper.java
 * 用途：MerchantStore领域的MyBatis基础持久化接口。
 * 维护注意：店铺基础查询；普通商家必须限定会话店铺，公开展示使用白名单，状态变更影响交易资格。 既有店铺配置必须调用Only定向SQL；null可选字段保留，服务费显式清除单独控制。禁止为了Mock测试吞锁异常或退化查询；普通save不是并发控制。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantStore;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface MerchantStoreMapper extends BaseMapper<MerchantStore> {

    default Optional<MerchantStore> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET status = #{status} WHERE id = #{id}")
    int updateStatusOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                         @org.apache.ibatis.annotations.Param("status") Integer status);

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET commission_rate = #{rate} WHERE id = #{id}")
    int updateCommissionOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                             @org.apache.ibatis.annotations.Param("rate") java.math.BigDecimal rate);

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET "
            + "notice = COALESCE(#{notice}, notice), "
            + "logo_url = COALESCE(#{logoUrl}, logo_url), "
            + "contact_phone = COALESCE(#{contactPhone}, contact_phone), "
            + "custom_pay_qrcode = COALESCE(#{customPayQrcode}, custom_pay_qrcode) "
            + "WHERE id = #{id}")
    int updateProfileOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                          @org.apache.ibatis.annotations.Param("notice") String notice,
                          @org.apache.ibatis.annotations.Param("logoUrl") String logoUrl,
                          @org.apache.ibatis.annotations.Param("contactPhone") String contactPhone,
                          @org.apache.ibatis.annotations.Param("customPayQrcode") String customPayQrcode);

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET "
            + "monthly_fee = COALESCE(#{monthlyFee}, monthly_fee), "
            + "fee_free_until = CASE WHEN #{clearDate} THEN NULL ELSE COALESCE(#{deadline}, fee_free_until) END, "
            + "fee_status = COALESCE(#{feeStatus}, fee_status) WHERE id = #{id}")
    int updateServiceFeeOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                            @org.apache.ibatis.annotations.Param("monthlyFee") java.math.BigDecimal monthlyFee,
                            @org.apache.ibatis.annotations.Param("deadline") java.time.LocalDateTime deadline,
                            @org.apache.ibatis.annotations.Param("feeStatus") String feeStatus,
                            @org.apache.ibatis.annotations.Param("clearDate") boolean clearDate);

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET "
            + "payment_channel_type = COALESCE(#{channel}, payment_channel_type), "
            + "custom_pay_qrcode = COALESCE(#{qrcode}, custom_pay_qrcode) WHERE id = #{id}")
    int updatePaymentChannelOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                                @org.apache.ibatis.annotations.Param("channel") String channel,
                                @org.apache.ibatis.annotations.Param("qrcode") String qrcode);

    default MerchantStore save(MerchantStore entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default Optional<MerchantStore> findByStoreCode(String storeCode) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantStore>()
                .eq(MerchantStore::getStoreCode, storeCode)));
    }
}
