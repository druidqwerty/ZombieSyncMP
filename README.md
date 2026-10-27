# ZombieSync MP

<p align="center"><img src="docs/preview.png" alt="ZombieSync MP" width="320"></p>

**Smooth multiplayer zombies for Project Zomboid (Build 42.21+)**

> Status: tested on a local dedicated server with two clients, not yet on a public server.

In vanilla B42, zombies that another player is fighting move in jerks on your screen: an update arrives every 0.2 s, the zombie walks toward an old guessed point, teleports (to the corner of a tile) when it falls 3 tiles behind, and snaps its head and body around on every packet. There is also a vanilla bug: if your copy of the other player lies on the ground for a moment, zombies on your screen sit down to "eat" them while the real zombies keep running, then jump into place.

With ZombieSync:
- a remote zombie is drawn along the path it really walked on its host's machine, a steady ~0.15–0.2 s behind (based on send time stamps);
- between packets it follows a smooth curve; prediction misses are blended out with a spring, without jerks or teleports; walls and collisions work as usual;
- it turns smoothly instead of snapping on every packet and faces where the host's zombie faces, also while biting;
- a remote zombie bites when its host's zombie does, and starts eating only if its host decided so;
- other players near you are drawn on the same clock as the zombies: their position is sent 10 times a second (vanilla: on every new tile, 8-way turn or 0.6 s) and your copy of them follows the path they really walked, so a zombie bites the player you see, not the air ahead of them;
- zombies you simulate are sent 4x more often (every 50 ms instead of 200), within an upload budget (256 KB/s by default: the full rate up to ~250 zombies, then a gradually longer period).

Falls, hits, climbing over fences and through windows, getting up are played with the game's own animations; the mod only records data during them and then picks the zombie up smoothly.

Client-side Java mod (requires ZombieBuddy). Every player must have it. If anything goes wrong it switches itself off and vanilla sync is used (`[ZombieSync] disabled ...` in the log).

<p align="center"><a href="https://boosty.to/lrgpp"><img src="docs/banner.png" alt="LrgPP mods on Boosty" width="640"></a></p>

Free and open source. Almost all of the code is written by an AI; the subscription, the testing and the time are mine. If the mod made your game better: [support on Boosty](https://boosty.to/lrgpp).

## What's new in 1.0.3
- Fixed: with a big horde the faster sending switched itself off. The default upload cap of 64 KB/s pushed the send period back to vanilla's 200 ms above ~60 zombies per host, and the buffer on such a sparse stream drew the zombies further behind than vanilla (485 ms vs 350 in a 300-zombie test). The cap is now 256 KB/s: the full 50 ms rate up to ~250 zombies, then a gradually longer period. 300 zombies now: drawn 180 ms behind (vanilla 260), mean error 0.12 tiles (0.20), teleports 0.
- Load measured: the mod's hooks take under 2% of one core on a client with 300 remote zombies and under 0.3% on their host; nothing measurable at 16. New setting `profile=true` prints the CPU time of each hook with the stats.

## What's new in 1.0.2
- New: other players are drawn on the same clock as the zombies. In vanilla your copy of another player falls behind on every start, turn and change of speed and catches up slowly, but the zombies chasing them bite where the player really is, so on your screen they bite the air ahead of the player you see. Now a player's position is sent 10 times a second while another player is near, and your copy of them follows the path they really walked, with the same delay as the zombies chasing them. Live test: biting zombie to the player you see 1.61 → 0.56 tiles (p95 7.71 → 1.31), the same as on the zombies' host (0.55). Climbing, falling, sitting, vehicles and grappling stay vanilla. Setting: `syncPlayers` (on by default).
- Fixed: a remote zombie no longer stays pressed into a wall corner while its host's zombie is already on the other side; after 0.6 s it is put back on its path (before, only when it fell more than 1 tile behind).
- Compatibility: works with mods that run zombie animation code on worker threads, such as PZ Optimization. Live-tested together with PZ Optimization, AnimationTweaks and SmoothRide.

## Installation
**Required on every client and in the server mod list.**

Steam Workshop: subscribe to [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) (2.3.4+) and [ZombieSync MP](https://steamcommunity.com/sharedfiles/filedetails/?id=3827946115). On the server add `ZombieSyncMP` to `Mods=` (after ZombieBuddy) and `3827946115` to `WorkshopItems=`.

Manual install:
1. Install **ZombieBuddy** 2.3.4+.
2. Download `ZombieSyncMP_1.0.3.zip` from [Releases](https://github.com/druidqwerty/ZombieSyncMP/releases) and unpack the `ZombieSyncMP` folder into `%UserProfile%\Zomboid\mods\`
   - You should see `... \mods\ZombieSyncMP\42\mod.info` and `... \42\media\java\ZombieSyncMP.jar`
3. Add `ZombieSyncMP` to your server's `Mods=` list (after ZombieBuddy).

On first connection, ZombieBuddy asks for Java mod permission for ZombieSyncMP — allow it.

## Configuration
On first run a file is created: `%UserProfile%\Zomboid\ZombieSyncMP.properties`

| key | default | |
|---|---|---|
| `mode` | `balanced` | `smooth` — max smoothness (+30–50 ms delay), `realtime` — lowest delay, more prediction |
| `sendIntervalMs` | `50` | how often your own zombies are sent (vanilla: 200) |
| `sendBudgetKBps` | `256` | upload cap for zombie updates (≈250 zombies at 50 ms; above it the send period grows, up to vanilla's 200 ms) |
| `sendBoost` | `true` | send your zombies more often |
| `fixEating` | `true` | remote zombies eat only when their host says so |
| `followAttacks` | `true` | a remote zombie attacks when (and as long as) its host's copy does |
| `syncPlayers` | `true` | other players are drawn on the zombies' clock (your position is sent 10 times a second while another player is near) |
| `enabled` | `true` | `false` turns the mod off completely |

Advanced: `minDelayMs`, `maxDelayMs`, `initialDelayMs`, `maxExtrapolationMs`, `idleTimeoutMs`, `correctionTimeMs`, `snapDistance`, `logStats`, `statsIntervalSec`, `profile` (CPU time per hook in the stats). Delete the file to reset to defaults.

## Is it working?
`%UserProfile%\Zomboid\console.txt` has `[ZombieSync]` lines:
- `loaded. ...` and `settings: ...` — the mod is active;
- `owner: N zombies, send period 50 ms, ...` — zombies you simulate (every 30 s);
- `remote: N zombies, ... delay avg 160 ...` — zombies other players simulate (every 30 s);
- `players: N, ... delay avg ...` — other players the mod draws (every 30 s).

In the debug Lua console:
- `ZombieSync_status()` — state, delay, number of zombies
- `ZombieSync_set("mode", "smooth")` — change a setting on the fly

## Live test
Local dedicated server + two clients (zombie host and observer), 16 zombies chasing the host, 145 FPS, together with PZ Optimization, AnimationTweaks and SmoothRide. Vanilla and ZombieSync alternate every minute in the same session with the same zombies (version 1.0.3, 2026-10-27):

| | vanilla | ZombieSync |
|---|---|---|
| zombie drawn behind real position | 355 ms | 190 ms |
| mean position error | 0.71 tiles | 0.18 tiles |
| position error p95 | 2.60 tiles | 0.66 tiles |
| teleports | 231 | 0 |
| pops per 1000 frames | 1.11 | 0.08 |
| frozen frames per 1000 | 163 | 8 |
| mean facing error | 43.9° | 0.9° |

With 300 zombies on one host (the host at 60 FPS, the observer at 145): zombie drawn 180 ms behind (vanilla 260), mean position error 0.12 tiles (0.20), teleports 0 (0). The mod's hooks take 15 ms of CPU per second on the observer (1.5% of one core) and 2 ms/s on the host; the host uploads about 250 KB/s of zombie updates (`sendBudgetKBps` caps it).

The other player, in a run where the host walks, runs, sprints and stops like a player at the keyboard:

| | vanilla | ZombieSync |
|---|---|---|
| your copy of the player drawn behind them | 110 ms (p95 480 ms) | 170 ms (p95 180 ms) |
| biting zombie to the player you see | 1.61 tiles (p95 7.71) | 0.56 tiles (p95 1.31) |

The copy is drawn on the same delay as the zombies around it, so on a straight walk it is a little further behind than in vanilla, but it no longer falls behind on starts and turns.

## Please note
- This mod was made 100% with AI.
- It was made only for playing with a friend.
- Tested only on a dedicated server. Not tested on a server hosted from the game menu.

## Building from Source
Requires Java 25+ and the game + ZombieBuddy jars.

```
JDK=/path/to/jdk-25/bin GAME=/path/to/ProjectZomboid MODVER=1.0.3 tools/build.sh
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
