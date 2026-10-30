package com.gathin.deviceinfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

/** Tests use fake system boundaries only; no real root, reboot, alarm or process kill. */
public final class ProgressiveTests {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        test("device strategy order is explicit and excludes app restart", () -> equal(Arrays.asList(
                ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER,
                ProgressiveReboot.Route.SYSTEM, ProgressiveReboot.Route.POWER_MENU,
                ProgressiveReboot.Route.NOTIFICATIONS, ProgressiveReboot.Route.VENDOR_SETTINGS),
                ProgressiveReboot.routes()));
        test("strategy order is immutable", () -> {
            try {
                ProgressiveReboot.routes().clear();
                throw new AssertionError("mutable routes");
            } catch (UnsupportedOperationException expected) { }
        });
        test("capability inspection never executes any route", () -> {
            FakeRoutes access = new FakeRoutes();
            access.states.put(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Availability.UNKNOWN);
            equal(RebootController.Permission.UNKNOWN, ProgressiveReboot.permission(access));
            require(ProgressiveReboot.hasDirectRoute(access), "root candidate not offered");
            contains(ProgressiveReboot.describe(access), "Root");
            equal(0, access.executed.size());
        });
        test("no capabilities is denied but manual options can still be offered by UI", () -> {
            FakeRoutes access = new FakeRoutes();
            equal(RebootController.Permission.DENIED, ProgressiveReboot.permission(access));
            require(!ProgressiveReboot.hasDirectRoute(access), "unavailable direct route offered");
            require(!ProgressiveReboot.open(access).requested, "unavailable route reported success");
            equal(0, access.executed.size());
        });
        test("root is tried before owner and stops on accepted request", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER);
            RebootController.Result result = ProgressiveReboot.open(access);
            require(result.requested && result.blocksRequests, "accepted reboot is not terminal");
            equal(Arrays.asList(ProgressiveReboot.Route.ROOT), access.executed);
        });
        test("definite root denial falls back and preserves history", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER);
            access.results.put(ProgressiveReboot.Route.ROOT, attempt(ProgressiveReboot.Status.FAILED, "root denied"));
            RebootController.Result result = ProgressiveReboot.open(access);
            require(result.requested, "owner fallback failed");
            contains(result.message, "root denied");
            equal(Arrays.asList(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER), access.executed);
        });
        test("root uncertainty never falls through to another action", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER,
                    ProgressiveReboot.Route.POWER_MENU);
            access.results.put(ProgressiveReboot.Route.ROOT, attempt(ProgressiveReboot.Status.UNCERTAIN, "timeout"));
            RebootController.Result result = ProgressiveReboot.open(access);
            require(!result.requested && result.blocksRequests, "uncertain request not blocked");
            contains(result.message, "未知");
            equal(Arrays.asList(ProgressiveReboot.Route.ROOT), access.executed);
        });
        test("system permission is retained as a device reboot route", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.SYSTEM);
            require(ProgressiveReboot.open(access).requested, "system route removed");
            equal(Arrays.asList(ProgressiveReboot.Route.SYSTEM), access.executed);
        });
        for (ProgressiveReboot.Route route : Arrays.asList(ProgressiveReboot.Route.POWER_MENU,
                ProgressiveReboot.Route.NOTIFICATIONS, ProgressiveReboot.Route.VENDOR_SETTINGS)) {
            test("manual assistance is not a completed device reboot: " + route, () -> {
                FakeRoutes access = new FakeRoutes();
                access.available(route);
                access.results.put(route, attempt(ProgressiveReboot.Status.ASSISTANCE_OPENED, "manual step"));
                require(!ProgressiveReboot.hasDirectRoute(access), "manual route marked direct");
                RebootController.Result result = ProgressiveReboot.open(access);
                require(result.requested && !result.blocksRequests, "assistance blocks future confirmed requests");
                contains(result.message, "尚未重启");
                equal(Arrays.asList(route), access.executed);
            });
        }
        test("permission denial at execution permits next route", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.SYSTEM);
            access.failureRoute = ProgressiveReboot.Route.ROOT;
            access.failure = new SecurityException("not authorized");
            require(ProgressiveReboot.open(access).requested, "known denial blocked fallback");
            equal(2, access.executed.size());
        });
        test("unexpected execution failure stops instead of guessing whether reboot started", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.SYSTEM);
            access.failureRoute = ProgressiveReboot.Route.ROOT;
            access.failure = new RuntimeException("binder broke");
            RebootController.Result result = ProgressiveReboot.open(access);
            require(result.blocksRequests && !result.requested, "unknown execution ignored");
            equal(1, access.executed.size());
        });
        test("null execution result is uncertain rather than safe to retry", () -> {
            FakeRoutes access = new FakeRoutes();
            access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.SYSTEM);
            access.results.put(ProgressiveReboot.Route.ROOT, null);
            require(ProgressiveReboot.open(access).blocksRequests, "null result treated as definite failure");
            equal(1, access.executed.size());
        });
        test("unknown capability is attempted only through execution", () -> {
            FakeRoutes access = new FakeRoutes();
            access.states.put(ProgressiveReboot.Route.SYSTEM, ProgressiveReboot.Availability.UNKNOWN);
            require(ProgressiveReboot.open(access).requested, "unknown capability not attempted");
            equal(Arrays.asList(ProgressiveReboot.Route.SYSTEM), access.executed);
        });
        test("inspection exception does not execute a route during description", () -> {
            FakeRoutes access = new FakeRoutes();
            access.inspectFailure = true;
            equal(RebootController.Permission.UNKNOWN, ProgressiveReboot.permission(access));
            contains(ProgressiveReboot.describe(access), "无法确认");
            equal(0, access.executed.size());
        });
        test("app restart requires its own action-specific confirmation", () -> {
            FakeActions access = new FakeActions();
            RebootController controller = new RebootController(access);
            require(!controller.confirm(Runnable::run), "unconfirmed action executed");
            require(controller.requestConfirmation(RebootController.Action.APP_RESTART), "app confirmation rejected");
            require(controller.confirm(Runnable::run), "app confirmation not consumed");
            equal(Arrays.asList(RebootController.Action.APP_RESTART), access.executed);
            equal(0, access.legacyReboots);
        });
        test("queued confirmation cannot change from device reboot to app restart", () -> {
            FakeActions access = new FakeActions();
            RebootController controller = new RebootController(access);
            List<Runnable> queued = new ArrayList<>();
            require(controller.requestConfirmation(), "device confirmation rejected");
            require(controller.confirm(queued::add), "not queued");
            controller.cancelConfirmation();
            require(!controller.requestConfirmation(RebootController.Action.APP_RESTART), "action changed while queued");
            queued.get(0).run();
            equal(Arrays.asList(RebootController.Action.DEVICE_REBOOT), access.executed);
        });
        test("assisted result permits a new confirmation but never reuses the old one", () -> {
            FakeActions access = new FakeActions();
            access.result = new RebootController.Result(true, false, "manual assistance");
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(Runnable::run), "not dispatched");
            require(controller.canRequest(), "assistance left controller blocked");
            require(!controller.confirm(Runnable::run), "consent reused");
            require(controller.requestConfirmation(), "new confirmation rejected");
        });
        test("uncertain result keeps gate blocked across retained state", () -> {
            FakeActions access = new FakeActions();
            access.result = new RebootController.Result(false, true, "unknown outcome");
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(), "confirmation rejected");
            require(controller.confirm(Runnable::run), "not dispatched");
            controller.cancelConfirmation();
            require(!controller.canOpenOptions(), "uncertain request enabled duplicate actions");
            require(!controller.requestConfirmation(RebootController.Action.APP_RESTART), "unknown device result killed app");
        });
        test("cancelled app confirmation never kills app or reboots device", () -> {
            FakeActions access = new FakeActions();
            RebootController controller = new RebootController(access);
            require(controller.requestConfirmation(RebootController.Action.APP_RESTART), "confirmation rejected");
            controller.cancelConfirmation();
            require(!controller.confirm(Runnable::run), "cancelled action executed");
            equal(0, access.executed.size());
        });
        test("null action cannot be confirmed", () -> require(
                !new RebootController(new FakeActions()).requestConfirmation(null), "null action accepted"));
        rootTests();
        sessionTests();
        appRestartTests();
        vendorTests();
        System.out.println("Progressive tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void rootTests() {
        test("root command is fixed argv with no input interpolation", () -> {
            FakeProcess process = new FakeProcess(0);
            List<String> argv = new ArrayList<>();
            ProgressiveReboot.Attempt result = RootReboot.run("/system/xbin/su", command -> {
                argv.addAll(Arrays.asList(command));
                return process;
            }, new FakeClock(), 150);
            equal(Arrays.asList("/system/xbin/su", "-c", "reboot"), argv);
            equal(ProgressiveReboot.Status.REBOOT_REQUESTED, result.status);
        });
        test("root process nonzero exit is a definite failure", () -> {
            ProgressiveReboot.Attempt result = RootReboot.run("su", command -> new FakeProcess(1), new FakeClock(), 150);
            equal(ProgressiveReboot.Status.FAILED, result.status);
            contains(result.message, "1");
        });
        for (int exit : new int[] { 2, 137, 143, -9 }) {
            test("abnormal or unclassified root exit blocks fallback: " + exit, () -> {
                ProgressiveReboot.Attempt attempt = RootReboot.run("su", command -> new FakeProcess(exit), new FakeClock(), 150);
                equal(ProgressiveReboot.Status.UNCERTAIN, attempt.status);
                FakeRoutes access = new FakeRoutes();
                access.available(ProgressiveReboot.Route.ROOT, ProgressiveReboot.Route.DEVICE_OWNER);
                access.results.put(ProgressiveReboot.Route.ROOT, attempt);
                require(ProgressiveReboot.open(access).blocksRequests, "abnormal exit unlocked request gate");
                equal(Arrays.asList(ProgressiveReboot.Route.ROOT), access.executed);
            });
        }
        test("missing root executable does not start a process", () -> {
            ProgressiveReboot.Attempt result = RootReboot.run(null, command -> {
                throw new AssertionError("process started without executable");
            }, new FakeClock(), 150);
            equal(ProgressiveReboot.Status.FAILED, result.status);
        });
        test("root process start failure is reported", () -> equal(ProgressiveReboot.Status.FAILED,
                RootReboot.run("su", command -> { throw new IOException("not found"); },
                        new FakeClock(), 150).status));
        test("root authorization timeout is uncertain and destroys wrapper", () -> {
            FakeProcess process = new FakeProcess(null);
            ProgressiveReboot.Attempt result = RootReboot.run("su", command -> process, new FakeClock(), 150);
            equal(ProgressiveReboot.Status.UNCERTAIN, result.status);
            require(process.destroyed, "timed-out process not cleaned up");
            contains(result.message, "超时");
        });
        test("root diagnostic output is bounded", () -> {
            FakeProcess process = new FakeProcess(1);
            byte[] output = new byte[20000];
            Arrays.fill(output, (byte) 'x');
            process.output = new ByteArrayInputStream(output);
            ProgressiveReboot.Attempt result = RootReboot.run("su", command -> process, new FakeClock(), 150);
            require(result.message.length() < 5000, "unbounded command output");
        });
    }

    private static void sessionTests() {
        test("finishing and reopening an Activity shares the live execution gate", () -> {
            RebootSession session = new RebootSession();
            FakeActions access = new FakeActions();
            RebootController original = session.attach(access, false);
            List<Runnable> queued = new ArrayList<>();
            require(original.requestConfirmation(), "confirmation rejected");
            original.confirm(queued::add);
            RebootController reopened = session.attach(new FakeActions(), false);
            require(original == reopened, "live gate replaced on reopen");
            require(!reopened.canOpenOptions(), "reopened Activity can submit duplicate");
            queued.get(0).run();
            equal(1, access.executed.size());
        });
        test("completed unknown outcome remains blocked after finishing and reopening", () -> {
            RebootSession session = new RebootSession();
            FakeActions access = new FakeActions();
            access.result = new RebootController.Result(false, true, "unknown");
            RebootController original = session.attach(access, false);
            original.requestConfirmation();
            original.confirm(Runnable::run);
            require(!original.isInProgress() && original.blocksRequests(), "completed uncertainty not saved as blocked");
            require(!session.attach(new FakeActions(), false).canOpenOptions(), "reopen erased uncertainty");
        });
        test("process restoration of blocked state does not restore consent or execute", () -> {
            FakeActions access = new FakeActions();
            RebootController restored = new RebootSession().attach(access, true);
            require(restored.blocksRequests() && !restored.isInProgress(), "restored unresolved request is not blocked");
            require(!restored.confirm(Runnable::run), "process restore executed an action");
            require(!restored.requestConfirmation(RebootController.Action.APP_RESTART), "unknown device request permits app kill");
            contains(restored.result().message, "未知");
            equal(0, access.executed.size());
        });
        test("completed uncertain state survives process recreation marker", () -> {
            FakeActions access = new FakeActions();
            access.result = new RebootController.Result(false, true, "timeout");
            RebootController before = new RebootSession().attach(access, false);
            before.requestConfirmation();
            before.confirm(Runnable::run);
            RebootController after = new RebootSession().attach(new FakeActions(), before.blocksRequests());
            require(!after.canOpenOptions(), "completed-but-unknown outcome was forgotten");
        });
        test("stale saved busy marker does not override a live definite failure", () -> {
            RebootSession session = new RebootSession();
            FakeActions access = new FakeActions();
            access.result = new RebootController.Result(false, false, "definite failure");
            RebootController before = session.attach(access, false);
            before.requestConfirmation();
            before.confirm(Runnable::run);
            require(session.attach(new FakeActions(), true).canRequest(), "stale snapshot replaced known live result");
        });
        test("new process without blocked state starts idle but not confirmed", () -> {
            RebootController controller = new RebootSession().attach(new FakeActions(), false);
            require(controller.canOpenOptions(), "new session blocked");
            require(!controller.confirm(Runnable::run), "new session restored consent");
        });
        test("stale Activity confirmation or destruction cannot consume or cancel another action", () -> {
            FakeActions access = new FakeActions();
            RebootController controller = new RebootSession().attach(access, false);
            controller.requestConfirmation(RebootController.Action.DEVICE_REBOOT);
            long oldToken = controller.confirmationToken();
            controller.cancelConfirmation(oldToken);
            controller.requestConfirmation(RebootController.Action.APP_RESTART);
            long newToken = controller.confirmationToken();
            require(oldToken != newToken, "confirmation identity reused");
            require(!controller.confirm(oldToken, Runnable::run), "old device dialog confirmed app restart");
            controller.cancelConfirmation(oldToken);
            require(controller.confirm(newToken, Runnable::run), "old Activity destroyed new confirmation");
            equal(Arrays.asList(RebootController.Action.APP_RESTART), access.executed);
        });
    }

    private static void appRestartTests() {
        test("app restart schedules self launch before exiting own process", () -> {
            FakeRestart access = new FakeRestart();
            RebootController.Result result = AppRestartPlan.execute(access);
            equal(Arrays.asList("schedule", "stop"), access.calls);
            require(access.delay >= 1000, "unsafe 100ms restart race");
            require(result.requested, "app restart not scheduled");
            contains(result.message, "仅重启本应用");
        });
        test("failed restart scheduling never kills current app", () -> {
            FakeRestart access = new FakeRestart();
            access.scheduleFailure = true;
            require(!AppRestartPlan.execute(access).requested, "scheduling failure ignored");
            equal(Arrays.asList("schedule"), access.calls);
        });
        test("failed process stop cancels scheduled restart", () -> {
            FakeRestart access = new FakeRestart();
            access.stopFailure = true;
            require(!AppRestartPlan.execute(access).requested, "stop failure ignored");
            equal(Arrays.asList("schedule", "stop", "cancel"), access.calls);
        });
    }

    private static void vendorTests() {
        test("vendor settings allowlist is finite and immutable", () -> {
            equal(2, VendorRebootNavigator.candidates().size());
            try {
                VendorRebootNavigator.candidates().clear();
                throw new AssertionError("mutable vendor allowlist");
            } catch (UnsupportedOperationException expected) { }
        });
        test("vendor settings reject foreign components and aliases", () -> {
            VendorRebootNavigator.Target target = VendorRebootNavigator.candidates().get(0);
            require(VendorRebootNavigator.isAllowed(target.packageName, target.className, null), "direct target rejected");
            require(VendorRebootNavigator.isAllowed(target.packageName, target.className, target.className), "self alias rejected");
            require(!VendorRebootNavigator.isAllowed(target.packageName, target.className, "com.vendor.Other"), "foreign alias accepted");
            require(!VendorRebootNavigator.isAllowed("com.evil", target.className, null), "foreign package accepted");
            require(!VendorRebootNavigator.isAllowed(target.packageName, "com.vendor.Other", null), "foreign class accepted");
        });
        test("vendor addition never widens the standard settings policy", () -> {
            for (VendorRebootNavigator.Target target : VendorRebootNavigator.candidates()) {
                require(!SettingsNavigator.candidates().contains(target.className), "homepage allowlist widened");
                require(!SettingsNavigator.isStandardTarget(target.className), "homepage alias policy widened");
            }
        });
    }

    private static ProgressiveReboot.Attempt attempt(ProgressiveReboot.Status status, String message) {
        return new ProgressiveReboot.Attempt(status, message);
    }

    private static final class FakeRoutes implements ProgressiveReboot.Access {
        final EnumMap<ProgressiveReboot.Route, ProgressiveReboot.Availability> states = new EnumMap<>(ProgressiveReboot.Route.class);
        final EnumMap<ProgressiveReboot.Route, ProgressiveReboot.Attempt> results = new EnumMap<>(ProgressiveReboot.Route.class);
        final List<ProgressiveReboot.Route> executed = new ArrayList<>();
        ProgressiveReboot.Route failureRoute;
        RuntimeException failure;
        boolean inspectFailure;
        void available(ProgressiveReboot.Route... routes) {
            for (ProgressiveReboot.Route route : routes) states.put(route, ProgressiveReboot.Availability.AVAILABLE);
        }
        @Override public ProgressiveReboot.Availability inspect(ProgressiveReboot.Route route) {
            if (inspectFailure) throw new IllegalStateException("cannot inspect");
            return states.containsKey(route) ? states.get(route) : ProgressiveReboot.Availability.UNAVAILABLE;
        }
        @Override public ProgressiveReboot.Attempt perform(ProgressiveReboot.Route route) {
            executed.add(route);
            if (route == failureRoute) throw failure;
            return results.containsKey(route) ? results.get(route) : attempt(ProgressiveReboot.Status.REBOOT_REQUESTED, "requested");
        }
    }

    private static final class FakeActions implements RebootController.Access {
        final List<RebootController.Action> executed = new ArrayList<>();
        RebootController.Result result = new RebootController.Result(true, "requested");
        int legacyReboots;
        @Override public RebootController.Permission permission() { return RebootController.Permission.GRANTED; }
        @Override public RebootController.Permission permission(RebootController.Action action) { return permission(); }
        @Override public void reboot() { legacyReboots++; }
        @Override public RebootController.Result execute(RebootController.Action action) {
            executed.add(action);
            return result;
        }
    }

    private static final class FakeClock implements RootReboot.Clock {
        long now;
        @Override public long millis() { return now; }
        @Override public void pause(long millis) { now += millis; }
    }

    private static final class FakeProcess extends Process {
        final Integer exit;
        boolean destroyed;
        InputStream output = new ByteArrayInputStream(new byte[0]);
        FakeProcess(Integer exit) { this.exit = exit; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { throw new AssertionError("unbounded waitFor used"); }
        @Override public int exitValue() {
            if (exit == null) throw new IllegalThreadStateException("still running");
            return exit;
        }
        @Override public void destroy() { destroyed = true; }
    }

    private static final class FakeRestart implements AppRestartPlan.Access {
        final List<String> calls = new ArrayList<>();
        boolean scheduleFailure;
        boolean stopFailure;
        long delay;
        @Override public void schedule(long delayMillis) {
            calls.add("schedule");
            delay = delayMillis;
            if (scheduleFailure) throw new SecurityException("alarm denied");
        }
        @Override public void stopProcess() {
            calls.add("stop");
            if (stopFailure) throw new IllegalStateException("cannot stop");
        }
        @Override public void cancelSchedule() { calls.add("cancel"); }
    }

    private static void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL " + name + ": " + error);
        }
    }
    private static void equal(Object expected, Object actual) {
        require(expected == null ? actual == null : expected.equals(actual), "expected " + expected + " but was " + actual);
    }
    private static void contains(String text, String value) { require(text != null && text.contains(value), "missing " + value + " in " + text); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
