package com.gathin.deviceinfo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Capability checks are passive; all destructive calls are behind RebootController consent. */
final class AndroidRebootAccess implements RebootController.Access, ProgressiveReboot.Access {
    private final Context context;
    private final AndroidRebootPages pages;

    AndroidRebootAccess(Context context) {
        this.context = context.getApplicationContext();
        pages = new AndroidRebootPages(this.context);
    }

    @Override public RebootController.Permission permission() { return ProgressiveReboot.permission(this); }

    @Override public RebootController.Permission permission(RebootController.Action action) {
        if (action == RebootController.Action.APP_RESTART) {
            return AndroidAppRestart.available(context) ? RebootController.Permission.GRANTED : RebootController.Permission.DENIED;
        }
        return permission();
    }

    @Override public RebootController.Result execute(RebootController.Action action) {
        if (action == RebootController.Action.APP_RESTART) return AppRestartPlan.execute(new AndroidAppRestart(context));
        return ProgressiveReboot.open(this);
    }

    @Override public String describe() { return ProgressiveReboot.describe(this); }
    boolean hasDirectRoute() { return ProgressiveReboot.hasDirectRoute(this); }

    @Override public ProgressiveReboot.Availability inspect(ProgressiveReboot.Route route) {
        switch (route) {
            case ROOT:
                // A file is only a candidate. Never execute su/id or request authorization while checking.
                return findSu() == null ? ProgressiveReboot.Availability.UNAVAILABLE : ProgressiveReboot.Availability.UNKNOWN;
            case DEVICE_OWNER:
                return available(Build.VERSION.SDK_INT >= 24 && Api24.isOwner(context));
            case SYSTEM:
                return available(context.checkCallingOrSelfPermission(Manifest.permission.REBOOT) == PackageManager.PERMISSION_GRANTED);
            case POWER_MENU:
                return available(Build.VERSION.SDK_INT >= 21 && RebootAccessibilityService.connected());
            case NOTIFICATIONS:
                return available(Build.VERSION.SDK_INT < 21 && RebootAccessibilityService.connected());
            case VENDOR_SETTINGS:
                return pages.vendorAvailability();
            default: return ProgressiveReboot.Availability.UNAVAILABLE;
        }
    }

    @Override public ProgressiveReboot.Attempt perform(ProgressiveReboot.Route route) {
        // Recheck each selected path at execution time, not merely when the dialog was opened.
        if (inspect(route) == ProgressiveReboot.Availability.UNAVAILABLE) {
            return failed("该方式已不可用");
        }
        switch (route) {
            case ROOT:
                return RootReboot.run(findSu(), new RootReboot.ProcessFactory() {
                    @Override public Process start(String[] command) throws java.io.IOException {
                        return new ProcessBuilder(command).redirectErrorStream(true).start();
                    }
                }, new RootReboot.Clock() {
                    @Override public long millis() { return System.nanoTime() / 1000000L; }
                    @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
                }, 20000);
            case DEVICE_OWNER:
                if (Build.VERSION.SDK_INT < 24 || !Api24.isOwner(context)) return failed("当前应用不是设备所有者");
                try {
                    Api24.reboot(context);
                    return submitted();
                } catch (IllegalStateException busy) {
                    // DevicePolicyManager explicitly refuses reboot during an ongoing call.
                    return failed("设备所有者接口拒绝当前状态：" + busy.getMessage());
                }
            case SYSTEM:
                if (context.getSystemService(Context.POWER_SERVICE) == null) return failed("系统电源服务不可用");
                reboot();
                return submitted();
            case POWER_MENU:
            case NOTIFICATIONS:
                return RebootAccessibilityService.openMenu(route == ProgressiveReboot.Route.POWER_MENU);
            case VENDOR_SETTINGS:
                return pages.openVendor();
            default: return failed("不支持此方式");
        }
    }

    // The controller and router recheck permission; unknown checks may try after consent.
    @SuppressLint("MissingPermission")
    @Override public void reboot() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new IllegalStateException("系统电源服务不可用");
        power.reboot(null);
    }

    private static ProgressiveReboot.Availability available(boolean value) {
        return value ? ProgressiveReboot.Availability.AVAILABLE : ProgressiveReboot.Availability.UNAVAILABLE;
    }
    private static ProgressiveReboot.Attempt submitted() {
        return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.REBOOT_REQUESTED, "系统已接收调用请求");
    }
    private static ProgressiveReboot.Attempt failed(String message) {
        return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.FAILED, message);
    }

    private static String findSu() {
        List<String> directories = new ArrayList<>(Arrays.asList("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/su/xbin"));
        String path = System.getenv("PATH");
        if (path != null) directories.addAll(Arrays.asList(path.split(File.pathSeparator)));
        for (String directory : directories) {
            if (!new File(directory).isAbsolute()) continue;
            File candidate = new File(directory, "su");
            if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
        }
        return null;
    }

    @TargetApi(24)
    private static final class Api24 {
        static boolean isOwner(Context context) {
            DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return manager != null && manager.isDeviceOwnerApp(context.getPackageName());
        }
        static void reboot(Context context) {
            DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) throw new SecurityException("不是设备所有者");
            manager.reboot(new ComponentName(context, RebootAdminReceiver.class));
        }
    }
}
