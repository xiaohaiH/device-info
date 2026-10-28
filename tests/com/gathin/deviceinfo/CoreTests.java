package com.gathin.deviceinfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free tests of the actual production policy, not a copy of it. */
public final class CoreTests {
    private static int passed;
    private static int failed;
    private static final String LEGACY = "com.android.settings.Settings";
    private static final String HOMEPAGE = "com.android.settings.homepage.SettingsHomepageActivity";
    private static final String DASHBOARD = "com.android.settings.Settings$SettingsDashboardActivity";

    public static void main(String[] args) {
        test("null device field is unknown", () -> equal("未知", DeviceReport.value(null)));
        test("blank device field is unknown", () -> equal("未知", DeviceReport.value(" \n ")));
        test("device field keeps firmware text", () -> equal("YunOS 2.1.0", DeviceReport.value(" YunOS 2.1.0 ")));
        test("unknown memory is not reported as zero", () -> equal("未知", DeviceReport.memory(0)));
        test("negative memory is unknown", () -> equal("未知", DeviceReport.memory(-1)));
        test("memory uses long arithmetic", () -> equal("4096 MiB", DeviceReport.memory(4L * 1024 * 1024 * 1024)));
        test("API 16 below launcher minimum", () -> contains(DeviceReport.compatibility(16), "低于"));
        test("API 19 below launcher minimum", () -> contains(DeviceReport.compatibility(19), "低于"));
        test("API 21 meets launcher minimum", () -> contains(DeviceReport.compatibility(21), "达到"));
        test("API 36 does not promise installation", () -> contains(DeviceReport.compatibility(36), "不保证"));
        test("unknown API does not make a compatibility claim", () -> contains(DeviceReport.compatibility(0), "无法判断"));
        test("report shows numeric API rather than deriving it from YunOS", () -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("系统版本", "YunOS 2.1.0");
            String report = DeviceReport.render(fields, 19);
            contains(report, "API 19");
            contains(report, "YunOS 2.1.0");
            contains(report, "低于");
        });
        test("report retains field order and labels unknown values", () -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("型号", "TV");
            fields.put("CPU", null);
            String report = DeviceReport.render(fields, 21);
            contains(report, "型号：TV");
            contains(report, "CPU：未知");
            require(report.indexOf("型号") < report.indexOf("CPU"), "field order changed");
        });
        test("only exact standard Settings components are candidates", () -> equal(
                Arrays.asList(LEGACY, HOMEPAGE, DASHBOARD), SettingsNavigator.candidates()));
        test("candidate allowlist cannot be modified", () -> {
            try {
                SettingsNavigator.candidates().add("com.vendor.tv.Settings");
                throw new AssertionError("mutable allowlist");
            } catch (UnsupportedOperationException expected) { }
        });
        test("foreign package is rejected even when marked system", () -> equal(
                SettingsNavigator.Status.NOT_SYSTEM,
                SettingsNavigator.availability("com.vendor.tv", true, true, true, true)));
        test("user-installed lookalike Settings package is rejected", () -> equal(
                SettingsNavigator.Status.NOT_SYSTEM,
                SettingsNavigator.availability("com.android.settings", false, true, true, true)));
        test("disabled component is not launched", () -> equal(
                SettingsNavigator.Status.DISABLED,
                SettingsNavigator.availability("com.android.settings", true, false, true, true)));
        test("private component is not launched", () -> equal(
                SettingsNavigator.Status.NOT_EXPORTED,
                SettingsNavigator.availability("com.android.settings", true, true, false, true)));
        test("protected component is not launched", () -> equal(
                SettingsNavigator.Status.PERMISSION_DENIED,
                SettingsNavigator.availability("com.android.settings", true, true, true, false)));
        test("public enabled standard system component can launch", () -> equal(
                SettingsNavigator.Status.AVAILABLE,
                SettingsNavigator.availability("com.android.settings", true, true, true, true)));
        test("non-alias Settings class is allowed", () -> require(SettingsNavigator.isStandardTarget(null), "class rejected"));
        test("alias to standard homepage is allowed", () -> require(SettingsNavigator.isStandardTarget(HOMEPAGE), "alias rejected"));
        test("relative standard alias is allowed", () -> require(SettingsNavigator.isStandardTarget(".Settings"), "relative alias rejected"));
        test("alias to vendor TV UI is rejected", () -> require(!SettingsNavigator.isStandardTarget("com.android.settings.tv.SimpleSettings"), "vendor alias allowed"));
        test("legacy standard Settings is tried before modern components", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.states.put(HOMEPAGE, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && result.opened, "not opened");
            equal(Arrays.asList(LEGACY), access.launched);
            equal(Arrays.asList(LEGACY), access.inspected);
        });
        test("missing legacy entry allows standard homepage", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(HOMEPAGE, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && result.opened, "not opened");
            equal(Arrays.asList(HOMEPAGE), access.launched);
            contains(result.message, HOMEPAGE);
        });
        test("all missing does not fall back to vendor or generic settings", () -> {
            FakeAccess access = new FakeAccess();
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && !result.opened, "unexpected success");
            equal(0, access.launched.size());
            equal(Arrays.asList(LEGACY, HOMEPAGE, DASHBOARD), access.inspected);
            contains(result.message, "未找到");
            contains(result.message, "不会回退");
        });
        test("blocked entries show reasons without launch attempts", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.DISABLED);
            access.states.put(HOMEPAGE, SettingsNavigator.Status.NOT_EXPORTED);
            access.states.put(DASHBOARD, SettingsNavigator.Status.PERMISSION_DENIED);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && !result.opened, "unexpected success");
            equal(0, access.launched.size());
            contains(result.message, "禁用");
            contains(result.message, "未导出");
            contains(result.message, "权限");
        });
        test("launch denial stays visible", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(LEGACY, SettingsNavigator.Status.PERMISSION_DENIED);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && !result.opened, "unexpected success");
            contains(result.message, "权限");
            equal(Arrays.asList(LEGACY), access.launched);
        });
        test("failed launch can try the next standard component", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.states.put(HOMEPAGE, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(LEGACY, SettingsNavigator.Status.ERROR);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && result.opened, "not opened");
            equal(Arrays.asList(LEGACY, HOMEPAGE), access.launched);
        });
        test("successful fallback preserves earlier failure details", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.states.put(HOMEPAGE, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(LEGACY, SettingsNavigator.Status.PERMISSION_DENIED);
            SettingsNavigator.Result result = SettingsNavigator.open(access);
            require(result != null && result.opened, "not opened");
            contains(result.message, LEGACY);
            contains(result.message, "权限");
        });
        test("diagnostic inspection never launches an activity", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            String summary = SettingsNavigator.describe(access);
            contains(summary, LEGACY);
            contains(summary, "可尝试打开");
            contains(summary, "厂商");
            equal(0, access.launched.size());
            equal(3, access.inspected.size());
        });
        System.out.println("Core tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static final class FakeAccess implements SettingsNavigator.Access {
        final Map<String, SettingsNavigator.Status> states = new LinkedHashMap<>();
        final Map<String, SettingsNavigator.Status> launchStates = new LinkedHashMap<>();
        final List<String> inspected = new ArrayList<>();
        final List<String> launched = new ArrayList<>();

        @Override public SettingsNavigator.Status inspect(String className) {
            inspected.add(className);
            return states.containsKey(className) ? states.get(className) : SettingsNavigator.Status.MISSING;
        }
        @Override public SettingsNavigator.Status launch(String className) {
            launched.add(className);
            return launchStates.containsKey(className) ? launchStates.get(className) : SettingsNavigator.Status.OPENED;
        }
    }

    private static void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure);
        }
    }
    private static void equal(Object expected, Object actual) {
        require(expected == null ? actual == null : expected.equals(actual),
                "expected <" + expected + "> but was <" + actual + ">");
    }
    private static void contains(String text, String expected) {
        require(text != null && text.contains(expected), "missing <" + expected + "> in <" + text + ">");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
