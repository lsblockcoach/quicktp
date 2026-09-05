package com.example.quicktp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * QuickTP 精简版 —— 纯客户端传送
 *
 * 服务器规则（26.1.2 源码验证）：
 *  - 每 tick 最多 5 个移动包，总位移² ≤ 100×n → 极限 ≈22.2 格/tick
 *  - 包内 onGround 直接结算摔落 → 全程 onGround=true + 微降 0.05
 *  - 目标区块未加载时摔落结算豁免（直射唯一安全通道）
 *  - playerMovementCheck 关闭时移动检查整体跳过（直射瞬间生效）
 *
 * 流程（两种模式，没有更多）：
 *  A. 直射：单包射向目标 → 8tick 内被弹回→走B；没弹回→落地验证（区块信号）
 *  B. 冲刺：每 tick 5包×4.44格，爬升380→高空微降斜线→碎步下降→立稳
 *     永不触发服务器弹回，是最慢也是最快的稳定方案（447格/s，原版上限）
 */
public class QuickTp implements ClientModInitializer {

    private static final int MODE_IDLE = 0;
    private static final int MODE_PROBE = 1;    // 直射（单包直达，仅用于目标区块未加载时）
    private static final int MODE_SPRINT = 2;   // 冲刺（自选步长·保底）
    private static final int MODE_LANDING = 3;  // 落地验证+立稳
    private static final int MODE_ELYTRA = 4;   // 鞘翅触发等待

    private static final int PKT = 5;                    // 每tick 5包
    private static final double STEP = 4.44;             // 普通模式水平步长（鞘翅用 7.7）
    private static final double DESCEND_STEP = 3.9;      // 下降步长（伤害=floor(3.9-3)=0）
    private static final double CRUISE_Y = 380.0;        // 巡航高度
    private static final double PROBE_WAIT = 8;          // 直射观察窗口（tick）

    private static int mode = MODE_IDLE;
    private static int timer = 0;                // 通用计时
    private static boolean elytraEligible = false; // 客户端预检：穿了鞘翅
    private static boolean elytraActive = false;   // 服务器已确认滑翔（可提速）
    private static int flyTimer = 0;               // 飞行中重发滑翔触发包计时
    private static int bounce = 0;                 // 连续弹回计数（触发偏移绕行）
    private static int upState = 0;               // 垂直大包状态: 0=未测 1=确认中 2=启用 -1=禁用
    private static int upTimer = 0;               // 垂直确认计时
    private static final ArrayDeque<double[]> QUEUE = new ArrayDeque<>();
    private static double[] target = null;       // 目标{x,y,z}
    private static double[] lastSent = null;

    private static boolean noFall = true;

    private static final KeyMapping STOP_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.quicktp.stop",
                    InputConstants.Type.KEYSYM,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_F12,
                    new KeyMapping.Category(Identifier.fromNamespaceAndPath("quicktp", "main"))));

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("//tpnofall")
                    .executes(ctx -> {
                        noFall = !noFall;
                        ctx.getSource().sendFeedback(Component.literal(
                                "§a[QuickTP] " + (noFall ? L("§fNoFall 已§a开启", "§fNoFall §aON") : L("§fNoFall 已§c关闭", "§fNoFall §cOFF"))));
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("//tp")
                    .then(ClientCommands.argument("x", StringArgumentType.string())
                    .then(ClientCommands.argument("y", StringArgumentType.string())
                    .then(ClientCommands.argument("z", StringArgumentType.string())
                            .executes(ctx -> {
                                LocalPlayer p = ctx.getSource().getPlayer();
                                return p == null ? 0 : start(ctx.getSource(), p,
                                        StringArgumentType.getString(ctx, "x"),
                                        StringArgumentType.getString(ctx, "y"),
                                        StringArgumentType.getString(ctx, "z"));
                            })))));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ============================================================ 开始
    private static int start(FabricClientCommandSource src, LocalPlayer p, String xs, String ys, String zs) {
        final double tx, ty, tz;
        try {
            tx = parse(xs, p.getX());
            ty = parse(ys, p.getY());
            tz = parse(zs, p.getZ());
        } catch (NumberFormatException e) {
            src.sendError(Component.literal(L(
                    "§c[QuickTP] §f坐标格式错误！示例: /tp 100 64 -200 或 /tp ~ ~10 ~",
                    "§c[QuickTP] §fInvalid coords! e.g. /tp 100 64 -200 or /tp ~ ~10 ~")));
            return 0;
        }

        // 落点：只有"目标低于地表"才抬到地表+1；高空目标（y>地表）保留原样→真的飞到高处
        double landY = ty;
        double sy = surfaceY(tx, tz);
        String hint = "";
        if (sy > 1) {
            if (ty < sy) {
                landY = sy + 1;
                hint = " §7(地下→地表)";
            } else if (ty > sy + 2) {
                hint = " §7(高空目标)";
            }
        }

        target = new double[]{tx, landY, tz};
        reset();
        bounce = 0;

        // 预检鞘翅（提速档位提示）
        elytraEligible = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        elytraActive = false;
        flyTimer = 0;
        upState = 0;
        upTimer = 0;

        double dist = Math.sqrt(sq(tx - p.getX()) + sq(ty - p.getY()) + sq(tz - p.getZ()));

        if (!chunkLoaded(tx, tz)) {
            // ===== 目标区块未加载 → 【直射】单包直达 =====
            // 未加载区块豁免摔落结算（touchingUnloadedChunk→return），直射唯一安全通道；
            // 成功信号=目标区块被服务器送达客户端（大跳/直射被接受才会发生）。
            mode = MODE_PROBE;
            timer = 0;
            lastSent = target;
            p.connection.send(new ServerboundMovePlayerPacket.Pos(tx, landY, tz, true, false));
            src.sendFeedback(Component.literal(String.format(
                    L("§a[QuickTP] §f直射！ §7(%.0f格)%s §8[F12取消]",
                      "§a[QuickTP] §fDirect shot! §7(%.0f blocks)%s §8[F12 cancel]"),
                    dist, elytraEligible ? L(" §a(鞘翅已备)", " §a(elytra ready)") : "")));
            return 1;
        }

        // ===== 目标区块已加载 → 水平直射到目标上空 + 碎步降落 =====
        // 水平大包 ya≈0 → 摔落距离零增长（无论服务器检查开不开都不摔）；
        // 撞上检查失效窗口=瞬间到上空+碎步落地；被弹回→SPRINT弹回保险丝自动转冲刺（自愈）
        mode = MODE_SPRINT;
        timer = 0;
        double hopY = Math.max(p.getY(), landY) + 8.0;
        lastSent = new double[]{tx, hopY, tz};
        p.connection.send(new ServerboundMovePlayerPacket.Pos(tx, hopY, tz, true, false));
        p.absSnapTo(tx, hopY, tz, p.getYRot(), p.getXRot());
        p.setDeltaMovement(Vec3.ZERO);
        QUEUE.clear();
        segment(tx, hopY, tz, tx, landY, tz, 7.0);   // 下降碎步（内部自动3.9格防摔）
        src.sendFeedback(Component.literal(String.format(
                L("§a[QuickTP] §f已加载区域 → 水平直射尝试 %.0f格%s §8[F12取消]",
                  "§a[QuickTP] §fLoaded area → horizontal direct shot %.0f blocks%s §8[F12 cancel]"),
                dist, elytraEligible ? L(" §a(鞘翅已备)", " §a(elytra ready)") : "")));
        return 1;
    }

    private static void reset() {
        QUEUE.clear();
        lastSent = null;
        timer = 0;
    }

    // ============================================================ 主循环
    private static void tick() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.connection == null || p.isDeadOrDying()) {
            reset();
            mode = MODE_IDLE;
            target = null;
            return;
        }

        while (STOP_KEY.consumeClick()) {
            if (mode != MODE_IDLE) {
                reset();
                mode = MODE_IDLE;
                target = null;
                p.sendSystemMessage(Component.literal(L(
                        "§c[QuickTP] §f传送已取消（F12）",
                        "§c[QuickTP] §fTeleport cancelled (F12)")));
            }
            return;
        }

        if (mode == MODE_IDLE) {
            if (noFall) tickNoFall(p);
            return;
        }

        // ---------- A. 直射（单包，仅用于目标区块未加载时） ----------
        if (mode == MODE_PROBE) {
            if (++timer > 8) {      // 8tick 后进入落地验证（区块信号判定成败）
                mode = MODE_LANDING;
                timer = 0;
            }
            return;
        }

        // ---------- C. 落地验证 + 立稳（成败=目标区块是否到来） ----------
        if (mode == MODE_LANDING) {
            double sy = surfaceY(target[0], target[2]);
            boolean got = sy > 1 && chunkLoaded(target[0], target[2]);
            if (!got && ++timer > 600) {
                // 30秒区块还没来 = 直射被弹回（服务器有移动检查）
                double far = dist3(target[0], target[1], target[2],
                        p.getX(), p.getY(), p.getZ());
                if (far > 10000) {
                    // 千万格距离无快速通道，别拿6小时冲刺折磨用户
                    mode = MODE_IDLE;
                    target = null;
                    p.sendSystemMessage(Component.literal(L(
                            "§c[QuickTP] §f直射被拦截且距离过远(" + Math.round(far) + "格)，已中止。服务器开着移动检查",
                            "§c[QuickTP] §fDirect shot blocked & distance too far (" + Math.round(far) + " blocks), aborted. Server has movement checks")));
                    return;
                }
                p.sendSystemMessage(Component.literal(L(
                        "§c[QuickTP] §f直射被拦截 → 冲刺模式(447格/s)",
                        "§c[QuickTP] §fDirect shot blocked → sprint mode (447 bps)")));
                mode = MODE_SPRINT;
                timer = 0;
                lastSent = null;
                QUEUE.clear();
                planSprint(p.getX(), p.getY(), p.getZ(), elytraActive ? 7.7 : 4.44);
                return;
            }
            if (!got && timer % 40 == 1) {
                // 等待提示（2秒一次）
                p.sendSystemMessage(Component.literal(String.format(
                        L("§7[QuickTP] §f等待着陆区块... §e%.0f, %.0f §8(已等%ds · 第%d次/15)",
                          "§7[QuickTP] §fWaiting for landing chunk... §e%.0f, %.0f §8(%ds · attempt %d/15)"),
                        target[0], target[2], timer / 20, timer / 40 + 1)));
            }
            if (!got && timer % 40 == 2) {
                // 每5秒重发一次直射包：anarchy 服务器检查随 TPS 波动间歇失效，
                // 多试几次总会撞上"检查失效窗口"（runsNormally==false 时代整个检查被跳过）
                p.connection.send(new ServerboundMovePlayerPacket.Pos(
                        target[0], target[1], target[2], true, false));
            }
            if (got && target[1] < sy) target[1] = sy + 1; // 仅地下目标抬到地表；高空目标保持
            for (int i = 0; i < 2; i++) {         // 立稳：onGround=true 微降定位包（摔落清零+防浮空踢）
                p.connection.send(new ServerboundMovePlayerPacket.Pos(
                        target[0], target[1] - 0.05, target[2], true, false));
            }
            p.absSnapTo(target[0], target[1], target[2], p.getYRot(), p.getXRot());
            p.setDeltaMovement(Vec3.ZERO);
            lastSent = null;
            if (got && ++timer > 12) {            // 区块到位后立稳 12tick 收尾
                mode = MODE_IDLE;
                p.sendSystemMessage(Component.literal(String.format(
                        L("§a[QuickTP] §f已传送到 §e%.1f, %.1f, %.1f",
                          "§a[QuickTP] §fTeleported to §e%.1f, %.1f, %.1f"),
                        target[0], target[1], target[2])));
                target = null;
            }
            return;
        }

        // ---------- B. 冲刺（永不弹回的主体） ----------
        if (mode == MODE_SPRINT) {
            // 鞘翅确认：飞行中每 3 秒重发触发包，一旦服务器确认立即提速
            if (elytraEligible && !elytraActive) {
                if (++flyTimer % 60 == 1) {
                    p.connection.send(new ServerboundPlayerCommandPacket(p,
                            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
                if (p.isFallFlying()) {
                    elytraActive = true;
                    reset();
                    planSprint(p.getX(), p.getY(), p.getZ(), 7.7);
                    p.sendSystemMessage(Component.literal(L(
                            "§a[QuickTP] §f鞘翅滑翔已确认！§7提速至 774格/s",
                            "§a[QuickTP] §fElytra flight confirmed! §7boosted to 774 bps")));
                }
            }

            // 兜底：任何入口漏设 lastSent 时，以玩家当前位置为锚点（防 NPE/防错位）
            if (lastSent == null) lastSent = anchor(p);

            // ---------- 垂直大包：上升段加速（60格/tick，一次性探测） ----------
            // 服务器接受大包（无移动检查/掉帧）→ 爬升提速3倍；弹回 → 本次传送禁用，静默回退小步
            if (!QUEUE.isEmpty() && upState >= 0) {
                double[] peek = QUEUE.peekFirst();
                boolean climbing = peek[1] > lastSent[1] + 5.0;
                if (climbing && upState != 1) {
                    if (upState == 2 || upState == 0) {
                        // 发 60 格上升大包（纵轴 ya>0 → 摔落距离不累积）
                        double ny = lastSent[1] + 60.0;
                        double sx = lastSent[0], sz = lastSent[2];
                        p.connection.send(new ServerboundMovePlayerPacket.Pos(sx, ny, sz, true, false));
                        p.absSnapTo(sx, ny, sz, p.getYRot(), p.getXRot());
                        p.setDeltaMovement(Vec3.ZERO);
                        lastSent = new double[]{sx, ny, sz};
                        // 跳过已被覆盖的队列小点
                        while (!QUEUE.isEmpty() && QUEUE.peekFirst()[1] < ny - 1.0) QUEUE.pollFirst();
                        upState = 1;
                        upTimer = 0;
                        return;
                    }
                }
                if (upState == 1 && ++upTimer > 3) {
                    // 确认: 3tick 内没被弹回 = 大包被接受 → 启用快升；被弹回 → 禁用
                    double dev = dist3(p.getX(), p.getY(), p.getZ(),
                            lastSent[0], lastSent[1], lastSent[2]);
                    upState = dev > 30.0 ? -1 : 2;
                    if (upState == -1) {
                        // 弹回后从实际位置继续（位置已被服务器拉回）
                        lastSent = null;
                    }
                }
            }

            if (QUEUE.isEmpty()) {
                mode = MODE_LANDING;   // 走立稳收尾
                timer = 0;
                return;
            }
            for (int i = 0; i < PKT && !QUEUE.isEmpty(); i++) {
                double[] pt = QUEUE.peekFirst();
                // 下降碎步门控：目标区块未生成→锚定等待（钉住防摔，绝不坠落）。
                // 不限超时！区块迟早生成；等待期间持续锚定（onGround=true+微降 →
                // 不摔不死不被踢），每 5 秒提示一次，F12 随时手动取消。
                if (pt[1] < lastSent[1] && !chunkLoaded(pt[0], pt[2])) {
                    timer++;
                    if (timer % 100 == 1) {
                        p.sendSystemMessage(Component.literal(String.format(
                                L("§7[QuickTP] §f仍在等待区块生成... §e%.0f, %.0f §8(已等%ds · F12取消)",
                                  "§7[QuickTP] §fWaiting for chunk... §e%.0f, %.0f §8(%ds · F12)"),
                                pt[0], pt[2], timer / 20)));
                    }
                    double ay = lastSent[1] - 0.05;
                    p.connection.send(new ServerboundMovePlayerPacket.Pos(lastSent[0], ay, lastSent[2], true, false));
                    p.absSnapTo(lastSent[0], ay, lastSent[2], p.getYRot(), p.getXRot());
                    p.setDeltaMovement(Vec3.ZERO);
                    return;
                }
                // 弹回保险 + 偏移绕行（解决起飞/降落路径被墙挡时的死循环）
                if (dist3(p.getX(), p.getY(), p.getZ(), lastSent[0], lastSent[1], lastSent[2]) > 26.0) {
                    bounce++;
                    if (bounce > 60) {
                        // 持续重试全是弹回 = 起飞点被完全堵死，别再死磕
                        reset();
                        mode = MODE_IDLE;
                        target = null;
                        p.sendSystemMessage(Component.literal(L(
                                "§c[QuickTP] §f起飞点被地形完全堵死，请移动几格后重试",
                                "§c[QuickTP] §fTakeoff point fully blocked, move a bit and retry")));
                        return;
                    }
                    if (bounce > 4) {
                        // 连续受阻：起飞点水平偏移（东/南/西/北轮换、距离递增8格）
                        int k = bounce - 4;
                        double ox = (k % 4 == 0) ? 8.0 * (k / 4 + 1) : (k % 4 == 1) ? -8.0 * (k / 4 + 1) : 0;
                        double oz = (k % 4 == 2) ? 8.0 * (k / 4 + 1) : (k % 4 == 3) ? -8.0 * (k / 4 + 1) : 0;
                        planSprint(p.getX() + ox, p.getY(), p.getZ() + oz, elytraActive ? 7.7 : 4.44);
                        // 提示节流：每 4 次弹回才提醒一次，防止刷屏
                        if (bounce % 4 == 1) {
                            String msg = L("§e[QuickTP] §f路径受阻，偏移绕行... §7(第", "§e[QuickTP] §fDetouring... §7(#")
                                    + (bounce - 4) + L("次)", ")");
                            p.sendSystemMessage(Component.literal(msg));
                        }
                    } else {
                        planSprint(p.getX(), p.getY(), p.getZ(), elytraActive ? 7.7 : 4.44);
                    }
                    lastSent = null;   // ← 关键修复：重置锚点，避免下一tick重复误判弹回刷屏
                    return;
                }
                lastSent = QUEUE.pollFirst();
                p.connection.send(new ServerboundMovePlayerPacket.Pos(lastSent[0], lastSent[1], lastSent[2], true, false));
            }
            p.absSnapTo(lastSent[0], lastSent[1], lastSent[2], p.getYRot(), p.getXRot());
            p.setDeltaMovement(Vec3.ZERO);
            bounce = 0;
        }
    }

    // ============================================================ 路径
    private static void planSprint(double px, double py, double pz, double stepLen) {
        QUEUE.clear();
        timer = 0;
        double tx = target[0], ty = target[1], tz = target[2];
        // 近距离(<400格)：低空直线（只比起点高8格），不会大起大落；
        // 远距离：才上 380 高空（防未知地形撞山）
        double hDist = Math.sqrt(sq(tx - px) + sq(tz - pz));
        double cruiseY;
        if (hDist < 400) {
            cruiseY = Math.max(py, ty) + 8.0;
        } else {
            cruiseY = Math.max(Math.max(py, CRUISE_Y), ty) + 8.0;
        }
        segment(px, py, pz, px, cruiseY, pz, stepLen);           // 爬升
        segment(px, cruiseY, pz, tx, cruiseY - 0.001, tz, stepLen); // 巡航微降斜线
        segment(tx, cruiseY, tz, tx, ty, tz, stepLen);           // 下降（内部自动用3.9碎步）
        timer = 0;
    }

    /** 路径点：水平步长可调；下降固定 3.9 碎步；巡航点微降防悬空标记 */
    private static void segment(double x1, double y1, double z1, double x2, double y2, double z2, double stepLen) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 0.01) return;
        boolean desc = y2 < y1 - 0.01;
        stepLen = desc ? DESCEND_STEP : stepLen;
        int steps = Math.max(1, (int) Math.ceil(d / stepLen));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            QUEUE.add(new double[]{
                    (i == steps) ? x2 : x1 + dx * t,
                    (i == steps) ? y2 : y1 + dy * t - ((desc || i == steps) ? 0 : 0.05 * i),
                    (i == steps) ? z2 : z1 + dz * t});
        }
    }

    // ============================================================ 工具
    private static double[] anchor(LocalPlayer p) {
        return new double[]{p.getX(), p.getY(), p.getZ()};
    }

    private static void tickNoFall(LocalPlayer p) {
        if (p.isDeadOrDying() || p.isSpectator() || p.getAbilities().mayfly) return;
        if (p.fallDistance > 2.0F && p.getDeltaMovement().y < 0) {
            p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY() - 0.05, p.getZ(), true, false));
        }
    }

    private static double surfaceY(double x, double z) {
        try {
            ClientLevel l = Minecraft.getInstance().level;
            if (l == null) return -1;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            ChunkAccess c = l.getChunkSource().getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false);
            if (c == null) return -1;
            return c.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15) + 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean chunkLoaded(double x, double z) {
        try {
            ClientLevel l = Minecraft.getInstance().level;
            if (l == null) return false;
            return l.getChunkSource().getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, ChunkStatus.FULL, false) != null;
        } catch (Exception e) {
            return true;
        }
    }

    private static double dist3(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(sq(x2 - x1) + sq(y2 - y1) + sq(z2 - z1));
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * 多语言：跟随 Minecraft 客户端语言设置（zh 系列→中文，其他→英文）
     */
    private static String L(String zh, String en) {
        try {
            String code = Minecraft.getInstance().options.languageCode;
            if (code != null && code.toLowerCase().startsWith("zh")) {
                return zh;
            }
        } catch (Exception ignored) {
        }
        return en;
    }

    private static double parse(String raw, double cur) {
        String s = raw.trim().replace('～', '~').replace('－', '-');
        if (s.startsWith("~")) {
            String r = s.substring(1);
            return r.isEmpty() ? cur : cur + Double.parseDouble(r);
        }
        return Double.parseDouble(s);
    }
}