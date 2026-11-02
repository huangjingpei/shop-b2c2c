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
