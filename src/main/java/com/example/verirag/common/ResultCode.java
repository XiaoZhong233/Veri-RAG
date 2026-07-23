package com.example.verirag.common;

import lombok.Getter;

/**
 * 业务状态码与提示文案枚举。
 */
@Getter
public enum ResultCode {
    /** Success */
    OK(200, "Success"),
    /** Invalid request parameters */
    BAD_REQUEST(400, "Invalid request parameters"),
    /** Not authenticated */
    UNAUTHORIZED(401, "Not authenticated or session has expired"),
    /** Access denied */
    FORBIDDEN(403, "Access denied"),
    /** Resource not found */
    NOT_FOUND(404, "Resource not found"),
    /** Internal server error */
    ERROR(500, "Internal server error");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
