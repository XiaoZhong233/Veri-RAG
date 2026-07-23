package com.example.verirag.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果封装。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    /** 总记录数 */
    private long total;
    /** 当前页数据 */
    private List<T> records;

    /**
     * 构建分页对象。
     *
     * @param total   总数
     * @param records 列表
     */
    public static <T> PageResult<T> of(long total, List<T> records) {
        return new PageResult<>(total, records);
    }
}
