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

    /** 事务内行锁：O2 用于串行化同店开店初始费率创建等并发写入；调用方必须处于事务中。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM merchant_store WHERE id = #{id} FOR UPDATE")
    MerchantStore lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET status = #{status} WHERE id = #{id}")
    int updateStatusOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                         @org.apache.ibatis.annotations.Param("status") Integer status);

    /** O4 开通闸门 CAS：仅待开通(0)可置营业(1)，返回 0 表示已被并发开通或状态已变化，调用方必须重读判定。 */
    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET status = 1 WHERE id = #{id} AND status = 0")
    int openIfPending(@org.apache.ibatis.annotations.Param("id") Long id);

    /**
     * O6 状态机 CAS：仅当当前状态在 expected 集合内才迁移到 newStatus；
     * 影响 0 行表示并发或非法流转，调用方必须抛 409，禁止据此静默成功。
     */
    @org.apache.ibatis.annotations.Update("<script>UPDATE merchant_store SET status = #{newStatus} WHERE id = #{id} AND status IN "
            + "<foreach collection='expected' item='s' open='(' separator=',' close=')'>#{s}</foreach></script>")
    int updateStatusCas(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("expected") java.util.List<Integer> expected,
                        @org.apache.ibatis.annotations.Param("newStatus") Integer newStatus);

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

    /**
     * O9：找指定到期日（身份证或营业执照任一命中）且未注销的店铺；
     * 用于资质到期 60/30/7 天提醒节点扫描，是否命中具体资质由调用方逐字段比对。
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM merchant_store WHERE status <> 4 "
            + "AND (id_card_expiry = #{date} OR license_expiry = #{date}) ORDER BY id LIMIT #{limit}")
    java.util.List<MerchantStore> findExpiringOn(@org.apache.ibatis.annotations.Param("date") java.time.LocalDate date,
                                                 @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * O9：找资质过期超过阈值（expiry &lt; threshold，阈值=今天-30天）且仍在营业(1)/打烊(2)的店铺；
     * 仅用于逾期 30 天自动冻结任务，冻结走 MerchantStoreStatusService.applyAction 审计留痕。
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM merchant_store WHERE status IN (1,2) "
            + "AND ((id_card_expiry IS NOT NULL AND id_card_expiry &lt; #{threshold}) "
            + "OR (license_expiry IS NOT NULL AND license_expiry &lt; #{threshold})) ORDER BY id LIMIT #{limit}")
    java.util.List<MerchantStore> findExpiredBeyond(@org.apache.ibatis.annotations.Param("threshold") java.time.LocalDate threshold,
                                                    @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * O9 商家更新资质有效期：COALESCE 定向更新，传 null 的列保持不变（两个资质可分别更新）；
     * 不允许清空已有有效期（“改为长期”需平台人工处理），调用方须先校验非空值为未来日期。
     */
    @org.apache.ibatis.annotations.Update("UPDATE merchant_store SET "
            + "id_card_expiry = COALESCE(#{idCardExpiry}, id_card_expiry), "
            + "license_expiry = COALESCE(#{licenseExpiry}, license_expiry) WHERE id = #{id}")
    int updateQualificationExpiries(@org.apache.ibatis.annotations.Param("id") Long id,
                                    @org.apache.ibatis.annotations.Param("idCardExpiry") java.time.LocalDate idCardExpiry,
                                    @org.apache.ibatis.annotations.Param("licenseExpiry") java.time.LocalDate licenseExpiry);
}
