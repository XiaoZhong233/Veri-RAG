package com.example.verirag.exception;


import com.example.verirag.common.R;
import com.example.verirag.common.ResultCode;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBiz(BusinessException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验（@Valid）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg.isEmpty() ? ResultCode.BAD_REQUEST.getMessage() : msg);
    }

    /**
     * 其它未捕获异常。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleAny(Exception e) {
        return R.fail(ResultCode.ERROR, e.getMessage() != null ? e.getMessage() : ResultCode.ERROR.getMessage());
    }
}
