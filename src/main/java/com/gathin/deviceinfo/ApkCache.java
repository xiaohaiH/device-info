package com.gathin.deviceinfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Private selected-file snapshots. Never exposes a source path or extracts APK contents. */
final class ApkCache {
    interface Cancellation { boolean cancelled(); }
    static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    static final long MAX_CACHE_BYTES = 1024L * 1024 * 1024;
    static final long MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private static final Object WRITE_LOCK = new Object();
    private final File directory;
    private final long maxFileBytes;
    private final long maxCacheBytes;
    private final boolean legacyDirectory;

    ApkCache(File directory, long maxFileBytes, long maxCacheBytes) throws IOException {
        this(directory, maxFileBytes, maxCacheBytes, false);
    }

    ApkCache(File directory, long maxFileBytes, long maxCacheBytes, boolean legacyDirectory) throws IOException {
        this.directory = directory.getCanonicalFile();
        this.maxFileBytes = maxFileBytes;
        this.maxCacheBytes = maxCacheBytes;
        this.legacyDirectory = legacyDirectory;
        ensureDirectory(true);
    }

    private void ensureDirectory(boolean initial) throws IOException {
        synchronized (WRITE_LOCK) {
            boolean missing = !directory.isDirectory();
            if (missing && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建安装临时目录");
            if (legacyDirectory && (missing || initial)) {
                // Reapply only this directory's legacy traversal rights after cache reclamation.
                // Never chmod a parent directory or allow other users to list/write this directory.
                if (!directory.setReadable(false, false) || !directory.setReadable(true, true)
                        || !directory.setWritable(false, false) || !directory.setWritable(true, true)
                        || !directory.setExecutable(true, false)) throw new IOException("无法准备旧系统安装目录");
            }
        }
    }

    File directory() { return directory; }

    File stage(InputStream input, long declaredSize, Cancellation cancellation) throws IOException {
        File partial = null;
        File finished = null;
        boolean published = false;
        try (InputStream source = input) {
            if (source == null) throw new IOException("无法读取所选文件");
            if (declaredSize > maxFileBytes) throw new IOException("APK 超过 512 MiB 大小限制");
            checkCancelled(cancellation);
            ensureDirectory(false);
            String token = UUID.randomUUID().toString().replace("-", "");
            partial = new File(directory, token + ".part");
            finished = new File(directory, token + ".apk");
            if (!partial.createNewFile()) throw new IOException("无法创建 APK 快照");
            long total = 0;
            byte[] buffer = new byte[32768];
            try (FileOutputStream output = new FileOutputStream(partial)) {
                int count;
                while ((count = source.read(buffer)) != -1) {
                    checkCancelled(cancellation);
                    if (count == 0) continue;
                    if (count > maxFileBytes - total) throw new IOException("APK 超过 512 MiB 大小限制");
                    synchronized (WRITE_LOCK) {
                        if (count > maxCacheBytes - usage()) throw new IOException("安装临时缓存已达上限，请稍后重试或清理本应用缓存");
                        output.write(buffer, 0, count);
                    }
                    total += count;
                }
                if (total == 0) throw new IOException("所选文件为空");
            }
            checkCancelled(cancellation);
            validate(partial);
            synchronized (WRITE_LOCK) {
                if (!partial.renameTo(finished)) throw new IOException("无法完成 APK 快照");
            }
            if (!finished.setReadOnly()) throw new IOException("无法保护 APK 快照为只读");
            checkCancelled(cancellation);
            published = true;
            return finished;
        } catch (IOException | RuntimeException failure) {
            // Includes a provider failing close() after the copy/rename completed.
            published = false;
            throw failure;
        } finally {
            if (partial != null) partial.delete();
            if (!published && finished != null) delete(finished);
        }
    }

    File resolve(String token) throws IOException {
        if (!ApkFiles.isToken(token)) throw new IOException("无效的安装文件标识");
        File file = new File(directory, token).getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.isFile()) throw new IOException("安装快照不存在或已过期");
        return file;
    }

    void remove(String token) {
        try { delete(resolve(token)); } catch (IOException ignored) { }
    }

    void cleanup(long now, long maxAge) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (artifact(file.getName()) && file.isFile() && file.lastModified() > 0 && now - file.lastModified() > maxAge) {
                try {
                    if (directory.equals(file.getCanonicalFile().getParentFile())) delete(file);
                } catch (IOException ignored) { }
            }
        }
    }

    private long usage() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("无法检查安装缓存空间");
        long bytes = 0;
        for (File file : files) if (artifact(file.getName()) && file.isFile()) bytes += file.length();
        return bytes;
    }

    private static boolean artifact(String name) {
        return ApkFiles.isToken(name) || name.matches("[0-9a-f]{32}\\.part");
    }

    private static void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancellation.cancelled()) throw new InterruptedIOException("已取消准备 APK");
    }

    private static void validate(File file) throws IOException {
        ApkZip.preflight(file); // Bound central-directory allocations before opening ZipFile.
        try (ZipFile archive = new ZipFile(file)) {
            ZipEntry manifest = archive.getEntry("AndroidManifest.xml");
            if (manifest == null || manifest.isDirectory() || manifest.getSize() <= 0 || manifest.getSize() > 4L * 1024 * 1024) {
                throw new IOException("不是受支持的单个 APK：AndroidManifest.xml 缺失或过大");
            }
            ZipEntry resources = archive.getEntry("resources.arsc");
            if (archive.size() > ApkZip.MAX_ENTRIES || resources != null && resources.getSize() > 64L * 1024 * 1024) {
                throw new IOException("APK 结构过大，无法安全解析");
            }
        }
    }

    private static void delete(File file) {
        // Restores owner-only writability for host-side tests/cleanup, never world-write.
        if (file.exists()) { file.setWritable(true, true); file.delete(); }
    }
}
