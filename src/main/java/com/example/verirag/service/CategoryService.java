package com.example.verirag.service;

import com.example.verirag.dto.CategorySaveRequest;
import com.example.verirag.entity.Category;

import java.util.List;

/**
 * 知识库分类维护。
 */
public interface CategoryService {

    List<Category> listAll();

    void save(CategorySaveRequest req);

    void delete(Long id);
}
