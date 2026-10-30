package com.gathin.deviceinfo;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** Bounded ZIP preflight BEFORE ZipFile/native package parsing can allocate an entry index. */
final class ApkZip {
    static final int MAX_ENTRIES = 10000;
    private static final long MAX_CENTRAL_BYTES = 4L * 1024 * 1024;
    private ApkZip() { }

    static void preflight(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            checkCancelled();
            long length = input.length();
            if (length < 22) throw invalid();
            byte[] tail = new byte[(int) Math.min(length, 65535L + 22)];
            input.seek(length - tail.length);
            input.readFully(tail);
            int end = -1;
            // Do not skip an ambiguous trailing signature and let another ZIP parser choose it.
            for (int i = tail.length - 4; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L) { end = i; break; }
            }
            if (end < 0 || end + 22 > tail.length || end + 22 + u16(tail, end + 20) != tail.length) throw invalid();
            long endOffset = length - tail.length + end;
            int entries = u16(tail, end + 10);
            long centralBytes = u32(tail, end + 12);
            long centralOffset = u32(tail, end + 16);
            if (entries == 0xffff || centralBytes == 0xffffffffL || centralOffset == 0xffffffffL) throw unsupported();
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 || u16(tail, end + 8) != entries) throw unsupported();
            if (entries < 1 || entries > MAX_ENTRIES || centralBytes > MAX_CENTRAL_BYTES) {
                throw new IOException("APK ZIP 中央目录过大，无法安全解析");
            }
            if (centralOffset + centralBytes != endOffset || centralOffset >= endOffset) throw invalid();

            byte[] header = new byte[46];
            byte[] name = new byte[65535];
            long position = centralOffset;
            int counted = 0;
            int manifests = 0;
            int resourceTables = 0;
            while (position < endOffset) {
                checkCancelled();
                if (++counted > entries || position + header.length > endOffset) throw invalid();
                input.seek(position);
                input.readFully(header);
                if (u32(header, 0) != 0x02014b50L) throw invalid();
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int commentLength = u16(header, 32);
                long compressed = u32(header, 20);
                long expanded = u32(header, 24);
                long localOffset = u32(header, 42);
                if (u16(header, 6) >= 45 || u16(header, 34) != 0 || compressed == 0xffffffffL
                        || expanded == 0xffffffffL || localOffset == 0xffffffffL) throw unsupported();
                int method = u16(header, 10);
                if ((u16(header, 8) & 1) != 0 || method != 0 && method != 8) throw unsupported();
                long recordEnd = position + header.length + nameLength + extraLength + commentLength;
                if (nameLength == 0 || recordEnd > endOffset || localOffset >= centralOffset) throw invalid();
                input.readFully(name, 0, nameLength);
                for (int i = 0; i < nameLength; i++) if (name[i] == 0) throw invalid();
                if (asciiName(name, nameLength, "AndroidManifest.xml")) {
                    if (++manifests != 1 || expanded < 1 || expanded > 4L * 1024 * 1024) throw invalid();
                }
                if (asciiName(name, nameLength, "resources.arsc")) {
                    if (++resourceTables != 1 || expanded > 64L * 1024 * 1024) throw invalid();
                }
                long extraEnd = input.getFilePointer() + extraLength;
                while (input.getFilePointer() + 4 <= extraEnd) {
                    int tag = littleShort(input);
                    int size = littleShort(input);
                    if (tag == 1) throw unsupported(); // ZIP64 extra records are not supported.
                    if (input.getFilePointer() + size > extraEnd) throw invalid();
                    input.seek(input.getFilePointer() + size);
                }
                position = recordEnd;
            }
            if (counted != entries || position != endOffset || manifests != 1) throw invalid();
        }
    }

    private static boolean asciiName(byte[] bytes, int length, String name) {
        if (length != name.length()) return false;
        for (int i = 0; i < length; i++) if ((bytes[i] & 255) != name.charAt(i)) return false;
        return true;
    }
    private static int littleShort(RandomAccessFile input) throws IOException { return input.readUnsignedByte() | (input.readUnsignedByte() << 8); }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
    private static long u32(byte[] bytes, int at) { return u16(bytes, at) | ((long) u16(bytes, at + 2) << 16); }
    private static IOException invalid() { return new IOException("APK ZIP 结构无效、清单异常或文件不完整"); }
    private static IOException unsupported() { return new IOException("不支持 ZIP64、多卷、加密或特殊压缩的 APK"); }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消 APK 结构检查");
    }
}
