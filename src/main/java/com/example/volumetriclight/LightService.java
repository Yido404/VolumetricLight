package com.example.volumetriclight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class LightService {
    private static final Map<String, TriggerState> runtime = new HashMap<>();
    private static final Map<String, UUID> targets = new HashMap<>();
    private static final Map<String, Boolean> visible = new HashMap<>();
    private static final Map<String, Vec3> lastAims = new HashMap<>();
    private static long clock;
    private static boolean rosterDirty;

    public static Light get(MinecraftServer server, String id) {
        Light l = LightData.get(server).lights.get(id);
        Light.require(l != null, "找不到灯光：" + id);
        return l;
    }
    public static Light create(MinecraftServer server, String id, String dimension, Vec3 position) {
        LightData data = LightData.get(server);
        Light.require(data.lights.size() < LightData.MAX_LIGHTS, "每个世界最多 64 盏灯");
        Light.require(!data.lights.containsKey(id), "灯光 ID 已存在");
        Light l = new Light(id); l.dimension = dimension;
        l.source = position.add(0, 5, 0); l.target = position.add(0, 0, 5);
        l.cornerA = position.add(-2, -1, -2); l.cornerB = position.add(2, 3, 2);
        return save(server, l, true);
    }
    public static Light save(MinecraftServer server, Light l, boolean create) {
        l.validate();
        LightData data = LightData.get(server);
        Light old = data.lights.get(l.id);
        Light.require(create ? old == null : old != null, create ? "灯光 ID 已存在" : "灯光已删除，请重新载入");
        Light.require(!create || data.lights.size() < LightData.MAX_LIGHTS, "每个世界最多 64 盏灯");
        Light.require(old == null || old.revision == l.revision, "这盏灯已被其他操作修改；草稿已保留，请重新载入后重试");
        Light.require(old == null || old.dimension.equals(l.dimension), "不能在编辑时改变灯光维度");
        if (l.tracking == Light.Tracking.PLAYER) {
            Light.require(!l.playerName.isEmpty(), "请选择追踪玩家");
            l.player = resolve(server, l.playerName, old == null ? null : old.player,
                    old == null ? "" : old.playerName);
        } else l.player = null;
        l.filter = l.filterName.isEmpty() ? null : resolve(server, l.filterName, old == null ? null : old.filter,
                old == null ? "" : old.filterName);
        if (old != null && l.write().equals(old.write())) return old.copy();
        l.revision = old == null ? 1 : old.revision + 1;
        data.lights.put(l.id, l.copy()); data.setDirty();
        runtime.remove(l.id); targets.remove(l.id); visible.remove(l.id);
        refresh(server); broadcast(server);
        return l;
    }
    private static UUID resolve(MinecraftServer server, String name, UUID old, String oldName) {
        if (old != null && name.equalsIgnoreCase(oldName)) return old;
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        Light.require(player != null, "首次绑定时玩家须在线：" + name);
        return player.getUUID();
    }
    public static void delete(MinecraftServer server, String id, long revision) {
        Light l = get(server, id);
        Light.require(l.revision == revision, "灯光已修改，请重新载入后再删除");
        LightData data = LightData.get(server); data.lights.remove(id); data.setDirty();
        runtime.remove(id); targets.remove(id); visible.remove(id); lastAims.remove(id); broadcast(server);
    }
    public static void setEnabled(MinecraftServer server, Light original, boolean enabled) {
        Light l = original.copy(); l.enabled = enabled; save(server, l, false);
        if (enabled) {
            runtime.computeIfAbsent(l.id, k -> new TriggerState()).commandOn();
            if (refresh(server)) broadcast(server);
        }
    }
    public static void request(ServerPlayer player, LightNetwork.Request request) {
        if (!player.hasPermissions(2)) {
            send(player, false, false, "需要管理员权限（等级 2）", ""); return;
        }
        try {
            CompoundTag tag = request.data();
            Light.require(tag != null, "无效请求");
            String id = tag.getString("id");
            switch (request.operation()) {
                case OPEN -> { send(player, true, true, "", ""); return; }
                case CREATE -> {
                    Light l = Light.read(tag);
                    Light.require(l.dimension.equals(player.level().dimension().location().toString()), "请在灯光所在维度编辑");
                    if (LightData.get(player.server).lights.containsKey(l.id)) {
                        int i = 1; while (LightData.get(player.server).lights.containsKey("light_" + i)) i++;
                        l.id = "light_" + i;
                    }
                    save(player.server, l, true);
                    send(player, false, true, "已新建灯光", l.id);
                }
                case SAVE -> {
                    Light l = Light.read(tag);
                    Light.require(l.dimension.equals(player.level().dimension().location().toString()), "请在灯光所在维度编辑");
                    save(player.server, l, false); send(player, false, true, "已应用并保存", l.id);
                }
                case DELETE -> {
                    Light l = get(player.server, id);
                    Light.require(l.dimension.equals(player.level().dimension().location().toString()), "请在灯光所在维度编辑");
                    delete(player.server, id, tag.getLong("revision")); send(player, false, true, "已删除灯光", id);
                }
            }
        } catch (IllegalArgumentException e) { send(player, false, false, e.getMessage(), ""); }
    }
    private static boolean refresh(MinecraftServer server) {
        boolean changed = false;
        for (Light l : LightData.get(server).lights.values()) {
            TriggerState state = runtime.computeIfAbsent(l.id, k -> new TriggerState());
            LinkedHashSet<UUID> inside = new LinkedHashSet<>();
            if (l.trigger != Light.Trigger.OFF) {
                var players = new ArrayList<>(server.getPlayerList().getPlayers());
                players.sort(Comparator.comparingDouble((ServerPlayer p) -> p.position().distanceToSqr(l.source)).thenComparing(ServerPlayer::getUUID));
                for (ServerPlayer p : players) {
                    if (p.isAlive() && !p.isSpectator() && p.level().dimension().location().toString().equals(l.dimension)
                            && (l.filter == null || l.filter.equals(p.getUUID())) && l.contains(p.position())) inside.add(p.getUUID());
                }
            }
            state.update(l, inside, clock);
            UUID target = l.tracking == Light.Tracking.PLAYER ? l.player : l.tracking == Light.Tracking.TRIGGER ? state.locked : null;
            boolean active = state.active;
            if (l.tracking != Light.Tracking.FIXED) {
                ServerPlayer p = target == null ? null : server.getPlayerList().getPlayer(target);
                active &= p != null && p.isAlive() && p.level().dimension().location().toString().equals(l.dimension);
            }
            if (!Objects.equals(targets.put(l.id, target), target)) changed = true;
            if (!Objects.equals(visible.put(l.id, active), active)) changed = true;
        }
        return changed;
    }
    static void send(ServerPlayer player, boolean open, boolean success, String message, String selected) {
        CompoundTag tag = new CompoundTag(); ListTag entries = new ListTag();
        String dimension = player.level().dimension().location().toString();
        for (Light l : LightData.get(player.server).lights.values()) if (l.dimension.equals(dimension)) {
            CompoundTag entry = l.write(); entry.putBoolean("active", visible.getOrDefault(l.id, l.enabled && l.trigger == Light.Trigger.OFF));
            UUID target = targets.get(l.id); if (target != null) entry.putUUID("currentTarget", target);
            ServerPlayer tracked = target == null ? null : player.server.getPlayerList().getPlayer(target);
            if (tracked != null) Light.putPoint(entry, "currentAim", tracked.position().add(0, tracked.getBbHeight() * .5, 0));
            entries.add(entry);
        }
        tag.put("lights", entries); tag.putString("dimension", dimension);
        ListTag names = new ListTag();
        player.server.getPlayerList().getPlayers().forEach(p -> names.add(StringTag.valueOf(p.getGameProfile().getName())));
        tag.put("players", names);
        LightNetwork.send(player, new LightNetwork.Snapshot(tag, open, success, message, selected));
    }
    static void broadcast(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p, false, true, "", "");
    }
    private static void syncAims(MinecraftServer server) {
        Map<String, Vec3> changed = new LinkedHashMap<>();
        for (Light l : LightData.get(server).lights.values()) {
            UUID target = targets.get(l.id); ServerPlayer p = target == null ? null : server.getPlayerList().getPlayer(target);
            if (p == null || !visible.getOrDefault(l.id, false)) continue;
            Vec3 point = p.position().add(0, p.getBbHeight() * .5, 0), old = lastAims.get(l.id);
            if (old == null || old.distanceToSqr(point) > .0001) { changed.put(l.id, point); lastAims.put(l.id, point); }
        }
        if (changed.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, Vec3> scoped = new LinkedHashMap<>();
            String dimension = player.level().dimension().location().toString();
            changed.forEach((id, p) -> { if (LightData.get(server).lights.get(id).dimension.equals(dimension)) scoped.put(id, p); });
            if (!scoped.isEmpty()) LightNetwork.send(player, new LightNetwork.Aims(scoped));
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        clock++; if (refresh(e.getServer()) || rosterDirty) { rosterDirty = false; broadcast(e.getServer()); }
        if (clock % 5 == 0) syncAims(e.getServer());
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { refresh(p.server); send(p, false, true, "", ""); rosterDirty = true; }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { refresh(p.server); send(p, false, true, "", ""); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { rosterDirty = true; }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e) { runtime.clear(); targets.clear(); visible.clear(); lastAims.clear(); clock = 0; rosterDirty = false; }
}
