package com.druid.zombiesync;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import zombie.GameTime;
import zombie.ai.State;
import zombie.ai.states.AttackNetworkState;
import zombie.ai.states.AttackState;
import zombie.ai.states.LungeNetworkState;
import zombie.ai.states.LungeState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.ThumpState;
import zombie.ai.states.WalkTowardNetworkState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieFaceTargetState;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieAI;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugLog;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.NetworkVariables;
import zombie.network.packets.character.ZombiePacket;
import zombie.network.packets.character.ZombieSynchronizationPacket;

/**
 * Glue between the game's zombie networking and {@link Track}. Everything runs on the main thread, except that the
 * attack hooks are animation variables, which PZ Optimization may read from its worker threads; the maps they touch
 * are synchronized for that.
 */
public final class Engine {
    static final String TAG = "[ZombieSync] ";
    static final long VANILLA_NEIGHBOR_PERIOD = 200L;
    static final int PACKET_BYTES = 53;

    private static final Map<IsoZombie, Track> tracks = Collections.synchronizedMap(new WeakHashMap<>());
    /** remote zombies the owner told to eat (EatBody packet); any other eating on a remote zombie is ours to undo */
    private static final Map<IsoZombie, Boolean> ownerEating = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean failed;

    private static double frameNow = Double.NaN;
    private static long frameNanos;

    // owner side
    private static int ownedZombies;
    private static long nextCountMs, nextShareMs;
    private static long sendPeriod = VANILLA_NEIGHBOR_PERIOD;
    private static int stampedSent;

    // NetworkZombieAI.parse enter -> exit hand-off
    private static NetworkZombieAI parseAi;
    private static float parseX, parseY, parseZ, parseDir;

    // eating decided by the owner only
    private static int eatPacketDepth;
    private static int eatBlocked, staleEatCleared, attacksEnded, unstuck;

    // stats
    private static long nextStatsMs;
    private static int undoneTeleports, yields;

    private Engine() {
    }

    // ---------------------------------------------------------------- lifecycle

    static void init() {
        String status;
        try {
            status = Config.loadOrCreate(configFile());
        } catch (Throwable t) {
            status = "config error: " + t;
        }
        log("loaded. " + status);
        log("settings: " + Config.describe());
    }

    static File configFile() {
        String dir = null;
        try {
            dir = zombie.ZomboidFileSystem.instance.getCacheDir();
        } catch (Throwable ignored) {
        }
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.home") + File.separator + "Zomboid";
        }
        return new File(dir, "ZombieSyncMP.properties");
    }

    static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (Throwable t) {
            System.out.println(TAG + msg);
        }
    }

    private static void fail(String where, Throwable t) {
        if (failed) return;
        failed = true;
        log("disabled after an error in " + where + "; zombies use vanilla sync until restart: " + t);
        for (StackTraceElement e : t.getStackTrace()) {
            log("    at " + e);
        }
        tracks.clear();
        Players.clear();
        try {
            if (GameClient.instance != null && GameClient.instance.sendZombieTimer.getDelay() < VANILLA_NEIGHBOR_PERIOD) {
                GameClient.instance.sendZombieTimer.setUpdatePeriod(VANILLA_NEIGHBOR_PERIOD);
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean receiverActive() {
        return Config.enabled && !failed && GameClient.client && GameTime.getServerTimeShiftIsSet();
    }

    static boolean senderActive() {
        return Config.enabled && Config.sendBoost && !failed && GameClient.client && GameTime.getServerTimeShiftIsSet();
    }

    static double serverNowMs() {
        return GameTime.getServerTime() / 1.0e6;
    }

    /** Each remote zombie with its buffer (to share a clock with the player it chases). */
    static void forEachTrack(BiConsumer<IsoZombie, Track> f) {
        synchronized (tracks) {
            for (Map.Entry<IsoZombie, Track> e : tracks.entrySet()) {
                if (e.getKey() != null) f.accept(e.getKey(), e.getValue());
            }
        }
    }

    static double now() {
        if (Double.isNaN(frameNow) || System.nanoTime() - frameNanos > 50_000_000L) {
            return serverNowMs();
        }
        return frameNow;
    }

    // ---------------------------------------------------------------- hooks (called from Patches)

    /** VehicleManager.clientUpdate entry: once per frame in a multiplayer client. */
    public static void frame() {
        if (failed || !GameClient.client) return;
        try {
            frameNanos = System.nanoTime();
            frameNow = GameTime.getServerTimeShiftIsSet() ? serverNowMs() : Double.NaN;
            if (!Config.enabled && !tracks.isEmpty()) tracks.clear();
            if (!Config.enabled || !Config.syncPlayers) Players.clear();
            long ms = System.currentTimeMillis();
            if (ms >= nextCountMs) {
                nextCountMs = ms + 1000L;
                updateSendPeriod();
                // zombie objects are pooled, so the weak map alone would keep buffers of long-gone zombies
                synchronized (tracks) {
                    tracks.values().removeIf(t -> ms - t.lastSeenMs > 30_000L);
                }
                Players.cleanup(ms);
            }
            if (ms >= nextShareMs) {
                nextShareMs = ms + 250L;
                Players.shareClocks();
            }
            Players.frame(ms);
            if (Config.logStats) {
                if (nextStatsMs == 0L) {
                    nextStatsMs = ms + Config.statsIntervalSec * 1000L;
                } else if (ms >= nextStatsMs) {
                    nextStatsMs = ms + Config.statsIntervalSec * 1000L;
                    reportStats();
                }
            }
        } catch (Throwable t) {
            fail("frame", t);
        }
    }

    /** Zombies this client simulates, and the send period that fits them into the upload budget. */
    private static void updateSendPeriod() {
        int count = 0;
        ArrayList<IsoZombie> list = IsoWorld.instance != null && IsoWorld.instance.currentCell != null
                ? IsoWorld.instance.currentCell.getZombieList() : null;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                IsoZombie z = list.get(i);
                if (z != null && z.onlineId != -1 && !z.isRemoteZombie()) count++;
            }
        }
        ownedZombies = count;
        long budget = Math.max(1L, Config.sendBudgetKBps * 1024L);
        long period = (long) Math.ceil(count * (double) PACKET_BYTES * 1000.0 / budget);
        sendPeriod = Math.max(Config.sendIntervalMs, Math.min(VANILLA_NEIGHBOR_PERIOD, period));
        applySendPeriod();
    }

    private static void applySendPeriod() {
        UpdateLimit timer = GameClient.instance.sendZombieTimer;
        long d = timer.getDelay();
        if (senderActive()) {
            // the server asks for 200 ms when another player is near and 4 s otherwise; only the first is shortened
            if (d <= VANILLA_NEIGHBOR_PERIOD && d != sendPeriod) timer.setUpdatePeriod(sendPeriod);
        } else if (d < VANILLA_NEIGHBOR_PERIOD) {
            timer.setUpdatePeriod(VANILLA_NEIGHBOR_PERIOD);
        }
    }

    /** ZombieSynchronizationPacket.parse exit: vanilla has just reset the send period. */
    public static void afterSyncParse(ZombieSynchronizationPacket p) {
        if (failed || p == null) return;
        try {
            if (p.hasNeighborPlayer) applySendPeriod();
        } catch (Throwable t) {
            fail("ZombieSynchronizationPacket.parse", t);
        }
    }

    /** ZombiePacket.set exit on the owner: stamp the send time into the facing angle. */
    public static void afterPacketSet(ZombiePacket pkt) {
        if (pkt == null || GameServer.server || !senderActive()) return;
        try {
            pkt.dirAngleRads = Stamp.encode(pkt.dirAngleRads, serverNowMs(), pkt.id);
            stampedSent++;
        } catch (Throwable t) {
            fail("ZombiePacket.set", t);
        }
    }

    /** NetworkZombieAI.parse entry: one owner update of a remote zombie. */
    public static void parseEnter(NetworkZombieAI ai, ZombiePacket pkt) {
        parseAi = null;
        if (ai == null || pkt == null || !receiverActive()) return;
        try {
            IsoZombie z = ai.zombie;
            if (z == null) return;
            double now = serverNowMs();
            Track tr = tracks.get(z);
            if (tr == null) {
                tr = new Track();
                tracks.put(z, tr);
            }
            if (tr.netId != pkt.id) {
                tr.clear(); // pooled zombie object reused for another zombie
                tr.netId = pkt.id;
            }
            boolean fresh = tr.n == 0 || tr.idle || !(now - tr.lastArrive <= Config.idleTimeoutMs);
            Sample smp = tr.obtain();
            smp.x = pkt.realX;
            smp.y = pkt.realY;
            double t = Stamp.decode(pkt.dirAngleRads, now, pkt.id);
            smp.stamped = !Double.isNaN(t);
            smp.t = smp.stamped ? t : now;
            smp.dir = smp.stamped ? Stamp.clean(pkt.dirAngleRads) : pkt.dirAngleRads;
            if (fresh) tr.blendFrom(z.getX(), z.getY(), z.getDirectionAngleRadians());
            tr.lastSeenMs = System.currentTimeMillis();
            tr.floor = pkt.realZ;
            if (Config.fixEating && z.getEatBodyTarget() != null && !ownerEating.containsKey(z)) {
                z.setEatBodyTarget(null, false); // started eating on its own (before the mod was on, or not ours to block)
                staleEatCleared++;
            }
            tr.add(smp, now, Config.unstampedAgeMs);
            parseAi = ai;
            parseX = z.getX();
            parseY = z.getY();
            parseZ = z.getZ();
            parseDir = z.getDirectionAngleRadians();
        } catch (Throwable t) {
            fail("NetworkZombieAI.parse", t);
        }
    }

    /**
     * NetworkZombieAI.parse exit: vanilla snaps the facing to the packet and teleports the zombie when it
     * is more than 3 tiles off (to the corner of the tile). While the buffer drives the zombie both are undone;
     * the buffer turns and moves it smoothly instead, and snaps by itself when the error is really large.
     */
    public static void parseExit(NetworkZombieAI ai) {
        if (ai == null || ai != parseAi) return;
        parseAi = null;
        try {
            IsoZombie z = ai.zombie;
            if (z == null || !drivesPose(z)) return;
            z.setForwardDirection((float) Math.cos(parseDir), (float) Math.sin(parseDir));
            float dx = z.getX() - parseX, dy = z.getY() - parseY;
            if (dx * dx + dy * dy > 0.25f && (int) Math.floor(z.getZ()) == (int) Math.floor(parseZ)) {
                z.setX(parseX);
                z.setY(parseY);
                z.setNextX(parseX);
                z.setNextY(parseY);
                z.setLastX(parseX);
                z.setLastY(parseY);
                z.setZ(parseZ);
                z.setCurrentSquareFromPosition();
                undoneTeleports++;
            }
        } catch (Throwable t) {
            fail("NetworkZombieAI.parse exit", t);
        }
    }

    /** States in which a remote zombie only walks, turns, stands or attacks: its pose comes from the buffer. */
    private static boolean drivesPose(IsoZombie z) {
        if (!z.isRemoteZombie() || z.isDead() || z.isOnFloor() || z.isRagdoll() || z.isBeingGrappled()
                || z.isReanimatedForGrappleOnly() || z.getVehicle() != null) {
            return false;
        }
        State st = z.getCurrentState();
        return st == WalkTowardNetworkState.instance() || st == ZombieIdleState.instance() || st == LungeNetworkState.instance()
                || st == AttackNetworkState.instance() || st == PathFindState.instance() || st == ZombieFaceTargetState.instance()
                || st == ZombieTurnAlerted.instance() || st == ThumpState.instance() || st == WalkTowardState.instance()
                || st == LungeState.instance() || st == AttackState.instance();
    }

    /** IsoZombie.postupdate entry: set where the zombie moves this frame (postupdate still resolves walls). */
    public static void beforePostUpdate(IsoZombie z) {
        if (z == null || !receiverActive() || tracks.isEmpty()) return;
        try {
            Track tr = tracks.get(z);
            if (tr == null) return;
            if (!z.isRemoteZombie()) {
                tracks.remove(z);
                return;
            }
            tr.lastSeenMs = System.currentTimeMillis();
            if (!drivesPose(z) || (int) Math.floor(z.getZ()) != tr.floor) {
                // a local animation moves it (hit reaction, climbing, falling, getting up, eating), or it is
                // on another floor than the owner's (stairs, vanilla will move it): keep buffering and blend
                // back in from wherever it ends up
                if (!tr.yielding) {
                    tr.yielding = true;
                    yields++;
                }
                return;
            }
            if (tr.yielding) {
                tr.yielding = false;
                tr.blendFrom(z.getX(), z.getY(), z.getDirectionAngleRadians());
            }
            followAttackEnd(z);
            if (tr.get(now()) != Track.OK) return;
            float zx = z.getX(), zy = z.getY();
            if (heldOffPose(tr, zx, zy, (float) Math.hypot(zx - tr.outX, zy - tr.outY), System.currentTimeMillis())) {
                // something on our side (a fence it climbed at the owner's, a corner it cut) keeps it from its
                // pose; vanilla would teleport it to the corner of the tile after 3 tiles, we put it on the pose
                z.setX(tr.outX);
                z.setY(tr.outY);
                z.setLastX(tr.outX);
                z.setLastY(tr.outY);
                z.setCurrentSquareFromPosition();
                unstuck++;
            }
            z.setNextX(tr.outX);
            z.setNextY(tr.outY);
            if (aimsLocally(z)) {
                tr.facingLocal = true; // it turns to a target it picked by itself: let the game aim it
            } else {
                if (tr.facingLocal) {
                    tr.facingLocal = false;
                    tr.alignFacing(z.getDirectionAngleRadians());
                }
                float fx = (float) Math.cos(tr.outDir), fy = (float) Math.sin(tr.outDir);
                if (fx != 0f || fy != 0f) z.setForwardDirection(fx, fy);
            }
        } catch (Throwable t) {
            fail("IsoZombie.postupdate", t);
        }
    }

    /**
     * Held back from its pose for blockedMs: far behind it, or not moving at all while clearly off it (pressed into
     * a wall corner the owner's character stands on the other side of; characters in a crowd keep shuffling, so they
     * don't count). Called once per driven frame with where the character stands and how far that is from the pose.
     */
    static boolean heldOffPose(Track tr, float x, float y, float err, long wall) {
        boolean still = Math.abs(x - tr.prevX) < 0.002f && Math.abs(y - tr.prevY) < 0.002f;
        tr.prevX = x;
        tr.prevY = y;
        if (err > Config.blockedDistance || (still && err > Config.stuckDistance)) {
            if (tr.blockedSince == 0L) tr.blockedSince = wall;
        } else {
            tr.blockedSince = 0L;
        }
        if (tr.blockedSince != 0L && wall - tr.blockedSince > Config.blockedMs) {
            tr.blockedSince = 0L;
            return true;
        }
        return false;
    }

    /**
     * IsoZombie.getShouldAttack exit. Vanilla lets a remote zombie decide by itself to attack when it gets
     * within 0.72 tiles of our copy of its target, and then plays the whole bite (about 1.5 s) while the
     * owner's zombie may already be chasing again. The owner reports its zombie's animation state in every
     * update; the remote one attacks while that state is "attack".
     */
    public static boolean shouldAttack(IsoZombie z, boolean vanilla) {
        if (z == null || !Config.followAttacks || !receiverActive()) return vanilla;
        try {
            if (!z.isRemoteZombie() || z.getTarget() == null) return vanilla;
            Track tr = tracks.get(z);
            if (tr == null || tr.n == 0 || tr.idle) return vanilla;
            return z.realState == NetworkVariables.ZombieState.Attack;
        } catch (Throwable t) {
            fail("IsoZombie.getShouldAttack", t);
            return vanilla;
        }
    }

    /**
     * IsoZombie.isFacingTarget exit. A bite starts only when the zombie faces our copy of its target, but a
     * remote zombie faces the way its owner's zombie does, at the real target, and our copy of a moving
     * player can be tiles behind it. Without this the zombie stands turning toward the copy and never bites;
     * letting the game aim it instead makes it bite the air behind itself.
     */
    public static boolean isFacingTarget(IsoZombie z, boolean vanilla) {
        if (vanilla || z == null || !Config.followAttacks || !receiverActive()) return vanilla;
        try {
            if (!z.isRemoteZombie() || z.getTarget() == null || z.isBumped() || !ownerAttacking(z)) return vanilla;
            Track tr = tracks.get(z);
            return tr != null && tr.n > 0 && !tr.idle && drivesPose(z);
        } catch (Throwable t) {
            fail("IsoZombie.isFacingTarget", t);
            return vanilla;
        }
    }

    /** The owner's zombie is biting a player or hitting a vehicle, so it faces its target there. */
    private static boolean ownerAttacking(IsoZombie z) {
        return z.realState == NetworkVariables.ZombieState.Attack || z.realState == NetworkVariables.ZombieState.AttackVehicle;
    }

    /**
     * The facing normally comes from the owner. The game aims the zombie only while it turns to a target our
     * side picked by itself (the owner's zombie is not attacking), so that it can finish turning, and, with
     * followAttacks off, while it bites on its own.
     */
    private static boolean aimsLocally(IsoZombie z) {
        State st = z.getCurrentState();
        if (!Config.followAttacks) return st == ZombieFaceTargetState.instance() || st == AttackNetworkState.instance();
        return st == ZombieFaceTargetState.instance() && !ownerAttacking(z);
    }

    /** The owner's zombie stopped attacking: end ours too instead of finishing the bite in place. */
    private static void followAttackEnd(IsoZombie z) {
        if (!Config.followAttacks || z.getCurrentState() != AttackNetworkState.instance()) return;
        if (z.realState == NetworkVariables.ZombieState.Attack) return;
        if (!"true".equals(z.getVariableString("ZombieBiteDone"))) {
            z.setVariable("ZombieBiteDone", true);
            attacksEnded++;
        }
    }

    /** NetworkZombieSimulator.becomeLocal / becomeRemote exit: the owner changed, start over. */
    public static void ownerChanged(IsoZombie z) {
        if (z == null || tracks.isEmpty()) return;
        tracks.remove(z);
    }

    public static void eatPacketEnter() {
        eatPacketDepth++;
    }

    public static void eatPacketExit() {
        if (eatPacketDepth > 0) eatPacketDepth--;
    }

    /**
     * IsoZombie.setEatBodyTarget(target, force, speed) entry. A remote zombie decides locally to start
     * eating when its attack ends with the target on the floor, which our copy of the target may be while
     * the real one is up: it then sits eating for ages while the owner's zombie walks on, and gets
     * teleported. The owner sends EatBody whenever its zombie really starts eating, so only that counts.
     */
    public static boolean skipEat(IsoZombie z, Object target) {
        if (z == null) return false;
        if (target == null) {
            if (!ownerEating.isEmpty()) ownerEating.remove(z);
            return false;
        }
        if (eatPacketDepth > 0) {
            ownerEating.put(z, Boolean.TRUE);
            return false;
        }
        if (!Config.fixEating || !receiverActive()) return false;
        try {
            if (!z.isRemoteZombie()) return false;
            eatBlocked++;
            return true;
        } catch (Throwable t) {
            fail("IsoZombie.setEatBodyTarget", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- reporting

    private static ArrayList<Track> trackList() {
        synchronized (tracks) {
            return new ArrayList<>(tracks.values());
        }
    }

    static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("ZombieSync: ").append(failed ? "FAILED (vanilla)" : (receiverActive() ? "active" : "idle")).append('\n');
        sb.append(Config.describe()).append('\n');
        sb.append(String.format(Locale.ROOT, "owner: %d zombies simulated here, send period %d ms (timer %d ms)%n",
                ownedZombies, sendPeriod, GameClient.instance != null ? GameClient.instance.sendZombieTimer.getDelay() : -1));
        int active = 0, yielding = 0;
        double sumDelay = 0, sumAge = 0;
        int ageCount = 0;
        for (Track t : trackList()) {
            if (t.n == 0 || t.idle) continue;
            active++;
            if (t.yielding) yielding++;
            sumDelay += Math.max(0, t.delay);
            double a = t.medianAge();
            if (!Double.isNaN(a)) {
                sumAge += a;
                ageCount++;
            }
        }
        sb.append(String.format(Locale.ROOT, "remote: %d zombies followed (%d in a local animation), delay avg %.0f ms, packet age %.0f ms%n",
                active, yielding, active > 0 ? sumDelay / active : 0.0, ageCount > 0 ? sumAge / ageCount : Double.NaN));
        Players.status(sb);
        return sb.toString();
    }

    private static void reportStats() {
        int n = 0, frames = 0, interp = 0, extrap = 0, hold = 0, corr = 0, snap = 0, samples = 0, stamped = 0, tele = 0, idle = 0;
        double sumDelay = 0, maxDelay = 0, maxCorr = 0, sumAge = 0;
        int ageCount = 0;
        for (Track t : trackList()) {
            if (t.stFrames == 0 && t.stSamples == 0) continue;
            n++;
            frames += t.stFrames;
            interp += t.stInterp;
            extrap += t.stExtrap;
            hold += t.stHold;
            corr += t.stCorr;
            snap += t.stSnap;
            samples += t.stSamples;
            stamped += t.stStamped;
            tele += t.stTeleport;
            idle += t.stIdle;
            sumDelay += t.stSumDelay;
            maxDelay = Math.max(maxDelay, t.stMaxDelay);
            maxCorr = Math.max(maxCorr, t.stMaxCorr);
            double a = t.medianAge();
            if (!Double.isNaN(a)) {
                sumAge += a;
                ageCount++;
            }
            t.resetStats();
        }
        int secs = Config.statsIntervalSec;
        if (ownedZombies > 0 || stampedSent > 0) {
            log(String.format(Locale.ROOT, "owner: %d zombies, send period %d ms, %d updates sent (%.1f/s)",
                    ownedZombies, sendPeriod, stampedSent, stampedSent / (double) secs));
        }
        stampedSent = 0;
        if (n > 0) {
            int f = Math.max(1, frames);
            log(String.format(Locale.ROOT,
                    "remote: %d zombies, %d updates (%.0f%% stamped), age %.0f ms, delay avg %.0f max %.0f ms, frames %d "
                            + "(interp %.1f%%, predict %.1f%%, hold %.1f%%), corrections %d (max %.2f), snaps %d, teleports %d, "
                            + "idle %d, local animations %d, vanilla teleports undone %d, local eating blocked %d (stopped %d), attacks ended %d, unstuck %d",
                    n, samples, samples > 0 ? 100.0 * stamped / samples : 0.0, ageCount > 0 ? sumAge / ageCount : Double.NaN,
                    sumDelay / f, maxDelay, frames, 100.0 * interp / f, 100.0 * extrap / f, 100.0 * hold / f,
                    corr, maxCorr, snap, tele, idle, yields, undoneTeleports, eatBlocked, staleEatCleared, attacksEnded, unstuck));
        }
        yields = 0;
        undoneTeleports = 0;
        eatBlocked = 0;
        staleEatCleared = 0;
        attacksEnded = 0;
        unstuck = 0;
        Players.report(secs);
    }
}
