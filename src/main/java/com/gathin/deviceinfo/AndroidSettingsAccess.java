package com.gathin.deviceinfo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** The only class that can launch another app; all destinations are explicit and allowlisted. */
final class AndroidSettingsAccess implements SettingsNavigator.Access {
    enum Destination { SETTINGS, RUNNING_SERVICES }

    private final Activity activity;
    private final PackageManager packages;
    private final Destination destination;

    AndroidSettingsAccess(Activity activity) {
        this(activity, Destination.SETTINGS);
    }

    AndroidSettingsAccess(Activity activity, Destination destination) {
        this.activity = activity;
        this.destination = destination;
        packages = activity.getPackageManager();
    }

    @Override public SettingsNavigator.Status inspect(String className) {
        boolean runningServices = destination == Destination.RUNNING_SERVICES;
        if (!(runningServices ? RunningServicesNavigator.candidates() : SettingsNavigator.candidates())
                .contains(className)) return SettingsNavigator.Status.NON_STANDARD_TARGET;
        ComponentName component = new ComponentName(SettingsNavigator.PACKAGE, className);
        try {
            ActivityInfo info = packages.getActivityInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
            ApplicationInfo app = info.applicationInfo;
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean enabled = enabled(packages.getApplicationEnabledSetting(app.packageName), app.enabled)
                    && enabled(packages.getComponentEnabledSetting(component), info.enabled);
            boolean permitted = info.permission == null || info.permission.isEmpty()
                    || activity.checkCallingOrSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED;
            SettingsNavigator.Status status = SettingsNavigator.availability(
                    app.packageName, system, enabled, info.exported, permitted);
            if (status == SettingsNavigator.Status.AVAILABLE) {
                boolean standardTarget = runningServices
                        ? RunningServicesNavigator.isStandardTarget(className, info.targetActivity)
                        : SettingsNavigator.isStandardTarget(info.targetActivity);
                if (!standardTarget) return SettingsNavigator.Status.NON_STANDARD_TARGET;
            }
            return status;
        } catch (PackageManager.NameNotFoundException missing) {
            return SettingsNavigator.Status.MISSING;
        } catch (SecurityException restricted) {
            return SettingsNavigator.Status.PERMISSION_DENIED;
        } catch (RuntimeException brokenFirmware) {
            return SettingsNavigator.Status.ERROR;
        }
    }

    @Override public SettingsNavigator.Status launch(String className) {
        // Recheck after inspection: an app/component may have been disabled in between.
        SettingsNavigator.Status status = inspect(className);
        if (status != SettingsNavigator.Status.AVAILABLE) return status;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(SettingsNavigator.PACKAGE, className));
            activity.startActivity(intent);
            return SettingsNavigator.Status.OPENED;
        } catch (ActivityNotFoundException missing) {
            return SettingsNavigator.Status.MISSING;
        } catch (SecurityException restricted) {
            return SettingsNavigator.Status.PERMISSION_DENIED;
        } catch (RuntimeException brokenFirmware) {
            return SettingsNavigator.Status.ERROR;
        }
    }

    private static boolean enabled(int override, boolean manifestDefault) {
        if (override == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return manifestDefault;
        return override == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
}
