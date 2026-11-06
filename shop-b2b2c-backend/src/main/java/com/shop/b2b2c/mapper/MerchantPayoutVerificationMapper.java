/*
 * 文件：MerchantPayoutVerificationMapper.java
 * 用途：O5 打款验证单持久化：按账户统计尝试次数、行锁确认、过期扫描。
 * 维护注意：
 * 1. confirm/expire 必须用 lockById 行锁并在锁内校验 PENDING 与 expires_at，防并发重复确认。
 * 2. countByAccountId 驱动 attempt_no 递增（最多 3 次）；唯一键 uk_payout_account_attempt 兜底并发。
 * 3. 查询结果含 amount，仅供平台侧服务/控制器使用；商家端视图必须在服务层脱敏后再返回。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantPayoutVerification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MerchantPayoutVerificationMapper extends BaseMapper<MerchantPayoutVerification> {

    @Select("SELECT * FROM merchant_payout_verification WHERE id = #{id} FOR UPDATE")
    MerchantPayoutVerification lockById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM merchant_payout_verification WHERE account_id = #{accountId}")
    int countByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT COUNT(*) FROM merchant_payout_verification WHERE account_id = #{accountId} AND status = 'FAILED'")
    int countFailedByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT * FROM merchant_payout_verification WHERE account_id = #{accountId} "
            + "AND status = 'PENDING' ORDER BY id DESC LIMIT 1")
    MerchantPayoutVerification findPendingByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT * FROM merchant_payout_verification WHERE account_id = #{accountId} ORDER BY id DESC")
    List<MerchantPayoutVerification> findByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT * FROM merchant_payout_verification WHERE merchant_id = #{merchantId} ORDER BY id DESC")
    List<MerchantPayoutVerification> findByMerchantId(@Param("merchantId") Long merchantId);

    @Select("SELECT id FROM merchant_payout_verification WHERE status = 'PENDING' AND expires_at <= #{now} "
            + "ORDER BY id LIMIT #{limit}")
    List<Long> findExpiredPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
