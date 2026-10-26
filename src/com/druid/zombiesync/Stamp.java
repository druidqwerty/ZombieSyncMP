package com.druid.zombiesync;

/**
 * The owner's send time, carried in the 16 low mantissa bits of the zombie packet's facing angle.
 *
 * The wire format stays vanilla (the server copies the float as is when it relays), the facing
 * changes by less than 1 degree, and a client without the mod just reads a slightly different angle.
 * Layout: 10 bits of time in 4 ms units (wraps every 4096 ms) and 6 check bits tied to the zombie
 * id, so a float that was never stamped is rejected 63 times out of 64; the track also rejects
 * stamps whose age does not fit.
 */
final class Stamp {
    static final int TICK_MS = 4;
    static final int TICK_BITS = 10;
    static final int WRAP = 1 << TICK_BITS;
    static final int CHECK_BITS = 6;

    private Stamp() {
    }

    private static int check(int ticks, int id) {
        int h = ticks * 0x9E3779B1 ^ (id & 0xFFFF) * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        return (h >>> (32 - CHECK_BITS)) & ((1 << CHECK_BITS) - 1);
    }

    static float encode(float angle, double nowMs, int id) {
        if (!Float.isFinite(angle)) return angle;
        int ticks = (int) (Math.floorDiv((long) Math.floor(nowMs), TICK_MS) & (WRAP - 1));
        int bits = Float.floatToRawIntBits(angle);
        bits = (bits & 0xFFFF0000) | (ticks << CHECK_BITS) | check(ticks, id);
        return Float.intBitsToFloat(bits);
    }

    /** Send time on the server clock, or NaN when the angle carries no valid stamp. */
    static double decode(float angle, double nowMs, int id) {
        if (!Float.isFinite(angle)) return Double.NaN;
        int low = Float.floatToRawIntBits(angle) & 0xFFFF;
        int ticks = low >>> CHECK_BITS;
        if ((low & ((1 << CHECK_BITS) - 1)) != check(ticks, id)) return Double.NaN;
        long nowTicks = Math.floorDiv((long) Math.floor(nowMs), TICK_MS);
        long ago = (nowTicks - ticks) & (WRAP - 1); // 0..1023 ticks before now, modulo the wrap
        if (ago > WRAP * 3 / 4) ago -= WRAP;          // up to a second "in the future": clock error
        return (nowTicks - ago) * (double) TICK_MS + TICK_MS * 0.5;
    }

    /** The angle without the stamp bits (centred, so the error is at most half a stamp). */
    static float clean(float angle) {
        if (!Float.isFinite(angle)) return angle;
        int bits = Float.floatToRawIntBits(angle);
        return Float.intBitsToFloat((bits & 0xFFFF0000) | 0x8000);
    }
}
