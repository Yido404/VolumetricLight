package com.example.volumetriclight.client;

import com.example.volumetriclight.Light;
import com.example.volumetriclight.VolumetricLight;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

@Mod.EventBusSubscriber(modid = VolumetricLight.ID, value = Dist.CLIENT)
public final class BeamRenderer {
    private static final int SHADOW_SIZE = 16, MAX_VISIBLE = 8;
    private static ShaderInstance shader;
    private static RenderTarget depth;
    private static final Map<String, Shadow> shadows = new HashMap<>();
    private static final Map<String, Vec3> smoothed = new HashMap<>();
    private static long previousFrame;
    public static boolean failed;

    @Mod.EventBusSubscriber(modid = VolumetricLight.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void shaders(RegisterShadersEvent e) throws IOException {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), new ResourceLocation(VolumetricLight.ID, "beam"),
                    DefaultVertexFormat.POSITION), s -> { shader = s; failed = false; });
        }
    }
    private static final class Shadow implements AutoCloseable {
        final DynamicTexture texture = new DynamicTexture(SHADOW_SIZE, SHADOW_SIZE, false);
        long updated;
        Light last;
        Vec3 aim = Vec3.ZERO;
        void update(Light l, Vec3 target) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 axis = target.subtract(l.source).normalize();
            Vec3 right = right(axis), up = axis.cross(right).normalize();
            double tangent = Math.tan(Math.toRadians(l.angle * .5));
            NativeImage pixels = texture.getPixels();
            for (int y = 0; y < SHADOW_SIZE; y++) for (int x = 0; x < SHADOW_SIZE; x++) {
                double u = (x + .5) / SHADOW_SIZE * 2 - 1, v = (y + .5) / SHADOW_SIZE * 2 - 1;
                Vec3 lateral = right.scale(u).add(up.scale(v));
                Vec3 from = l.source.add(lateral.scale(l.radius));
                Vec3 to = l.source.add(axis.scale(l.range)).add(lateral.scale(l.radius + tangent * l.range));
                var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                double distance = hit.getType() == HitResult.Type.MISS ? l.range : Math.max(0, hit.getLocation().subtract(l.source).dot(axis));
                int packed = (int) Math.round(Math.min(1, distance / l.range) * 65535);
                pixels.setPixelRGBA(x, y, 0xff000000 | (packed & 255) | ((packed >> 8) << 8));
            }
            texture.upload(); texture.setFilter(true, false);
            updated = System.nanoTime(); aim = target;
            last = new Light(l.id); last.source = l.source; last.radius = l.radius; last.angle = l.angle; last.range = l.range;
        }
        @Override public void close() { texture.close(); }
    }
    private static Vec3 right(Vec3 axis) {
        return axis.cross(Math.abs(axis.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || shader == null || failed) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Vec3 camera = e.getCamera().getPosition();
        ArrayList<LightClient.Entry> entries = new ArrayList<>();
        for (LightClient.Entry entry : LightClient.lights.values())
            if (entry.active() && (LightClient.preview == null || !entry.light().id.equals(LightClient.preview.id))) entries.add(entry);
        if (LightClient.preview != null && LightClient.preview.enabled
                && (LightClient.testTicks <= 0 || LightClient.testTracking || LightClient.testActive))
            entries.add(new LightClient.Entry(LightClient.preview, true, null));
        entries.removeIf(v -> !v.light().dimension.equals(mc.level.dimension().location().toString())
                || v.light().source.distanceTo(camera) > v.light().range + 64);
        entries.sort(Comparator.comparingDouble(v -> v.light() == LightClient.preview ? -1 : v.light().source.distanceToSqr(camera)));
        if (entries.isEmpty()) return;
        shadows.entrySet().removeIf(v -> {
            if (!LightClient.lights.containsKey(v.getKey()) && (LightClient.preview == null || !LightClient.preview.id.equals(v.getKey()))) {
                v.getValue().close(); smoothed.remove(v.getKey()); return true;
            }
            return false;
        });
        try {
            RenderTarget main = mc.getMainRenderTarget();
            if (depth == null || depth.width != main.width || depth.height != main.height) {
                if (depth != null) depth.destroyBuffers();
                depth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            }
            depth.copyDepthFrom(main); main.bindWrite(false);
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull(); RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            RenderSystem.setShader(() -> shader);
            shader.setSampler("SceneDepth", depth.getDepthTextureId());
            shader.getUniform("InvProjection").set(new Matrix4f(e.getProjectionMatrix()).invert());
            shader.getUniform("InvView").set(new Matrix4f().set(RenderSystem.getInverseViewRotationMatrix()));
            long now = System.nanoTime();
            double seconds = previousFrame == 0 ? .05 : Math.min(.1, (now - previousFrame) / 1e9); previousFrame = now;
            boolean updatedShadow = false;
            for (int i = 0; i < Math.min(MAX_VISIBLE, entries.size()); i++) {
                LightClient.Entry entry = entries.get(i); Light l = entry.light();
                Vec3 desired = LightClient.aim(entry, e.getPartialTick());
                Vec3 old = smoothed.getOrDefault(l.id, desired);
                Vec3 target = l.tracking == Light.Tracking.FIXED && !(l == LightClient.preview && LightClient.testTracking && LightClient.testTicks > 0)
                        ? desired : old.lerp(desired, 1 - Math.exp(-seconds * (2 + 24 * (1 - l.smoothing))));
                smoothed.put(l.id, target);
                Vec3 axis = target.subtract(l.source).normalize(); if (axis.lengthSqr() < .5) continue;
                Shadow shadow = shadows.computeIfAbsent(l.id, k -> new Shadow());
                boolean geometryChanged = shadow.last == null || shadow.last.source.distanceToSqr(l.source) > .0001
                        || shadow.last.angle != l.angle || shadow.last.radius != l.radius || shadow.last.range != l.range;
                // shortcut: 16x16 block shadows refresh at most four times per second; increase resolution for detailed occluders.
                if (shadow.last == null || !updatedShadow && (geometryChanged || now - shadow.updated > 250000000L)) {
                    shadow.update(l, target); updatedShadow = true;
                }
                shader.setSampler("LightShadow", shadow.texture.getId());
                Vec3 right = right(axis), up = axis.cross(right).normalize(), relative = l.source.subtract(camera);
                shader.getUniform("LightStart").set((float) relative.x, (float) relative.y, (float) relative.z);
                uniform("LightAxis", axis); uniform("LightRight", right); uniform("LightUp", up);
                Vec3 shadowAxis = shadow.aim.subtract(shadow.last.source).normalize(), shadowRight = right(shadowAxis);
                uniform("ShadowAxis", shadowAxis); uniform("ShadowRight", shadowRight); uniform("ShadowUp", shadowAxis.cross(shadowRight));
                Vec3 shadowOffset = l.source.subtract(shadow.last.source); uniform("ShadowOffset", shadowOffset);
                shader.getUniform("ShadowShape").set(shadow.last.radius, (float) Math.tan(Math.toRadians(shadow.last.angle * .5)), shadow.last.range);
                shader.getUniform("LightShape").set(l.radius, (float) Math.tan(Math.toRadians(l.angle * .5)), l.range, l.softness);
                shader.getUniform("LightColor").set(((l.color >> 16) & 255) / 255f * l.brightness,
                        ((l.color >> 8) & 255) / 255f * l.brightness, (l.color & 255) / 255f * l.brightness);
                BufferBuilder b = Tesselator.getInstance().getBuilder(); b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                b.vertex(-1, -1, 0).endVertex(); b.vertex(1, -1, 0).endVertex(); b.vertex(1, 1, 0).endVertex(); b.vertex(-1, 1, 0).endVertex();
                BufferUploader.drawWithShader(b.end());
            }
        } catch (RuntimeException ex) {
            failed = true; VolumetricLight.LOG.error("Volumetric renderer disabled after an error", ex);
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("体积光渲染失败，请检查日志；F3+T 可重载资源"), false);
        } finally {
            RenderSystem.depthMask(true); RenderSystem.enableDepthTest(); RenderSystem.enableCull(); RenderSystem.defaultBlendFunc(); RenderSystem.disableBlend();
        }
        if (mc.screen instanceof LightEditor editor && editor.guides && LightClient.preview != null) guides(e, LightClient.preview, camera);
    }
    private static void uniform(String name, Vec3 v) { shader.getUniform(name).set((float) v.x, (float) v.y, (float) v.z); }
    private static void guides(RenderLevelStageEvent e, Light l, Vec3 camera) {
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        pose.mulPoseMatrix(new Matrix4f().set(RenderSystem.getInverseViewRotationMatrix()).invert());
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffer = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffer.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, new AABB(l.source.add(-.1, -.1, -.1), l.source.add(.1, .1, .1)), 1, .7f, .2f, 1);
        LevelRenderer.renderLineBox(pose, lines, new AABB(l.target.add(-.1, -.1, -.1), l.target.add(.1, .1, .1)), .2f, 1, 1, 1);
        if (l.trigger != Light.Trigger.OFF) LevelRenderer.renderLineBox(pose, lines, new AABB(l.cornerA, l.cornerB), 1, .7f, .2f, .8f);
        buffer.endBatch(RenderType.lines());
    }
    public static void clear() {
        shadows.values().forEach(Shadow::close); shadows.clear(); smoothed.clear(); previousFrame = 0;
        if (depth != null) { depth.destroyBuffers(); depth = null; }
    }
}
