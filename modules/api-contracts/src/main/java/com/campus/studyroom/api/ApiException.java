package com.campus.studyroom.api;

import java.util.Map;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;
  public final Map<String, Object> details;

  public ApiException(int status, String code, String message) {
    this(status, code, message, Map.of());
  }

  public ApiException(int status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public static ApiException notFound() {
    return new ApiException(404, "NOT_FOUND", "未找到该记录");
  }

  public static ApiException forbidden() {
    return new ApiException(403, "FORBIDDEN", "无此操作权限");
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(409, code, message);
  }
}
