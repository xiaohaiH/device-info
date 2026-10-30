package com.gathin.deviceinfo;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

/** Independent reboot-assistance pages; does not widen the settings/home navigation policy. */
final class AndroidRebootPages {
    private final Context context;
    private final PackageManager packages;

    AndroidRebootPages(Context context) {
        this.context = context.getApplicationContext();
        packages = this.context.getPackageManager();
    }

    ProgressiveReboot.Availability vendorAvailability() {
        boolean unknown = false;
        for (VendorRebootNavigator.Target target : VendorRebootNavigator.candidates()) {
            SettingsNavigator.Status state = inspect(new ComponentName(target.packageName, target.className), true);
            if (state == SettingsNavigator.Status.AVAILABLE) return ProgressiveReboot.Availability.AVAILABLE;
            unknown |= state == SettingsNavigator.Status.ERROR;
        }
        return unknown ? ProgressiveReboot.Availability.UNKNOWN : ProgressiveReboot.Availability.UNAVAILABLE;
    }

    ProgressiveReboot.Attempt openVendor() {
        StringBuilder failures = new StringBuilder();
        for (VendorRebootNavigator.Target target : VendorRebootNavigator.candidates()) {
            ComponentName component = new ComponentName(target.packageName, target.className);
            SettingsNavigator.Status status = inspect(component, true);
            if (status == SettingsNavigator.Status.AVAILABLE) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.ASSISTANCE_OPENED,
                            "已打开厂商定时开关机页面，请自行配置；不会自动重启。\n" + component.flattenToShortString());
                } catch (ActivityNotFoundException missing) {
                    status = SettingsNavigator.Status.MISSING;
                } catch (SecurityException denied) {
                    status = SettingsNavigator.Status.PERMISSION_DENIED;
                } catch (RuntimeException uncertain) {
                    return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.UNCERTAIN, "厂商页面启动状态未知：" + uncertain.getMessage());
                }
            }
            failures.append(component.flattenToShortString()).append("：").append(status).append("\n");
        }
        return new ProgressiveReboot.Attempt(ProgressiveReboot.Status.FAILED,
                "未找到可打开的受支持厂商页面。仅检查部分小米/华为历史入口；请在设备设置中手动查找。\n" + failures);
    }

    RebootController.Result openAccessibilitySettings() {
        try {
            Intent discovery = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).setPackage(SettingsNavigator.PACKAGE);
            ResolveInfo resolved = packages.resolveActivity(discovery, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null) return new RebootController.Result(false, "未找到无障碍设置入口，请从系统设置手动打开。");
            ComponentName component = new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            SettingsNavigator.Status status = inspect(component, false);
            if (status != SettingsNavigator.Status.AVAILABLE) return new RebootController.Result(false, "无法打开无障碍设置：" + status);
            context.startActivity(discovery.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new RebootController.Result(true, false,
                    "已打开无障碍设置，请自行决定是否启用“设备信息重启辅助”。返回后需重新选择重启操作，不会自动执行。若系统限制侧载应用开启此服务，请遵循系统提示。");
        } catch (RuntimeException failure) {
            return new RebootController.Result(false, "无法打开无障碍设置：" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private SettingsNavigator.Status inspect(ComponentName component, boolean vendor) {
        String packageName = component.getPackageName();
        if (vendor ? !VendorRebootNavigator.isAllowed(packageName, component.getClassName(), null)
                : !SettingsNavigator.PACKAGE.equals(packageName)) return SettingsNavigator.Status.NON_STANDARD_TARGET;
        try {
            ActivityInfo info = packages.getActivityInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
            ApplicationInfo app = info.applicationInfo;
            if (!packageName.equals(app.packageName)
                    || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                return SettingsNavigator.Status.NOT_SYSTEM;
            }
            if (!enabled(packages.getApplicationEnabledSetting(packageName), app.enabled)
                    || !enabled(packages.getComponentEnabledSetting(component), info.enabled)) return SettingsNavigator.Status.DISABLED;
            if (!info.exported) return SettingsNavigator.Status.NOT_EXPORTED;
            if (info.permission != null && !info.permission.isEmpty()
                    && context.checkCallingOrSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) return SettingsNavigator.Status.PERMISSION_DENIED;
            if (vendor && !VendorRebootNavigator.isAllowed(packageName, component.getClassName(), info.targetActivity)) {
                return SettingsNavigator.Status.NON_STANDARD_TARGET;
            }
            if (!vendor && info.targetActivity != null) {
                String target = info.targetActivity.startsWith(".") ? packageName + info.targetActivity
                        : info.targetActivity.indexOf('.') < 0 ? packageName + "." + info.targetActivity : info.targetActivity;
                if (!target.startsWith(packageName + ".")) return SettingsNavigator.Status.NON_STANDARD_TARGET;
            }
            return SettingsNavigator.Status.AVAILABLE;
        } catch (PackageManager.NameNotFoundException missing) {
            return SettingsNavigator.Status.MISSING;
        } catch (SecurityException denied) {
            return SettingsNavigator.Status.PERMISSION_DENIED;
        } catch (RuntimeException failure) {
            return SettingsNavigator.Status.ERROR;
        }
    }

    private static boolean enabled(int override, boolean defaultValue) {
        return override == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? defaultValue
                : override == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
}
