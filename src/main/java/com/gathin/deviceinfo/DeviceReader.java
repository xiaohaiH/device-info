package com.gathin.deviceinfo;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only framework information. No shell, hidden API, network, or unique device identifiers. */
final class DeviceReader {
    private DeviceReader() { }

    static Map<String, String> read(Context context) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Android 版本", Build.VERSION.RELEASE);
        fields.put("设备型号", Build.MODEL);
        fields.put("制造商 / 品牌", DeviceReport.value(Build.MANUFACTURER) + " / " + DeviceReport.value(Build.BRAND));
        fields.put("设备代号", Build.DEVICE);
        fields.put("产品代号", Build.PRODUCT);
        fields.put("硬件 / 主板", DeviceReport.value(Build.HARDWARE) + " / " + DeviceReport.value(Build.BOARD));
        fields.put("固件版本", Build.DISPLAY);
        fields.put("CPU 架构", Build.VERSION.SDK_INT >= 21 ? Api21.abis() : legacyAbis());
        readMemory(context, fields);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        fields.put("应用可用分辨率", metrics.widthPixels + " × " + metrics.heightPixels);
        fields.put("屏幕密度", metrics.densityDpi + " dpi");
        return fields;
    }

    private static String legacyAbis() {
        String first = DeviceReport.value(Build.CPU_ABI);
        String second = Build.CPU_ABI2;
        return second == null || second.isEmpty() || second.equals(first) ? first : first + ", " + second;
    }

    private static void readMemory(Context context, Map<String, String> fields) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                fields.put("系统内存", "无法读取");
                return;
            }
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            fields.put("系统可见总内存", DeviceReport.memory(memory.totalMem)); // API 16.
            fields.put("当前可用内存", DeviceReport.memory(memory.availMem));
        } catch (RuntimeException unavailable) {
            fields.put("系统内存", "读取被固件限制");
        }
    }

    /** Keep modern references out of classes loaded on Dalvik/API 16. */
    @TargetApi(21)
    private static final class Api21 {
        static String abis() {
            return Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0
                    ? legacyAbis() : Arrays.toString(Build.SUPPORTED_ABIS);
        }
    }
}
