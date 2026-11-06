package com.example.volumetriclight;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class LightData extends SavedData {
    public static final int MAX_LIGHTS = 64;
    public final Map<String, Light> lights = new LinkedHashMap<>();
    private final ListTag unreadable = new ListTag();
    public static LightData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(LightData::load, LightData::new, "volumetriclight");
    }
    static LightData load(CompoundTag tag) {
        LightData data = new LightData();
        for (Tag entry : tag.getList("lights", Tag.TAG_COMPOUND)) {
            try {
                Light light = Light.read((CompoundTag) entry);
                if (data.lights.containsKey(light.id)) throw new IllegalArgumentException("Duplicate light ID");
                data.lights.put(light.id, light);
            } catch (RuntimeException e) {
                data.unreadable.add(entry.copy());
                VolumetricLight.LOG.error("Preserving unreadable light in saved data: {}", entry, e);
            }
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag entries = unreadable.copy();
        lights.values().forEach(l -> entries.add(l.write()));
        tag.put("lights", entries); tag.putInt("version", 1);
        return tag;
    }
}
