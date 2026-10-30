package com.gathin.deviceinfo;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.OpenableColumns;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Public Android file/installer APIs only. No shell, Root or silent installation. */
final class AndroidApkAccess {
    static final String MIME = "application/vnd.android.package-archive";
    private static ApkCache sharedCache;
    private final Context context;

    static final class Launch {
        final Intent intent;
        final boolean returnsResult;
        Launch(Intent intent, boolean returnsResult) { this.intent = intent; this.returnsResult = returnsResult; }
    }

    /** Called only on the bounded cancellation pool, never from the UI thread. */
    static final class InputControl implements Runnable {
        final CancellationSignal signal = new CancellationSignal(); // API16.
        volatile InputStream stream;
        @Override public void run() {
            try {
                InputStream current = stream;
                if (current != null) try { current.close(); } catch (IOException ignored) { }
            } finally { signal.cancel(); }
        }
    }

    AndroidApkAccess(Context context) { this.context = context.getApplicationContext(); }

    static String authority(Context context) { return context.getPackageName() + ".apkfiles"; }

    static synchronized ApkCache cache(Context context) throws IOException {
        if (sharedCache == null) {
            File directory = new File(context.getCacheDir(), Build.VERSION.SDK_INT < 24 ? "apk-install-legacy" : "apk-install");
            // Old AOSP installers reject content URIs; only the dedicated legacy directory is
            // traversable. The cache policy reapplies those rights if the directory is reclaimed.
            sharedCache = new ApkCache(directory, ApkCache.MAX_APK_BYTES, ApkCache.MAX_CACHE_BYTES, Build.VERSION.SDK_INT < 24);
        }
        return sharedCache;
    }

    void cleanup() throws IOException { cache(context).cleanup(System.currentTimeMillis(), ApkCache.MAX_AGE_MILLIS); }
    void discard(ApkInstallSession.Prepared item) {
        if (item != null) try { cache(context).remove(item.token); } catch (IOException ignored) { }
    }

    static List<Intent> pickerIntents() {
        List<Intent> intents = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 19) intents.add(Api19.picker());
        intents.add(picker(Intent.ACTION_GET_CONTENT));
        return intents;
    }

    private static Intent picker(String action) {
        // Some USB/document providers label APKs as generic binary: validate after selection.
        return new Intent(action).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    boolean needsReadPermission() {
        return Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28 && !Api23.canRead(context);
    }

    boolean canInstallPackages() { return Build.VERSION.SDK_INT < 26 || Api26.canInstall(context); }

    Intent unknownSourceSettings() throws IOException {
        if (Build.VERSION.SDK_INT < 26) throw new IOException("请在系统安装器中处理旧系统的未知来源设置");
        Intent intent = Api26.settings(context).setPackage(SettingsNavigator.PACKAGE);
        Intent explicit = resolveSystem(intent);
        if (explicit == null) throw new IOException("未找到可用的“允许安装未知应用”设置，请手动在系统设置中查找");
        return explicit;
    }

    List<File> roots() {
        List<File> roots = new ArrayList<>();
        addRoot(roots, Environment.getExternalStorageDirectory());
        if (Build.VERSION.SDK_INT >= 30) {
            try { for (File volume : Api30.volumes(context)) addRoot(roots, volume); }
            catch (RuntimeException restricted) { /* Keep legacy public storage candidates. */ }
        }
        for (File candidate : mountCandidates("/storage")) {
            if (!"emulated".equals(candidate.getName()) && !"self".equals(candidate.getName())) addRoot(roots, candidate);
        }
        for (File candidate : mountCandidates("/mnt")) {
            String name = candidate.getName().toLowerCase(Locale.ROOT);
            if (name.contains("usb") || name.contains("udisk") || name.contains("sdcard")
                    || name.startsWith("ext") || name.equals("media_rw")) addRoot(roots, candidate);
        }
        for (String path : new String[] { "/mnt/usb_storage", "/mnt/usbhost1", "/mnt/udisk", "/mnt/external_sd", "/udisk" }) {
            addRoot(roots, new File(path));
        }
        return Collections.unmodifiableList(roots);
    }

    List<ApkFiles.Entry> list(File root, File directory) throws IOException {
        if (!ApkFiles.within(root, directory)) throw new IOException("目录超出所选存储范围");
        return ApkFiles.list(root, directory, 2000, children(directory, 2000));
    }

    private static List<File> children(File directory, int limit) throws IOException {
        return Build.VERSION.SDK_INT >= 26 ? Api26.children(directory, limit) : ApkFiles.legacyChildren(directory, limit);
    }

    private static List<File> mountCandidates(String path) {
        try { return children(new File(path), 2000); }
        catch (IOException | RuntimeException unavailable) { return Collections.emptyList(); }
    }

    private static void addRoot(List<File> roots, File file) {
        if (file == null || !file.isDirectory()) return;
        try {
            File root = file.getCanonicalFile();
            if (!roots.contains(root)) roots.add(root);
        } catch (IOException ignored) { }
    }

    ApkInstallSession.Prepared prepare(Uri uri, ApkCache.Cancellation cancellation, InputControl control) throws IOException {
        if (uri == null) throw new IOException("未选择文件");
        String name;
        long size = -1;
        File local = null;
        if ("file".equals(uri.getScheme())) {
            if (uri.getPath() == null || uri.getAuthority() != null && !uri.getAuthority().isEmpty()) throw new IOException("无效的本地文件路径");
            local = new File(uri.getPath()).getCanonicalFile();
            boolean allowed = false;
            for (File root : roots()) if (ApkFiles.within(root, local)) { allowed = true; break; }
            if (!allowed || !local.isFile() || !local.canRead()) throw new IOException("文件不在可读取的存储范围，或 USB 已移除；请使用系统选择器");
            name = local.getName();
            size = local.length();
        } else if ("content".equals(uri.getScheme()) && !authority(context).equals(uri.getAuthority())) {
            name = null;
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null, control.signal)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int display = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int length = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (display >= 0 && !cursor.isNull(display)) name = cursor.getString(display);
                    if (length >= 0 && !cursor.isNull(length)) size = cursor.getLong(length);
                }
            }
            if (name == null && uri.getLastPathSegment() != null) name = new File(uri.getLastPathSegment()).getName();
        } else {
            throw new IOException("仅接受用户选择的本地文件或文档 URI，不支持下载链接/内部缓存引用");
        }
        if (!ApkFiles.isApkName(name)) throw new IOException("请选择单个 .apk 文件，不支持 APKS/XAPK 或其他文件类型");
        if (cancellation.cancelled()) throw new IOException("已取消准备 APK");
        ApkCache cache = cache(context);
        cleanup();
        InputStream source = local == null ? openDocument(uri, control.signal) : new FileInputStream(local);
        final File snapshot;
        control.stream = source;
        try { snapshot = cache.stage(source, size, cancellation); }
        finally { control.stream = null; }
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(snapshot.getAbsolutePath(), 0);
            if (info == null || info.applicationInfo == null || info.packageName == null) {
                throw new IOException("系统无法解析此 APK：文件可能损坏或不兼容当前 Android");
            }
            if (cancellation.cancelled()) throw new IOException("已取消准备 APK");
            return new ApkInstallSession.Prepared(snapshot.getName(), display(name), info.packageName,
                    info.versionName == null ? "?" : display(info.versionName), snapshot.length());
        } catch (IOException | RuntimeException failure) {
            cache.remove(snapshot.getName());
            throw failure;
        }
    }

    private InputStream openDocument(Uri uri, CancellationSignal signal) throws IOException {
        AssetFileDescriptor descriptor = Build.VERSION.SDK_INT >= 19 ? Api19.open(context, uri, signal)
                : context.getContentResolver().openAssetFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("文档提供方未返回可读文件");
        try {
            signal.throwIfCanceled();
            return descriptor.createInputStream(); // Closing this stream also closes its descriptor.
        } catch (IOException | RuntimeException failure) {
            descriptor.close();
            throw failure;
        }
    }

    Launch installer(ApkInstallSession.Prepared item) throws IOException {
        if (item == null) throw new IOException("请先选择 APK");
        File file = cache(context).resolve(item.token);
        Uri uri = Build.VERSION.SDK_INT >= 24
                ? new Uri.Builder().scheme("content").authority(authority(context)).appendPath("apk").appendPath(item.token).build()
                : Uri.fromFile(file);
        Intent preferred = installIntent(Intent.ACTION_INSTALL_PACKAGE, uri).putExtra(Intent.EXTRA_RETURN_RESULT, true);
        Intent explicit = resolveSystem(preferred);
        boolean returnsResult = explicit != null;
        if (explicit == null) explicit = resolveSystem(installIntent(Intent.ACTION_VIEW, uri));
        if (explicit == null) throw new IOException("未找到可用的系统 APK 安装器；不会改用 Root 或静默安装");
        if (Build.VERSION.SDK_INT < 24 && !file.setReadable(true, false)) throw new IOException("无法让旧系统安装器读取所选 APK");
        return new Launch(explicit, returnsResult);
    }

    private static Intent installIntent(String action, Uri uri) {
        Intent intent = new Intent(action).setDataAndType(uri, MIME);
        if ("content".equals(uri.getScheme())) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("APK", uri));
        }
        return intent;
    }

    /** Resolve before adding a grant; never send the selected APK to an arbitrary third-party handler. */
    private Intent resolveSystem(Intent intent) {
        PackageManager packages = context.getPackageManager();
        List<ResolveInfo> matches = packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (matches == null) return null;
        for (ResolveInfo match : matches) {
            ActivityInfo info = match.activityInfo;
            if (info == null || info.applicationInfo == null) continue;
            try {
                ComponentName component = new ComponentName(info.packageName, info.name);
                info = packages.getActivityInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
                ApplicationInfo app = info.applicationInfo;
                if (app == null || !info.packageName.equals(app.packageName)
                        || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
                        || !enabled(packages.getApplicationEnabledSetting(info.packageName), app.enabled)
                        || !enabled(packages.getComponentEnabledSetting(component), info.enabled) || !info.exported) continue;
                if (info.permission != null && !info.permission.isEmpty()
                        && context.checkCallingOrSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) continue;
                if (info.targetActivity != null && !info.targetActivity.isEmpty()) {
                    String target = info.targetActivity.startsWith(".") ? info.packageName + info.targetActivity
                            : info.targetActivity.indexOf('.') < 0 ? info.packageName + "." + info.targetActivity : info.targetActivity;
                    ComponentName targetComponent = new ComponentName(info.packageName, target);
                    ActivityInfo actual = packages.getActivityInfo(targetComponent, PackageManager.GET_DISABLED_COMPONENTS);
                    if (!enabled(packages.getComponentEnabledSetting(targetComponent), actual.enabled)) continue;
                }
                return new Intent(intent).setComponent(component);
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Try next system match. */ }
        }
        return null;
    }

    private static boolean enabled(int override, boolean defaultValue) {
        return override == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? defaultValue : override == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static String display(String text) {
        if (text == null) return "?";
        String clean = text.replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]", "?");
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }

    @TargetApi(19)
    private static final class Api19 {
        static Intent picker() { return AndroidApkAccess.picker(Intent.ACTION_OPEN_DOCUMENT); }
        static AssetFileDescriptor open(Context context, Uri uri, CancellationSignal signal) throws IOException {
            return context.getContentResolver().openAssetFileDescriptor(uri, "r", signal);
        }
    }
    @TargetApi(23)
    private static final class Api23 {
        static boolean canRead(Context context) { return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED; }
    }
    @TargetApi(26)
    private static final class Api26 {
        static List<File> children(File directory, int limit) throws IOException {
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = java.nio.file.Files.newDirectoryStream(directory.toPath())) {
                final java.util.Iterator<java.nio.file.Path> paths = stream.iterator();
                return ApkFiles.collect(new java.util.Iterator<File>() {
                    @Override public boolean hasNext() { return paths.hasNext(); }
                    @Override public File next() { return paths.next().toFile(); }
                    @Override public void remove() { throw new UnsupportedOperationException(); }
                }, limit);
            }
        }
        static boolean canInstall(Context context) { return context.getPackageManager().canRequestPackageInstalls(); }
        static Intent settings(Context context) { return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())); }
    }
    @TargetApi(30)
    private static final class Api30 {
        static List<File> volumes(Context context) {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            List<File> roots = new ArrayList<>();
            if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) {
                String state = volume.getState();
                if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) roots.add(volume.getDirectory());
            }
            return roots;
        }
    }
}
