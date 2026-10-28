package com.druid.zombiesync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

/**
 * Offline check of the receiver buffer against a known zombie path, through the same chain as a real
 * game: the owner stamps and sends every 50 ms, the server relays only the newest update once per
 * 100 ms tick, the network adds latency, jitter and loss, and the observer draws at 144 fps.
 */
public final class SimTest {
    static int failures;

    /** Ground truth: walk, turn, stop, sprint, stop, a teleport, walk on. */
    static double[] truth(double tMs) {
        double t = tMs / 1000.0;
        double x = 1000, y = 1000, dir = 0;
        if (t < 6) {                       // walk east at 1.1 t/s with a gentle curve
            dir = 0.15 * Math.sin(t);
            x += 1.1 * t;
            y += 0.3 * (1 - Math.cos(t));
        } else if (t < 9) {                // turn around while walking: half circle radius 1.5
            double a = (t - 6) / 3.0 * Math.PI;
            x += 6.6 + 1.5 * Math.sin(a);
            y += 0.3 * (1 - Math.cos(6)) + 1.5 * (1 - Math.cos(a));
            dir = a;
        } else if (t < 11) {               // stand (attack)
            x += 6.6;
            y += 0.3 * (1 - Math.cos(6)) + 3.0;
            dir = Math.PI;
        } else if (t < 14) {               // sprint west 4.5 t/s
            x += 6.6 - 4.5 * (t - 11);
            y += 0.3 * (1 - Math.cos(6)) + 3.0;
            dir = Math.PI;
        } else if (t < 16) {
            x += 6.6 - 13.5;
            y += 0.3 * (1 - Math.cos(6)) + 3.0;
            dir = Math.PI / 2;
        } else {                           // teleported 20 tiles away, walking north
            x += 30;
            y += 20 + 1.0 * (t - 16);
            dir = Math.PI / 2;
        }
        return new double[]{x, y, Track.wrap(dir)};
    }

    static final class Packet {
        double arrive;
        float x, y, dir;
        int id;
    }

    static final class Result {
        double errMean, errP95, lagMs, pops, maxStep, maxJump;
        int frames, snaps, teleports, back, frozen, jumps, longStalls;
    }

    /** The server relays a packet to the observer: loss, latency, jitter, and network stalls on the way. */
    static void relay(Packet src, double at, Random rnd, double latency, double jitter, double loss,
                      ArrayList<double[]> spikes, ArrayList<Packet> wire) {
        if (rnd.nextDouble() < loss) return;
        Packet k = new Packet();
        k.x = src.x;
        k.y = src.y;
        k.dir = src.dir;
        k.id = src.id;
        k.arrive = at + latency * 0.5 + rnd.nextDouble() * jitter * 0.5;
        for (double[] w : spikes) {
            if (k.arrive >= w[0] && k.arrive < w[1]) k.arrive = w[1] + rnd.nextDouble() * 2.0;
        }
        wire.add(k);
    }

    /** Network stalls on the way to the observer (spikeEvery ms apart, spikeMin-spikeMax long) and server stalls. */
    static double spikeEvery, spikeMin, spikeMax, stallEvery, stallMin, stallMax;

    static Result run(String label, long seed, double latency, double jitter, double loss, boolean stamped, boolean verbose) {
        Random rnd = new Random(seed);
        // stall windows, planned up front: [start, end) pairs
        ArrayList<double[]> spikes = new ArrayList<>(), stalls = new ArrayList<>();
        Random srnd = new Random(seed * 31 + 5);
        for (double t = 1500; spikeEvery > 0 && t < 19000; t += spikeEvery * (0.5 + srnd.nextDouble())) {
            spikes.add(new double[]{t, t + spikeMin + srnd.nextDouble() * (spikeMax - spikeMin)});
        }
        for (double t = 2500; stallEvery > 0 && t < 19000; t += stallEvery * (0.5 + srnd.nextDouble())) {
            stalls.add(new double[]{t, t + stallMin + srnd.nextDouble() * (stallMax - stallMin)});
        }
        Track tr = new Track();
        ArrayDeque<Packet> inflight = new ArrayDeque<>();
        ArrayList<Packet> wire = new ArrayList<>();
        double sendEvery = 50, tickEvery = 100;
        double nextSend = 0, nextTick = 37, end = 20000;
        Packet pending = null;
        boolean resumed = false;
        int longStalls = 0, jumps = 0;
        double frameDt = 1000.0 / 144.0;
        ArrayList<Double> errs = new ArrayList<>();
        double prevX = Double.NaN, prevY = Double.NaN, prevTx = 0, prevTy = 0;
        int pops = 0;
        double maxStep = 0, maxJump = 0;
        int back = 0, frozen = 0;
        double sumLag = 0;
        int lagN = 0;
        int frames = 0;
        boolean seeded = false;
        for (double now = 0; now < end; now += frameDt) {
            // owner sends
            while (nextSend <= now) {
                double[] p = truth(nextSend);
                Packet k = new Packet();
                k.x = (float) p[0];
                k.y = (float) p[1];
                k.dir = stamped ? Stamp.encode((float) p[2], nextSend, 42) : (float) p[2];
                k.id = 42;
                k.arrive = nextSend + latency * 0.5 + rnd.nextDouble() * jitter * 0.5; // reaches the server
                inflight.add(k);
                nextSend += sendEvery + rnd.nextDouble() * 16.0; // frame-quantised timer
            }
            // server keeps the newest and relays once per tick; while its loop hangs nothing is processed, so the
            // packet picked before the stall stays the pending one (it goes out first when the loop resumes)
            boolean hanging = false;
            for (double[] w : stalls) hanging |= now >= w[0] && now < w[1];
            while (!hanging && !resumed && !inflight.isEmpty() && inflight.peek().arrive <= nextTick) {
                pending = inflight.poll();
            }
            while (nextTick <= now) {
                for (double[] w : stalls) {
                    if (nextTick >= w[0] && nextTick < w[1]) { // the server loop hangs: no relay
                        nextTick = w[1];
                        resumed = true;
                        if (w[1] - w[0] + tickEvery > Config.maxDelayMs) longStalls++; // the data gap is up to a tick longer
                    }
                }
                if (nextTick > now) break;
                if (pending != null) {
                    relay(pending, nextTick, rnd, latency, jitter, loss, spikes, wire);
                    pending = null;
                }
                if (resumed) {
                    // as the real server does after a stall (live run 20261028_152358): the packet queued at the
                    // stall's start goes out first (above), the newest one right after it
                    resumed = false;
                    Packet newest = null;
                    while (!inflight.isEmpty() && inflight.peek().arrive <= nextTick) newest = inflight.poll();
                    if (newest != null) relay(newest, nextTick + 1.0, rnd, latency, jitter, loss, spikes, wire);
                }
                while (!inflight.isEmpty() && inflight.peek().arrive <= nextTick + tickEvery) {
                    pending = inflight.poll();
                }
                nextTick += tickEvery;
            }
            // observer receives
            for (int i = 0; i < wire.size(); i++) {
                Packet k = wire.get(i);
                if (k.arrive > now) continue;
                wire.remove(i--);
                Sample s = tr.obtain();
                s.x = k.x;
                s.y = k.y;
                double t = Stamp.decode(k.dir, now, k.id);
                s.stamped = !Double.isNaN(t);
                s.t = s.stamped ? t : now;
                s.dir = s.stamped ? Stamp.clean(k.dir) : k.dir;
                if (!seeded) {
                    tr.blendFrom(s.x, s.y, s.dir);
                    seeded = true;
                }
                tr.add(s, now, Config.unstampedAgeMs);
            }
            if (tr.get(now) != Track.OK) continue;
            frames++;
            double[] tru = truth(now);
            double err = Math.hypot(tr.outX - tru[0], tr.outY - tru[1]);
            if (now > 1000 && !(now > 15900 && now < 16600)) {
                errs.add(err);
                // lag: best delay 0..600 ms explaining the drawn position
                double best = 1e9, bestD = 0;
                for (double d = 0; d <= 600; d += 5) {
                    double[] q = truth(now - d);
                    double e = Math.hypot(tr.outX - q[0], tr.outY - q[1]);
                    if (e < best) {
                        best = e;
                        bestD = d;
                    }
                }
                double[] q = truth(now - bestD);
                if (Math.hypot(q[0] - tru[0], q[1] - tru[1]) > 0.2) {
                    sumLag += bestD;
                    lagN++;
                }
            }
            if (!Double.isNaN(prevX)) {
                double[] tl = truth(now - tr.delay);
                double step = Math.hypot(tr.outX - prevX, tr.outY - prevY);
                double tstep = Math.hypot(tl[0] - prevTx, tl[1] - prevTy);
                boolean tp = now > 15900 && now < 16600;
                if (Math.abs(step - tstep) > 0.12 && !tp) pops++;
                if (!tp) maxStep = Math.max(maxStep, step);
                if (!tp && step > 1.0) jumps++;
                if (!tp) maxJump = Math.max(maxJump, step - tstep);
                // backward: the drawn zombie moves against the way the truth (as drawn, delayed) moves
                double dot = (tr.outX - prevX) * (tl[0] - prevTx) + (tr.outY - prevY) * (tl[1] - prevTy);
                if (!tp && tstep > 0.004 && step > 0.004 && dot < 0) back++;
                if (!tp && tstep > 0.008 && step < 0.0005) frozen++;
                prevTx = tl[0];
                prevTy = tl[1];
            } else {
                double[] tl = truth(now - Math.max(0, tr.delay));
                prevTx = tl[0];
                prevTy = tl[1];
            }
            prevX = tr.outX;
            prevY = tr.outY;
            if (!Float.isFinite(tr.outX) || !Float.isFinite(tr.outY) || !Float.isFinite(tr.outDir)) {
                System.out.println("  NaN at " + now);
                failures++;
                break;
            }
        }
        Result r = new Result();
        errs.sort(null);
        double sum = 0;
        for (double e : errs) sum += e;
        r.errMean = errs.isEmpty() ? Double.NaN : sum / errs.size();
        r.errP95 = errs.isEmpty() ? Double.NaN : errs.get((int) (0.95 * (errs.size() - 1)));
        r.lagMs = lagN > 0 ? sumLag / lagN : Double.NaN;
        r.pops = pops;
        r.maxStep = maxStep;
        r.maxJump = maxJump;
        r.back = back;
        r.frozen = frozen;
        r.jumps = jumps;
        r.longStalls = longStalls;
        r.frames = frames;
        r.snaps = tr.stSnap;
        r.teleports = tr.stTeleport;
        System.out.printf("%-40s err %.3f p95 %.3f lag %4.0f pops %3d step %.3f jump %.3f back %3d frozen %4d delay %4.0f tele %d snap %d late %d gap %d stale %d jumps %d/%d long stalls%n",
                label, r.errMean, r.errP95, r.lagMs, pops, maxStep, maxJump, back, frozen, tr.delay, tr.stTeleport, tr.stSnap,
                tr.stLate, tr.stGapSnap, tr.stStale, jumps, longStalls);
        return r;
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        // stamp round trip, wrap-around, rejection of unstamped floats
        int good = 0, falseOk = 0;
        Random rnd = new Random(1);
        for (int i = 0; i < 20000; i++) {
            float a = (float) (rnd.nextDouble() * 2 * Math.PI - Math.PI);
            double sent = 1.0e8 + rnd.nextDouble() * 1.0e6;
            double age = rnd.nextDouble() * 900.0;
            int id = rnd.nextInt(65536) - 32768;
            float e = Stamp.encode(a, sent, id);
            double t = Stamp.decode(e, sent + age, id);
            if (!Double.isNaN(t) && Math.abs(t - sent) <= 4.0 && Math.abs(Track.wrap(Stamp.clean(e) - a)) < 0.01) good++;
            if (!Double.isNaN(Stamp.decode(a, sent + age, id))) falseOk++;
        }
        System.out.printf("stamp: %d/20000 decoded within 4 ms, %d unstamped floats accepted (%.2f%%)%n", good, falseOk, falseOk / 200.0);
        expect(good == 20000, "every stamp decodes");
        expect(falseOk < 20000 / 40, "unstamped floats are mostly rejected");

        Result a = run("LAN, stamped", 7, 10, 10, 0.0, true, false);
        Result b = run("internet 80 ms, jitter 30, 2% loss", 8, 80, 30, 0.02, true, false);
        Result c = run("owner without the mod (no stamps)", 9, 40, 20, 0.0, false, false);
        Result d = run("bad link 150 ms, jitter 80, 8% loss", 10, 150, 80, 0.08, true, false);
        // friend over the internet: network stalls of 0.3-1 s (everything late arrives at once), server stalls
        spikeEvery = 4000; spikeMin = 300; spikeMax = 1000;
        Result e = run("WAN 110 ms + 0.3-1 s spikes", 12, 110, 30, 0.015, true, false);
        spikeEvery = 0; stallEvery = 4000; stallMin = 300; stallMax = 800;
        Result f = run("WAN 110 ms + 0.3-0.8 s server stalls", 13, 110, 30, 0.015, true, false);
        stallEvery = 7000; stallMin = 2500; stallMax = 3800;
        Result g = run("WAN 110 ms + 2.5-3.8 s server stalls", 14, 110, 30, 0.015, true, false);
        // the live test's stall range, several seeds: stale packets older than a stamp can tell (> ~3 s) included
        int gJumps = 0, gLong = 0, gBack = 0;
        for (long seed = 21; seed <= 26; seed++) {
            stallEvery = 5000; stallMin = 300; stallMax = 4500;
            Result h = run("WAN 110 ms + 0.3-4.5 s server stalls #" + seed, seed, 110, 30, 0.015, true, false);
            gJumps += h.jumps;
            gLong += h.longStalls;
            gBack += h.back;
        }
        stallEvery = 0;
        expect(a.errMean < 0.6 && a.pops == 0, "LAN: small error, no pops");
        expect(b.pops <= 2, "internet: no visible pops");
        expect(c.errMean < 1.0, "unstamped still follows");
        expect(d.maxStep < 0.6, "bad link: no frame moves more than 0.6 tiles");
        expect(a.teleports == 1 && b.teleports == 1, "the teleport is detected once");
        expect(e.back <= 3 && e.maxJump < 0.25 && e.snaps <= 1, "WAN spikes: no backward motion, no jumps");
        expect(f.back <= 3 && f.maxJump < 0.25 && f.snaps <= 1, "server stalls: no backward motion, no jumps");
        expect(g.back <= 3 && g.jumps <= g.longStalls, "long server stalls: no backward motion, one clean jump each");
        expect(gBack <= 6 && gJumps <= gLong, "0.3-4.5 s stalls: no backward motion, at most one jump per stall the buffer cannot ride out");
        System.out.println(failures == 0 ? "SimTest: all checks passed" : "SimTest: " + failures + " check(s) FAILED");
        if (failures != 0) System.exit(1);
    }
}
