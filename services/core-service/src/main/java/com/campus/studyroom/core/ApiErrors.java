package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> business(ApiException e) {
    var b = ResponseEntity.status(e.status);
    if (e.status == 429)
      b.header("Retry-After", Objects.toString(e.details.getOrDefault("retryAfter", 60)));
    return b.body(
        Map.of(
            "error",
            Map.of("code", e.code, "message", e.getMessage(), "details", e.details),
            "requestId",
            com.campus.studyroom.api.RequestIds.current().toString()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return business(new ApiException(400, "INVALID_REQUEST", "请求格式不正确，请检查输入"));
  }

  @ExceptionHandler({
    org.springframework.dao.DataIntegrityViolationException.class,
    jakarta.persistence.OptimisticLockException.class,
    org.springframework.dao.CannotAcquireLockException.class
  })
  ResponseEntity<?> conflict(Exception e) {
    return business(new ApiException(409, "DATA_CONFLICT", "记录已存在或被其他操作修改，请刷新重试"));
  }
}
