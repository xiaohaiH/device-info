package com.gathin.deviceinfo;

import java.util.EnumSet;
import java.util.concurrent.Executor;

/** Permission-aware, one-shot confirmation gate. Inspection and cancellation are read-only. */
final class RebootController {
    enum Permission { GRANTED, DENIED, UNKNOWN }
    enum Action { DEVICE_REBOOT, APP_RESTART }

    interface Access {
        Permission permission();
        void reboot();
        default Permission permission(Action action) {
            return action == Action.DEVICE_REBOOT ? permission() : Permission.DENIED;
        }
        default Result execute(Action action) {
            if (action != Action.DEVICE_REBOOT) return new Result(false, "不支持此操作。");
            reboot();
            return new Result(true, "已请求重启设备，请等待系统响应；是否实际重启由系统决定。");
        }
        default String describe() { return null; }
    }

    static final class Result {
        final boolean requested; // The selected action was requested, not proof of device reboot.
        final boolean blocksRequests; // Submitted/uncertain destructive requests must not repeat.
        final String message;
        Result(boolean requested, String message) { this(requested, requested, message); }
        Result(boolean requested, boolean blocksRequests, String message) {
            this.requested = requested;
            this.blocksRequests = blocksRequests;
            this.message = message;
        }
    }

    private final Access access;
    private boolean confirmationPending;
    private boolean requestStarted;
    private final EnumSet<Action> deniedAtExecution = EnumSet.noneOf(Action.class);
    private long confirmationGeneration;
    private Action pendingAction;
    private Action startedAction;
    private Result lastResult;

    RebootController(Access access) { this(access, false); }

    RebootController(Access access, boolean restoredBlocked) {
        this.access = access;
        if (restoredBlocked) {
            requestStarted = true;
            lastResult = new Result(false, true, "上次重启操作结果未知，已阻止后续操作；不会恢复任务或自动重试，请先核实设备状态。");
        }
    }

    synchronized Permission permission() { return permission(Action.DEVICE_REBOOT); }

    synchronized Permission permission(Action action) {
        if (action == null || deniedAtExecution.contains(action)) return Permission.DENIED;
        try {
            Permission permission = access.permission(action);
            return permission == null ? Permission.UNKNOWN : permission;
        } catch (RuntimeException cannotInspect) {
            return Permission.UNKNOWN;
        }
    }

    synchronized boolean canOpenOptions() { return !confirmationPending && !requestStarted; }

    synchronized boolean canRequest() { return canRequest(Action.DEVICE_REBOOT); }

    synchronized boolean canRequest(Action action) {
        return action != null && canOpenOptions() && permission(action) != Permission.DENIED;
    }

    synchronized boolean requestConfirmation() { return requestConfirmation(Action.DEVICE_REBOOT); }

    synchronized boolean requestConfirmation(Action action) {
        if (!canRequest(action)) return false;
        pendingAction = action;
        confirmationGeneration++;
        confirmationPending = true;
        return true;
    }

    synchronized void cancelConfirmation() {
        confirmationPending = false;
        pendingAction = null;
    }

    synchronized long confirmationToken() { return confirmationPending ? confirmationGeneration : 0; }

    synchronized void cancelConfirmation(long token) {
        if (confirmationPending && token == confirmationGeneration) cancelConfirmation();
    }

    synchronized boolean blocksRequests() { return requestStarted; }

    synchronized boolean isInProgress() { return requestStarted && lastResult == null; }

    synchronized Result result() { return lastResult; }

    /** Consume consent before queueing work, so recreation cannot cancel or reuse it. */
    boolean confirm(Executor executor) { return confirm(confirmationToken(), executor); }

    boolean confirm(long token, Executor executor) {
        final Action action;
        synchronized (this) {
            if (!confirmationPending || requestStarted || token != confirmationGeneration) return false;
            action = pendingAction;
            pendingAction = null;
            confirmationPending = false;
            startedAction = action;
            requestStarted = true;
            lastResult = null;
        }
        try {
            executor.execute(new Runnable() {
                @Override public void run() { executeRequest(action); }
            });
        } catch (RuntimeException schedulingFailure) {
            complete(new Result(false, "无法启动重启请求。\n" + detail(schedulingFailure)));
        }
        return true;
    }

    /** The executor must run this off the UI thread; successful reboot may never return. */
    private void executeRequest(Action action) {
        Result result;
        try {
            if (permission(action) == Permission.DENIED) {
                result = new Result(false, "操作权限不足或没有可用方式，已取消本次请求。");
            } else {
                result = access.execute(action);
                if (result == null) result = new Result(false, true, "执行结果未知，已停止后续操作，请先核实设备状态。");
            }
        } catch (SecurityException denied) {
            synchronized (this) { deniedAtExecution.add(action); }
            result = new Result(false, "系统拒绝重启权限，本次会话已禁用重启按钮。\n" + detail(denied));
        } catch (RuntimeException failure) {
            result = new Result(false, "无法请求重启设备。\n" + detail(failure));
        }
        complete(result);
    }

    private synchronized void complete(Result result) {
        lastResult = result;
        // Keep a submitted request disabled; retrying a failure requires a fresh confirmation.
        if (!result.blocksRequests) requestStarted = false;
    }

    synchronized String describe() {
        String state;
        if (requestStarted) {
            state = startedAction == Action.APP_RESTART ? "仅重启本应用的请求已提交，请等待或手动重新打开应用。"
                    : "已提交重启请求或执行结果未知，已禁用重复操作。";
        } else {
            try {
                String description = access.describe();
                if (description != null) return description;
            } catch (RuntimeException cannotDescribe) { /* Keep basic permission diagnostics. */ }
            switch (permission()) {
                case GRANTED: state = "已获系统重启权限；仍需二次确认，不会自动重启。"; break;
                case DENIED: state = "无重启权限，按钮已禁用。普通安装无法自行取得系统级 REBOOT 权限。"; break;
                default: state = "无法预先判断重启权限；仅在二次确认后尝试，执行失败会显示原因。";
            }
        }
        return "重启设备\n\n" + state;
    }

    private static String detail(RuntimeException error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }
}
