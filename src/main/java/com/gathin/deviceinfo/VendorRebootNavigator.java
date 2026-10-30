package com.gathin.deviceinfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Historical OEM scheduled-power pages only; availability is always checked at runtime. */
final class VendorRebootNavigator {
    static final class Target {
        final String packageName;
        final String className;
        Target(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }

    private static final List<Target> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            new Target("com.miui.securitycenter", "com.miui.powercenter.bootshutdown.PowerShutdownOnTime"),
            new Target("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.ScheduledPowerOnOffActivity")));

    private VendorRebootNavigator() { }
    static List<Target> candidates() { return CANDIDATES; }

    static boolean isAllowed(String packageName, String className, String aliasTarget) {
        for (Target target : CANDIDATES) {
            if (!target.packageName.equals(packageName) || !target.className.equals(className)) continue;
            if (aliasTarget == null || aliasTarget.isEmpty()) return true;
            String name = aliasTarget.startsWith(".") ? packageName + aliasTarget
                    : aliasTarget.indexOf('.') < 0 ? packageName + "." + aliasTarget : aliasTarget;
            return target.className.equals(name);
        }
        return false;
    }
}
