package com.example.verirag.common;

import lombok.Data;

/**
 * 统一 API 返回包装类。
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> {

    /** 状态码 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 载荷数据 */
    private T data;

    /**
     * 成功，无数据。
     */
    public static <T> R<T> ok() {
        return ok(null);
    }

    /**
     * 成功，携带数据。
     *
     * @param data 数据
     */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(ResultCode.OK.getCode());
        r.setMessage(ResultCode.OK.getMessage());
        r.setData(data);
        return r;
    }

    /**
     * 失败，使用枚举默认文案。
     *
     * @param rc 结果码
     */
    public static <T> R<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage());
    }

    /**
     * 失败，自定义文案。
     *
     * @param rc      结果码
     * @param message 覆盖提示
     */
    public static <T> R<T> fail(ResultCode rc, String message) {
        return fail(rc.getCode(), message);
    }

    /**
     * 失败。
     *
     * @param code    业务码
     * @param message 提示
     */
    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
