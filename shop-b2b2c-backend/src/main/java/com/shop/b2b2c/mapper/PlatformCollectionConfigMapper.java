/*
 * 文件：PlatformCollectionConfigMapper.java
 * 用途：平台代收全局总闸配置的数据访问（M1），只操作 id=1 的单行配置。
 * 维护注意：更新必须带 version 条件做乐观并发，返回 0 表示版本已变化；禁止按商家批量写配置。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PlatformCollectionConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformCollectionConfigMapper extends BaseMapper<PlatformCollectionConfig> {

    default PlatformCollectionConfig findSingleton() {
        return selectById(1L);
    }

    /**
     * 单行配置乐观更新：只有版本号匹配才写入，并自增版本；返回 0 时调用方必须重读后再决定。
     */
    @Update("UPDATE platform_collection_config SET enabled = #{enabled}, version = version + 1, "
            + "updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = 1 AND version = #{expectedVersion}")
    int updateSingleton(@Param("enabled") boolean enabled,
                        @Param("updatedBy") Long updatedBy,
                        @Param("expectedVersion") long expectedVersion);
}
