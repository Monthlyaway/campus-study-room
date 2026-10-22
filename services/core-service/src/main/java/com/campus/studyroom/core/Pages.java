package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.util.*;

public final class Pages {
  private Pages() {}

  public static <T> PageData<T> of(List<T> items, int page, int size) {
    if (page < 1 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGE", "页码从1开始，每页1至100条");
    int start = (int) Math.min((long) (page - 1) * size, items.size());
    return new PageData<>(
        items.subList(start, Math.min(start + size, items.size())), page, size, items.size());
  }
}
