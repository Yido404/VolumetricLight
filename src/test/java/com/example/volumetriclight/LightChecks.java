package com.example.volumetriclight;

import com.mojang.brigadier.CommandDispatcher;
import java.util.LinkedHashSet;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;

public final class LightChecks {
    public static void main(String[] args) throws Exception {
        Light light = new Light("demo");
        light.source = new Vec3(1.25, 76, 3.5); light.target = new Vec3(4, 65, 7);
        light.player = UUID.randomUUID(); light.playerName = "Steve"; light.tracking = Light.Tracking.PLAYER;
        light.trigger = Light.Trigger.ENTER; light.duration = 5; light.cooldown = 10;
        assert light.copy().write().equals(light.write()) : "NBT round-trip lost settings";
        assert Light.read(TagParser.parseTag(light.exportCommand().substring("vlight import ".length()))).write().equals(light.write());
        LightData data = new LightData(); data.lights.put(light.id, light);
        assert LightData.load(data.save(new CompoundTag())).lights.get("demo").write().equals(light.write()) : "SavedData round-trip";
        Light invalid = light.copy(); invalid.angle = Float.NaN; rejects(invalid::validate);
        invalid = light.copy(); invalid.source = new Vec3(Double.POSITIVE_INFINITY, 1, 1); rejects(invalid::validate);
        invalid = light.copy(); invalid.range = 65; rejects(invalid::validate);
        invalid = light.copy(); invalid.id = "../escape"; rejects(invalid::validate);
        invalid = light.copy(); invalid.target = invalid.source; rejects(invalid::validate);
        invalid = light.copy(); invalid.cornerB = invalid.cornerA; rejects(invalid::validate);
        light.cornerA = new Vec3(4, 4, 4); light.cornerB = Vec3.ZERO;
        assert light.contains(new Vec3(0, 4, 1)) && !light.contains(new Vec3(5, 2, 2));

        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        TriggerState state = new TriggerState();
        state.update(light, inside(), 0); assert !state.active;
        state.update(light, inside(a), 1); assert state.active && state.locked.equals(a);
        state.update(light, inside(a), 6); assert !state.active : "Standing still retriggered pulse";
        state.update(light, inside(), 7);
        state.update(light, inside(b), 8); assert !state.active : "Cooldown ignored";
        state.update(light, inside(b), 12); assert !state.active : "Cooldown expiry must not create a new entry";
        state.update(light, inside(), 13); state.update(light, inside(b), 14); assert state.active;
        light.trigger = Light.Trigger.PRESENCE; state = new TriggerState();
        state.update(light, inside(a, b), 1); assert state.active && state.locked.equals(a);
        state.update(light, inside(b), 2); assert state.active && state.locked.equals(b) : "Last-player semantics";
        state.update(light, inside(), 3); assert !state.active;
        state.commandOn(); state.update(light, inside(), 4); assert state.active : "Command cannot turn on a region-controlled lamp";
        state.update(light, inside(a), 5); state.update(light, inside(), 6); assert !state.active : "Next region event must resume automation";
        light.trigger = Light.Trigger.ENTER; light.action = Light.Action.OFF; state = new TriggerState();
        state.update(light, inside(), 1); assert state.active;
        state.update(light, inside(a), 2); assert !state.active : "OFF trigger cannot turn an initially lit lamp off";
        light.trigger = Light.Trigger.OFF; light.enabled = false; state = new TriggerState();
        state.update(light, inside(a), 0); assert !state.active;

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>(); LightCommands.register(dispatcher);
        var root = dispatcher.getRoot().getChild("vlight");
        assert root.getChild("on").getCommand() == null && root.getChild("on").getChild("id").getCommand() != null;
        assert root.getChild("source").getChild("id").getChild("pos").getCommand() != null;
        assert root.getChild("track").getChild("id").getChild("player").getCommand() != null;
        assert root.getChild("trigger").getChild("id").getChild("off").getCommand() != null;
        assert root.getChild("trigger").getChild("id").getChild("action").getChild("off").getCommand() != null;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var aims = new LightNetwork.Aims(java.util.Map.of("demo", light.target)); aims.encode(buffer);
            assert LightNetwork.Aims.decode(buffer).positions().equals(aims.positions());
        } finally { buffer.release(); }
        System.out.println("Light checks passed: validation, save/export, trigger edges/cooldowns/locking, command tree.");
    }
    private static LinkedHashSet<UUID> inside(UUID... ids) { return new LinkedHashSet<>(java.util.List.of(ids)); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid settings accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
