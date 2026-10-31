package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.Category;
import com.shop.b2b2c.repository.CategoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "商品类目 (Category)")
@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;

    @Operation(summary = "【公开】获取全部商品分类")
    @GetMapping("/api/v1/public/categories")
    public R<List<Category>> listCategories() {
        return R.ok(categoryRepository.findAllByOrderBySortOrderAsc());
    }

    @Operation(summary = "【超管】新增平台统一商品类目")
    @PostMapping("/api/v1/admin/categories")
    public R<Category> createCategory(@RequestBody Category category) {
        return R.ok("类目创建成功", categoryRepository.save(category));
    }
}
