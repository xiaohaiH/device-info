package com.gathin.deviceinfo;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.Locale;

/** Read-only, shallow storage browsing. No recursive search or arbitrary filesystem root. */
final class ApkFiles {
    static final class Entry {
        final File file;
        final boolean directory;
        Entry(File file, boolean directory) { this.file = file; this.directory = directory; }
    }
    private ApkFiles() { }

    static boolean isApkName(String name) {
        return name != null && name.length() > 4 && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static boolean isToken(String name) { return name != null && name.matches("[0-9a-f]{32}\\.apk"); }

    static boolean within(File root, File file) throws IOException {
        String base = root.getCanonicalPath();
        String path = file.getCanonicalPath();
        return path.equals(base) || path.startsWith(base.endsWith(File.separator) ? base : base + File.separator);
    }

    static List<Entry> list(File root, File directory, int limit) throws IOException {
        checkCancelled();
        if (!within(root, directory)) throw new IOException("目录超出所选存储范围");
        return list(root, directory, limit, legacyChildren(directory, limit));
    }

    static List<Entry> list(File root, File directory, int limit, List<File> children) throws IOException {
        checkCancelled();
        if (!within(root, directory)) throw new IOException("目录超出所选存储范围");
        if (children.size() > limit) throw new IOException("目录项目过多，请使用系统文件选择器");
        List<Entry> entries = new ArrayList<>();
        for (File child : children) {
            checkCancelled();
            if (!within(root, child)) continue;
            boolean isDirectory = child.isDirectory();
            if (isDirectory || child.isFile() && isApkName(child.getName())) entries.add(new Entry(child, isDirectory));
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                int order = left.file.getName().compareToIgnoreCase(right.file.getName());
                return order != 0 ? order : left.file.getName().compareTo(right.file.getName());
            }
        });
        return Collections.unmodifiableList(entries);
    }

    /** Legacy API: the platform still allocates ALL names before this check (not a hard memory bound). */
    static List<File> legacyChildren(File directory, int limit) throws IOException {
        checkCancelled();
        String[] names = directory.list(); // Avoid also creating an unbounded File[] as listFiles() does.
        checkCancelled();
        if (names == null) throw new IOException("无法读取目录：存储可能已移除或访问受限，请尝试系统文件选择器");
        if (names.length > limit) throw new IOException("目录项目过多，请使用系统文件选择器");
        List<File> children = new ArrayList<>(names.length);
        for (String name : names) children.add(new File(directory, name));
        return children;
    }

    static List<File> collect(Iterator<File> input, int limit) throws IOException {
        List<File> children = new ArrayList<>();
        checkCancelled();
        while (input.hasNext()) {
            checkCancelled();
            if (children.size() >= limit) throw new IOException("目录项目过多，请使用系统文件选择器");
            children.add(input.next());
        }
        return children;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消目录读取");
    }
}
