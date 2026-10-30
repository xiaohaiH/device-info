package com.gathin.deviceinfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit running-services shortcuts, deliberately separate from the settings homepage. */
final class RunningServicesNavigator {
    private static final String LEGACY_ALIAS = SettingsNavigator.PACKAGE + ".RunningServices";
    private static final String LEGACY_HOST = SettingsNavigator.PACKAGE + ".Settings$ManageApplicationsActivity";
    private static final List<String> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            SettingsNavigator.PACKAGE + ".Settings$RunningServicesActivity", LEGACY_ALIAS));
    private static final String LIMITATION = "仅尝试系统“正在运行的服务”入口，不会回退到厂商管家、普通应用管理或最近任务界面。"
            + "实际页面由系统/厂商固件决定，部分版本可能只显示应用管理；入口被删除或受限时，本应用无法恢复或绕过限制。";

    private RunningServicesNavigator() { }

    static List<String> candidates() { return CANDIDATES; }

    static boolean isStandardTarget(String className, String targetActivity) {
        if (!CANDIDATES.contains(className)) return false;
        if (targetActivity == null || targetActivity.isEmpty()) return true; // Not an alias.
        String name = targetActivity;
        if (name.startsWith(".")) name = SettingsNavigator.PACKAGE + name;
        else if (name.indexOf('.') < 0) name = SettingsNavigator.PACKAGE + "." + name;
        if (CANDIDATES.contains(name)) return true;
        // AOSP 4.x uses this exact alias to select the running tab in ManageApplications.
        // The host is NOT a launch candidate, nor a valid redirect for other entries.
        return LEGACY_ALIAS.equals(className) && LEGACY_HOST.equals(name);
    }

    static SettingsNavigator.Result open(SettingsNavigator.Access access) {
        return SettingsNavigator.open(access, CANDIDATES, "任务管理器（正在运行的服务）", LIMITATION);
    }

    static String describe(SettingsNavigator.Access access) {
        return SettingsNavigator.describe(access, CANDIDATES, "任务管理器入口检查（正在运行的服务）", LIMITATION);
    }
}
