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
