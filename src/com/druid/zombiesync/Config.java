package com.druid.zombiesync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Tunables. Pure Java so the offline simulator can use it too. */
final class Config {
    // master switches
    static volatile boolean enabled = true;        // remote zombies follow the ZombieSync buffer
    static volatile boolean sendBoost = true;      // zombies this client simulates are sent more often
    static volatile boolean fixEating = true;      // only the owner decides that a zombie starts eating
    static volatile boolean followAttacks = true;  // a remote zombie attacks when (and as long as) the owner's does

    // owner side: the period follows the number of zombies we simulate, within this upload budget
    static volatile int sendIntervalMs = 50;       // vanilla: 200 ms when another player is near
    static volatile int sendBudgetKBps = 64;       // above it the period grows back toward vanilla

    // receiver side: delay = P(packet age) + k * packet gap + margin
    static volatile String mode = "balanced";      // smooth | balanced | realtime
    static volatile double minDelayMs = 40.0;
    static volatile double maxDelayMs = 600.0;
    static volatile double initialDelayMs = 150.0;
    static volatile double delayGrowRate = 0.15;   // max ms of delay gained per ms (zombie runs 15% slow)
    static volatile double delayShrinkRate = 0.06; // max ms of delay lost per ms (zombie runs 6% fast)
    static volatile double delayRateAccel = 0.3;   // how fast that rate itself may change, per second
    static volatile double unstampedAgeMs = 60.0;  // assumed age of packets without the owner's time stamp

    static volatile double maxExtrapolationMs = 200.0;
    static volatile double idleTimeoutMs = 1200.0; // no packets for this long -> hand back to vanilla
    static volatile double correctionTimeMs = 180.0;
    static volatile double jumpThreshold = 0.03;   // tiles; smaller differences are treated as motion
    static volatile double snapDistance = 4.0;     // tiles; larger errors snap instead of blending
    static volatile double blockedDistance = 1.0;  // tiles behind its pose (a fence, a wall in the way) ...
    static volatile double blockedMs = 600.0;      // ... for this long: put it on its pose
    static volatile double teleportDistance = 5.0;
    static volatile double maxSpeed = 12.0;        // tiles/s; faster sample-to-sample moves are teleports
    static volatile double hermiteMaxDeviation = 0.35;

    static volatile boolean logStats = true;
    static volatile int statsIntervalSec = 30;

    private Config() {
    }

    static double pct() {
        switch (mode) {
            case "smooth": return 0.95;
            case "realtime": return 0.5;
            default: return 0.8;
        }
    }

    static double kGap() {
        switch (mode) {
            case "smooth": return 1.0;
            case "realtime": return 0.0;
            default: return 0.5;
        }
    }

    static double marginMs() {
        switch (mode) {
            case "smooth": return 20.0;
            case "realtime": return 0.0;
            default: return 10.0;
        }
    }

    static String describe() {
        return "enabled=" + enabled + " sendBoost=" + sendBoost + " fixEating=" + fixEating + " followAttacks=" + followAttacks
                + " sendIntervalMs=" + sendIntervalMs
                + " sendBudgetKBps=" + sendBudgetKBps + " mode=" + mode + " minDelayMs=" + minDelayMs + " maxDelayMs=" + maxDelayMs
                + " maxExtrapolationMs=" + maxExtrapolationMs + " correctionTimeMs=" + correctionTimeMs;
    }

    static boolean set(String key, String value) {
        try {
            String v = value.trim();
            switch (key) {
                case "enabled": enabled = Boolean.parseBoolean(v); return true;
                case "sendBoost": sendBoost = Boolean.parseBoolean(v); return true;
                case "fixEating": fixEating = Boolean.parseBoolean(v); return true;
                case "followAttacks": followAttacks = Boolean.parseBoolean(v); return true;
                case "sendIntervalMs": sendIntervalMs = clampI(Integer.parseInt(v), 20, 200); return true;
                case "sendBudgetKBps": sendBudgetKBps = clampI(Integer.parseInt(v), 8, 4096); return true;
                case "mode":
                    String m = v.toLowerCase();
                    if (!m.equals("smooth") && !m.equals("balanced") && !m.equals("realtime")) return false;
                    mode = m;
                    return true;
                case "minDelayMs": minDelayMs = clampD(Double.parseDouble(v), 0, 500); return true;
                case "maxDelayMs": maxDelayMs = clampD(Double.parseDouble(v), 100, 2000); return true;
                case "initialDelayMs": initialDelayMs = clampD(Double.parseDouble(v), 0, 1000); return true;
                case "unstampedAgeMs": unstampedAgeMs = clampD(Double.parseDouble(v), 0, 1000); return true;
                case "maxExtrapolationMs": maxExtrapolationMs = clampD(Double.parseDouble(v), 0, 600); return true;
                case "idleTimeoutMs": idleTimeoutMs = clampD(Double.parseDouble(v), 300, 10000); return true;
                case "correctionTimeMs": correctionTimeMs = clampD(Double.parseDouble(v), 10, 1000); return true;
                case "snapDistance": snapDistance = clampD(Double.parseDouble(v), 1, 50); return true;
                case "logStats": logStats = Boolean.parseBoolean(v); return true;
                case "statsIntervalSec": statsIntervalSec = clampI(Integer.parseInt(v), 5, 600); return true;
                default: return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final String[] KEYS = {
        "enabled", "sendBoost", "fixEating", "followAttacks", "sendIntervalMs", "sendBudgetKBps", "mode", "minDelayMs", "maxDelayMs",
        "initialDelayMs", "maxExtrapolationMs", "idleTimeoutMs", "correctionTimeMs", "snapDistance", "logStats", "statsIntervalSec"
    };

    private static String get(String key) {
        switch (key) {
            case "enabled": return String.valueOf(enabled);
            case "sendBoost": return String.valueOf(sendBoost);
            case "fixEating": return String.valueOf(fixEating);
            case "followAttacks": return String.valueOf(followAttacks);
            case "sendIntervalMs": return String.valueOf(sendIntervalMs);
            case "sendBudgetKBps": return String.valueOf(sendBudgetKBps);
            case "mode": return mode;
            case "minDelayMs": return String.valueOf(minDelayMs);
            case "maxDelayMs": return String.valueOf(maxDelayMs);
            case "initialDelayMs": return String.valueOf(initialDelayMs);
            case "maxExtrapolationMs": return String.valueOf(maxExtrapolationMs);
            case "idleTimeoutMs": return String.valueOf(idleTimeoutMs);
            case "correctionTimeMs": return String.valueOf(correctionTimeMs);
            case "snapDistance": return String.valueOf(snapDistance);
            case "logStats": return String.valueOf(logStats);
            case "statsIntervalSec": return String.valueOf(statsIntervalSec);
            default: return null;
        }
    }

    /** Loads the file if it exists, otherwise writes one with the defaults. Returns a status line. */
    static String loadOrCreate(File file) {
        if (file.isFile()) {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return "config read failed (" + e + "), using defaults";
            }
            int applied = 0;
            StringBuilder bad = new StringBuilder();
            for (String key : p.stringPropertyNames()) {
                if (set(key, p.getProperty(key))) {
                    applied++;
                } else {
                    bad.append(' ').append(key);
                }
            }
            return "config " + file + ": " + applied + " value(s)" + (bad.length() > 0 ? ", ignored:" + bad : "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ZombieSync MP settings. Delete this file to restore defaults.\n");
        sb.append("# mode: smooth (max smoothness, a bit more delay), balanced (default), realtime (min delay, more prediction)\n");
        for (String key : KEYS) {
            sb.append(key).append('=').append(get(key)).append('\n');
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            return "config created with defaults: " + file;
        } catch (Exception e) {
            return "config not written (" + e + "), using defaults";
        }
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
