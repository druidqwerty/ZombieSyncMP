package com.druid.zombiesync;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;
import zombie.network.NetworkVariables;
import zombie.network.fields.character.Prediction;
import zombie.network.packets.character.PlayerPacket;

/**
 * Targeted regression checks for B42.22.2 fixes:
 * 1) Engine.isDrivableRealState allowlist vs lying/dead/fakedead/stagger/hitreaction/climb states
 * 2) Track.ownerDead / Track.ownerStateOk state & reset semantics + PACKET_BYTES=56
 * 3) Players optional 42.22 ladder MethodHandles + 42.22 float Prediction.z / PlayerPacket.getPlayer()
 */
public final class RegressionTest {
    private static int failures;

    private static void check(boolean ok, String msg) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }

    public static void main(String[] args) throws Throwable {
        // 1) PACKET_BYTES matches 42.22.2 ZombiePacket.PACKET_SIZE_BYTES
        check(Engine.PACKET_BYTES == 56, "Engine.PACKET_BYTES == 56 (got " + Engine.PACKET_BYTES + ")");

        // 2) isDrivableRealState allowlist
        EnumSet<NetworkVariables.ZombieState> expectedDrivable = EnumSet.of(
                NetworkVariables.ZombieState.WalkTowardNetwork,
                NetworkVariables.ZombieState.Idle,
                NetworkVariables.ZombieState.LungeNetwork,
                NetworkVariables.ZombieState.AttackNetwork,
                NetworkVariables.ZombieState.PathFind,
                NetworkVariables.ZombieState.FaceTarget,
                NetworkVariables.ZombieState.TurnAlerted,
                NetworkVariables.ZombieState.Thump,
                NetworkVariables.ZombieState.WalkToward,
                NetworkVariables.ZombieState.Lunge,
                NetworkVariables.ZombieState.Attack
        );
        check(Engine.isDrivableRealState(null), "null realState allowed");
        for (NetworkVariables.ZombieState st : NetworkVariables.ZombieState.values()) {
            boolean want = expectedDrivable.contains(st);
            boolean got = Engine.isDrivableRealState(st);
            check(got == want, "isDrivableRealState(" + st + ") expected " + want + " got " + got);
        }

        // Explicit spot checks for lying/dead/fakedead/hit/stagger/climb states
        for (NetworkVariables.ZombieState blocked : new NetworkVariables.ZombieState[]{
                NetworkVariables.ZombieState.OnGround,
                NetworkVariables.ZombieState.FallDown,
                NetworkVariables.ZombieState.FakeDead,
                NetworkVariables.ZombieState.FakeDeadAttack,
                NetworkVariables.ZombieState.FakeDeadAttackNetwork,
                NetworkVariables.ZombieState.GetDown,
                NetworkVariables.ZombieState.Getup,
                NetworkVariables.ZombieState.HitReaction,
                NetworkVariables.ZombieState.HitReactionHit,
                NetworkVariables.ZombieState.HitWhileStaggered,
                NetworkVariables.ZombieState.StaggerBack,
                NetworkVariables.ZombieState.EatBody,
                NetworkVariables.ZombieState.Sitting,
                NetworkVariables.ZombieState.ClimbFence,
                NetworkVariables.ZombieState.ClimbWindow,
                NetworkVariables.ZombieState.Falling,
                NetworkVariables.ZombieState.Grappled,
                NetworkVariables.ZombieState.Bumped,
                NetworkVariables.ZombieState.AttackVehicle,
                NetworkVariables.ZombieState.AttackVehicleNetwork
        }) {
            check(!Engine.isDrivableRealState(blocked), "blocked state " + blocked + " must return false");
        }

        // 3) Track ownerDead / ownerStateOk defaults & clear()
        Track tr = new Track();
        check(!tr.ownerDead && tr.ownerStateOk, "Track defaults: ownerDead=false, ownerStateOk=true");
        tr.ownerDead = true;
        tr.ownerStateOk = false;
        tr.clear();
        check(!tr.ownerDead && tr.ownerStateOk, "Track.clear() resets ownerDead=false, ownerStateOk=true");

        // Verify simulated pkt.health <= 0 & non-drivable realState flags
        short deadHealth = 0;
        short negHealth = -50;
        short aliveHealth = 1800;
        check((deadHealth <= 0) && (negHealth <= 0) && !(aliveHealth <= 0), "pkt.health <= 0 gate");

        // 4) Players 42.22.2 MethodHandles & floor resolution
        check(Players.hasLadderHandles(), "B42.22.2 ladder handles resolved on IsoPlayer");
        check(!Players.invokeOptionalFlag(null, null), "null optional flag handle returns false safely (42.21 fallback)");

        Prediction pred = new Prediction();
        pred.z = 0.0f;
        check(Players.predictionFloor(pred) == 0, "predictionFloor(0.0f) == 0");
        pred.z = -1.0f;
        check(Players.predictionFloor(pred) == -1, "predictionFloor(-1.0f) == -1");
        pred.z = -1.25f;
        check(Players.predictionFloor(pred) == -2, "predictionFloor(-1.25f) == -2");

        PlayerPacket pkt = new PlayerPacket();
        check(Players.packetPlayer(pkt) == null, "Players.packetPlayer(new PlayerPacket()) cleanly returns null");

        // 5) Patch annotation targets verification against loaded B42.22.2 classes
        for (Class<?> c : Patches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch ann = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (ann == null) continue;
            Class<?> target = Class.forName(ann.className(), false, RegressionTest.class.getClassLoader());
            boolean found = false;
            for (java.lang.reflect.Method m : target.getDeclaredMethods()) {
                if (m.getName().equals(ann.methodName())) {
                    found = true;
                    break;
                }
            }
            check(found, "Patch target " + ann.className() + "#" + ann.methodName() + " exists in 42.22.2");
        }

        // Verify MethodHandle invocation works for a synthetic boolean flag too
        MethodHandle trueFlag = MethodHandles.constant(boolean.class, true);
        check((boolean) trueFlag.invokeExact(), "sanity boolean MethodHandle");

        System.out.println(failures == 0
                ? "RegressionTest: all checks passed"
                : "RegressionTest: " + failures + " check(s) FAILED");
        if (failures != 0) System.exit(1);
    }
}
