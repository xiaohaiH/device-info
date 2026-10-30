package com.gathin.deviceinfo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed resource budget even when a cancelled USB/provider call ignores interruption. */
final class ApkWorkQueue {
    private static final ThreadPoolExecutor CANCELLATIONS = pool(1, 4, "apk-io-cancel");
    private final ThreadPoolExecutor workers;

    ApkWorkQueue(int threads, int queued) { workers = pool(threads, queued, "apk-io"); }

    Handle submit(Runnable operation) {
        Task task = new Task(operation);
        workers.execute(task); // Caller reports rejection instead of spawning an unbounded fallback.
        return new Handle(workers, task);
    }

    static final class Handle {
        private final ThreadPoolExecutor owner;
        private final Task task;
        Handle(ThreadPoolExecutor owner, Task task) { this.owner = owner; this.task = task; }
        void cancel(final Runnable closeOwnedIo) {
            if (!task.cancel(true)) return;
            owner.remove(task); // Free queued work, not the slot of an actually blocked running call.
            if (!task.started || closeOwnedIo == null) return;
            try {
                CANCELLATIONS.execute(new Runnable() {
                    @Override public void run() {
                        try { closeOwnedIo.run(); } catch (RuntimeException ignored) { }
                    }
                });
            } catch (RejectedExecutionException busy) {
                // Cleanup itself can block in a broken provider. Its budget is also fixed.
                // The worker remains counted; session invalidation still forbids publishing/installing.
            }
        }
    }

    private static final class Task extends FutureTask<Void> {
        volatile boolean started;
        Task(Runnable operation) { super(operation, null); }
        @Override public void run() { started = true; super.run(); }
    }

    private static ThreadPoolExecutor pool(int threads, int queued, final String name) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queued), new ThreadFactory() {
                    @Override public Thread newThread(Runnable work) {
                        Thread thread = new Thread(work, name);
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
