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
    private static final String RUNNING = "com.android.settings.Settings$RunningServicesActivity";
    private static final String RUNNING_LEGACY = "com.android.settings.RunningServices";
    private static final String MANAGE_APPS = "com.android.settings.Settings$ManageApplicationsActivity";

    public static void main(String[] args) {
        test("null device field is unknown", () -> equal("未知", DeviceReport.value(null)));
        test("blank device field is unknown", () -> equal("未知", DeviceReport.value(" \n ")));
        test("device field keeps firmware text", () -> equal("YunOS 2.1.0", DeviceReport.value(" YunOS 2.1.0 ")));
        test("unknown memory is not reported as zero", () -> equal("未知", DeviceReport.memory(0)));
        test("negative memory is unknown", () -> equal("未知", DeviceReport.memory(-1)));
        test("memory uses long arithmetic", () -> equal("4096 MiB", DeviceReport.memory(4L * 1024 * 1024 * 1024)));
        for (final int sdk : new int[] {16, 19, 21, 36}) {
            test("report shows system API " + sdk, () -> equal("系统 API 等级：" + sdk + "\n\n",
                    DeviceReport.render(new LinkedHashMap<String, String>(), sdk)));
        }
        test("zero API is unknown", () -> equal("系统 API 等级：未知\n\n",
                DeviceReport.render(new LinkedHashMap<String, String>(), 0)));
        test("negative API is unknown", () -> equal("系统 API 等级：未知\n\n",
                DeviceReport.render(new LinkedHashMap<String, String>(), -1)));
        test("report shows numeric API rather than deriving it from YunOS", () -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("系统版本", "YunOS 2.1.0");
            String report = DeviceReport.render(fields, 19);
            contains(report, "系统 API 等级：19");
            contains(report, "YunOS 2.1.0");
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
        test("report contains only system API and supplied device fields", () -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("型号", " TV ");
            fields.put("CPU", null);
            equal("系统 API 等级：19\n\n型号：TV\n\nCPU：未知\n\n", DeviceReport.render(fields, 19));
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
        runningServicesTests();
        rebootTests();
        System.out.println("Core tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void runningServicesTests() {
        test("running-services candidates are exact and ordered", () -> equal(
                Arrays.asList(RUNNING, RUNNING_LEGACY), RunningServicesNavigator.candidates()));
        test("running-services allowlist cannot be modified", () -> {
            try {
                RunningServicesNavigator.candidates().add(MANAGE_APPS);
                throw new AssertionError("mutable allowlist");
            } catch (UnsupportedOperationException expected) { }
        });
        test("running-services direct activity is allowed", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING, null), "activity rejected"));
        test("empty running-services alias target is a direct activity", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING, ""), "activity rejected"));
        test("legacy running-services alias can target standard running activity", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, RUNNING), "alias rejected"));
        test("relative running-services alias is allowed", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, ".Settings$RunningServicesActivity"),
                "relative alias rejected"));
        test("legacy running-services alias can target AOSP manage-apps host", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, MANAGE_APPS), "AOSP alias rejected"));
        test("relative AOSP manage-apps alias target is allowed", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, ".Settings$ManageApplicationsActivity"),
                "relative AOSP alias rejected"));
        test("bare AOSP manage-apps alias target is allowed", () -> require(
                RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, "Settings$ManageApplicationsActivity"),
                "bare AOSP alias rejected"));
        test("manage-apps exception applies only to the legacy alias", () -> require(
                !RunningServicesNavigator.isStandardTarget(RUNNING, MANAGE_APPS), "overbroad alias exception"));
        test("running-services rejects unlisted source components", () -> require(
                !RunningServicesNavigator.isStandardTarget(MANAGE_APPS, null), "generic apps entry allowed"));
        test("running-services rejects home and vendor alias targets", () -> {
            for (String target : Arrays.asList(LEGACY, HOMEPAGE, "com.vendor.TaskManager",
                    "com.android.settings.tv.SimpleSettings")) {
                require(!RunningServicesNavigator.isStandardTarget(RUNNING_LEGACY, target),
                        "unexpected alias allowed: " + target);
            }
        });
        test("settings homepage does not acquire running-services alias permissions", () -> {
            require(!SettingsNavigator.isStandardTarget(RUNNING), "home allowlist widened");
            require(!SettingsNavigator.isStandardTarget(RUNNING_LEGACY), "home allowlist widened");
            require(!SettingsNavigator.isStandardTarget(MANAGE_APPS), "home alias exception widened");
        });
        test("running-services prefers direct activity and stops after success", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.AVAILABLE);
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(result.opened, "not opened");
            equal(Arrays.asList(RUNNING), access.inspected);
            equal(Arrays.asList(RUNNING), access.launched);
            contains(result.message, "正在运行的服务");
            contains(result.message, RUNNING);
        });
        test("missing direct running-services entry falls back to legacy alias", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(result.opened, "not opened");
            equal(Arrays.asList(RUNNING_LEGACY), access.launched);
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.inspected);
            contains(result.message, "先前尝试");
            contains(result.message, "未找到");
        });
        test("missing running-services never opens homepage or generic apps", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.states.put(MANAGE_APPS, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(!result.opened, "unexpected success");
            equal(0, access.launched.size());
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.inspected);
            contains(result.message, "无法打开");
            contains(result.message, "正在运行的服务");
            contains(result.message, "不会回退");
        });
        SettingsNavigator.Status[] blocked = { SettingsNavigator.Status.DISABLED,
                SettingsNavigator.Status.NOT_EXPORTED, SettingsNavigator.Status.NOT_SYSTEM,
                SettingsNavigator.Status.NON_STANDARD_TARGET, SettingsNavigator.Status.PERMISSION_DENIED,
                SettingsNavigator.Status.ERROR };
        String[] reasons = { "禁用", "未导出", "不是标准系统设置包", "非标准组件", "权限", "异常" };
        for (int i = 0; i < blocked.length; i++) {
            final SettingsNavigator.Status status = blocked[i];
            final String reason = reasons[i];
            test("running-services blocks " + status + " without launching", () -> {
                FakeAccess access = new FakeAccess();
                access.states.put(RUNNING, status);
                access.states.put(RUNNING_LEGACY, status);
                SettingsNavigator.Result result = RunningServicesNavigator.open(access);
                require(!result.opened, "unexpected success");
                equal(0, access.launched.size());
                contains(result.message, reason);
            });
        }
        test("running-services blocked preferred entry permits legacy alias", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.NOT_EXPORTED);
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(result.opened, "not opened");
            equal(Arrays.asList(RUNNING_LEGACY), access.launched);
            contains(result.message, "未导出");
        });
        test("running-services launch denial stays visible after successful fallback", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.AVAILABLE);
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(RUNNING, SettingsNavigator.Status.PERMISSION_DENIED);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(result.opened, "not opened");
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.launched);
            contains(result.message, "先前尝试");
            contains(result.message, "权限");
        });
        test("running-services disappearing at launch permits fallback", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.AVAILABLE);
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(RUNNING, SettingsNavigator.Status.MISSING);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(result.opened, "not opened");
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.launched);
            contains(result.message, "未找到");
        });
        test("running-services reports all launch failures", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.AVAILABLE);
            access.states.put(RUNNING_LEGACY, SettingsNavigator.Status.AVAILABLE);
            access.launchStates.put(RUNNING, SettingsNavigator.Status.ERROR);
            access.launchStates.put(RUNNING_LEGACY, SettingsNavigator.Status.PERMISSION_DENIED);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(!result.opened, "unexpected success");
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.launched);
            contains(result.message, "异常");
            contains(result.message, "权限");
        });
        test("running-services handles unknown inspection status", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, null);
            SettingsNavigator.Result result = RunningServicesNavigator.open(access);
            require(!result.opened, "unexpected success");
            equal(0, access.launched.size());
            contains(result.message, "异常");
        });
        test("running-services diagnostic inspection never launches", () -> {
            FakeAccess access = new FakeAccess();
            access.states.put(RUNNING, SettingsNavigator.Status.AVAILABLE);
            String summary = RunningServicesNavigator.describe(access);
            contains(summary, "任务管理器入口检查");
            contains(summary, "正在运行的服务");
            contains(summary, "可尝试打开");
            contains(summary, "厂商");
            equal(0, access.launched.size());
            equal(Arrays.asList(RUNNING, RUNNING_LEGACY), access.inspected);
        });
    }

    private static void rebootTests() {
        test("granted reboot permission enables confirmation", () -> {
            RebootController controller = new RebootController(new FakeRebootAccess());
            equal(RebootController.Permission.GRANTED, controller.permission());
            require(controller.canRequest(), "granted permission disabled");
        });
        test("denied reboot permission disables without executing", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.permission = RebootController.Permission.DENIED;
            RebootController controller = new RebootController(access);
            require(!controller.canRequest(), "denied permission enabled");
            require(!controller.requestConfirmation(), "denied permission armed");
            require(!controller.confirm(Runnable::run), "denied reboot requested");
            contains(controller.describe(), "无重启权限");
            equal(0, access.reboots);
        });
        test("unknown reboot permission permits only a confirmed attempt", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.permission = RebootController.Permission.UNKNOWN;
            RebootController controller = new RebootController(access);
            require(controller.canRequest(), "unknown permission disabled");
            require(!controller.confirm(Runnable::run), "unconfirmed unknown permission executed");
            equal(0, access.reboots);
            require(controller.requestConfirmation(), "unknown confirmation rejected");
            require(rebootNow(controller).requested, "unknown confirmed attempt rejected");
            equal(1, access.reboots);
        });
        test("failed permission inspection is unknown and can try after confirmation", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.inspectionFailure = new IllegalStateException("firmware check unavailable");
            RebootController controller = new RebootController(access);
            equal(RebootController.Permission.UNKNOWN, controller.permission());
            contains(controller.describe(), "无法预先判断");
            require(controller.requestConfirmation(), "unknown confirmation rejected");
            require(rebootNow(controller).requested, "inspection failure prevented confirmed attempt");
            equal(1, access.reboots);
        });
        test("security error during inspection is not proof reboot permission is denied", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.inspectionFailure = new SecurityException("cannot inspect");
            RebootController controller = new RebootController(access);
            equal(RebootController.Permission.UNKNOWN, controller.permission());
            require(controller.canRequest(), "unknown inspection disabled");
        });
        test("null inspection result remains unknown", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.permission = null;
            equal(RebootController.Permission.UNKNOWN, new RebootController(access).permission());
        });
        test("reboot without confirmation is rejected without even inspecting", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(!controller.confirm(Runnable::run), "unconfirmed reboot requested");
            equal(null, controller.result());
            equal(0, access.inspections);
            equal(0, access.reboots);
        });
        test("opening reboot confirmation never reboots", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            require(!controller.canRequest(), "second confirmation enabled while pending");
            equal(0, access.reboots);
        });
        test("cancelling reboot disarms pending confirmation", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            controller.cancelConfirmation();
            require(!controller.confirm(Runnable::run), "cancelled reboot executed");
            require(controller.canRequest(), "cancelled button remains disabled");
            equal(0, access.reboots);
        });
        test("duplicate confirmation requests do not replace the original", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            require(!controller.requestConfirmation(), "duplicate confirmation accepted");
            require(rebootNow(controller).requested, "original confirmation lost");
            equal(1, access.reboots);
        });
        test("confirmed reboot is requested only once", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            RebootController.Result result = rebootNow(controller);
            require(result.requested, "confirmed request not sent");
            contains(result.message, "已请求");
            require(!controller.confirm(Runnable::run), "confirmation reused");
            require(!controller.requestConfirmation(), "submitted reboot armed again");
            require(!controller.canRequest(), "submitted reboot enabled again");
            equal(1, access.reboots);
        });
        test("permission is rechecked after reboot confirmation opens", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            access.permission = RebootController.Permission.DENIED;
            RebootController.Result result = rebootNow(controller);
            require(!result.requested, "revoked permission executed");
            contains(result.message, "权限");
            equal(0, access.reboots);
        });
        test("execution permission denial disables even if inspection still says granted", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.rebootFailure = new SecurityException("firmware denied REBOOT");
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            RebootController.Result result = rebootNow(controller);
            require(!result.requested, "denied execution reported success");
            contains(result.message, "权限");
            contains(result.message, "firmware denied REBOOT");
            equal(RebootController.Permission.DENIED, controller.permission());
            require(!controller.canRequest(), "execution denial ignored");
            require(!controller.requestConfirmation(), "denied request rearmed");
            equal(1, access.reboots);
        });
        test("reboot runtime failure needs a fresh confirmation to retry", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.rebootFailure = new IllegalStateException("power service unavailable");
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            RebootController.Result result = rebootNow(controller);
            require(!result.requested, "failed execution reported success");
            contains(result.message, "power service unavailable");
            require(controller.canRequest(), "retry disabled after non-permission error");
            require(!controller.confirm(Runnable::run), "failed confirmation reused");
            equal(1, access.reboots);
            access.rebootFailure = null;
            require(controller.requestConfirmation(), "retry confirmation rejected");
            require(rebootNow(controller).requested, "confirmed retry failed");
            equal(2, access.reboots);
        });
        test("reboot error without a message still reports exception type", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.rebootFailure = new IllegalStateException();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            contains(rebootNow(controller).message, "IllegalStateException");
        });
        test("reboot is disabled during the actual platform call", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            access.duringReboot = () -> {
                require(!controller.canRequest(), "reboot enabled during execution");
                require(!controller.requestConfirmation(), "reentrant confirmation accepted");
                require(!controller.confirm(Runnable::run), "reentrant execution accepted");
            };
            require(controller.requestConfirmation(), "confirmation rejected");
            require(rebootNow(controller).requested, "confirmed request not sent");
            equal(1, access.reboots);
        });
        test("new controller never restores a pending reboot confirmation", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController previous = new RebootController(access);
            require(previous.requestConfirmation(), "confirmation rejected");
            require(!new RebootController(access).confirm(Runnable::run), "confirmation restored");
            equal(0, access.reboots);
        });
        test("permission checks can detect a later system grant", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.permission = RebootController.Permission.DENIED;
            RebootController controller = new RebootController(access);
            require(!controller.canRequest(), "denied permission enabled");
            access.permission = RebootController.Permission.GRANTED;
            require(controller.canRequest(), "new grant not observed");
            equal(0, access.reboots);
        });
        test("retained reboot keeps queued execution and late failure across UI recreation", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            access.rebootFailure = new IllegalStateException("late firmware error");
            RebootController controller = new RebootController(access);
            List<Runnable> queued = new ArrayList<>();
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(queued::add), "confirmed work not queued");
            require(controller.isInProgress(), "queued request not marked in progress");
            equal(null, controller.result());
            // Activity recreation retains the controller, discards only unconfirmed consent.
            controller.cancelConfirmation();
            RebootController replacementUi = controller;
            require(!replacementUi.canRequest(), "recreated UI enables duplicate request");
            require(!replacementUi.requestConfirmation(), "recreated UI arms duplicate request");
            require(!replacementUi.confirm(Runnable::run), "recreated UI submits duplicate request");
            equal(0, access.reboots);
            queued.get(0).run();
            require(!replacementUi.isInProgress(), "completed request still in progress");
            require(!replacementUi.result().requested, "late failure reported as success");
            contains(replacementUi.result().message, "late firmware error");
            equal(1, access.reboots);
            require(!replacementUi.confirm(Runnable::run), "late failure automatically retried");
        });
        test("retained controller discards unconfirmed consent without submitting work", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            controller.cancelConfirmation();
            require(!controller.confirm(Runnable::run), "unconfirmed retained request executed");
            require(!controller.isInProgress(), "cancelled confirmation marked busy");
            equal(null, controller.result());
            equal(0, access.reboots);
        });
        test("retained successful request and result remain one shot", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            List<Runnable> queued = new ArrayList<>();
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(queued::add), "confirmed work not queued");
            controller.cancelConfirmation();
            queued.get(0).run();
            require(controller.result().requested, "submitted result lost");
            require(!controller.isInProgress(), "returned request still running");
            require(!controller.canRequest(), "submitted request re-enabled");
            require(!controller.confirm(Runnable::run), "retained confirmation reused");
            equal(1, access.reboots);
        });
        test("permission is checked when queued work actually executes", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            List<Runnable> queued = new ArrayList<>();
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(queued::add), "confirmed work not queued");
            access.permission = RebootController.Permission.DENIED;
            queued.get(0).run();
            require(!controller.result().requested, "queued revoked permission executed");
            contains(controller.result().message, "权限");
            equal(0, access.reboots);
        });
        test("worker scheduling failure is reported and requires reconfirmation", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(work -> { throw new IllegalStateException("cannot start worker"); }),
                    "confirmed request not accepted");
            require(!controller.isInProgress(), "failed scheduler left request busy");
            contains(controller.result().message, "cannot start worker");
            require(!controller.confirm(Runnable::run), "scheduler failure reused confirmation");
            equal(0, access.reboots);
        });
        test("reboot diagnostic inspection is read only", () -> {
            FakeRebootAccess access = new FakeRebootAccess();
            RebootController controller = new RebootController(access);
            contains(controller.describe(), "重启设备");
            contains(controller.describe(), "二次确认");
            equal(0, access.reboots);
            require(!controller.confirm(Runnable::run), "inspection armed confirmation");
        });
    }

    private static RebootController.Result rebootNow(RebootController controller) {
        require(controller.confirm(Runnable::run), "confirmation rejected");
        RebootController.Result result = controller.result();
        require(result != null, "completed request result missing");
        return result;
    }

    private static final class FakeRebootAccess implements RebootController.Access {
        RebootController.Permission permission = RebootController.Permission.GRANTED;
        RuntimeException inspectionFailure;
        RuntimeException rebootFailure;
        Runnable duringReboot;
        int inspections;
        int reboots;

        @Override public RebootController.Permission permission() {
            inspections++;
            if (inspectionFailure != null) throw inspectionFailure;
            return permission;
        }
        @Override public void reboot() {
            reboots++;
            if (duringReboot != null) duringReboot.run();
            if (rebootFailure != null) throw rebootFailure;
        }
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
