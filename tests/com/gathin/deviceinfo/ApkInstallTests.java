package com.gathin.deviceinfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** File/session tests only. Fixtures are never executed, installed or sent to a device. */
public final class ApkInstallTests {
    private static int passed;
    private static int failed;
    private static File temporary;
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("device-info-apk-tests-").toFile();
        try {
            fileTests();
            cacheTests();
            sessionTests();
            workTests();
            zipTests();
            if (args.length == 1) test("supplied release APK passes bounded preflight and snapshot copying", () -> {
                File artifact = new File(args[0]);
                ApkZip.preflight(artifact);
                ApkCache cache = cache("release-artifact", ApkCache.MAX_APK_BYTES, ApkCache.MAX_CACHE_BYTES);
                File snapshot = cache.stage(Files.newInputStream(artifact.toPath()), artifact.length(), () -> false);
                equal(artifact.length(), snapshot.length());
            });
        } finally { remove(temporary); }
        System.out.println("APK install tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void fileTests() {
        test("APK suffix accepts mixed case but not bundles or misleading names", () -> {
            require(ApkFiles.isApkName("应用.APK"), "uppercase APK rejected");
            for (String name : Arrays.asList(null, "", ".apk", "a.apks", "a.xapk", "a.apk.zip", "a.apk ", "../a.apk", "dir\\a.apk")) {
                require(!ApkFiles.isApkName(name), "bad name accepted: " + name);
            }
        });
        test("canonical containment includes root and descendants", () -> {
            File root = directory("containment");
            require(ApkFiles.within(root, root), "root rejected");
            require(ApkFiles.within(root, new File(root, "child/a.apk")), "child rejected");
        });
        test("canonical containment rejects traversal and prefix siblings", () -> {
            File root = directory("boundary");
            require(!ApkFiles.within(root, new File(root, "../outside.apk")), "parent traversal accepted");
            require(!ApkFiles.within(root, new File(root.getPath() + "-other/a.apk")), "prefix sibling accepted");
        });
        test("browser lists directories first and APKs only in stable order", () -> {
            File root = directory("listing");
            new File(root, "z-folder").mkdir();
            write(new File(root, "b.apk"), new byte[] { 1 });
            write(new File(root, "A.APK"), new byte[] { 1 });
            write(new File(root, "hidden.txt"), new byte[] { 1 });
            List<ApkFiles.Entry> rows = ApkFiles.list(root, root, 20);
            equal(3, rows.size());
            equal("z-folder", rows.get(0).file.getName());
            equal("A.APK", rows.get(1).file.getName());
            equal("b.apk", rows.get(2).file.getName());
            try { rows.clear(); throw new AssertionError("mutable listing"); }
            catch (UnsupportedOperationException expected) { }
        });
        test("browser rows capture type so rendering need not stat USB on the UI thread", () -> {
            File root = directory("cached-entry-type");
            File child = new File(root, "folder"); require(child.mkdir(), "mkdir failed");
            ApkFiles.Entry entry = ApkFiles.list(root, root, 20).get(0);
            require(child.delete(), "delete failed"); write(child, new byte[] { 1 });
            require(entry.directory, "entry type was not captured by listing");
        });
        test("browser never recursively enumerates children", () -> {
            File root = directory("shallow");
            File child = new File(root, "child"); child.mkdir();
            write(new File(child, "nested.apk"), new byte[] { 1 });
            equal(1, ApkFiles.list(root, root, 20).size());
        });
        test("browser rejects paths outside selected storage root", () -> {
            File root = directory("browse-root");
            expectIo(() -> ApkFiles.list(root, temporary, 20));
        });
        test("streaming enumeration stops before consuming entries past the limit", () -> {
            final int[] read = { 0 };
            java.util.Iterator<File> input = new java.util.Iterator<File>() {
                @Override public boolean hasNext() { return true; }
                @Override public File next() { read[0]++; return new File("entry" + read[0]); }
                @Override public void remove() { throw new UnsupportedOperationException(); }
            };
            expectIo(() -> ApkFiles.collect(input, 3));
            equal(3, read[0]);
        });
        test("cancelled directory work stops before further filesystem enumeration", () -> {
            File root = directory("interrupted-browser");
            Thread.currentThread().interrupt();
            try { expectIo(() -> ApkFiles.list(root, root, 20)); }
            finally { Thread.interrupted(); }
        });
        test("browser reports oversized directories and removed media", () -> {
            File root = directory("bounded-list");
            write(new File(root, "one.apk"), new byte[] { 1 });
            write(new File(root, "two.apk"), new byte[] { 1 });
            expectIo(() -> ApkFiles.list(root, root, 1));
            expectIo(() -> ApkFiles.list(root, new File(root, "removed-usb"), 20));
        });
        test("only opaque flat APK tokens can be shared", () -> {
            require(ApkFiles.isToken("0123456789abcdef0123456789abcdef.apk"), "valid token rejected");
            for (String token : Arrays.asList(null, "app.apk", "../app.apk", "/app.apk", "a%2fb.apk", "0123456789abcdef0123456789abcdef.apk/other", "0123456789abcdef0123456789abcdef.part")) {
                require(!ApkFiles.isToken(token), "invalid token accepted: " + token);
            }
        });
    }

    private static void cacheTests() {
        test("selected APK becomes an independent opaque snapshot", () -> {
            File source = new File(directory("snapshot-source"), "original.apk");
            byte[] bytes = apk(); write(source, bytes);
            ApkCache cache = cache("snapshot-cache", 8192, 16384);
            File staged = cache.stage(Files.newInputStream(source.toPath()), bytes.length, () -> false);
            require(ApkFiles.isToken(staged.getName()), "unsafe public name");
            require(Arrays.equals(bytes, Files.readAllBytes(staged.toPath())), "copy changed bytes");
            write(source, new byte[] { 2 });
            require(Arrays.equals(bytes, Files.readAllBytes(staged.toPath())), "source mutation affected snapshot");
            equal(staged.getCanonicalFile(), cache.resolve(staged.getName()));
        });
        test("snapshot does not delete or change the original", () -> {
            byte[] bytes = apk();
            File source = new File(directory("preserve-source"), "source.apk"); write(source, bytes);
            ApkCache cache = cache("preserve-cache", 8192, 16384);
            File staged = cache.stage(Files.newInputStream(source.toPath()), bytes.length, () -> false);
            cache.remove(staged.getName());
            require(source.exists() && Arrays.equals(bytes, Files.readAllBytes(source.toPath())), "original modified");
        });
        test("snapshot copy closes the selected input", () -> {
            final boolean[] closed = { false };
            InputStream stream = new ByteArrayInputStream(apk()) {
                @Override public void close() throws IOException { closed[0] = true; super.close(); }
            };
            cache("closed-input", 8192, 16384).stage(stream, -1, () -> false);
            require(closed[0], "input leaked");
        });
        test("input close failure cannot leave an unclaimed published snapshot", () -> {
            ApkCache cache = cache("close-failure", 8192, 16384);
            InputStream stream = new ByteArrayInputStream(apk()) {
                @Override public void close() throws IOException { throw new IOException("provider close failed"); }
            };
            expectIo(() -> cache.stage(stream, -1, () -> false));
            equal(0, cache.directory().list().length);
        });
        test("declared oversized input fails before writing and closes source", () -> {
            final boolean[] closed = { false };
            InputStream stream = new ByteArrayInputStream(apk()) {
                @Override public void close() throws IOException { closed[0] = true; super.close(); }
            };
            ApkCache cache = cache("declared-limit", 128, 256);
            expectIo(() -> cache.stage(stream, 1000, () -> false));
            require(closed[0], "rejected input leaked");
            equal(0, cache.directory().list().length);
        });
        test("actual bytes are bounded even if provider reports smaller size", () -> {
            ApkCache cache = cache("actual-limit", 32, 64);
            expectIo(() -> cache.stage(new ByteArrayInputStream(apk()), 1, () -> false));
            equal(0, cache.directory().list().length);
        });
        test("empty and malformed files never become shareable APKs", () -> {
            ApkCache cache = cache("invalid-apk", 8192, 16384);
            expectIo(() -> cache.stage(new ByteArrayInputStream(new byte[0]), 0, () -> false));
            expectIo(() -> cache.stage(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), 3, () -> false));
            expectIo(() -> cache.stage(new ByteArrayInputStream(zip("not-a-manifest.txt", new byte[] { 1 })), -1, () -> false));
            equal(0, cache.directory().list().length);
        });
        test("truncated ZIP is rejected and partial file removed", () -> {
            byte[] bytes = apk();
            ApkCache cache = cache("truncated-apk", 8192, 16384);
            expectIo(() -> cache.stage(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length / 2)), -1, () -> false));
            equal(0, cache.directory().list().length);
        });
        test("manifest decompression size is limited before Android parsing", () -> {
            ApkCache cache = cache("manifest-limit", 1024 * 1024, 2 * 1024 * 1024);
            byte[] bytes = zip("AndroidManifest.xml", new byte[5 * 1024 * 1024]);
            expectIo(() -> cache.stage(new ByteArrayInputStream(bytes), bytes.length, () -> false));
        });
        test("cancelled preparation cannot publish a snapshot", () -> {
            ApkCache cache = cache("cancelled-copy", 8192, 16384);
            expectIo(() -> cache.stage(new ByteArrayInputStream(apk()), -1, () -> true));
            equal(0, cache.directory().list().length);
        });
        test("source read error removes partial snapshot", () -> {
            ApkCache cache = cache("broken-input", 8192, 16384);
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("USB removed"); }
            };
            expectIo(() -> cache.stage(broken, -1, () -> false));
            equal(0, cache.directory().list().length);
        });
        test("cache quota includes existing snapshots", () -> {
            byte[] bytes = apk();
            ApkCache cache = cache("cache-quota", bytes.length + 1, bytes.length + 5);
            File first = cache.stage(new ByteArrayInputStream(bytes), -1, () -> false);
            expectIo(() -> cache.stage(new ByteArrayInputStream(bytes), -1, () -> false));
            require(first.exists(), "live first snapshot deleted for quota");
            equal(1, cache.directory().list().length);
        });
        test("provider cannot resolve traversal, unrecognized files or directories", () -> {
            ApkCache cache = cache("resolve-boundary", 8192, 16384);
            expectIo(() -> cache.resolve("../outside.apk"));
            expectIo(() -> cache.resolve("0123456789abcdef0123456789abcdef.apk"));
            File directory = new File(cache.directory(), "0123456789abcdef0123456789abcdef.apk"); directory.mkdir();
            expectIo(() -> cache.resolve(directory.getName()));
        });
        test("cache can prepare again after its directory is reclaimed", () -> {
            ApkCache cache = cache("reclaimed-cache", 8192, 16384);
            File before = cache.stage(new ByteArrayInputStream(apk()), -1, () -> false);
            cache.remove(before.getName());
            require(cache.directory().delete(), "cache directory not removed");
            File after = cache.stage(new ByteArrayInputStream(apk()), -1, () -> false);
            require(after.isFile(), "reclaimed directory never recovered");
        });
        test("expiry cleanup only removes recognized old staged artifacts", () -> {
            ApkCache cache = cache("expiry", 8192, 16384);
            File old = cache.stage(new ByteArrayInputStream(apk()), -1, () -> false);
            old.setWritable(true); require(old.setLastModified(1000), "timestamp failed");
            File recent = cache.stage(new ByteArrayInputStream(apk()), -1, () -> false);
            File unrelated = new File(cache.directory(), "keep.txt"); write(unrelated, new byte[] { 1 }); unrelated.setLastModified(1000);
            cache.cleanup(10000, 5000);
            require(!old.exists(), "expired snapshot retained");
            require(recent.exists() && unrelated.exists(), "cleanup removed unrelated/current files");
        });
    }

    private static void sessionTests() {
        test("idle session never installs without prepared selection", () -> {
            ApkInstallSession session = new ApkInstallSession();
            require(!session.launch(true), "idle session launched installer");
            equal(ApkInstallSession.Phase.IDLE, session.phase());
        });
        test("picker cancellation does not prepare or install", () -> {
            ApkInstallSession session = new ApkInstallSession();
            require(session.pick(), "picker rejected");
            session.cancelPicker();
            equal(ApkInstallSession.Phase.IDLE, session.phase());
            require(!session.launch(true), "cancelled picker installed");
        });
        test("one pending picker and one preparation at a time", () -> {
            ApkInstallSession session = new ApkInstallSession();
            require(session.pick() && !session.pick(), "duplicate picker allowed");
            long ticket = session.prepare();
            require(ticket != 0 && session.isPreparing(ticket), "preparation missing");
            equal(0L, session.prepare());
            require(!session.pick() && !session.launch(true), "busy preparation permits action");
        });
        test("preparation completion only enables an explicit installer action", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long ticket = session.prepare();
            require(session.complete(ticket, prepared()), "completion rejected");
            equal(ApkInstallSession.Phase.READY, session.phase());
            require(session.item() != null, "prepared metadata lost");
            require(session.launch(true), "explicit launch rejected");
            require(!session.launch(true), "duplicate installer launch accepted");
            require(session.expectsResult(), "result contract lost");
        });
        test("VIEW fallback never claims reliable installation result", () -> {
            ApkInstallSession session = ready();
            require(session.launch(false), "fallback launch rejected");
            require(!session.expectsResult(), "VIEW fallback claimed reliable result");
        });
        test("installer launch failure requires another user click", () -> {
            ApkInstallSession session = ready();
            session.launch(true);
            session.launchFailed("missing installer");
            equal(ApkInstallSession.Phase.READY, session.phase());
            equal("missing installer", session.error());
        });
        test("stale completion cannot replace a newer selection", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long old = session.prepare();
            session.reset();
            long current = session.prepare();
            require(!session.complete(old, prepared()), "stale snapshot published");
            require(session.isPreparing(current), "current preparation overwritten");
            require(session.complete(current, prepared()), "current completion rejected");
        });
        test("non-retained destruction cleans READY snapshots and invalidates preparation", () -> {
            ApkInstallSession session = ready();
            ApkInstallSession.Prepared item = session.item();
            require(session.detach(false) == item, "stopped non-finishing Activity orphaned snapshot");
            long ticket = session.prepare();
            session.detach(false);
            require(!session.complete(ticket, prepared()), "destroyed Activity accepted late preparation");
        });
        test("configuration retention preserves state but destroyed installer snapshots stay protected", () -> {
            ApkInstallSession session = ready();
            ApkInstallSession.Prepared item = session.item();
            require(session.detach(true) == null && session.item() == item, "rotation discarded retained snapshot");
            session.launch(true);
            require(session.detach(false) == null, "destroying Activity discarded handed-off APK");
        });
        test("reset atomically returns a snapshot completed after an earlier empty observation", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long ticket = session.prepare();
            require(session.item() == null, "unexpected pre-completion item");
            ApkInstallSession.Prepared item = prepared();
            require(session.complete(ticket, item), "completion rejected");
            require(session.reset() == item, "reset lost ownership of just-completed snapshot");
            require(session.item() == null, "reset retained snapshot");
        });
        test("reset never gives out a handed-off snapshot for premature deletion", () -> {
            ApkInstallSession session = ready(); session.launch(true);
            require(session.reset() == null, "in-use installer snapshot became discardable");
        });
        test("cancelled preparation stays cancelled after late failure", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long old = session.prepare();
            session.reset();
            session.failed(old, "late error");
            equal(ApkInstallSession.Phase.IDLE, session.phase());
            require(session.error() == null, "late failure changed idle session");
        });
        test("preparation failure cannot launch installer", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long ticket = session.prepare();
            session.failed(ticket, "USB removed");
            equal(ApkInstallSession.Phase.IDLE, session.phase());
            require(!session.launch(true), "failed preparation installed");
        });
        test("same retained session preserves copy but never dispatches installation", () -> {
            ApkInstallSession session = new ApkInstallSession();
            long ticket = session.prepare();
            ApkInstallSession rotated = session;
            require(rotated.complete(ticket, prepared()), "retained preparation lost");
            equal(ApkInstallSession.Phase.READY, rotated.phase());
        });
        for (ApkInstallSession.Phase phase : ApkInstallSession.Phase.values()) {
            test("process restore never restores prepared file or install consent: " + phase, () -> {
                ApkInstallSession restored = ApkInstallSession.restore(phase.name(), true);
                require(restored.item() == null && !restored.launch(true), "process restore installed old selection");
                equal(phase == ApkInstallSession.Phase.PICKING || phase == ApkInstallSession.Phase.INSTALLING
                        ? phase : ApkInstallSession.Phase.IDLE, restored.phase());
            });
        }
        test("invalid saved phase restores safely idle", () -> equal(ApkInstallSession.Phase.IDLE,
                ApkInstallSession.restore("bogus", false).phase()));
        test("finishing installer clears selection without triggering another action", () -> {
            ApkInstallSession session = ready();
            session.launch(true);
            session.finish();
            equal(ApkInstallSession.Phase.IDLE, session.phase());
            require(!session.launch(true), "installer result relaunched");
        });
    }

    private static void zipTests() {
        test("bounded ZIP preflight accepts an ordinary single APK archive", () -> {
            File file = new File(directory("preflight-valid"), "test.apk"); write(file, apk());
            ApkZip.preflight(file);
        });
        test("ZIP preflight rejects excessive declared entry count before indexing", () -> {
            byte[] bytes = apk();
            int end = bytes.length - 22;
            put16(bytes, end + 8, 60000); put16(bytes, end + 10, 60000);
            File file = new File(directory("preflight-count"), "test.apk"); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
        });
        test("ZIP preflight bounds a central directory even in a small sparse APK", () -> {
            File file = new File(directory("preflight-size"), "test.apk");
            byte[] footer = new byte[22]; put32(footer, 0, 0x06054b50L);
            put16(footer, 8, 1); put16(footer, 10, 1); put32(footer, 12, 5L * 1024 * 1024);
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.setLength(5L * 1024 * 1024 + 22); output.seek(5L * 1024 * 1024); output.write(footer);
            }
            expectIo(() -> ApkZip.preflight(file));
        });
        test("ZIP preflight rejects ZIP64 sentinels and multi-disk archives", () -> {
            byte[] bytes = apk(); int end = bytes.length - 22;
            put16(bytes, end + 8, 0xffff); put16(bytes, end + 10, 0xffff);
            File file = new File(directory("preflight-zip64"), "test.apk"); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
            bytes = apk(); put16(bytes, bytes.length - 22 + 4, 1); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
        });
        test("ZIP preflight rejects central record length escaping its declared boundary", () -> {
            byte[] bytes = apk(); int central = signature(bytes, 0x02014b50L);
            put16(bytes, central + 28, 65535);
            File file = new File(directory("preflight-record"), "test.apk"); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
        });
        test("ZIP preflight rejects entry-count mismatch and oversized manifest declarations", () -> {
            byte[] bytes = apk(); int end = bytes.length - 22;
            put16(bytes, end + 8, 2); put16(bytes, end + 10, 2);
            File file = new File(directory("preflight-mismatch"), "test.apk"); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
            bytes = apk(); put32(bytes, signature(bytes, 0x02014b50L) + 24, 5L * 1024 * 1024); write(file, bytes);
            expectIo(() -> ApkZip.preflight(file));
        });
        test("ZIP preflight rejects truncated or ambiguous footer rather than guessing", () -> {
            byte[] bytes = apk();
            File file = new File(directory("preflight-footer"), "test.apk");
            write(file, Arrays.copyOf(bytes, bytes.length - 2)); expectIo(() -> ApkZip.preflight(file));
            byte[] trailing = Arrays.copyOf(bytes, bytes.length + 4); put32(trailing, bytes.length, 0x06054b50L);
            write(file, trailing); expectIo(() -> ApkZip.preflight(file));
        });
    }

    private static int signature(byte[] bytes, long value) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            long found = (bytes[i] & 255L) | ((bytes[i + 1] & 255L) << 8) | ((bytes[i + 2] & 255L) << 16) | ((bytes[i + 3] & 255L) << 24);
            if (found == value) return i;
        }
        throw new AssertionError("fixture signature absent");
    }
    private static void put16(byte[] bytes, int offset, int value) { bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >>> 8); }
    private static void put32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void workTests() {
        test("blocked abandoned work still counts against bounded worker and queue limits", () -> {
            ApkWorkQueue queue = new ApkWorkQueue(2, 1);
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Runnable blocked = () -> { started.countDown(); awaitIgnoringInterrupt(release); };
            ApkWorkQueue.Handle first = queue.submit(blocked);
            queue.submit(blocked);
            try {
                require(started.await(2, TimeUnit.SECONDS), "workers did not start");
                ApkWorkQueue.Handle queued = queue.submit(() -> { });
                first.cancel(null); // Simulates a provider which ignores interruption.
                try { queue.submit(() -> { }); throw new AssertionError("unbounded work accepted"); }
                catch (RejectedExecutionException expected) { }
                queued.cancel(null); // A cancelled queued task must release its queue slot.
                queue.submit(() -> { });
            } finally { release.countDown(); }
        });
        test("potentially blocking IO cancellation never runs on the caller thread", () -> {
            ApkWorkQueue queue = new ApkWorkQueue(1, 1);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            CountDownLatch cancelReturned = new CountDownLatch(1);
            ApkWorkQueue.Handle task = queue.submit(() -> { started.countDown(); awaitIgnoringInterrupt(releaseWorker); });
            try {
                require(started.await(2, TimeUnit.SECONDS), "worker did not start");
                Thread caller = new Thread(() -> {
                    task.cancel(() -> { cleanupStarted.countDown(); awaitIgnoringInterrupt(releaseCleanup); });
                    cancelReturned.countDown();
                });
                caller.setDaemon(true); caller.start();
                require(cancelReturned.await(2, TimeUnit.SECONDS), "caller blocked cancelling IO");
                require(cleanupStarted.await(2, TimeUnit.SECONDS), "IO cancellation not dispatched");
            } finally { releaseCleanup.countDown(); releaseWorker.countDown(); }
        });
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try { latch.await(); return; }
            catch (InterruptedException ignored) { /* Fake unresponsive provider, released by test. */ }
        }
    }

    private static ApkInstallSession ready() {
        ApkInstallSession session = new ApkInstallSession();
        session.complete(session.prepare(), prepared());
        return session;
    }
    private static ApkInstallSession.Prepared prepared() {
        return new ApkInstallSession.Prepared("0123456789abcdef0123456789abcdef.apk", "选择.apk", "example.app", "1.0", 100);
    }
    private static File directory(String name) throws IOException {
        File dir = new File(temporary, name);
        if (!dir.mkdirs()) throw new IOException("cannot create test directory " + name);
        return dir;
    }
    private static ApkCache cache(String name, long maxFile, long quota) throws IOException {
        return new ApkCache(directory(name), maxFile, quota);
    }
    private static byte[] apk() throws IOException { return zip("AndroidManifest.xml", new byte[] { 1, 2, 3 }); }
    private static byte[] zip(String entry, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry)); zip.write(content); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static void write(File file, byte[] bytes) throws IOException { Files.write(file.toPath(), bytes); }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.setWritable(true); file.delete();
    }
    private static void expectIo(Check body) throws Exception {
        try { body.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { }
    }
    private static void test(String name, Check body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure); }
    }
    private static void equal(Object expected, Object actual) { require(expected == null ? actual == null : expected.equals(actual), "expected " + expected + " but was " + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
