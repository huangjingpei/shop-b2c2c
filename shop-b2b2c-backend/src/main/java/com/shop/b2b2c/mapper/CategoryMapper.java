/*
 * 文件：CategoryMapper.java
 * 用途：Category领域的MyBatis基础持久化接口。
 * 维护注意：平台类目基础查询与排序；层级合法性、引用约束及管理权限需业务层保证。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.Category;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    default Optional<Category> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Category save(Category entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<Category> findAllByOrderBySortOrderAsc() {
        return selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSortOrder));
    }
}
