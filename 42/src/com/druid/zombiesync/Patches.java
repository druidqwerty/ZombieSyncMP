package com.druid.zombiesync;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieAI;
import zombie.iso.IsoMovingObject;
import zombie.network.packets.character.ZombiePacket;
import zombie.network.packets.character.ZombieSynchronizationPacket;

/**
 * ZombieBuddy advice. The bodies are inlined into game classes, so they only call public methods of
 * {@link Engine}; all logic and error handling lives there.
 */
public class Patches {

    /** Once per frame on a multiplayer client. */
    @Patch(className = "zombie.vehicles.VehicleManager", methodName = "clientUpdate")
    public static class Frame {
        @Patch.OnEnter
        public static void enter() {
            Engine.frame();
        }
    }

    /** Owner side: the server's zombie sync packet resets the send period (200 ms with a player near). */
    @Patch(className = "zombie.network.packets.character.ZombieSynchronizationPacket", methodName = "parse")
    public static class SyncParse {
        @Patch.OnExit
        public static void exit(@Patch.This ZombieSynchronizationPacket self) {
            Engine.afterSyncParse(self);
        }
    }

    /** Owner side: every zombie update this client sends; carries the send time. */
    @Patch(className = "zombie.network.packets.character.ZombiePacket", methodName = "set")
    public static class PacketSet {
        @Patch.OnExit
        public static void exit(@Patch.This ZombiePacket self) {
            Engine.afterPacketSet(self);
        }
    }

    /** Receiver side: every update of a zombie another client simulates. */
    @Patch(className = "zombie.characters.NetworkZombieAI", methodName = "parse")
    public static class Parse {
        @Patch.OnEnter
        public static void enter(@Patch.This NetworkZombieAI self, @Patch.Argument(0) ZombiePacket pkt) {
            Engine.parseEnter(self, pkt);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.This NetworkZombieAI self) {
            Engine.parseExit(self);
        }
    }

    /** Receiver side: where a remote zombie moves this frame. */
    @Patch(className = "zombie.characters.IsoZombie", methodName = "postupdate")
    public static class PostUpdate {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoZombie self) {
            Engine.beforePostUpdate(self);
        }
    }

    @Patch(className = "zombie.popman.NetworkZombieSimulator", methodName = "becomeLocal")
    public static class BecomeLocal {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoZombie z) {
            Engine.ownerChanged(z);
        }
    }

    @Patch(className = "zombie.popman.NetworkZombieSimulator", methodName = "becomeRemote")
    public static class BecomeRemote {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoZombie z) {
            Engine.ownerChanged(z);
        }
    }

    /** A remote zombie attacks when its owner says it does, not when our copy of the target happens to be close. */
    @Patch(className = "zombie.characters.IsoZombie", methodName = "getShouldAttack")
    public static class ShouldAttack {
        @Patch.OnExit
        public static void exit(@Patch.This IsoZombie self, @Patch.Return(readOnly = false) boolean ret) {
            ret = Engine.shouldAttack(self, ret);
        }
    }

    /** Only the 3-argument overload: the 2-argument one forwards to it. */
    @Patch(className = "zombie.characters.IsoZombie", methodName = "setEatBodyTarget")
    public static class EatTarget {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoZombie self, @Patch.Argument(0) IsoMovingObject target,
                                    @Patch.Argument(1) boolean force, @Patch.Argument(2) float eatSpeed) {
            return Engine.skipEat(self, target);
        }
    }

    /** The owner's EatBody packet: the one source that may make a remote zombie start eating. */
    @Patch(className = "zombie.network.GameClient", methodName = "receiveEatBody")
    public static class EatPacket {
        @Patch.OnEnter
        public static void enter() {
            Engine.eatPacketEnter();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            Engine.eatPacketExit();
        }
    }
}
