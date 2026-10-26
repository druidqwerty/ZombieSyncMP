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
        double errMean, errP95, lagMs, pops, maxStep;
        int frames, snaps, teleports;
    }

    static Result run(String label, long seed, double latency, double jitter, double loss, boolean stamped, boolean verbose) {
        Random rnd = new Random(seed);
        Track tr = new Track();
        ArrayDeque<Packet> inflight = new ArrayDeque<>();
        ArrayList<Packet> wire = new ArrayList<>();
        double sendEvery = 50, tickEvery = 100;
        double nextSend = 0, nextTick = 37, end = 20000;
        Packet pending = null;
        double frameDt = 1000.0 / 144.0;
        ArrayList<Double> errs = new ArrayList<>();
        double prevX = Double.NaN, prevY = Double.NaN, prevTx = 0, prevTy = 0;
        int pops = 0;
        double maxStep = 0;
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
            // server keeps the newest and relays once per tick
            while (!inflight.isEmpty() && inflight.peek().arrive <= nextTick) {
                pending = inflight.poll();
            }
            while (nextTick <= now) {
                if (pending != null) {
                    if (rnd.nextDouble() >= loss) {
                        Packet k = new Packet();
                        k.x = pending.x;
                        k.y = pending.y;
                        k.dir = pending.dir;
                        k.id = pending.id;
                        k.arrive = nextTick + latency * 0.5 + rnd.nextDouble() * jitter * 0.5;
                        wire.add(k);
                    }
                    pending = null;
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
                if (Math.abs(step - tstep) > 0.12 && !(now > 15900 && now < 16600)) pops++;
                if (!(now > 15900 && now < 16600)) maxStep = Math.max(maxStep, step);
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
        r.frames = frames;
        r.snaps = tr.stSnap;
        r.teleports = tr.stTeleport;
        System.out.printf("%-34s frames %5d  err %.3f p95 %.3f  lag %4.0f ms  pops %3d  max step %.3f  delay %.0f ms  teleports %d snaps %d%n",
                label, r.frames, r.errMean, r.errP95, r.lagMs, pops, maxStep, tr.delay, tr.stTeleport, tr.stSnap);
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
        expect(a.errMean < 0.6 && a.pops == 0, "LAN: small error, no pops");
        expect(b.pops <= 2, "internet: no visible pops");
        expect(c.errMean < 1.0, "unstamped still follows");
        expect(d.maxStep < 0.6, "bad link: no frame moves more than 0.6 tiles");
        expect(a.teleports == 1 && b.teleports == 1, "the teleport is detected once");
        System.out.println(failures == 0 ? "SimTest: all checks passed" : "SimTest: " + failures + " check(s) FAILED");
        if (failures != 0) System.exit(1);
    }
}
