package com.mrmafia33.surnborn;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: solar reserve (0-100) and night flight seconds left (999 = not applicable). */
public record SolarPayload(int solar, int flight) implements CustomPayload {
    public static final CustomPayload.Id<SolarPayload> ID = new CustomPayload.Id<>(Identifier.of(Surnborn.ID, "solar"));
    public static final PacketCodec<RegistryByteBuf, SolarPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SolarPayload::solar,
            PacketCodecs.VAR_INT, SolarPayload::flight,
            SolarPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
