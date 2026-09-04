# QuickTP

**Client-side teleport mod · Minecraft 26.1.2 (Fabric)** — No server permission needed, fully compatible with Meteor Client.

> 🌐 [中文版 README（Chinese）](./README_zh.md)

## Features

| Feature | Description |
|---|---|
| `/tp x y z` | Teleport (supports `~` relative coords, full-width `～`) |
| **Direct shot** | Instant single-packet teleport when target chunk is unloaded (fall-damage settlement exempted by `touchingUnloadedChunk`) |
| **Sprint fallback** | Auto downgrade when server has movement checks: 22 blocks/tick (447 bps, vanilla mathematical cap) |
| **Elytra boost** | Auto-triggers gliding when elytra detected: 774 bps |
| **NoFall** | `/tpnofall` toggle (default ON): continuous `onGround=true` packets zero out fall distance |
| **F12** | Emergency cancel |

## Usage

```
/tp 100 64 -200       absolute coords
/tp ~ ~50 ~           relative coords
/tpnofall             toggle NoFall
F12                   cancel teleport
```

## How it works (reversed from 26.1.2 server source)

`ServerGamePacketListenerImpl` movement validation:

- Max 5 move packets per tick (more gets clamped to 1), displacement² ≤ 100×count → sprint cap ≈22.2 blocks/tick
- The packet's `onGround` parameter is used directly for fall-damage settlement → all packets use `onGround=true` + 0.05 dip, fall distance stays 0
- Unloaded target chunk exempts fall settlement entirely (`touchingUnloadedChunk`) → the safe channel for direct shot
- Wall-clipping check (moved-wrongly) has no exemption and cannot be bypassed → high-altitude routes / detours

## Auto mode selection

```
/tp x y z
  ├─ Target chunk unloaded → DIRECT SHOT (instant, confirmed by chunk arrival signal)
  │     └─ bounced (server checks) → auto downgrade to sprint
  ├─ Target chunk loaded → SPRINT (climb 380 → cruise with -0.05 dip → 3.9-block steps → land)
  │     └─ elytra detected → gliding boost to 774 bps
  └─ Always safe: 3.9-block steps (damage=floor(3.9-3)=0) + anchor waiting + landing seek
```

## Build

```bash
gradle build
# output: build/libs/quicktp-1.0.0.jar → copy to mods/
```

- Toolchain: `net.fabricmc.fabric-loom` 1.17.x, unmapped 26.1.2 jar (no mappings needed)
- Dependency: Fabric API (plain `implementation`)

## Compatibility

- **Meteor Client**: zero mixins, isolated client command space — no conflict
- **Client-only**: `environment: client`, server is unaware
- Requires Fabric API (0.155+)

## Honest technical limits

- With `playerMovementCheck` enabled, NO client can exceed 22.36 blocks/tick (vanilla linear threshold)
- Wall-clipping through generated chunks is impossible (moved-wrongly has no exemption); clipping only works in ungenerated chunk paths
- Server-side solution for true instant teleport: `/gamerule playerMovementCheck false` (requires server OP)

## Files

```
src/main/java/com/example/quicktp/QuickTp.java   Main logic (single file)
ghps_qt.sh                                      One-click upload script (SSH 443)
```