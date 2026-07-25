package com.example.verirag.exception;


import com.example.verirag.common.R;
import com.example.verirag.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBiz(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        return ResponseEntity.status(status != null ? status : HttpStatus.BAD_REQUEST)
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验（@Valid）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest()
                .body(R.fail(ResultCode.BAD_REQUEST.getCode(),
                        msg.isEmpty() ? ResultCode.BAD_REQUEST.getMessage() : msg));
    }

    /**
     * Multipart 在进入 Controller 前解析；单独处理以便前端得到可操作的上传限制提示。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(R.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "上传文件过大：单个文件最大 50MB，整个上传请求最大 55MB"));
    }

    /**
     * 其它未捕获异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleAny(Exception e) {
        log.error("Unhandled request exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.ERROR,
                        e.getMessage() != null ? e.getMessage() : ResultCode.ERROR.getMessage()));
    }
}
