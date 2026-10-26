package com.druid.zombiesync;

/** One network snapshot of a remote zombie. */
final class Sample {
    /** owner send time on the server clock, ms */
    double t;
    /** local arrival time on the server clock, ms */
    double arrive;
    /** true when {@link #t} came from the owner's stamp, false when it is estimated from the arrival time */
    boolean stamped;
    float x, y;
    /** facing, radians */
    float dir;
    /** made up locally (a seed at the zombie's drawn pose), never sent by the owner */
    boolean synthetic;

    boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(dir) && Double.isFinite(t);
    }

    void copyFrom(Sample o) {
        t = o.t;
        arrive = o.arrive;
        stamped = o.stamped;
        x = o.x;
        y = o.y;
        dir = o.dir;
        synthetic = o.synthetic;
    }
}
