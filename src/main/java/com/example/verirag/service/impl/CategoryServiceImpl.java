package com.example.verirag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.verirag.common.ResultCode;
import com.example.verirag.dto.CategorySaveRequest;
import com.example.verirag.entity.Category;
import com.example.verirag.entity.Document;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.CategoryMapper;
import com.example.verirag.mapper.DocumentMapper;
import com.example.verirag.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final DocumentMapper documentMapper;

    @Override
    public List<Category> listAll() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId));
    }

    @Override
    public void save(CategorySaveRequest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Category name must not be blank");
        }

        Category category = new Category();
        category.setId(req.getId());
        category.setName(req.getName().trim());
        category.setDescription(trimToNull(req.getDescription()));
        category.setIcon(trimToNull(req.getIcon()));
        category.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());

        if (category.getId() == null) {
            categoryMapper.insert(category);
            return;
        }

        if (categoryMapper.selectById(category.getId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Category not found");
        }
        categoryMapper.updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Category id must not be null");
        }
        if (categoryMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Category not found");
        }
        //某个分类下已经有文档 则不允许删除
        Long documentCount = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getCategoryId, id));
        if (documentCount != null && documentCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "This category still contains documents and cannot be deleted");
        }
        categoryMapper.deleteById(id);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
