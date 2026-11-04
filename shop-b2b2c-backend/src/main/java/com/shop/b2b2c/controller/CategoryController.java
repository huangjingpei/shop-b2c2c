/*
 * 文件：CategoryController.java
 * 用途：公开类目列表及平台类目创建入口。
 * 维护注意：创建须依赖后台角色拦截；请求实体的ID/父子关系和字段校验尚待加强，不能把当前CRUD解释成完整类目治理。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.Category;
import com.shop.b2b2c.mapper.CategoryMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "商品类目 (Category)")
@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryMapper categoryMapper;

    @Operation(summary = "【公开】获取全部商品分类")
    @GetMapping("/api/v1/public/categories")
    public R<List<Category>> listCategories() {
        return R.ok(categoryMapper.findAllByOrderBySortOrderAsc());
    }

    @Operation(summary = "【超管】新增平台统一商品类目")
    @PostMapping("/api/v1/admin/categories")
    public R<Category> createCategory(@RequestBody Category category) {
        if (category == null || category.getName() == null || category.getName().isBlank()) {
            throw new BusinessException(400, "类目名称不能为空");
        }
        ManagementGuards.text(category.getName(), 64, true);
        ManagementGuards.text(category.getIcon(), 64, false);
        category.setId(null); // 明确新增语义：禁止携带ID覆盖既有类目
        if (category.getParentId() == null || category.getParentId() < 0) {
            category.setParentId(0L);
        }
        categoryMapper.insert(category);
        return R.ok("类目创建成功", category);
    }
}
