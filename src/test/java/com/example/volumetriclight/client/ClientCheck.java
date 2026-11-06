package com.example.volumetriclight.client;

import com.example.volumetriclight.Light;
import com.example.volumetriclight.LightData;
import com.example.volumetriclight.LightService;
import com.example.volumetriclight.VolumetricLight;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.InputEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in integration/render check in a dedicated disposable world; excluded from the released jar. */
@Mod.EventBusSubscriber(modid = VolumetricLight.ID, value = Dist.CLIENT)
public final class ClientCheck {
    private static int stage, ticks;
    private static boolean language;
    private static volatile boolean ready;
    private static volatile Throwable failure;
    private static NativeImage beamOn;
    private static Light pickingDraft, beforePick, beforeCancel;
    private static Vec3 firstCorner;
    private static final String WORLD = "vlight-preview";
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Boolean.getBoolean("vlight.previewUI")) return;
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new AssertionError("Client integration check failed", failure);
        if (stage == 0) {
            if (mc.screen instanceof AccessibilityOnboardingScreen) {
                mc.options.onboardAccessibility = false; mc.setScreen(new TitleScreen()); return;
            }
            if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
            if (!language) {
                language = true; mc.getLanguageManager().setSelected("zh_cn"); mc.options.languageCode = "zh_cn";
                mc.options.guiScale().set(2); mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
                mc.options.pauseOnLostFocus = false; mc.options.hideGui = true; mc.reloadResourcePacks(); return;
            }
            stage = 1;
            if (Files.exists(Path.of("saves", WORLD, "level.dat"))) mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
            else mc.createWorldOpenFlows().createFreshLevel(WORLD,
                    new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (stage == 1) {
            stage = 2;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var server = mc.getSingleplayerServer(); var level = server.overworld();
                    var player = server.getPlayerList().getPlayers().get(0);
                    level.setDayTime(18000);
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    player.teleportTo(level, 0, 65, -4, 0, -8);
                    for (int x = -12; x <= 12; x++) for (int z = -8; z <= 22; z++) {
                        level.setBlock(new BlockPos(x, 64, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, 78, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                        if (x == -12 || x == 12 || z == -8 || z == 22)
                            for (int y = 65; y < 78; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    }
                    for (int z : new int[]{0, 10, 20}) for (int x : new int[]{-12, 12})
                        level.setBlock(new BlockPos(x, 70, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    var source = player.createCommandSourceStack().withPermission(4);
                    if (LightData.get(server).lights.containsKey("demo")) LightService.delete(server, "demo", LightService.get(server, "demo").revision);
                    String[] commands = {
                        "vlight create demo", "vlight name demo 大厅聚光灯", "vlight source demo -6 75 8", "vlight target demo 3 65 13",
                        "vlight angle demo 36", "vlight range demo 20", "vlight brightness demo 1.6", "vlight radius demo 0.15",
                        "vlight trigger demo box 0 65 10 5 69 16", "vlight trigger demo action pulse", "vlight trigger demo duration 100",
                        "vlight trigger demo off", "vlight track demo @s", "vlight track demo off", "vlight off demo", "vlight on demo"
                    };
                    for (String command : commands) if (server.getCommands().getDispatcher().execute(command, source) != 1) throw new AssertionError(command);
                    Light before = LightService.get(server, "demo").copy();
                    server.getCommands().getDispatcher().execute("vlight on demo", source);
                    if (LightService.get(server, "demo").revision != before.revision) throw new AssertionError("Repeated on must be idempotent");
                    Light stale = before.copy(); stale.revision--;
                    try { LightService.save(server, stale, false); throw new AssertionError("Stale revision accepted"); }
                    catch (IllegalArgumentException expected) { }
                    if (LightData.get(server).save(new net.minecraft.nbt.CompoundTag()).getList("lights", 10).isEmpty())
                        throw new AssertionError("World save lost lights");
                    server.getCommands().getDispatcher().execute("vlight import " + before.write(), source);
                    ready = true;
                } catch (Throwable ex) { failure = ex; }
            });
            return;
        }
        if (!ready || ticks < 225 && !LightClient.lights.containsKey("demo") || mc.getOverlay() != null) return;
        if (BeamRenderer.failed) throw new AssertionError("Beam renderer failed; inspect latest.log");
        ticks++;
        if (ticks == 80) mc.setScreen(new LightEditor());
        if (ticks == 110) capture("vlight-editor.png");
        if (ticks == 112) {
            nameField().setValue("大厅主灯");
            if (LightClient.lights.get("demo").light().name.equals("大厅主灯")) throw new AssertionError("Draft leaked into saved state");
        }
        if (ticks == 113) click("应用");
        if (ticks == 123 && !LightClient.lights.get("demo").light().name.equals("大厅主灯")) throw new AssertionError("UI save packet failed");
        if (ticks == 125) { nameField().setValue("不应保存"); click("取消"); }
        if (ticks == 127) {
            if (mc.screen != null || LightClient.preview != null || !LightClient.lights.get("demo").light().name.equals("大厅主灯"))
                throw new AssertionError("Cancel failed to discard the local draft");
            mc.setScreen(new LightEditor());
        }
        if (ticks == 130) click("追踪");
        if (ticks == 145) capture("vlight-tracking.png");
        if (ticks == 150) click("触发");
        if (ticks == 165) capture("vlight-trigger.png");
        if (ticks == 166) {
            pickingDraft = LightClient.preview; beforePick = pickingDraft.copy();
            click("在世界中选择区域（两个角点）");
        }
        if (ticks == 167) {
            mc.options.keyAttack.setDown(true); mousePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            firstCorner = pickingDraft.cornerA;
            if (firstCorner.equals(beforePick.cornerA)) throw new AssertionError("First press did not select corner A");
        }
        if (ticks >= 167 && ticks <= 171) {
            for (int i = 0; i < 3; i++) {
                var held = new InputEvent.InteractionKeyMappingTriggered(0, mc.options.keyAttack, InteractionHand.MAIN_HAND);
                MinecraftForge.EVENT_BUS.post(held);
                if (!held.isCanceled() || held.shouldSwingHand()) throw new AssertionError("World attack was not suppressed while selecting");
            }
            if (mc.screen != null || !pickingDraft.cornerA.equals(firstCorner) || !pickingDraft.cornerB.equals(beforePick.cornerB))
                throw new AssertionError("Held left button selected the second corner");
        }
        if (ticks == 172) { mc.options.keyAttack.setDown(false); mc.player.setYRot(45); }
        if (ticks == 175) {
            mousePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            if (!(mc.screen instanceof LightEditor) || !pickingDraft.cornerA.equals(firstCorner)
                    || pickingDraft.cornerB.equals(firstCorner) || pickingDraft.cornerB.equals(beforePick.cornerB))
                throw new AssertionError("Second press did not select a distinct corner B");
            beforeCancel = pickingDraft.copy();
        }
        if (ticks == 176) { capture("vlight-two-corners.png"); click("在世界中选择区域（两个角点）"); }
        if (ticks == 177) mousePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (ticks == 178) {
            mousePress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            if (!(mc.screen instanceof LightEditor) || !pickingDraft.cornerA.equals(beforeCancel.cornerA) || !pickingDraft.cornerB.equals(beforeCancel.cornerB))
                throw new AssertionError("Cancelling after the first corner changed the saved selection");
            mc.player.setYRot(0); VolumetricLight.LOG.info("VLIGHT_PICK_CHECK_PASSED: held button, two distinct presses, cancel");
        }
        if (ticks == 180) { mc.getWindow().setWindowed(960, 640); mc.options.guiScale().set(3); mc.resizeDisplay(); }
        if (ticks == 190) capture("vlight-small.png");
        if (ticks == 200) { mc.setScreen(null); mc.options.hideGui = true; }
        if (ticks == 215) { capture("vlight-beam-on.png"); beamOn = Screenshot.takeScreenshot(mc.getMainRenderTarget()); }
        if (ticks == 225) LightClient.lights.clear();
        if (ticks == 230) {
            capture("vlight-beam-off.png");
            try (NativeImage off = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                long difference = 0; int affected = 0;
                for (int y = 0; y < off.getHeight(); y++) for (int x = 0; x < off.getWidth(); x++) {
                    int a = beamOn.getPixelRGBA(x, y), b = off.getPixelRGBA(x, y);
                    int d = Math.max(0, (a & 255) - (b & 255)) + Math.max(0, (a >> 8 & 255) - (b >> 8 & 255))
                            + Math.max(0, (a >> 16 & 255) - (b >> 16 & 255));
                    difference += d; if (d > 15) affected++;
                }
                beamOn.close();
                if (affected < 2000 || difference < off.getWidth() * off.getHeight()) throw new AssertionError("No visible volumetric beam: pixels=" + affected + ", energy=" + difference);
                VolumetricLight.LOG.info("Beam image check: affectedPixels={}, addedRGB={}", affected, difference);
            }
        }
        if (ticks == 245) { VolumetricLight.LOG.info("VLIGHT_CLIENT_CHECK_PASSED"); mc.stop(); }
    }
    private static void click(String text) {
        for (var child : Minecraft.getInstance().screen.children()) if (child instanceof Button b && b.getMessage().getString().equals(text)) { b.onPress(); return; }
        throw new AssertionError("Missing tab " + text);
    }
    private static void mousePress(int button) {
        var event = new InputEvent.MouseButton.Pre(button, GLFW.GLFW_PRESS, 0);
        MinecraftForge.EVENT_BUS.post(event);
        if (!event.isCanceled()) throw new AssertionError("Selection click was not consumed");
    }
    private static EditBox nameField() {
        for (var child : Minecraft.getInstance().screen.children())
            if (child instanceof EditBox b && b.getMessage().getString().equals("灯光名称")) return b;
        throw new AssertionError("Name field missing");
    }
    private static void capture(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof LightEditor editor) for (var child : editor.children()) if (child instanceof AbstractWidget w && w.visible)
            if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > editor.width || w.getY() + w.getHeight() > editor.height)
                throw new AssertionError("Widget outside screen: " + w.getMessage().getString());
        Screenshot.grab(new File("."), name, mc.getMainRenderTarget(), message -> {});
    }
}
