package com.campus.studyroom.api;

import java.util.List;

public record PageData<T>(List<T> items, int page, int size, long total) {}
