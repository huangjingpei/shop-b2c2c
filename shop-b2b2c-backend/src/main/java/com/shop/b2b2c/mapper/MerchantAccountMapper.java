/*
 * 文件：MerchantAccountMapper.java
 * 用途：商家服务费账户数据访问（T34），提供按商户加锁读取与余额/欠费原子更新。
 * 维护注意：所有账户变更必须先 lockByMerchantId 获取行锁；禁止在无锁状态下先查后改。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface MerchantAccountMapper extends BaseMapper<MerchantAccount> {

    @Select("SELECT * FROM merchant_account WHERE merchant_id = #{merchantId} FOR UPDATE")
    MerchantAccount lockByMerchantId(@Param("merchantId") Long merchantId);

    default Optional<MerchantAccount> findByMerchantId(Long merchantId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantAccount>()
                .eq(MerchantAccount::getMerchantId, merchantId)));
    }
}
