package com.gathin.deviceinfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A closed allowlist, deliberately not a generic ACTION_SETTINGS/vendor-app resolver. */
final class SettingsNavigator {
    static final String PACKAGE = "com.android.settings";
    private static final List<String> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            PACKAGE + ".Settings",
            PACKAGE + ".homepage.SettingsHomepageActivity",
            PACKAGE + ".Settings$SettingsDashboardActivity"));
    private static final String LIMITATION = "仅尝试标准 Android 设置入口，不会回退到厂商简化设置。"
            + "如果厂商改写了标准组件，界面仍由固件决定；被删除的原生设置无法由本应用恢复。";

    private SettingsNavigator() { }

    enum Status { AVAILABLE, OPENED, MISSING, DISABLED, NOT_EXPORTED, NOT_SYSTEM, NON_STANDARD_TARGET, PERMISSION_DENIED, ERROR }

    interface Access {
        Status inspect(String className);
        Status launch(String className);
    }

    static final class Result {
        final boolean opened;
        final String message;
        Result(boolean opened, String message) {
            this.opened = opened;
            this.message = message;
        }
    }

    static List<String> candidates() { return CANDIDATES; }

    static boolean isStandardTarget(String targetActivity) {
        if (targetActivity == null || targetActivity.isEmpty()) return true; // Not an alias.
        String name = targetActivity.startsWith(".") ? PACKAGE + targetActivity : targetActivity;
        return CANDIDATES.contains(name);
    }

    static Status availability(String packageName, boolean system, boolean enabled,
                               boolean exported, boolean allowed) {
        if (!PACKAGE.equals(packageName) || !system) return Status.NOT_SYSTEM;
        if (!enabled) return Status.DISABLED;
        if (!exported) return Status.NOT_EXPORTED;
        if (!allowed) return Status.PERMISSION_DENIED;
        return Status.AVAILABLE;
    }

    static Result open(Access access) {
        StringBuilder attempts = new StringBuilder();
        for (String candidate : CANDIDATES) {
            Status status = access.inspect(candidate);
            if (status == Status.AVAILABLE) {
                status = access.launch(candidate);
                if (status == Status.OPENED) {
                    String history = attempts.length() == 0 ? "" : "先前尝试：\n" + attempts + "\n";
                    return new Result(true, "已请求打开标准组件：\n" + candidate + "\n\n" + history + LIMITATION);
                }
            }
            append(attempts, candidate, status);
        }
        return new Result(false, "无法打开标准 Android 设置。\n\n" + attempts + LIMITATION);
    }

    static String describe(Access access) {
        StringBuilder summary = new StringBuilder("标准设置入口检查\n\n");
        for (String candidate : CANDIDATES) append(summary, candidate, access.inspect(candidate));
        return summary.append(LIMITATION).toString();
    }

    private static void append(StringBuilder text, String candidate, Status status) {
        text.append(candidate).append("\n→ ").append(label(status)).append("\n\n");
    }

    private static String label(Status status) {
        if (status == null) return "检查或启动异常";
        switch (status) {
            case AVAILABLE: return "可尝试打开（不代表界面未被厂商修改）";
            case OPENED: return "已请求打开";
            case MISSING: return "未找到此组件";
            case DISABLED: return "组件或应用已禁用";
            case NOT_EXPORTED: return "组件未导出，外部应用不能打开";
            case NOT_SYSTEM: return "不是标准系统设置包，拒绝打开";
            case NON_STANDARD_TARGET: return "入口被重定向到非标准组件，拒绝打开";
            case PERMISSION_DENIED: return "系统权限限制，不能打开";
            default: return "检查或启动异常";
        }
    }
}
