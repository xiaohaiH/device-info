package com.gathin.deviceinfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Device-only routing. App restart is intentionally NOT a fallback route. */
final class ProgressiveReboot {
    enum Route {
        ROOT("Root（可能需要授权）"), DEVICE_OWNER("设备所有者"), SYSTEM("系统 REBOOT 权限"),
        POWER_MENU("无障碍电源菜单"), NOTIFICATIONS("无障碍通知栏"), VENDOR_SETTINGS("厂商定时开关机设置");
        final String label;
        Route(String label) { this.label = label; }
    }
    enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
    enum Status { REBOOT_REQUESTED, ASSISTANCE_OPENED, FAILED, UNCERTAIN }

    static final class Attempt {
        final Status status;
        final String message;
        Attempt(Status status, String message) { this.status = status; this.message = message; }
    }
    interface Access {
        Availability inspect(Route route);
        Attempt perform(Route route);
    }

    private static final List<Route> ROUTES = Collections.unmodifiableList(Arrays.asList(Route.values()));
    private ProgressiveReboot() { }
    static List<Route> routes() { return ROUTES; }

    static Availability inspect(Access access, Route route) {
        try {
            Availability state = access.inspect(route);
            return state == null ? Availability.UNKNOWN : state;
        } catch (RuntimeException failure) {
            return Availability.UNKNOWN;
        }
    }

    static RebootController.Permission permission(Access access) {
        boolean unknown = false;
        for (Route route : ROUTES) {
            Availability state = inspect(access, route);
            if (state == Availability.AVAILABLE) return RebootController.Permission.GRANTED;
            unknown |= state == Availability.UNKNOWN;
        }
        return unknown ? RebootController.Permission.UNKNOWN : RebootController.Permission.DENIED;
    }

    static boolean hasDirectRoute(Access access) {
        for (Route route : Arrays.asList(Route.ROOT, Route.DEVICE_OWNER, Route.SYSTEM)) {
            if (inspect(access, route) != Availability.UNAVAILABLE) return true;
        }
        return false;
    }

    static RebootController.Result open(Access access) {
        StringBuilder history = new StringBuilder();
        for (Route route : ROUTES) {
            if (inspect(access, route) == Availability.UNAVAILABLE) {
                history.append(route.label).append("：不可用\n");
                continue;
            }
            Attempt attempt;
            try {
                attempt = access.perform(route);
            } catch (SecurityException denied) {
                attempt = new Attempt(Status.FAILED, "权限拒绝：" + denied.getMessage());
            } catch (RuntimeException uncertain) {
                attempt = new Attempt(Status.UNCERTAIN, uncertain.getClass().getSimpleName() + ": " + uncertain.getMessage());
            }
            if (attempt == null || attempt.status == null) attempt = new Attempt(Status.UNCERTAIN, "未返回明确结果");
            history.append(route.label).append("：").append(attempt.message).append("\n");
            switch (attempt.status) {
                case REBOOT_REQUESTED:
                    return new RebootController.Result(true, true, "已请求整机重启，不保证系统已执行。\n\n" + history);
                case ASSISTANCE_OPENED:
                    return new RebootController.Result(true, false, "仅打开辅助界面，设备尚未重启；请自行操作。\n\n" + history);
                case UNCERTAIN:
                    return new RebootController.Result(false, true, "执行结果未知，已停止自动降级和重复请求。请先核实设备状态。\n\n" + history);
                default: break;
            }
        }
        return new RebootController.Result(false, false, "没有可用的整机重启方式。可从重启选项开启无障碍辅助，或使用设备电源键。\n\n" + history);
    }

    static String describe(Access access) {
        StringBuilder text = new StringBuilder("渐进式重启方式\n\n");
        for (Route route : ROUTES) {
            Availability state = inspect(access, route);
            text.append(route.label).append("：").append(state == Availability.AVAILABLE ? "可尝试"
                    : state == Availability.UNKNOWN ? "无法确认，需确认后尝试" : "不可用").append("\n");
        }
        return text.append("\n检测不会执行命令或自动重启。系统菜单/设置需要手动操作；仅重启应用是独立选项。" ).toString();
    }
}
