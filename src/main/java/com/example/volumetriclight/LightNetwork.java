package com.example.volumetriclight;

import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class LightNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(VolumetricLight.ID, "lights"),
            () -> "1", "1"::equals, "1"::equals);
    public enum Operation { OPEN, CREATE, SAVE, DELETE }
    public record Request(Operation operation, CompoundTag data) {
        void encode(FriendlyByteBuf b) { b.writeEnum(operation); b.writeNbt(data); }
        static Request decode(FriendlyByteBuf b) { return new Request(b.readEnum(Operation.class), b.readNbt()); }
        static void handle(Request request, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) LightService.request(player, request);
            });
            context.setPacketHandled(true);
        }
    }
    public record Snapshot(CompoundTag data, boolean open, boolean success, String message, String selected) {
        void encode(FriendlyByteBuf b) {
            b.writeNbt(data); b.writeBoolean(open); b.writeBoolean(success); b.writeUtf(message, 256); b.writeUtf(selected, 48);
        }
        static Snapshot decode(FriendlyByteBuf b) { return new Snapshot(b.readNbt(), b.readBoolean(), b.readBoolean(), b.readUtf(256), b.readUtf(48)); }
        static void handle(Snapshot state, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.example.volumetriclight.client.LightClient.accept(state)));
            context.setPacketHandled(true);
        }
    }
    public record Aims(Map<String, Vec3> positions) {
        void encode(FriendlyByteBuf b) {
            b.writeVarInt(positions.size()); positions.forEach((id, p) -> { b.writeUtf(id, 48); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); });
        }
        static Aims decode(FriendlyByteBuf b) {
            int size = b.readVarInt(); Light.require(size >= 0 && size <= LightData.MAX_LIGHTS, "Invalid aim packet size");
            Map<String, Vec3> positions = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) positions.put(b.readUtf(48), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()));
            return new Aims(positions);
        }
        static void handle(Aims aims, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.example.volumetriclight.client.LightClient.acceptAims(aims.positions)));
            context.setPacketHandled(true);
        }
    }
    static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(Request::encode).decoder(Request::decode)
                .consumerNetworkThread(Request::handle).add();
        CHANNEL.messageBuilder(Snapshot.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(Snapshot::encode).decoder(Snapshot::decode)
                .consumerNetworkThread(Snapshot::handle).add();
        CHANNEL.messageBuilder(Aims.class, 2, NetworkDirection.PLAY_TO_CLIENT).encoder(Aims::encode).decoder(Aims::decode)
                .consumerNetworkThread(Aims::handle).add();
    }
    public static void send(Operation op, CompoundTag data) { CHANNEL.sendToServer(new Request(op, data)); }
    static void send(ServerPlayer player, Snapshot data) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), data); }
    static void send(ServerPlayer player, Aims data) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), data); }
}
