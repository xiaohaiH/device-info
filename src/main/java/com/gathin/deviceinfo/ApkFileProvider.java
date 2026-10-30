package com.gathin.deviceinfo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Only immutable APK snapshots, only read mode. Export and per-URI grants are enforced by Android. */
public final class ApkFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || !AndroidApkAccess.authority(getContext()).equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null || segments.size() != 2
                || !"apk".equals(segments.get(0)) || !ApkFiles.isToken(segments.get(1))) {
            throw new FileNotFoundException("Invalid APK snapshot URI");
        }
        try { return AndroidApkAccess.cache(getContext()).resolve(segments.get(1)); }
        catch (IOException missing) { throw new FileNotFoundException("APK snapshot unavailable"); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("APK snapshots are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { file(uri); return AndroidApkAccess.MIME; }
        catch (FileNotFoundException unavailable) { return null; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = file(uri); }
        catch (FileNotFoundException unavailable) { throw new IllegalArgumentException("APK snapshot unavailable", unavailable); }
        String[] columns = projection == null ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
}
