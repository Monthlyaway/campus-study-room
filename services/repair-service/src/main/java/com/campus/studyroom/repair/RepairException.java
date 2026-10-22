package com.campus.studyroom.repair;

final class RepairException extends RuntimeException {
    final int status;
    final String code;
    RepairException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    static RepairException conflict(String code, String message) { return new RepairException(409, code, message); }
    static RepairException forbidden() { return new RepairException(403, "FORBIDDEN", "无此操作权限"); }
    static RepairException missing() { return new RepairException(404, "NOT_FOUND", "工单不存在或不可见"); }
}
