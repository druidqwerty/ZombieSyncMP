package com.druid.zombiesync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import zombie.ai.State;
import zombie.ai.states.BumpedState;
import zombie.ai.states.IdleState;
import zombie.ai.states.PlayerAimState;
import zombie.ai.states.PlayerEmoteState;
import zombie.ai.states.PlayerHitReactionPVPState;
import zombie.ai.states.PlayerHitReactionState;
import zombie.ai.states.PlayerStrafeState;
import zombie.ai.states.SwipeStatePlayer;
import zombie.ai.states.player.PlayerMovementState;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.character.PlayerPacket;

/**
 * Other players, drawn on the same clock as the zombies.
 *
 * Vanilla sends a player's position when it steps onto another tile, turns to another of 8 directions or
 * every 600 ms, and moves the copy on other clients with its walking animation toward a point guessed from
 * the last packet, at most 10% faster than the animation. The copy falls behind whenever the player starts,
 * turns or moves faster than its copy's animation, and only slowly catches up. The zombies, drawn about
 * 0.15-0.2 s behind the owner, then reach and bite the player where it really is, ahead of its copy.
 *
 * Here the owner sends its position 10 times a second while another player is near, with the send time in
 * the facing angle like a zombie packet, and the copy follows the real path through the same snapshot buffer
 * as a zombie. A player and the remote zombies chasing it share one delay (the longest of theirs), so they are
 * drawn at the same moment of the past and a bite lands on the player as drawn.
 * The copy is driven only while it stands, walks, runs, aims, swings or reacts to a hit; climbing, falling,
 * sitting, vehicles and the like stay with vanilla, and the buffer blends back in afterwards.
 */
public final class Players {
    static final long SEND_MS = 100L; // our position updates while another player is near (vanilla: one per tile, 2-5/s)

    private static final Map<IsoPlayer, Track> tracks = Collections.synchronizedMap(new WeakHashMap<>());
    private static final IdentityHashMap<Object, double[]> clocks = new IdentityHashMap<>();
    private static boolean sharing;
    private static volatile boolean failed;

    // owner side
    private static final long[] lastForcedMs = new long[4];
    private static boolean neighbor;
    private static long nextNeighborMs;
    private static int stampedSent;

    // stats
    private static int undoneTeleports, unstuck, placed, yields;

    private Players() {
    }

    static boolean active() {
        return Config.syncPlayers && !failed;
    }

    private static void fail(String where, Throwable t) {
        if (failed) return;
        failed = true;
        Engine.log("player sync disabled after an error in " + where + "; other players use vanilla sync until restart: " + t);
        for (StackTraceElement e : t.getStackTrace()) {
            Engine.log("    at " + e);
        }
        tracks.clear();
    }

    // ---------------------------------------------------------------- owner side

    /** Once per frame: our own players' positions go out SEND_MS apart while another player is near. */
    static void frame(long ms) {
        if (!active() || !Engine.senderActive()) return;
        try {
            if (ms >= nextNeighborMs) {
                nextNeighborMs = ms + 500L;
                neighbor = otherPlayerNear();
            }
            if (!neighbor) return;
            for (int i = 0; i < IsoPlayer.players.length && i < lastForcedMs.length; i++) {
                IsoPlayer p = IsoPlayer.players[i];
                if (p == null || !p.isLocalPlayer() || p.isDead() || p.getVehicle() != null) continue;
                if (ms - lastForcedMs[i] >= SEND_MS) {
                    lastForcedMs[i] = ms;
                    p.getNetworkCharacterAI().needToUpdate(); // the next PlayerPacket.set sends it (reliable, as vanilla's)
                }
            }
        } catch (Throwable t) {
            fail("player send", t);
        }
    }

    /** Another player is in the world around us: someone draws our copy. */
    private static boolean otherPlayerNear() {
        for (IsoPlayer q : GameClient.IDToPlayerMap.values()) {
            if (q != null && !q.isLocalPlayer() && !(q instanceof IsoAnimal) && q.getCurrentSquare() != null) return true;
        }
        return false;
    }

    /** PlayerPacket.set exit: a packet about to be sent for our player carries the send time. */
    public static void afterPacketSet(PlayerPacket pkt, IsoPlayer chr, PacketTypes.PacketType type) {
        if (type == null || pkt == null || chr == null || GameServer.server || !active() || !Engine.senderActive()) return;
        try {
            pkt.prediction.direction = Stamp.encode(pkt.prediction.direction, Engine.serverNowMs(), chr.getOnlineID());
            stampedSent++;
        } catch (Throwable t) {
            fail("PlayerPacket.set", t);
        }
    }

    // ---------------------------------------------------------------- receiver side

    /** PlayerPacket.processClient entry: one update of another player. */
    public static void beforePacket(PlayerPacket pkt) {
        if (pkt == null || !active() || !Engine.receiverActive()) return;
        try {
            IsoPlayer p = pkt.id.getPlayer();
            if (p == null || p.isLocalPlayer() || p instanceof IsoAnimal) return;
            double now = Engine.serverNowMs();
            Track tr = tracks.get(p);
            if (tr == null) {
                tr = new Track();
                tracks.put(p, tr);
            }
            short id = p.getOnlineID();
            if (tr.netId != id) {
                tr.clear();
                tr.netId = id;
            }
            boolean fresh = tr.n == 0 || tr.idle || !(now - tr.lastArrive <= Config.idleTimeoutMs);
            Sample smp = tr.obtain();
            smp.x = pkt.prediction.x;
            smp.y = pkt.prediction.y;
            double t = Stamp.decode(pkt.prediction.direction, now, id);
            smp.stamped = !Double.isNaN(t);
            smp.t = smp.stamped ? t : now;
            smp.dir = smp.stamped ? Stamp.clean(pkt.prediction.direction) : pkt.prediction.direction;
            if (fresh) tr.blendFrom(p.getX(), p.getY(), p.getDirectionAngleRadians());
            tr.lastSeenMs = System.currentTimeMillis();
            if (smp.stamped) tr.stampedMs = tr.lastSeenMs;
            tr.floor = pkt.prediction.z;
            tr.add(smp, now, Config.unstampedAgeMs);
        } catch (Throwable t) {
            fail("PlayerPacket.processClient", t);
        }
    }

    /** States in which the copy only stands, walks, runs, aims, swings or reacts to a hit: its position comes from the buffer. */
    private static boolean drivable(IsoPlayer p) {
        if (p.isDead() || p.getVehicle() != null || p.isOnFloor() || p.isRagdoll() || p.isBeingGrappled() || p.isGrappling()
                || p.isPerformingGrappleAnimation() || p.isDraggingCorpse()) {
            return false;
        }
        State st = p.getCurrentState();
        return st == null || st == IdleState.instance() || st == PlayerMovementState.instance() || st == PlayerStrafeState.instance()
                || st == PlayerAimState.instance() || st == SwipeStatePlayer.instance() || st == PlayerHitReactionState.instance()
                || st == PlayerHitReactionPVPState.instance() || st == BumpedState.instance() || st == PlayerEmoteState.instance();
    }

    /** IsoPlayer.postupdate entry: where another player's copy moves this frame (postupdate still resolves walls). */
    public static void beforePostUpdate(IsoPlayer p) {
        if (p == null || tracks.isEmpty() || !active() || !Engine.receiverActive()) return;
        try {
            Track tr = tracks.get(p);
            if (tr == null) return;
            if (p.isLocalPlayer()) {
                tracks.remove(p);
                return;
            }
            long wall = System.currentTimeMillis();
            tr.lastSeenMs = wall;
            // only a stream from an owner with the mod: stamped, and dense enough to draw from (vanilla's own
            // stream is a packet per tile, its copy is better left to vanilla)
            double gap = tr.sendGap();
            boolean stream = wall - tr.stampedMs < 2000L && !(gap > 150.0);
            if (!stream || !drivable(p) || (int) Math.floor(p.getZ()) != tr.floor) {
                tr.driving = false;
                if (!tr.yielding) {
                    tr.yielding = true;
                    yields++;
                }
                return;
            }
            if (tr.yielding) {
                tr.yielding = false;
                tr.blendFrom(p.getX(), p.getY(), p.getDirectionAngleRadians());
            }
            boolean drove = tr.driving;
            float lastX = tr.outX, lastY = tr.outY;
            if (tr.get(Engine.now()) != Track.OK) {
                tr.driving = false;
                return;
            }
            float px = p.getX(), py = p.getY();
            if (drove && Math.hypot(px - lastX, py - lastY) > 0.5 && Math.hypot(tr.outX - lastX, tr.outY - lastY) < 0.5) {
                // since we put it there last frame something jumped it away that the owner's path does not explain:
                // vanilla teleports the copy to the last packet when its own path finding fails or the copy seems stuck
                place(p, lastX, lastY);
                px = lastX;
                py = lastY;
                undoneTeleports++;
            }
            float err = (float) Math.hypot(px - tr.outX, py - tr.outY);
            if (err > Config.snapDistance) {
                // a teleport, or vanilla had it somewhere else entirely: postupdate would drag it there a tile per frame
                place(p, tr.outX, tr.outY);
                tr.blockedSince = 0L;
                placed++;
            } else if (Engine.heldOffPose(tr, px, py, err, wall)) {
                place(p, tr.outX, tr.outY);
                unstuck++;
            }
            p.setNextX(tr.outX);
            p.setNextY(tr.outY);
            tr.driving = true;
        } catch (Throwable t) {
            fail("IsoPlayer.postupdate", t);
        }
    }

    private static void place(IsoPlayer p, float x, float y) {
        p.setX(x);
        p.setY(y);
        p.setLastX(x);
        p.setLastY(y);
        p.setCurrentSquareFromPosition();
    }

    /**
     * Four times a second: a driven player and the remote zombies whose target it is get one delay, the longest of
     * theirs. Zombie updates come through the server's zombie packer and are older on arrival than player updates,
     * so on its own the player would be drawn ahead of the zombies chasing it.
     */
    static void shareClocks() {
        if (!active() || !Engine.receiverActive()) {
            if (sharing) {
                sharing = false;
                Engine.forEachTrack((z, t) -> t.share(0.0));
            }
            return;
        }
        try {
            clocks.clear();
            synchronized (tracks) {
                for (Map.Entry<IsoPlayer, Track> e : tracks.entrySet()) {
                    if (e.getKey() != null && e.getValue().driving) clocks.put(e.getKey(), new double[] {e.getValue().own});
                }
            }
            if (clocks.isEmpty() && !sharing) return;
            sharing = !clocks.isEmpty();
            Engine.forEachTrack((z, t) -> {
                double[] c = t.n > 0 && !t.idle ? clocks.get(z.getTarget()) : null;
                if (c != null) c[0] = Math.max(c[0], t.own);
            });
            Engine.forEachTrack((z, t) -> {
                double[] c = clocks.get(z.getTarget());
                t.share(c != null ? c[0] : 0.0);
            });
            synchronized (tracks) {
                for (Map.Entry<IsoPlayer, Track> e : tracks.entrySet()) {
                    double[] c = clocks.get(e.getKey());
                    e.getValue().share(c != null ? c[0] : 0.0);
                }
            }
        } catch (Throwable t) {
            fail("shared delay", t);
        }
    }

    // ---------------------------------------------------------------- housekeeping and reporting

    /** Once a second: forget copies not seen for a while. */
    static void cleanup(long ms) {
        if (tracks.isEmpty()) return;
        synchronized (tracks) {
            tracks.values().removeIf(t -> ms - t.lastSeenMs > 30_000L);
        }
    }

    static void clear() {
        tracks.clear();
    }

    private static ArrayList<Track> trackList() {
        synchronized (tracks) {
            return new ArrayList<>(tracks.values());
        }
    }

    static void status(StringBuilder sb) {
        int driven = 0;
        double sumDelay = 0;
        for (Track t : trackList()) {
            if (!t.driving) continue;
            driven++;
            sumDelay += Math.max(0, t.delay);
        }
        sb.append(String.format(Locale.ROOT, "players: %s, %d other player(s) followed, delay avg %.0f ms%n",
                failed ? "FAILED (vanilla)" : (Config.syncPlayers ? "on" : "off"), driven, driven > 0 ? sumDelay / driven : 0.0));
    }

    static void report(int secs) {
        int n = 0, frames = 0, interp = 0, extrap = 0, hold = 0, corr = 0, snap = 0, samples = 0, stamped = 0;
        double sumDelay = 0, maxCorr = 0, sumAge = 0;
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
            sumDelay += t.stSumDelay;
            maxCorr = Math.max(maxCorr, t.stMaxCorr);
            double a = t.medianAge();
            if (!Double.isNaN(a)) {
                sumAge += a;
                ageCount++;
            }
            t.resetStats();
        }
        if (stampedSent > 0) {
            Engine.log(String.format(Locale.ROOT, "player: %d position updates sent (%.1f/s)", stampedSent, stampedSent / (double) secs));
        }
        stampedSent = 0;
        if (n > 0) {
            int f = Math.max(1, frames);
            Engine.log(String.format(Locale.ROOT,
                    "players: %d, %d updates (%.0f%% stamped), age %.0f ms, delay avg %.0f ms, frames %d (interp %.1f%%, predict %.1f%%, "
                            + "hold %.1f%%), corrections %d (max %.2f), snaps %d, local animations %d, vanilla teleports undone %d, "
                            + "unstuck %d, placed %d",
                    n, samples, samples > 0 ? 100.0 * stamped / samples : 0.0, ageCount > 0 ? sumAge / ageCount : Double.NaN,
                    sumDelay / f, frames, 100.0 * interp / f, 100.0 * extrap / f, 100.0 * hold / f, corr, maxCorr, snap, yields,
                    undoneTeleports, unstuck, placed));
        }
        yields = 0;
        undoneTeleports = 0;
        unstuck = 0;
        placed = 0;
    }
}
