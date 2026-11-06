/*
 * 文件：MerchantAgreementVersionMapper.java
 * 用途：协议版本查询与发布状态维护。
 * 维护注意：
 * 1. findPublishedByType 只返回 status='PUBLISHED' 且已到生效时间的版本，按生效时间倒序，取第一条即当前生效；
 * 2. 发布新版本时由服务层在事务内先下线同类型旧版本，再插入新版本，保证"同类型仅一条当前生效"；
 * 3. 不提供 DELETE；下线只迁移状态为 OFFLINE，保留历史版本供既有同意记录对账。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantAgreementVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MerchantAgreementVersionMapper extends BaseMapper<MerchantAgreementVersion> {

    @Select("SELECT * FROM merchant_agreement_version WHERE protocol_type = #{protocolType} "
            + "AND status = 'PUBLISHED' AND effective_from <= NOW() "
            + "ORDER BY effective_from DESC, id DESC")
    List<MerchantAgreementVersion> findPublishedByType(@Param("protocolType") String protocolType);

    @Select("SELECT * FROM merchant_agreement_version WHERE status = 'PUBLISHED' AND effective_from <= NOW() "
            + "ORDER BY protocol_type, effective_from DESC, id DESC")
    List<MerchantAgreementVersion> findAllPublished();

    @Select("SELECT * FROM merchant_agreement_version WHERE protocol_type = #{protocolType} "
            + "AND version = #{version} LIMIT 1")
    MerchantAgreementVersion findByTypeAndVersion(@Param("protocolType") String protocolType,
                                                  @Param("version") String version);

    @Update("UPDATE merchant_agreement_version SET status = 'OFFLINE' "
            + "WHERE protocol_type = #{protocolType} AND status = 'PUBLISHED'")
    int offlinePublishedByType(@Param("protocolType") String protocolType);
}
