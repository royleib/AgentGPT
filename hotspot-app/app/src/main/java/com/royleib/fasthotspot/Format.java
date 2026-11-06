package com.royleib.fasthotspot;

import java.util.Locale;

public final class Format {
    private Format() { }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(Locale.US, v < 10 ? "%.2f %s" : "%.1f %s", v, units[i]);
    }

    /** Bytes/second shown as megabits/second, the unit speed tests use. */
    public static String rate(long bytesPerSecond) {
        double mbps = bytesPerSecond * 8 / 1_000_000.0;
        return String.format(Locale.US, mbps < 10 ? "%.2f Mbps" : "%.1f Mbps", mbps);
    }
}
