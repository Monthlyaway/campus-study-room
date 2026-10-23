package com.campus.studyroom.api;


public record ApiResponse<T>(T data, String requestId) {
  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data, RequestIds.current().toString());
  }
}
