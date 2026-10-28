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
    static volatile boolean syncPlayers = true;    // other players are drawn on the zombies' clock (see Players)

    // owner side: the period follows the number of zombies we simulate, within this upload budget.
    // The server relays each zombie at ~10 Hz whatever we send, but 100 ms sends still cost ~30 ms of delay
    // (the update waiting for the relay is older); 256 KB/s keeps 50 ms up to ~250 zombies (53 B per update),
    // 64 KB/s gave vanilla's 200 ms from ~60 zombies on.
    static volatile int sendIntervalMs = 50;       // vanilla: 200 ms when another player is near
    static volatile int sendBudgetKBps = 256;      // above it the period grows back toward vanilla

    // receiver side: delay = P(packet age) + k * packet gap + margin
    static volatile String mode = "balanced";      // smooth | balanced | realtime
    static volatile double minDelayMs = 40.0;
    static volatile double maxDelayMs = 1500.0;    // a stall can push the delay this far (see starveMs)
    static volatile double initialDelayMs = 150.0;
    static volatile double delayGrowRate = 0.15;   // max ms of delay gained per ms (zombie runs 15% slow)
    static volatile double delayShrinkRate = 0.06; // max ms of delay lost per ms (zombie runs 6% fast)
    static volatile double delayRateAccel = 0.3;   // how fast that rate itself may change, per second
    static volatile double unstampedAgeMs = 60.0;  // assumed age of packets without the owner's time stamp

    static volatile double maxExtrapolationMs = 200.0;
    static volatile double starveMs = 60.0;        // predicting this far past the newest packet: the buffer ran dry ...
    static volatile double starveSlowdown = 1.0;   // ... and the drawn time slows down, to a stop at maxExtrapolationMs
    static volatile double idleTimeoutMs = 4000.0; // no packets for this long -> hand back to vanilla (a stall of a few s is not)
    static volatile double correctionTimeMs = 180.0;
    static volatile double jumpThreshold = 0.03;   // tiles; smaller differences are treated as motion
    static volatile double snapDistance = 4.0;     // tiles; larger errors snap instead of blending
    static volatile double blockedDistance = 1.0;  // tiles behind its pose (a fence, a wall in the way) ...
    static volatile double stuckDistance = 0.35;   // ... or this far off it without moving at all ...
    static volatile double blockedMs = 600.0;      // ... for this long: put it on its pose
    static volatile double maxSpeed = 12.0;        // tiles/s; faster sample-to-sample moves are teleports
    static volatile double hermiteMaxDeviation = 0.35;

    static volatile boolean logStats = true;
    static volatile int statsIntervalSec = 30;
    static volatile boolean profile = false;      // CPU time per hook in the stats (see Prof)

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
                + " syncPlayers=" + syncPlayers + " sendIntervalMs=" + sendIntervalMs
                + " sendBudgetKBps=" + sendBudgetKBps + " mode=" + mode + " minDelayMs=" + minDelayMs + " maxDelayMs=" + maxDelayMs
                + " maxExtrapolationMs=" + maxExtrapolationMs + " idleTimeoutMs=" + idleTimeoutMs + " correctionTimeMs=" + correctionTimeMs;
    }

    static boolean set(String key, String value) {
        try {
            String v = value.trim();
            switch (key) {
                case "enabled": enabled = Boolean.parseBoolean(v); return true;
                case "sendBoost": sendBoost = Boolean.parseBoolean(v); return true;
                case "fixEating": fixEating = Boolean.parseBoolean(v); return true;
                case "followAttacks": followAttacks = Boolean.parseBoolean(v); return true;
                case "syncPlayers": syncPlayers = Boolean.parseBoolean(v); return true;
                case "sendIntervalMs": sendIntervalMs = clampI(Integer.parseInt(v), 20, 200); return true;
                case "sendBudgetKBps": sendBudgetKBps = clampI(Integer.parseInt(v), 8, 4096); return true;
                case "mode":
                    String m = v.toLowerCase();
                    if (!m.equals("smooth") && !m.equals("balanced") && !m.equals("realtime")) return false;
                    mode = m;
                    return true;
                case "minDelayMs": minDelayMs = clampD(Double.parseDouble(v), 0, 500); return true;
                case "maxDelayMs": maxDelayMs = clampD(Double.parseDouble(v), 100, 4000); return true;
                case "initialDelayMs": initialDelayMs = clampD(Double.parseDouble(v), 0, 1000); return true;
                case "unstampedAgeMs": unstampedAgeMs = clampD(Double.parseDouble(v), 0, 1000); return true;
                case "maxExtrapolationMs": maxExtrapolationMs = clampD(Double.parseDouble(v), 0, 600); return true;
                case "starveMs": starveMs = clampD(Double.parseDouble(v), 0, 1000); return true;
                case "starveSlowdown": starveSlowdown = clampD(Double.parseDouble(v), 0, 1); return true;
                case "idleTimeoutMs": idleTimeoutMs = clampD(Double.parseDouble(v), 300, 10000); return true;
                case "correctionTimeMs": correctionTimeMs = clampD(Double.parseDouble(v), 10, 1000); return true;
                case "snapDistance": snapDistance = clampD(Double.parseDouble(v), 1, 50); return true;
                case "logStats": logStats = Boolean.parseBoolean(v); return true;
                case "profile": profile = Boolean.parseBoolean(v); return true;
                case "statsIntervalSec": statsIntervalSec = clampI(Integer.parseInt(v), 5, 600); return true;
                default: return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final String[] KEYS = {
        "enabled", "sendBoost", "fixEating", "followAttacks", "syncPlayers", "sendIntervalMs", "sendBudgetKBps", "mode", "minDelayMs", "maxDelayMs",
        "initialDelayMs", "maxExtrapolationMs", "starveMs", "starveSlowdown", "idleTimeoutMs", "correctionTimeMs", "snapDistance", "logStats", "statsIntervalSec", "profile"
    };

    private static final java.util.Map<String, String> startup = new java.util.HashMap<>();

    /** Remembers the values from the file / built-in defaults (Lua reads them as "startup.<key>"). */
    static void snapshotStartup() {
        for (String key : KEYS) startup.put(key, get(key));
    }

    static String get(String key) {
        if (key.startsWith("startup.")) return startup.get(key.substring(8));
        switch (key) {
            case "enabled": return String.valueOf(enabled);
            case "sendBoost": return String.valueOf(sendBoost);
            case "fixEating": return String.valueOf(fixEating);
            case "followAttacks": return String.valueOf(followAttacks);
            case "syncPlayers": return String.valueOf(syncPlayers);
            case "sendIntervalMs": return String.valueOf(sendIntervalMs);
            case "sendBudgetKBps": return String.valueOf(sendBudgetKBps);
            case "mode": return mode;
            case "minDelayMs": return String.valueOf(minDelayMs);
            case "maxDelayMs": return String.valueOf(maxDelayMs);
            case "initialDelayMs": return String.valueOf(initialDelayMs);
            case "maxExtrapolationMs": return String.valueOf(maxExtrapolationMs);
            case "starveMs": return String.valueOf(starveMs);
            case "starveSlowdown": return String.valueOf(starveSlowdown);
            case "idleTimeoutMs": return String.valueOf(idleTimeoutMs);
            case "correctionTimeMs": return String.valueOf(correctionTimeMs);
            case "snapDistance": return String.valueOf(snapDistance);
            case "logStats": return String.valueOf(logStats);
            case "profile": return String.valueOf(profile);
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
            // 1.0.x wrote every key with its default; values still at a 1.0.x default whose default changed in 1.1
            // are dropped, so an old file does not pin the old behaviour (a 1.2 s data gap handed the zombie back to
            // vanilla, which teleports it); customised values stay
            boolean v1 = !p.containsKey("configVersion");
            int upgraded = 0;
            for (String key : p.stringPropertyNames()) {
                if (key.equals("configVersion")) continue;
                if (v1 && isOldDefault(key, p.getProperty(key))) {
                    upgraded++;
                    continue;
                }
                if (set(key, p.getProperty(key))) {
                    applied++;
                } else {
                    bad.append(' ').append(key);
                }
            }
            String status = "config " + file + ": " + applied + " value(s)" + (bad.length() > 0 ? ", ignored:" + bad : "");
            if (v1) status += "; " + write(file, "upgraded from 1.0 (" + upgraded + " old default(s) replaced)");
            return status;
        }
        return write(file, "created with defaults");
    }

    private static boolean isOldDefault(String key, String value) {
        try {
            double v = Double.parseDouble(value.trim());
            switch (key) {
                case "maxDelayMs": return v == 600.0;
                case "idleTimeoutMs": return v == 1200.0;
                default: return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String write(File file, String what) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ZombieSync MP settings. Delete this file to restore defaults. Read once, when the game starts.\n");
        sb.append("# Normally nothing here needs editing: on a server, enabled, mode, sendIntervalMs, sendBudgetKBps, maxDelayMs\n");
        sb.append("# (= StallBufferMs), syncPlayers, followAttacks and fixEating come from the host's sandbox options (page\n");
        sb.append("# ZombieSync MP, an admin can change them in a running game) and win over this file; logStats and statsIntervalSec\n");
        sb.append("# are in the game's Options > Mods and win over this file. The rest is for tuning.\n");
        sb.append("# mode: balanced (default: zombies ~0.2 s behind), smooth (30-50 ms more), realtime (lowest delay, more prediction)\n");
        sb.append("# maxDelayMs: no packets (lag spike, server hitch) -> zombies slow down instead of freezing, up to this much delay\n");
        sb.append("configVersion=2\n");
        for (String key : KEYS) {
            sb.append(key).append('=').append(get(key)).append('\n');
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            return "config " + what + ": " + file;
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
