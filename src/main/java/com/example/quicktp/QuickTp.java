package com.example.quicktp;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * QuickTP —— 纯客户端传送 MOD
 *
 * 原理：原版服务器的玩家位置是"客户端权威"的。客户端直接向服务器发送
 * ServerboundMovePlayerPacket.Pos（位置同步包），服务器就会把玩家放到该坐标。
 * 整个过程只用到客户端自己的网络连接，不需要任何服务器端权限。
 *
 * 用法：/tp <x> <y> <z>   （支持 ~ 相对坐标，例如 /tp ~ 100 ~-50）
 */
public class QuickTp implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Fabric 客户端命令：只在客户端本地注册，不会发给服务器，
        // 与 Meteor 的 "." 命令系统互不相干，也不占用服务器原版 /tp。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("tp")
                        .then(ClientCommands.argument("x", StringArgumentType.word())
                        .then(ClientCommands.argument("y", StringArgumentType.word())
                        .then(ClientCommands.argument("z", StringArgumentType.word())
                                .executes(ctx -> teleport(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "x"),
                                        StringArgumentType.getString(ctx, "y"),
                                        StringArgumentType.getString(ctx, "z")
                                ))))))
        );
    }

    private static int teleport(FabricClientCommandSource source, String xs, String ys, String zs) {
        LocalPlayer player = source.getPlayer();
        if (player == null || player.connection == null) {
            return 0;
        }

        double x, y, z;
        try {
            x = parseCoord(xs, player.getX());
            y = parseCoord(ys, player.getY());
            z = parseCoord(zs, player.getZ());
        } catch (NumberFormatException e) {
            source.sendError(Component.literal("§c[QuickTP] §f坐标格式错误！示例: /tp 100 64 -200 或 /tp ~ ~10 ~"));
            return 0;
        }

        // 1. 直接发送位置包 —— 这是真正的传送（超快，单包直达）
        player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, true, false));

        // 2. 同步客户端本地位置并清零速度，防止客户端预测导致回弹/漂移
        player.absSnapTo(x, y, z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);

        source.sendFeedback(Component.literal(
                String.format("§a[QuickTP] §f已传送到 §e%.1f, %.1f, %.1f", x, y, z)));
        return 1;
    }

    /** 支持 "~"、 "~5"、 "-123.4" 三种写法 */
    private static double parseCoord(String input, double current) {
        if (input.startsWith("~")) {
            String rest = input.substring(1);
            return rest.isEmpty() ? current : current + Double.parseDouble(rest);
        }
        return Double.parseDouble(input);
    }
}
