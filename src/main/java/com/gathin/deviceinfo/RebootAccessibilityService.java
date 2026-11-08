package com.gathin.deviceinfo;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/** Opt-in global actions only. No screen content, event collection, gestures or automatic clicks. */
public final class RebootAccessibilityService extends AccessibilityService {
    private static volatile RebootAccessibilityService service;

    private FloatingBackButton floatingBack;

    @Override protected void onServiceConnected() {
        service = this;
        if (Build.VERSION.SDK_INT >= 22 && floatingBackEnabled(this) && !showFloatingBack()) {
            preferences(this).edit().putBoolean("enabled", false).apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("floating_back", Context.MODE_PRIVATE);
    }

    static boolean floatingBackEnabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    static boolean setFloatingBackEnabled(Context context, boolean enabled) {
        RebootAccessibilityService current = service;
        if (enabled) {
            if (Build.VERSION.SDK_INT < 22 || current == null || !current.showFloatingBack()) return false;
        } else if (current != null) {
            current.removeFloatingBack();
        }
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        return true;
    }

    @TargetApi(22)
    private boolean showFloatingBack() {
        if (floatingBack != null) return true;
        try {
            floatingBack = new FloatingBackButton(this);
            return true;
        } catch (RuntimeException failure) {
            android.util.Log.w("FloatingBack", "Could not add accessibility overlay", failure);
            return false;
        }
    }

    private void removeFloatingBack() {
        if (Build.VERSION.SDK_INT >= 22 && floatingBack != null) {
            floatingBack.remove();
            floatingBack = null;
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) {
        removeFloatingBack();
        if (service == this) service = null;
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        removeFloatingBack();
        if (service == this) service = null;
        super.onDestroy();
    }

    static boolean connected() { return service != null; }

    static ProgressiveReboot.Attempt openMenu(boolean powerMenu) {
        RebootAccessibilityService current = service;
        if (current == null) return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.FAILED, "无障碍服务未连接，请手动开启后重新操作");
        boolean opened;
        if (powerMenu) {
            if (Build.VERSION.SDK_INT < 21) return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.FAILED, "此系统没有公开电源菜单操作");
            opened = Api21.openPowerMenu(current);
        } else {
            opened = current.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }
        if (!opened) return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.FAILED, "系统未接受无障碍菜单请求");
        return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.ASSISTANCE_OPENED, powerMenu
                ? "已请求显示电源菜单，请自行选择重启；固件可能不提供重启项"
                : "已请求展开通知栏，请自行查看厂商是否提供重启开关；普通通知栏不保证有此开关");
    }

    @TargetApi(21)
    private static final class Api21 {
        static boolean openPowerMenu(AccessibilityService service) { return service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); }
    }
}
