package com.example.volumetriclight;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class LightCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("vlight").requires(s -> s.hasPermission(2))
            .executes(c -> { c.getSource().sendSuccess(() -> Component.literal("/vlight edit | create | list | source | target | radius | angle | range | brightness | softness | color | track | trigger | on | off | import | delete"), false); return 1; });
        root.then(Commands.literal("edit").executes(c -> {
            LightService.send(c.getSource().getPlayerOrException(), true, true, "", ""); return 1;
        }));
        root.then(Commands.literal("list").executes(c -> {
            LightData.get(c.getSource().getServer()).lights.values().forEach(l -> c.getSource().sendSuccess(
                () -> Component.literal(l.id + " · " + l.name + " · " + l.dimension + " · " + (l.enabled ? "启用" : "关闭")), false)); return 1;
        }));
        root.then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word()).executes(c -> run(c, () -> {
            LightService.create(c.getSource().getServer(), StringArgumentType.getString(c, "id"),
                    c.getSource().getLevel().dimension().location().toString(), c.getSource().getPosition());
        }))));
        for (String operation : new String[]{"on", "off", "delete"}) root.then(withId(operation, id -> id.executes(c -> {
            if (operation.equals("delete")) return run(c, () -> {
                Light l = selected(c); LightService.delete(c.getSource().getServer(), l.id, l.revision);
            });
            return run(c, () -> LightService.setEnabled(c.getSource().getServer(), selected(c), operation.equals("on")));
        })));
        root.then(withId("name", id -> id.then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> change(c, l -> l.name = StringArgumentType.getString(c, "name"))))));
        for (String operation : new String[]{"source", "target"}) root.then(withId(operation, id -> id
            .then(Commands.argument("pos", Vec3Argument.vec3(false)).executes(c -> {
                Vec3 p = Vec3Argument.getVec3(c, "pos");
                return change(c, l -> { if (operation.equals("source")) l.source = p; else { l.target = p; l.tracking = Light.Tracking.FIXED; } });
            }))));
        numeric(root, "radius", 0, 4, (l, v) -> l.radius = v);
        numeric(root, "angle", 2, 100, (l, v) -> l.angle = v);
        numeric(root, "range", 1, 64, (l, v) -> l.range = v);
        numeric(root, "brightness", 0, 4, (l, v) -> l.brightness = v);
        numeric(root, "softness", .01f, 1, (l, v) -> l.softness = v);
        numeric(root, "smoothing", .02f, 1, (l, v) -> l.smoothing = v);
        root.then(withId("color", id -> id.then(Commands.argument("rgb", StringArgumentType.word()).executes(c -> change(c, l -> {
            String hex = StringArgumentType.getString(c, "rgb").replace("#", "");
            Light.require(hex.matches("[0-9a-fA-F]{6}"), "颜色示例：FFDEA8"); l.color = Integer.parseInt(hex, 16);
        })))));
        root.then(withId("track", id -> id.then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            ServerPlayer player = EntityArgument.getPlayer(c, "player");
            return change(c, l -> { l.tracking = Light.Tracking.PLAYER; l.playerName = player.getGameProfile().getName(); });
        })).then(Commands.literal("trigger").executes(c -> change(c, l -> l.tracking = Light.Tracking.TRIGGER)))
          .then(Commands.literal("off").executes(c -> change(c, l -> l.tracking = Light.Tracking.FIXED)))));
        var trigger = idArgument();
        trigger.then(Commands.literal("box").then(Commands.argument("a", Vec3Argument.vec3(false))
            .then(Commands.argument("b", Vec3Argument.vec3(false)).executes(c -> {
                Vec3 a = Vec3Argument.getVec3(c, "a"), b = Vec3Argument.getVec3(c, "b");
                return change(c, l -> { l.cornerA = a; l.cornerB = b; if (l.trigger == Light.Trigger.OFF) l.trigger = Light.Trigger.ENTER; });
            }))));
        for (Light.Trigger mode : Light.Trigger.values()) trigger.then(Commands.literal(mode.name().toLowerCase(java.util.Locale.ROOT))
            .executes(c -> change(c, l -> l.trigger = mode)));
        var actionNode = Commands.literal("action");
        for (Light.Action action : Light.Action.values()) actionNode.then(Commands.literal(action.name().toLowerCase(java.util.Locale.ROOT))
            .executes(c -> change(c, l -> l.action = action)));
        trigger.then(actionNode);
        trigger.then(Commands.literal("any").executes(c -> change(c, l -> l.filterName = "")));
        trigger.then(Commands.literal("player").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            ServerPlayer player = EntityArgument.getPlayer(c, "player"); return change(c, l -> l.filterName = player.getGameProfile().getName());
        })));
        for (String field : new String[]{"duration", "cooldown"}) trigger.then(Commands.literal(field)
            .then(Commands.argument("ticks", IntegerArgumentType.integer(field.equals("duration") ? 1 : 0, 72000)).executes(c -> change(c, l -> {
                int ticks = IntegerArgumentType.getInteger(c, "ticks"); if (field.equals("duration")) l.duration = ticks; else l.cooldown = ticks;
            }))));
        root.then(Commands.literal("trigger").then(trigger));
        root.then(Commands.literal("import").then(Commands.argument("data", CompoundTagArgument.compoundTag()).executes(c -> run(c, () -> {
            Light l = Light.read(CompoundTagArgument.getCompoundTag(c, "data"));
            Light.require(l.dimension.equals(c.getSource().getLevel().dimension().location().toString()), "请在灯光所在维度执行导入");
            Light existing = LightData.get(c.getSource().getServer()).lights.get(l.id);
            if (existing != null) l.revision = existing.revision;
            LightService.save(c.getSource().getServer(), l, existing == null);
        }))));
        dispatcher.register(root);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
        return Commands.argument("id", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                LightData.get(c.getSource().getServer()).lights.keySet(), b));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> withId(String operation,
            Consumer<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>> configure) {
        var id = idArgument(); configure.accept(id); return Commands.literal(operation).then(id);
    }
    private static void numeric(LiteralArgumentBuilder<CommandSourceStack> root, String name, float min, float max, BiConsumer<Light, Float> setter) {
        root.then(withId(name, id -> id.then(Commands.argument("value", FloatArgumentType.floatArg(min, max))
            .executes(c -> change(c, l -> setter.accept(l, FloatArgumentType.getFloat(c, "value")))))));
    }
    private static Light selected(CommandContext<CommandSourceStack> c) {
        Light l = LightService.get(c.getSource().getServer(), StringArgumentType.getString(c, "id"));
        Light.require(l.dimension.equals(c.getSource().getLevel().dimension().location().toString()), "请在灯光所在维度执行"); return l;
    }
    private static int change(CommandContext<CommandSourceStack> c, Consumer<Light> edit) throws CommandSyntaxException {
        return run(c, () -> { Light l = selected(c).copy(); edit.accept(l); LightService.save(c.getSource().getServer(), l, false); });
    }
    private static int run(CommandContext<CommandSourceStack> c, Runnable action) throws CommandSyntaxException {
        try { action.run(); c.getSource().sendSuccess(() -> Component.literal("体积光设置已更新"), false); return 1; }
        catch (IllegalArgumentException e) { throw new SimpleCommandExceptionType(Component.literal(e.getMessage())).create(); }
    }
}
