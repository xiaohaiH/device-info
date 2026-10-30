package com.gathin.deviceinfo;

/** App-only restart, invoked under its own explicit confirmation, never as device fallback. */
final class AppRestartPlan {
    interface Access {
        void schedule(long delayMillis);
        void stopProcess();
        void cancelSchedule();
    }

    private AppRestartPlan() { }

    static RebootController.Result execute(Access access) {
        try {
            // Leave margin for process teardown; Android may still delay/block the launch.
            access.schedule(1000);
        } catch (RuntimeException failure) {
            return new RebootController.Result(false, "无法安排应用重启，当前进程未结束：" + failure.getMessage());
        }
        try {
            access.stopProcess();
            return new RebootController.Result(true, "已安排仅重启本应用，不会重启设备。自动重新打开可能被系统延迟或阻止，请准备手动打开。");
        } catch (RuntimeException failure) {
            try {
                access.cancelSchedule();
                return new RebootController.Result(false, "未能结束本应用，已取消自动重新打开：" + failure.getMessage());
            } catch (RuntimeException cannotCancel) {
                return new RebootController.Result(false, true, "应用重启状态未知，无法取消已安排的启动，请勿重复操作。");
            }
        }
    }
}
