package com.druid.zombiesync;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPU time spent inside the mod's hooks, by hook (Config.profile=true). The attack hooks may run on PZ Optimization's
 * worker threads, so the counters are LongAdders. Reported with the stats, then reset.
 */
final class Prof {
    static final Prof FRAME = new Prof("frame");
    static final Prof POST = new Prof("zombie.postupdate");
    static final Prof PARSE = new Prof("zombie.parse");
    static final Prof STAMP = new Prof("zombie.send");
    static final Prof ATTACK = new Prof("attack hooks");
    static final Prof PLAYER_POST = new Prof("player.postupdate");
    static final Prof PLAYER_PARSE = new Prof("player.parse");
    private static final Prof[] ALL = {FRAME, POST, PARSE, STAMP, ATTACK, PLAYER_POST, PLAYER_PARSE};

    final String name;
    private final LongAdder calls = new LongAdder();
    private final LongAdder nanos = new LongAdder();

    private Prof(String name) {
        this.name = name;
    }

    /** t0 = System.nanoTime() taken at the hook's entry. */
    void add(long t0) {
        calls.increment();
        nanos.add(System.nanoTime() - t0);
    }

    /** One line: per hook, calls per second and milliseconds of CPU per second of wall time (= % of one core). */
    static String report(int secs) {
        StringBuilder sb = new StringBuilder("profile:");
        double total = 0;
        for (Prof p : ALL) {
            long c = p.calls.sumThenReset();
            long ns = p.nanos.sumThenReset();
            if (c == 0) continue;
            double ms = ns / 1.0e6;
            total += ms;
            sb.append(String.format(Locale.ROOT, " %s %.0f/s %.2f ms/s (%.1f us avg);", p.name, c / (double) secs, ms / secs, ns / 1.0e3 / c));
        }
        sb.append(String.format(Locale.ROOT, " total %.2f ms/s", total / secs));
        return sb.toString();
    }
}
