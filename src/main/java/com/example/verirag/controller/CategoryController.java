package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.dto.CategorySaveRequest;
import com.example.verirag.entity.Category;
import com.example.verirag.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库分类。
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 全部分类（树前扁平列表），登录用户可查。
     */
    @GetMapping
    public R<List<Category>> list() {
        return R.ok(categoryService.listAll());
    }

    /**
     * 新增或更新分类（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody CategorySaveRequest req) {
        categoryService.save(req);
        return R.ok();
    }

    /**
     * 删除分类（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
