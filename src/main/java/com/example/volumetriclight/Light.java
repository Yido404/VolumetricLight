package com.example.volumetriclight;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class Light {
    public enum Tracking { FIXED, PLAYER, TRIGGER }
    public enum Trigger { OFF, ENTER, PRESENCE }
    public enum Action { ON, OFF, PULSE }
    public String id, name, dimension = "minecraft:overworld";
    public long revision;
    public Vec3 source = new Vec3(0, 74, 0), target = new Vec3(5, 64, 0);
    public float radius = .15f, angle = 30, range = 24, brightness = 1, softness = .35f, smoothing = .25f;
    public int color = 0xffdea8;
    public boolean enabled = true;
    public Tracking tracking = Tracking.FIXED;
    public String playerName = "", filterName = "";
    public UUID player, filter;
    public Trigger trigger = Trigger.OFF;
    public Action action = Action.PULSE;
    public Vec3 cornerA = new Vec3(-2, 63, -2), cornerB = new Vec3(2, 67, 2);
    public int duration = 100, cooldown = 40;

    public Light(String id) { this.id = id; this.name = id; }
    public Light copy() { return read(write()); }
    public void validate() {
        require(id != null && id.matches("[a-z0-9_-]{1,48}"), "灯光 ID 只能使用 1–48 个小写字母、数字、下划线和短横线");
        require(name != null && !name.isBlank() && name.length() <= 48, "名称须为 1–48 个字符");
        require(ResourceLocation.tryParse(dimension) != null, "无效维度");
        point(source); point(target); point(cornerA); point(cornerB);
        require(source.distanceToSqr(target) >= .0001, "光源和照射点不能重合");
        number(radius, 0, 4, "出光口半径"); number(angle, 2, 100, "张角"); number(range, 1, 64, "射程");
        number(brightness, 0, 4, "亮度"); number(softness, .01f, 1, "柔边"); number(smoothing, .02f, 1, "转向平滑度");
        require(color >= 0 && color <= 0xffffff, "颜色须为六位 RGB");
        require(playerName.matches("[A-Za-z0-9_]{0,16}") && filterName.matches("[A-Za-z0-9_]{0,16}"), "玩家名称格式不正确");
        require(duration >= 1 && duration <= 72000 && cooldown >= 0 && cooldown <= 72000, "持续时间为 1–72000 tick，冷却为 0–72000 tick");
        if (trigger != Trigger.OFF) {
            Vec3 delta = cornerA.subtract(cornerB);
            require(Math.abs(delta.x) > .01 && Math.abs(delta.y) > .01 && Math.abs(delta.z) > .01, "区域三个方向都必须有长度");
            require(Math.abs(delta.x) <= 512 && Math.abs(delta.y) <= 512 && Math.abs(delta.z) <= 512, "区域每边不能超过 512 格");
        }
        require(revision >= 0, "无效修订号");
    }
    private static void point(Vec3 p) {
        require(p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z)
                && Math.abs(p.x) <= 30000000 && Math.abs(p.z) <= 30000000 && Math.abs(p.y) <= 2048, "坐标超出范围或不是有限数字");
    }
    private static void number(float n, float min, float max, String label) {
        require(Float.isFinite(n) && n >= min && n <= max, label + "范围为 " + min + "–" + max);
    }
    public static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    public boolean contains(Vec3 p) {
        return p.x >= Math.min(cornerA.x, cornerB.x) && p.x <= Math.max(cornerA.x, cornerB.x)
            && p.y >= Math.min(cornerA.y, cornerB.y) && p.y <= Math.max(cornerA.y, cornerB.y)
            && p.z >= Math.min(cornerA.z, cornerB.z) && p.z <= Math.max(cornerA.z, cornerB.z);
    }
    public CompoundTag write() {
        CompoundTag n = new CompoundTag();
        n.putString("id", id); n.putString("name", name); n.putString("dimension", dimension); n.putLong("revision", revision);
        putPoint(n, "source", source); putPoint(n, "target", target); putPoint(n, "a", cornerA); putPoint(n, "b", cornerB);
        n.putFloat("radius", radius); n.putFloat("angle", angle); n.putFloat("range", range); n.putFloat("brightness", brightness);
        n.putFloat("softness", softness); n.putFloat("smoothing", smoothing); n.putInt("color", color); n.putBoolean("enabled", enabled);
        n.putString("tracking", tracking.name()); n.putString("trigger", trigger.name()); n.putString("action", action.name());
        n.putString("playerName", playerName); n.putString("filterName", filterName);
        if (player != null) n.putUUID("player", player); if (filter != null) n.putUUID("filter", filter);
        n.putInt("duration", duration); n.putInt("cooldown", cooldown);
        return n;
    }
    public static Light read(CompoundTag n) {
        Light l = new Light(n.getString("id"));
        l.name = n.getString("name"); l.dimension = n.getString("dimension"); l.revision = n.getLong("revision");
        l.source = getPoint(n, "source"); l.target = getPoint(n, "target"); l.cornerA = getPoint(n, "a"); l.cornerB = getPoint(n, "b");
        l.radius = n.getFloat("radius"); l.angle = n.getFloat("angle"); l.range = n.getFloat("range"); l.brightness = n.getFloat("brightness");
        l.softness = n.getFloat("softness"); l.smoothing = n.getFloat("smoothing"); l.color = n.getInt("color"); l.enabled = n.getBoolean("enabled");
        l.tracking = Tracking.valueOf(n.getString("tracking")); l.trigger = Trigger.valueOf(n.getString("trigger")); l.action = Action.valueOf(n.getString("action"));
        l.playerName = n.getString("playerName"); l.filterName = n.getString("filterName");
        l.player = n.hasUUID("player") ? n.getUUID("player") : null; l.filter = n.hasUUID("filter") ? n.getUUID("filter") : null;
        l.duration = n.getInt("duration"); l.cooldown = n.getInt("cooldown");
        l.validate();
        return l;
    }
    public static void putPoint(CompoundTag n, String key, Vec3 p) {
        CompoundTag v = new CompoundTag(); v.putDouble("x", p.x); v.putDouble("y", p.y); v.putDouble("z", p.z); n.put(key, v);
    }
    public static Vec3 getPoint(CompoundTag n, String key) {
        CompoundTag v = n.getCompound(key); return new Vec3(v.getDouble("x"), v.getDouble("y"), v.getDouble("z"));
    }
    public String exportCommand() { return "vlight import " + write(); }
    public String colorHex() { return String.format(Locale.ROOT, "#%06X", color); }
}
