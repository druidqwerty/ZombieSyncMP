# ZombieSync MP

**Smooth multiplayer zombies for Project Zomboid (Build 42.21+)**

> Status: experimental. Tested on a local server with two clients, not yet on a public server.

In vanilla B42, zombies that another player is fighting move in jerks on your screen: an update arrives every 0.2 s, the zombie walks toward an old guessed point, teleports (to the corner of a tile) when it falls 3 tiles behind, and snaps its head and body around on every packet. There is also a vanilla bug: if your copy of the other player lies on the ground for a moment, zombies on your screen sit down to "eat" them while the real zombies keep running, then jump into place.

With ZombieSync:
- a remote zombie is drawn along the path it really walked on its owner's machine, a constant ~0.1–0.15 s behind (based on send time stamps);
- between packets it follows a smooth curve; prediction misses are blended out with a spring, without jerks or teleports; walls and collisions work as usual;
- it turns smoothly instead of snapping on every packet;
- a remote zombie starts eating only if its owner decided so;
- zombies you simulate are sent 4x more often (every 50 ms instead of 200), within an upload budget (64 KB/s by default; with a big horde the rate falls back toward vanilla).

Falls, hits, climbing over fences and through windows, getting up are played with the game's own animations; the mod only records data during them and then picks the zombie up smoothly.

Client-side Java mod (requires ZombieBuddy). Every player must have it. If anything goes wrong it switches itself off and vanilla sync is used (`[ZombieSync] disabled ...` in the log).

## Installation
**Required on every client and in the server mod list.**

1. Install **ZombieBuddy** 2.3.4+.
2. Download `ZombieSyncMP_1.0.0.zip` from [Releases](https://github.com/druidqwerty/ZombieSyncMP/releases) and unpack the `ZombieSyncMP` folder into `%UserProfile%\Zomboid\mods\`
   - You should see `... \mods\ZombieSyncMP\42\mod.info` and `... \42\media\java\ZombieSyncMP.jar`
3. Add `ZombieSyncMP` to your server's `Mods=` list (after ZombieBuddy).
4. On first connection, ZombieBuddy will ask for Java mod permission for ZombieSyncMP — allow it.

## Configuration
On first run a file is created: `%UserProfile%\Zomboid\ZombieSyncMP.properties`

| key | default | |
|---|---|---|
| `mode` | `balanced` | `smooth` — max smoothness (+30–50 ms delay), `realtime` — lowest delay, more prediction |
| `sendIntervalMs` | `50` | how often your own zombies are sent (vanilla: 200) |
| `sendBudgetKBps` | `64` | upload cap for zombie updates |
| `sendBoost` | `true` | send your zombies more often |
| `fixEating` | `true` | remote zombies eat only when their owner says so |
| `followAttacks` | `true` | a remote zombie attacks when (and as long as) its owner's copy does |
| `enabled` | `true` | `false` turns the mod off completely |

Advanced: `minDelayMs`, `maxDelayMs`, `initialDelayMs`, `maxExtrapolationMs`, `idleTimeoutMs`, `correctionTimeMs`, `snapDistance`, `logStats`, `statsIntervalSec`.

In the debug Lua console:
- `ZombieSync_status()` — state, delay, number of zombies
- `ZombieSync_set("mode", "smooth")` — change a setting on the fly

## Live test
Local server + two clients (zombie owner and observer), 20 zombies, 145 FPS:

| | vanilla | ZombieSync |
|---|---|---|
| zombie drawn behind real position | 300 ms | 175 ms |
| mean position error | 0.58 tiles | 0.15 tiles |
| position error p95 | 2.39 tiles | 0.56 tiles |
| teleports | 173 | 0 |
| pops per 1000 frames | 0.75 | 0.01 |
| frozen frames per 1000 | 129 | 8 |
| mean facing error | 30.9° | 17.6° |

Known issue: facing error in the last build (mean 17.6°) is higher than in the build before it (1.0°); still being tuned.

## Building from Source
Requires Java 25+ and the game + ZombieBuddy jars.

```
JDK=/path/to/jdk-25/bin GAME=/path/to/ProjectZomboid tools/build.sh
```

The script builds `build/ZombieSyncMP.jar`, assembles the mod folder in `dist/ZombieSyncMP/` and runs the offline simulator (`test/`).

## See also
[SmoothRide](https://github.com/druidqwerty/SmoothRide) — the same approach for vehicles.

## License
MIT License — see LICENSE file.

## Author
[druidqwerty](https://github.com/druidqwerty)
