package com.campus.studyroom.repair;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class RepairPolicy {
    private RepairPolicy() {}
    static void requireState(RepairStatus actual, RepairStatus... allowed) {
        for (RepairStatus state : allowed) if (state == actual) return;
        throw RepairException.conflict("STATE_CONFLICT", "当前工单状态不允许此操作，请刷新后重试");
    }
    static void requireVersion(long actual, Long expected) {
        if (expected == null || expected < 0) throw new RepairException(400, "INVALID_ARGUMENT", "需要有效的 expectedVersion");
        if (actual != expected) throw RepairException.conflict("VERSION_CONFLICT", "数据已变化，请刷新后重试");
    }
    static void independentAcceptor(UUID actor, UUID repairer) {
        if (actor.equals(repairer)) throw RepairException.forbidden();
    }
    static String text(String value, int max, boolean required, String field) {
        String trimmed = value == null ? "" : value.trim();
        if ((required && trimmed.isEmpty()) || trimmed.codePointCount(0, trimmed.length()) > max)
            throw new RepairException(422, "INVALID_ARGUMENT", field + (required ? " 必须为 1–" : " 不得超过 ") + max + " 字");
        return trimmed;
    }
    static Instant assignmentDeadline(Instant submitted) { return submitted.plus(Duration.ofHours(24)); }
    static Instant workDeadline(Instant firstAssignment) { return firstAssignment.plus(Duration.ofHours(48)); }
    static Instant acceptanceDeadline(Instant completed) { return completed.plus(Duration.ofHours(48)); }
    static long retrySeconds(int attempts) { return attempts >= 8 ? 300 : Math.min(1L << Math.max(1, attempts), 300); }
    static void rating(Integer score) {
        if (score == null || score < 1 || score > 5) throw new RepairException(422, "INVALID_RATING", "评分必须是 1–5 的整数");
    }
    static boolean expectedResourceState(String operation, String state) {
        return (operation.equals("BLOCK") && state.equals("ACTIVE")) || (operation.equals("RELEASE") && state.equals("RELEASED"));
    }
}
