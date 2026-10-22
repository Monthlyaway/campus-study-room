package com.campus.studyroom.repair;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class RepairErrors {
  private static final Logger log = LoggerFactory.getLogger(RepairErrors.class);

  private ResponseEntity<?> error(int status, String code, String message, HttpServletRequest req) {
    org.slf4j.MDC.put("errorCode", code);
    Object requestId = req.getAttribute("repairRequestId");
    if (requestId == null) requestId = UUID.randomUUID();
    if (status == 403 || status == 404)
      log.warn("event=repair_access_denied requestId={} errorCode={}", requestId, code);
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "error",
                Map.of("code", code, "message", message, "details", Map.of()),
                "requestId",
                requestId));
  }

  @ExceptionHandler(RepairException.class)
  ResponseEntity<?> business(RepairException ex, HttpServletRequest req) {
    return error(ex.status, ex.code, ex.getMessage(), req);
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MissingRequestValueException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception ex, HttpServletRequest req) {
    return error(400, "INVALID_ARGUMENT", "请求格式不正确，请检查字段和请求编号", req);
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    PessimisticLockingFailureException.class
  })
  ResponseEntity<?> concurrency(Exception ex, HttpServletRequest req) {
    return error(409, "VERSION_CONFLICT", "数据已变化或正在处理，请刷新后重试", req);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> constraint(DataIntegrityViolationException ex, HttpServletRequest req) {
    return error(409, "DATA_CONFLICT", "请求与现有记录冲突，请刷新后重试", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex, HttpServletRequest req) {
    log.error(
        "event=repair_unexpected_error requestId={} exceptionType={}",
        req.getAttribute("repairRequestId"),
        ex.getClass().getSimpleName());
    return error(500, "INTERNAL_ERROR", "服务暂时无法完成请求，请保留请求编号", req);
  }
}
