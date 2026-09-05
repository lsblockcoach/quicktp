# QuickTP

**纯客户端传送 MOD · Minecraft 26.1.2 (Fabric)** — 零服务端权限，与 Meteor Client 完全兼容

> 🌐 [English README](./README.md)

## 功能

| 功能 | 说明 |
|---|---|
| `///tp x y z` | 传送（支持 `~` 相对坐标、全角 `～`）|
| **直射** | 目标区块未加载时单包瞬移（未加载区块豁免摔落结算）|
| **冲刺保底** | 服务器有移动检查时自动降级，22格/tick（447 格/s，原版数学上限）|
| **鞘翅提速** | 检测到鞘翅自动触发滑翔，提速至 774 格/s |
| **NoFall** | `///tpnofall` 开关（默认开）：下落全程 `onGround=true` 清零摔落距离 |
| **多语言** | 消息跟随游戏客户端语言（中文/英文）|
| **F12** | 紧急取消传送 |

## 用法

```
///tp 100 64 -200       绝对坐标
///tp ~ ~50 ~           相对坐标
///tpnofall             切换 NoFall
F12                   取消传送
```

## 工作原理（基于 26.1.2 服务端源码逆向）

`ServerGamePacketListenerImpl` 的移动校验规则：

- 每 tick 最多 5 个移动包（超过反而按 1 算），位移² ≤ 100×包数 → 普通冲刺上限 ≈22.2 格/tick
- 包内 `onGround` 参数直接用于摔落结算 → 全程 `onGround=true` + 微降 0.05，摔落距离恒为 0
- 目标区块未加载时摔落结算整体豁免（`touchingUnloadedChunk`）→ 直射模式的安全通道
- 穿墙碰撞检查（moved-wrongly）无豁免，无法绕过 → 采用高空航线/绕行

## 模式选择（自动）

```
///tp x y z
  ├─ 目标区块未加载 → 直射（单包瞬间，区块到来信号确认）
  │     └─ 被弹回（服务器开检查）→ 自动降级冲刺
  ├─ 目标区块已加载 → 冲刺（爬升380 → 高空微降巡航 → 碎步下降 → 立稳）
  │     └─ 有鞘翅 → 触发滑翔提速 774 格/s
  └─ 全程防摔：碎步3.9格（伤害=floor(3.9-3)=0）+ 锚定等待区块 + 落点寻地
```

## 构建

```bash
gradle build
# 产物: build/libs/quicktp-1.0.0.jar → 复制到 mods 目录
```

- 构建体系：`net.fabricmc.fabric-loom` 1.17.x，MC 26.1.2 未混淆 jar，无 mappings
- 依赖：Fabric API（普通 `implementation`）

## 兼容性

- **Meteor Client**：零 Mixin、独立客户端命令空间，无冲突
- **纯客户端**：`environment: client`，服务端无感
- 需要 Fabric API（0.155+）

## 技术边界（诚实说明）

- 服务器开着 `playerMovementCheck` 时，任何客户端都突破不了 22.36 格/tick（原版线性阈值）
- 已生成区块内的穿墙不可能（moved-wrongly 无豁免），穿墙只在未生成区块路径有效
- 服务器端配置可解：`/gamerule playerMovementCheck false`（需要服主权限）后直射全场景生效

## 文件

```
src/main/java/com/example/quicktp/QuickTp.java   主逻辑（单文件）
ghps_qt.sh                                       一键上传脚本（SSH 443）
```