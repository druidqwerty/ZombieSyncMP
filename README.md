# ZombieSync MP

<p align="center"><img src="docs/preview.png" alt="ZombieSync MP" width="320"></p>

**Smooth multiplayer zombies for Project Zomboid (Build 42.21+)**

> Status: tested on a local dedicated server with two clients, not yet on a public server.

In vanilla B42, zombies that another player is fighting move in jerks on your screen: an update arrives every 0.2 s, the zombie walks toward an old guessed point, teleports (to the corner of a tile) when it falls 3 tiles behind, and snaps its head and body around on every packet. There is also a vanilla bug: if your copy of the other player lies on the ground for a moment, zombies on your screen sit down to "eat" them while the real zombies keep running, then jump into place.

With ZombieSync:
- a remote zombie is drawn along the path it really walked on its owner's machine, a steady ~0.15–0.2 s behind (based on send time stamps);
- between packets it follows a smooth curve; prediction misses are blended out with a spring, without jerks or teleports; walls and collisions work as usual;
- it turns smoothly instead of snapping on every packet and faces where the owner's zombie faces, also while biting;
- a remote zombie bites when its owner's zombie does, and starts eating only if its owner decided so;
- zombies you simulate are sent 4x more often (every 50 ms instead of 200), within an upload budget (64 KB/s by default; with a big horde the rate falls back toward vanilla).

Falls, hits, climbing over fences and through windows, getting up are played with the game's own animations; the mod only records data during them and then picks the zombie up smoothly.

Client-side Java mod (requires ZombieBuddy). Every player must have it. If anything goes wrong it switches itself off and vanilla sync is used (`[ZombieSync] disabled ...` in the log).

<p align="center"><a href="https://boosty.to/lrgpp"><img src="docs/banner.png" alt="LrgPP mods on Boosty" width="640"></a></p>

Free and open source. Almost all of the code is written by an AI; the subscription, the testing and the time are mine. If the mod made your game better: [support on Boosty](https://boosty.to/lrgpp).

## What's new in 1.0.1
- Fixed: remote zombies faced the wrong way while biting (in 1.0.0 they turned toward your copy of the player, which can be tiles behind the real one). They now face where the owner's zombie faces: mean facing error 17.6° → 1.2°.
- Fixed: a remote zombie no longer stands turning in place instead of biting; the bite starts together with the owner's.

## Installation
**Required on every client and in the server mod list.**

Steam Workshop: subscribe to [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) (2.3.4+) and [ZombieSync MP](https://steamcommunity.com/sharedfiles/filedetails/?id=3827946115). On the server add `ZombieSyncMP` to `Mods=` (after ZombieBuddy) and `3827946115` to `WorkshopItems=`.

Manual install:
1. Install **ZombieBuddy** 2.3.4+.
2. Download `ZombieSyncMP_1.0.1.zip` from [Releases](https://github.com/druidqwerty/ZombieSyncMP/releases) and unpack the `ZombieSyncMP` folder into `%UserProfile%\Zomboid\mods\`
   - You should see `... \mods\ZombieSyncMP\42\mod.info` and `... \42\media\java\ZombieSyncMP.jar`
3. Add `ZombieSyncMP` to your server's `Mods=` list (after ZombieBuddy).

On first connection, ZombieBuddy asks for Java mod permission for ZombieSyncMP — allow it.

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

Advanced: `minDelayMs`, `maxDelayMs`, `initialDelayMs`, `maxExtrapolationMs`, `idleTimeoutMs`, `correctionTimeMs`, `snapDistance`, `logStats`, `statsIntervalSec`. Delete the file to reset to defaults.

## Is it working?
`%UserProfile%\Zomboid\console.txt` has `[ZombieSync]` lines:
- `loaded. ...` and `settings: ...` — the mod is active;
- `owner: N zombies, send period 50 ms, ...` — zombies you simulate (every 30 s);
- `remote: N zombies, ... delay avg 160 ...` — zombies other players simulate (every 30 s).

In the debug Lua console:
- `ZombieSync_status()` — state, delay, number of zombies
- `ZombieSync_set("mode", "smooth")` — change a setting on the fly

## Live test
Local dedicated server + two clients (zombie owner and observer), 16 zombies chasing the owner, 145 FPS. Vanilla and ZombieSync alternate every minute in the same session with the same zombies (version 1.0.1, 2026-10-27):

| | vanilla | ZombieSync |
|---|---|---|
| zombie drawn behind real position | 340 ms | 175 ms |
| mean position error | 0.81 tiles | 0.20 tiles |
| position error p95 | 2.71 tiles | 0.66 tiles |
| teleports | 267 | 0 |
| pops per 1000 frames | 1.17 | 0.05 |
| frozen frames per 1000 | 220 | 10 |
| mean facing error | 45.0° | 1.2° |

## Please note
- This mod was made 100% with AI.
- It was made only for playing with a friend.
- Tested only on a dedicated server. Not tested on a server hosted from the game menu.

## Building from Source
Requires Java 25+ and the game + ZombieBuddy jars.

```
JDK=/path/to/jdk-25/bin GAME=/path/to/ProjectZomboid MODVER=1.0.1 tools/build.sh
```

The script builds `build/ZombieSyncMP.jar`, assembles the mod folder in `dist/ZombieSyncMP/` and runs the offline simulator (`test/`).

## My mods
- [SmoothRide MP](https://steamcommunity.com/sharedfiles/filedetails/?id=3827367565) — smooth multiplayer vehicles.
- **ZombieSync MP** — smooth multiplayer zombies (this mod).

## License
MIT License — see LICENSE file.

## Author
[druidqwerty](https://github.com/druidqwerty)

For questions or issues, open a GitHub issue.
