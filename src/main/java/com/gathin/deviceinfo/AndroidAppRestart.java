package com.gathin.deviceinfo;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

/** Best-effort app-only restart. No device reboot, exact-alarm permission request or timing guarantee. */
final class AndroidAppRestart implements AppRestartPlan.Access {
    private final Context context;
    private final AlarmManager alarms;
    private PendingIntent pending;

    AndroidAppRestart(Context context) {
        this.context = context.getApplicationContext();
        alarms = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE);
    }

    static boolean available(Context context) { return context.getSystemService(Context.ALARM_SERVICE) != null; }

    @Override public void schedule(long delayMillis) {
        if (alarms == null) throw new IllegalStateException("系统定时服务不可用");
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= Api23.immutableFlag();
        pending = Build.VERSION.SDK_INT >= 35 ? Api35.pending(context, intent, flags)
                : PendingIntent.getActivity(context, 27182, intent, flags);
        if (pending == null) throw new IllegalStateException("无法创建应用启动请求");
        long when = SystemClock.elapsedRealtime() + delayMillis;
        if (Build.VERSION.SDK_INT >= 19 && (Build.VERSION.SDK_INT < 31 || Api31.canScheduleExact(alarms))) {
            Api19.schedule(alarms, when, pending);
        } else {
            // No new exact-alarm permission. This can be substantially delayed by Android.
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
        }
    }

    @Override public void stopProcess() { Process.killProcess(Process.myPid()); }

    @Override public void cancelSchedule() {
        if (pending != null) {
            alarms.cancel(pending);
            pending.cancel();
        }
    }

    @TargetApi(19)
    private static final class Api19 {
        // Caller checks canScheduleExactAlarms on API31+, never requests additional permission.
        @SuppressLint({"MissingPermission", "ScheduleExactAlarm"})
        static void schedule(AlarmManager manager, long when, PendingIntent pending) {
            manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
        }
    }
    @TargetApi(23)
    private static final class Api23 {
        static int immutableFlag() { return PendingIntent.FLAG_IMMUTABLE; }
    }
    @TargetApi(31)
    private static final class Api31 {
        static boolean canScheduleExact(AlarmManager manager) { return manager.canScheduleExactAlarms(); }
    }
    @TargetApi(35)
    private static final class Api35 {
        static PendingIntent pending(Context context, Intent intent, int flags) {
            ActivityOptions options = ActivityOptions.makeBasic();
            // Explicit immutable self-launch only; this opt-in still cannot guarantee OS approval.
            options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return PendingIntent.getActivity(context, 27182, intent, flags, options.toBundle());
        }
    }
}
