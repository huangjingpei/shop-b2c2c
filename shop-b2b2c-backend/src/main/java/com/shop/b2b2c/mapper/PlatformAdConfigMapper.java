/*
 * 文件：PlatformAdConfigMapper.java
 * 用途：PlatformAdConfig领域的MyBatis基础持久化接口。
 * 维护注意：广告位置与启用配置读取；排序与启用不等于目标URL安全，业务层还需审核跳转目标。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PlatformAdConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PlatformAdConfigMapper extends BaseMapper<PlatformAdConfig> {

    default Optional<PlatformAdConfig> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default PlatformAdConfig save(PlatformAdConfig entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<PlatformAdConfig> findByAdLocationAndIsEnabledOrderBySortOrderDesc(String adLocation, Integer isEnabled) {
        return selectList(new LambdaQueryWrapper<PlatformAdConfig>()
                .eq(PlatformAdConfig::getAdLocation, adLocation)
                .eq(PlatformAdConfig::getIsEnabled, isEnabled)
                .orderByDesc(PlatformAdConfig::getSortOrder));
    }

    default Optional<PlatformAdConfig> findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc(String adLocation, Integer isEnabled) {
        List<PlatformAdConfig> list = findByAdLocationAndIsEnabledOrderBySortOrderDesc(adLocation, isEnabled);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
