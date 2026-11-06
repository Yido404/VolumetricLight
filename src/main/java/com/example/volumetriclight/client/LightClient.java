package com.example.volumetriclight.client;

import com.example.volumetriclight.Light;
import com.example.volumetriclight.LightNetwork;
import com.example.volumetriclight.VolumetricLight;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = VolumetricLight.ID, value = Dist.CLIENT)
public final class LightClient {
    public record Entry(Light light, boolean active, UUID target) {}
    public static final Map<String, Entry> lights = new LinkedHashMap<>();
    public static final List<String> players = new ArrayList<>();
    private static final Map<String, Vec3> serverAims = new LinkedHashMap<>();
    public static Light preview;
    public static int testTicks;
    public static boolean testTracking;
    public static boolean testActive = true;
    private static com.example.volumetriclight.TriggerState testState;
    private static int testElapsed;
    private static final UUID TEST_PLAYER = new UUID(0, 1);
    private static LightEditor picking;
    private static int pickMode;
    private static boolean hadWorld;
    public static String dimension = "";
    public static final KeyMapping EDITOR = new KeyMapping("key.volumetriclight.editor", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L, "key.categories.volumetriclight");

    @Mod.EventBusSubscriber(modid = VolumetricLight.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(EDITOR); }
    }
    public static void accept(LightNetwork.Snapshot state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || state.data() == null) return;
        String incoming = state.data().getString("dimension");
        if (!incoming.equals(mc.level.dimension().location().toString())) return;
        dimension = incoming; lights.clear(); players.clear(); serverAims.clear();
        for (Tag item : state.data().getList("lights", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) item;
            Light l = Light.read(tag);
            lights.put(l.id, new Entry(l, tag.getBoolean("active"), tag.hasUUID("currentTarget") ? tag.getUUID("currentTarget") : null));
            if (tag.contains("currentAim")) serverAims.put(l.id, Light.getPoint(tag, "currentAim"));
        }
        for (Tag name : state.data().getList("players", Tag.TAG_STRING)) players.add(name.getAsString());
        if (state.open() && mc.player != null) mc.setScreen(new LightEditor());
        LightEditor editor = mc.screen instanceof LightEditor e ? e : picking;
        if (editor != null) editor.received(state);
        else if (!state.message().isEmpty() && mc.player != null) mc.player.displayClientMessage(Component.literal(state.message()), false);
    }
    public static void beginPick(LightEditor editor, int mode) {
        picking = editor; pickMode = mode; Minecraft.getInstance().setScreen(null);
    }
    private static void endPick(boolean cancel) {
        LightEditor editor = picking; picking = null;
        if (cancel && pickMode == 3 && editor != null) editor.cancelRegionPick();
        if (editor != null) Minecraft.getInstance().setScreen(editor);
    }
    @SubscribeEvent public static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (picking == null) return;
        e.setCanceled(true); e.setSwingHand(false);
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre e) {
        if (picking == null || e.getAction() != GLFW.GLFW_PRESS) return;
        if (e.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT && e.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        e.setCanceled(true);
        if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { endPick(true); return; }
        // Attack interactions repeat while held; only a fresh mouse press may select a point.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { endPick(true); return; }
        Vec3 from = mc.gameRenderer.getMainCamera().getPosition();
        var direction = mc.gameRenderer.getMainCamera().getLookVector();
        Vec3 to = from.add(new Vec3(direction).scale(64));
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            mc.player.displayClientMessage(Component.literal("请点选 64 格内的方块表面"), true); return;
        }
        Vec3 point = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(.03));
        picking.picked(pickMode, point);
        if (pickMode == 2) pickMode = 3; else endPick(false);
    }
    @SubscribeEvent public static void key(InputEvent.Key e) {
        if (picking != null && e.getKey() == GLFW.GLFW_KEY_ESCAPE && e.getAction() == GLFW.GLFW_PRESS) endPick(true);
    }
    @SubscribeEvent public static void overlay(RenderGuiEvent.Post e) {
        if (picking == null) return;
        Minecraft mc = Minecraft.getInstance();
        String what = switch (pickMode) { case 0 -> "光源位置"; case 1 -> "照射位置"; case 2 -> "区域第一个角点"; default -> "区域第二个角点"; };
        e.getGuiGraphics().drawCenteredString(mc.font, "左键选择" + what + " · 右键 / Esc 返回", mc.getWindow().getGuiScaledWidth() / 2, 24, 0xffe1a6);
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles e) {
        if (Minecraft.getInstance().screen instanceof LightEditor editor) { e.setYaw(editor.cameraYaw()); e.setPitch(editor.viewPitch); }
    }
    @SubscribeEvent public static void hideHud(RenderGuiEvent.Pre e) {
        if (Minecraft.getInstance().screen instanceof LightEditor) e.setCanceled(true);
    }
    @SubscribeEvent public static void fov(ViewportEvent.ComputeFov e) {
        if (Minecraft.getInstance().screen instanceof LightEditor editor) e.setFOV(editor.viewFov);
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (hadWorld) { lights.clear(); players.clear(); serverAims.clear(); preview = null; picking = null; dimension = ""; BeamRenderer.clear(); }
            hadWorld = false; return;
        }
        hadWorld = true;
        if (testTicks > 0) {
            testTicks--;
            if (!testTracking && testState != null && preview != null) {
                testElapsed++; LinkedHashSet<UUID> inside = new LinkedHashSet<>();
                if (testElapsed >= 10 && testElapsed < 60) inside.add(TEST_PLAYER);
                testState.update(preview, inside, testElapsed); testActive = testState.active;
            }
        }
        while (EDITOR.consumeClick()) if (mc.screen == null && picking == null) LightNetwork.send(LightNetwork.Operation.OPEN, new CompoundTag());
    }
    public static Vec3 aim(Entry entry, float partial) {
        Minecraft mc = Minecraft.getInstance(); Light l = entry.light();
        if (l == preview && testTracking && testTicks > 0 && mc.level != null) {
            double time = (mc.level.getGameTime() + partial) * .06;
            return l.target.add(Math.sin(time) * 2, 1, Math.cos(time) * 2);
        }
        if (l == preview && !testTracking && testTicks > 0 && l.tracking == Light.Tracking.TRIGGER)
            return l.cornerA.lerp(l.cornerB, .5);
        UUID target = entry.target();
        if (l == preview && l.tracking == Light.Tracking.PLAYER && mc.level != null) {
            var p = mc.level.players().stream().filter(v -> v.getGameProfile().getName().equalsIgnoreCase(l.playerName)).findFirst().orElse(null);
            if (p != null) target = p.getUUID();
        }
        if (l.tracking != Light.Tracking.FIXED && target != null && mc.level != null) {
            var p = mc.level.getPlayerByUUID(target);
            if (p != null) return p.getPosition(partial).add(0, p.getBbHeight() * .5, 0);
        }
        return l.tracking != Light.Tracking.FIXED ? serverAims.getOrDefault(l.id, l.target) : l.target;
    }
    public static void acceptAims(Map<String, Vec3> positions) {
        positions.forEach((id, p) -> { if (lights.containsKey(id)) serverAims.put(id, p); });
    }
    public static void testTrigger() {
        testTracking = false; testTicks = 140; testElapsed = 0;
        testState = new com.example.volumetriclight.TriggerState(); testActive = false;
    }
}
