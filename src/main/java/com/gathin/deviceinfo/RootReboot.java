package com.gathin.deviceinfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded fixed-command runner. A timed-out su may have dispatched reboot: never retry blindly. */
final class RootReboot {
    interface ProcessFactory { Process start(String[] command) throws IOException; }
    interface Clock {
        long millis();
        void pause(long millis) throws InterruptedException;
    }

    private RootReboot() { }

    static ProgressiveReboot.Attempt run(String executable, ProcessFactory factory, Clock clock, long timeoutMillis) {
        if (executable == null || executable.trim().isEmpty()) return result(ProgressiveReboot.Status.FAILED, "未找到 su");
        Process process = null;
        boolean exited = false;
        try {
            process = factory.start(new String[] { executable, "-c", "reboot" });
            final Capture capture = new Capture(process.getInputStream());
            Thread reader = new Thread(capture, "root-reboot-output");
            reader.setDaemon(true);
            reader.start();
            process.getOutputStream().close();
            long started = clock.millis();
            while (true) {
                try {
                    int code = process.exitValue();
                    exited = true;
                    reader.join(100);
                    // Only conventional command/authorization failure codes permit fallback.
                    // Signals (e.g. 137/143), negative and unclassified codes do not prove no dispatch.
                    ProgressiveReboot.Status status = code == 0 ? ProgressiveReboot.Status.REBOOT_REQUESTED
                            : code == 1 || code == 126 || code == 127 ? ProgressiveReboot.Status.FAILED
                            : ProgressiveReboot.Status.UNCERTAIN;
                    String output = capture.text();
                    return result(status, "su 退出码 " + code + (output.isEmpty() ? "" : "；" + output));
                } catch (IllegalThreadStateException running) {
                    if (clock.millis() - started >= timeoutMillis) {
                        return result(ProgressiveReboot.Status.UNCERTAIN, "Root 请求超时；可能仍在等待授权或已经提交重启，停止后续尝试");
                    }
                    clock.pause(50);
                }
            }
        } catch (IOException failure) {
            return result(process == null ? ProgressiveReboot.Status.FAILED : ProgressiveReboot.Status.UNCERTAIN,
                    "Root 命令错误：" + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(ProgressiveReboot.Status.UNCERTAIN, "Root 请求被中断，执行状态未知");
        } catch (RuntimeException failure) {
            return result(ProgressiveReboot.Status.UNCERTAIN, "Root 请求状态未知：" + failure.getClass().getSimpleName());
        } finally {
            if (process != null && !exited) {
                try { process.destroy(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static ProgressiveReboot.Attempt result(ProgressiveReboot.Status status, String message) {
        return new ProgressiveReboot.Attempt(status, message);
    }

    /** Drain to avoid pipe deadlock, but retain only a small amount of diagnostic text. */
    private static final class Capture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Capture(InputStream input) { this.input = input; }
        @Override public void run() {
            byte[] buffer = new byte[256];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (this) {
                        int keep = Math.min(count, 2048 - bytes.size());
                        if (keep > 0) bytes.write(buffer, 0, keep);
                    }
                }
            } catch (IOException ignored) {
                // Process exit/cleanup can close the pipe. The exit/timeout outcome remains authoritative.
            } finally {
                try { input.close(); } catch (IOException ignored) { }
            }
        }
        synchronized String text() {
            try { return bytes.toString("UTF-8").trim(); }
            catch (IOException impossible) { return ""; }
        }
    }
}
