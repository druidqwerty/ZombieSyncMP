package com.druid.zombiesync;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Snapshot buffer for one remote zombie.
 *
 * The zombie is drawn at renderTime = now - delay on the server clock, where the delay follows the
 * measured age of the owner's packets (stamped with the owner's send time) instead of chasing a
 * guessed point like vanilla does. Between snapshots the position follows a Catmull-Rom curve
 * (C1-continuous); past the newest one it is predicted from the last velocity for a short while.
 * When new data changes the curve under the zombie (a prediction miss, a late packet, a resumed
 * stream), the jump goes into a visual offset that a critically damped spring pulls back to zero,
 * so the zombie neither pops nor surges. Facing is interpolated along the shortest arc the same way.
 *
 * No game classes are referenced here: the offline simulator drives this class directly.
 */
final class Track {
    static final int CAP = 48;
    static final int NONE = 0;
    static final int OK = 1;

    static final int M_INTERP = 0;
    static final int M_EXTRAP = 1;
    static final int M_HOLD = 2;
    static final int M_FIRST = 3;

    final Sample[] s = new Sample[CAP];
    int n;
    private final ArrayDeque<Sample> pool = new ArrayDeque<>();

    private final double[] age = new double[48];
    private int ageN, agePos;
    private final double[] gap = new double[24];
    private int gapN, gapPos;
    private final double[] tmp = new double[48];
    private double newestT = Double.NEGATIVE_INFINITY;

    double delay = -1.0;
    private double delayRate;
    double target = Config.initialDelayMs;
    double lastNow = Double.NaN;
    double renderTime = Double.NaN;
    double lastArrive = Double.NaN;
    boolean idle;
    boolean yielding; // a local animation moves the zombie; the buffer only records
    long lastSeenMs;  // wall clock of the last frame or packet for this zombie
    int floor;        // the owner's floor (z level) in the newest packet
    long blockedSince; // wall clock since the zombie could not reach its pose (a fence, a wall), 0 = not blocked
    boolean facingLocal; // facing comes from the game (turning to the target before a bite), not the buffer
    int lastMode = -1;

    // the next pose starts from where the zombie is drawn (activation, end of a local animation)
    private boolean blendPending;
    private float bx, by, bdir;
    private boolean snapPending;

    // which part of the buffer produced last frame's pose
    private int mMode = -1;
    private double mA, mB;
    private boolean mLinear;

    // previous frame's raw (uncorrected) pose
    private boolean hasRaw;
    private double rawTime;
    private float rpx, rpy, rvx, rvy, rdir;

    // visual correction on top of the raw pose: offset + its velocity (spring), facing offset
    float cx, cy;
    private float cvx, cvy;
    float cdir;

    float outX, outY, outDir;

    // stats since the last report
    int stFrames, stInterp, stExtrap, stHold, stCorr, stSnap, stSamples, stStamped, stTeleport, stDropped, stIdle;
    double stMaxCorr, stSumDelay, stMaxDelay;

    short zombieId = -1;

    Sample obtain() {
        Sample x = pool.poll();
        if (x == null) return new Sample();
        x.synthetic = false;
        x.stamped = false;
        return x;
    }

    void recycle(Sample x) {
        if (x != null) pool.add(x);
    }

    private void clearSamples() {
        for (int i = 0; i < n; i++) {
            pool.add(s[i]);
            s[i] = null;
        }
        n = 0;
        newestT = Double.NEGATIVE_INFINITY;
    }

    /** Forget everything (zombie reused, ownership changed). */
    void clear() {
        clearSamples();
        hasRaw = false;
        mMode = -1;
        zeroCorrection();
        renderTime = Double.NaN;
        lastNow = Double.NaN;
        idle = false;
        blendPending = false;
        snapPending = false;
    }

    private void zeroCorrection() {
        cx = cy = 0f;
        cvx = cvy = 0f;
        cdir = 0f;
    }

    /** The next pose blends in from this drawn pose instead of jumping to the buffer. */
    void blendFrom(float x, float y, float dir) {
        blendPending = true;
        bx = x;
        by = y;
        bdir = dir;
    }

    /** The game turned the zombie by itself; continue from that facing and ease back to the buffer's. */
    void alignFacing(float current) {
        float raw = (float) wrap(outDir - cdir);
        cdir = (float) wrap(current - raw);
        outDir = current;
    }

    double medianAge() {
        return ageN > 0 ? percentile(age, ageN, 0.5) : Double.NaN;
    }

    /** Takes ownership of {@code in} (it must come from {@link #obtain()}). */
    boolean add(Sample in, double now, double defaultAge) {
        if (!in.isFinite()) {
            stDropped++;
            pool.add(in);
            return false;
        }
        in.arrive = now;
        if (in.stamped) {
            double a = now - in.t;
            double med = medianAge();
            if (a < -250.0 || a > 2500.0 || (ageN >= 8 && Math.abs(a - med) > 400.0)) {
                in.stamped = false; // a stamp that does not fit: garbage bits or a stale relay
            }
        }
        if (!in.stamped) {
            double med = medianAge();
            in.t = now - (Double.isNaN(med) ? defaultAge : med);
        }
        if (n > 0) {
            Sample last = s[n - 1];
            double dt = in.t - last.t;
            double d = Math.hypot(in.x - last.x, in.y - last.y);
            if (d > Config.teleportDistance && (dt <= 0.0 || d * 1000.0 / dt > Config.maxSpeed)) {
                clearSamples(); // teleport: show the new place at once
                snapPending = true;
                stTeleport++;
            } else if (dt > Config.idleTimeoutMs) {
                clearSamples(); // the stream stopped and resumed: old snapshots describe a different walk
            }
        }
        if (in.stamped) {
            pushAge(now - in.t);
            stStamped++;
        }
        if (newestT != Double.NEGATIVE_INFINITY && in.t > newestT && in.t - newestT < 1000.0) {
            pushGap(in.t - newestT);
        }
        if (in.t > newestT) newestT = in.t;
        lastArrive = now;
        idle = false;
        stSamples++;
        insertSorted(in);
        prune(now);
        updateTarget();
        return true;
    }

    private void insertSorted(Sample in) {
        int i = n - 1;
        while (i >= 0 && s[i].t > in.t) i--;
        if (i >= 0 && Math.abs(s[i].t - in.t) < 0.5) {
            pool.add(s[i]); // same stamp twice: keep the newer copy
            s[i] = in;
            return;
        }
        if (n == CAP) {
            if (i < 0) {
                pool.add(in); // older than everything we keep
                return;
            }
            pool.add(s[0]);
            System.arraycopy(s, 1, s, 0, n - 1);
            n--;
            i--;
        }
        int pos = i + 1;
        System.arraycopy(s, pos, s, pos + 1, n - pos);
        s[pos] = in;
        n++;
    }

    private void prune(double now) {
        double ref = Double.isNaN(renderTime) ? now - Config.maxDelayMs : renderTime;
        double cutoff = ref - 600.0;
        int drop = 0;
        while (n - drop > 3 && s[drop + 1].t < cutoff) drop++;
        if (drop > 0) {
            for (int i = 0; i < drop; i++) pool.add(s[i]);
            System.arraycopy(s, drop, s, 0, n - drop);
            for (int i = n - drop; i < n; i++) s[i] = null;
            n -= drop;
        }
    }

    private void pushAge(double v) {
        age[agePos] = v;
        agePos = (agePos + 1) % age.length;
        if (ageN < age.length) ageN++;
    }

    private void pushGap(double v) {
        gap[gapPos] = v;
        gapPos = (gapPos + 1) % gap.length;
        if (gapN < gap.length) gapN++;
    }

    private double percentile(double[] src, int count, double p) {
        System.arraycopy(src, 0, tmp, 0, count);
        Arrays.sort(tmp, 0, count);
        int idx = (int) Math.round(p * (count - 1));
        return tmp[Math.max(0, Math.min(count - 1, idx))];
    }

    double ageP(double p) {
        return ageN > 0 ? percentile(age, ageN, p) : Double.NaN;
    }

    double sendGap() {
        return gapN > 0 ? percentile(gap, gapN, 0.5) : Double.NaN;
    }

    void updateTarget() {
        double t;
        if (ageN < 4) {
            t = Config.initialDelayMs;
        } else {
            double p = percentile(age, ageN, Config.pct());
            double g = gapN >= 4 ? percentile(gap, gapN, 0.5) : 100.0;
            t = p + Config.kGap() * g + Config.marginMs();
        }
        target = Math.max(Config.minDelayMs, Math.min(Config.maxDelayMs, t));
    }

    /** Computes this frame's pose into outX/outY/outDir. NONE: nothing to show, leave the zombie to vanilla. */
    int get(double now) {
        if (n == 0) return NONE;

        double dtFrame = Double.isNaN(lastNow) ? 0.0 : now - lastNow;
        if (dtFrame < 0.0) dtFrame = 0.0;
        if (dtFrame > 250.0) dtFrame = 250.0;
        lastNow = now;

        // the delay moves toward its target with a limited, smoothly changing rate:
        // the zombie's apparent speed changes by a few percent at most and never in a step
        if (delay < 0.0) {
            delay = target;
            delayRate = 0.0;
        } else {
            double want = (target - delay) / 700.0;
            want = Math.max(-Config.delayShrinkRate, Math.min(Config.delayGrowRate, want));
            double maxChange = Config.delayRateAccel * dtFrame / 1000.0;
            delayRate += Math.max(-maxChange, Math.min(maxChange, want - delayRate));
            delay += delayRate * dtFrame;
            if (delay < Config.minDelayMs) { delay = Config.minDelayMs; delayRate = 0.0; }
            if (delay > Config.maxDelayMs) { delay = Config.maxDelayMs; delayRate = 0.0; }
        }

        double r = now - delay;
        if (!Double.isNaN(renderTime) && r < renderTime && renderTime - r < 1000.0) {
            r = renderTime; // the server clock shift can step back a little; never run time backwards
        }
        renderTime = r;

        int ia = n - 1;
        while (ia >= 0 && s[ia].t > r) ia--;

        float px, py, vx, vy, dir;
        int mode;
        double nA, nB = Double.NaN;
        boolean linear = false;
        if (ia < 0) {
            Sample a = s[0];
            px = a.x;
            py = a.y;
            vx = vy = 0f;
            dir = a.dir;
            mode = M_FIRST;
            nA = a.t;
        } else if (ia < n - 1) {
            Sample a = s[ia];
            Sample b = s[ia + 1];
            double span = b.t - a.t;
            double h = span / 1000.0;
            double u = span > 0.0 ? (r - a.t) / span : 1.0;
            if (u < 0.0) u = 0.0;
            if (u > 1.0) u = 1.0;
            double lx = a.x + (b.x - a.x) * u;
            double ly = a.y + (b.y - a.y) * u;
            double hx = lx, hy = ly, hdx = 0.0, hdy = 0.0;
            boolean sane = h > 0.0 && h < 0.6;
            if (sane) {
                // Catmull-Rom tangents (tiles/s) from the neighbours; one-sided at the ends and across gaps
                double sx = (b.x - a.x) / h, sy = (b.y - a.y) / h;
                double max = sx, may = sy, mbx = sx, mby = sy;
                if (ia > 0 && b.t - s[ia - 1].t <= 700.0) {
                    Sample p0 = s[ia - 1];
                    double ta = (b.t - p0.t) / 1000.0;
                    max = (b.x - p0.x) / ta;
                    may = (b.y - p0.y) / ta;
                }
                if (ia + 2 < n && s[ia + 2].t - a.t <= 700.0) {
                    Sample p3 = s[ia + 2];
                    double tb = (p3.t - a.t) / 1000.0;
                    mbx = (p3.x - a.x) / tb;
                    mby = (p3.y - a.y) / tb;
                }
                if (Math.hypot(max, may) > Config.maxSpeed || Math.hypot(mbx, mby) > Config.maxSpeed) {
                    sane = false;
                } else {
                    double u2 = u * u, u3 = u2 * u;
                    double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
                    hx = h00 * a.x + h10 * h * max + h01 * b.x + h11 * h * mbx;
                    hy = h00 * a.y + h10 * h * may + h01 * b.y + h11 * h * mby;
                    if (Math.hypot(hx - lx, hy - ly) > Config.hermiteMaxDeviation) {
                        sane = false;
                    } else {
                        double d00 = 6 * u2 - 6 * u, d10 = 3 * u2 - 4 * u + 1, d01 = -6 * u2 + 6 * u, d11 = 3 * u2 - 2 * u;
                        hdx = (d00 * a.x + d01 * b.x) / h + d10 * max + d11 * mbx;
                        hdy = (d00 * a.y + d01 * b.y) / h + d10 * may + d11 * mby;
                    }
                }
            }
            if (sane) {
                px = (float) hx;
                py = (float) hy;
                vx = (float) hdx;
                vy = (float) hdy;
            } else {
                linear = true;
                px = (float) lx;
                py = (float) ly;
                if (h > 0.0) {
                    vx = (float) ((b.x - a.x) / h);
                    vy = (float) ((b.y - a.y) / h);
                } else {
                    vx = vy = 0f;
                }
            }
            dir = (float) (a.dir + wrap(b.dir - a.dir) * u);
            mode = M_INTERP;
            nA = a.t;
            nB = b.t;
        } else {
            Sample a = s[ia];
            double ex = r - a.t;
            boolean hold = ex > Config.maxExtrapolationMs;
            if (hold) {
                ex = Config.maxExtrapolationMs;
                if (!Double.isNaN(lastArrive) && now - lastArrive > Config.idleTimeoutMs) {
                    // nothing new for a long time: the owner stopped sending; vanilla takes over
                    if (!idle) stIdle++;
                    idle = true;
                    hasRaw = false;
                    mMode = -1;
                    zeroCorrection();
                    lastMode = M_HOLD;
                    return NONE;
                }
            }
            float lvx = 0f, lvy = 0f;
            if (ia >= 1) {
                Sample p = s[ia - 1];
                double pdt = (a.t - p.t) / 1000.0;
                if (pdt >= 0.02 && pdt <= 0.4) {
                    lvx = (float) ((a.x - p.x) / pdt);
                    lvy = (float) ((a.y - p.y) / pdt);
                    float sp = (float) Math.hypot(lvx, lvy);
                    if (sp > Config.maxSpeed) {
                        lvx = lvy = 0f;
                    }
                }
            }
            float e = (float) (ex / 1000.0);
            px = a.x + lvx * e;
            py = a.y + lvy * e;
            if (hold) {
                vx = vy = 0f;
            } else {
                vx = lvx;
                vy = lvy;
            }
            dir = a.dir;
            mode = hold ? M_HOLD : M_EXTRAP;
            nA = a.t;
        }

        // 1) advance the correction spring (critically damped, so it never overshoots back)
        double w0 = 2000.0 / Config.correctionTimeMs; // 1/s
        double dts = dtFrame / 1000.0;
        if (dts > 0.0) {
            double ex = Math.exp(-w0 * dts);
            double k = cvx + w0 * cx;
            cx = (float) ((cx + k * dts) * ex);
            cvx = (float) ((cvx - w0 * k * dts) * ex);
            k = cvy + w0 * cy;
            cy = (float) ((cy + k * dts) * ex);
            cvy = (float) ((cvy - w0 * k * dts) * ex);
            cdir *= (float) Math.exp(-dts * 1000.0 / Config.correctionTimeMs);
        }

        // 2) a fresh start blends in from the drawn pose; otherwise, if the curve under the zombie
        //    changed (not just the next snapshot), fold the jump into the correction
        if (snapPending) {
            snapPending = false;
            blendPending = false;
            zeroCorrection();
            hasRaw = false;
            stSnap++;
        }
        if (blendPending) {
            blendPending = false;
            float ex = bx - px, ey = by - py;
            if (Math.hypot(ex, ey) > Config.snapDistance) {
                zeroCorrection();
                stSnap++;
            } else {
                cx = ex;
                cy = ey;
                cvx = cvy = 0f;
                cdir = (float) wrap(bdir - dir);
            }
        } else if (hasRaw) {
            double dtr = (r - rawTime) / 1000.0;
            boolean natural = (mode == mMode && nA == mA && (nB == mB || (Double.isNaN(nB) && Double.isNaN(mB))))
                    || (mMode == M_INTERP && (mode == M_INTERP || mode == M_EXTRAP) && nA == mB);
            boolean foldPos = !natural;
            boolean foldVel = !natural || ((linear || mLinear) && nA != mA);
            if (dtr < 0.0 || dtr > 0.5) {
                zeroCorrection();
            } else if (foldPos || foldVel) {
                if (foldPos) {
                    float ex = (float) (rpx + rvx * dtr) - px;
                    float ey = (float) (rpy + rvy * dtr) - py;
                    float j = (float) Math.hypot(ex, ey);
                    if (j > Config.snapDistance) {
                        zeroCorrection();
                        stSnap++;
                        foldVel = false;
                    } else {
                        cx += ex;
                        cy += ey;
                        if (j > Config.jumpThreshold) stCorr++;
                        float dj = (float) wrap(rdir - dir);
                        if (Math.abs(dj) < Math.toRadians(120.0)) {
                            cdir += dj;
                        }
                    }
                }
                if (foldVel) {
                    cvx += rvx - vx;
                    cvy += rvy - vy;
                }
            }
        }
        float cl = (float) Math.hypot(cx, cy);
        if (cl > Config.snapDistance) {
            zeroCorrection();
            stSnap++;
            cl = 0f;
        }
        float cvl = (float) Math.hypot(cvx, cvy);
        if (cvl > 20f) {
            cvx *= 20f / cvl;
            cvy *= 20f / cvl;
        }
        cdir = (float) wrap(cdir);

        hasRaw = true;
        rawTime = r;
        rpx = px;
        rpy = py;
        rvx = vx;
        rvy = vy;
        rdir = dir;
        mMode = mode;
        mA = nA;
        mB = nB;
        mLinear = linear;

        outX = px + cx;
        outY = py + cy;
        outDir = (float) wrap(dir + cdir);

        stFrames++;
        if (mode == M_INTERP) stInterp++;
        else if (mode == M_EXTRAP) stExtrap++;
        else stHold++;
        stSumDelay += delay;
        if (delay > stMaxDelay) stMaxDelay = delay;
        if (cl > stMaxCorr) stMaxCorr = cl;
        lastMode = mode;
        return OK;
    }

    void resetStats() {
        stFrames = stInterp = stExtrap = stHold = stCorr = stSnap = stSamples = stStamped = stTeleport = stDropped = stIdle = 0;
        stMaxCorr = stSumDelay = stMaxDelay = 0.0;
    }

    /** Angle difference folded into (-pi, pi]. */
    static double wrap(double a) {
        a = a % (2.0 * Math.PI);
        if (a > Math.PI) a -= 2.0 * Math.PI;
        if (a <= -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
