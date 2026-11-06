/*
 * 文件：MerchantOnboardingAttachmentMapper.java
 * 用途：入驻证件附件元数据的持久化与按申请查询。
 * 维护注意：只查询元数据，不读取文件内容；按申请查询必须带 application_id，避免跨申请越权拿到附件记录。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantOnboardingAttachment;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MerchantOnboardingAttachmentMapper extends BaseMapper<MerchantOnboardingAttachment> {

    default List<MerchantOnboardingAttachment> findByApplicationId(Long applicationId) {
        return selectList(new LambdaQueryWrapper<MerchantOnboardingAttachment>()
                .eq(MerchantOnboardingAttachment::getApplicationId, applicationId)
                .orderByAsc(MerchantOnboardingAttachment::getId));
    }

    default MerchantOnboardingAttachment findByApplicationAndId(Long applicationId, Long id) {
        return selectOne(new LambdaQueryWrapper<MerchantOnboardingAttachment>()
                .eq(MerchantOnboardingAttachment::getApplicationId, applicationId)
                .eq(MerchantOnboardingAttachment::getId, id));
    }
}
