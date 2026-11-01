package com.mrmafia33.surnborn;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record KeyPayload(int action) implements CustomPayload {
    public static final CustomPayload.Id<KeyPayload> ID = new CustomPayload.Id<>(Identifier.of(Surnborn.ID, "key"));
    public static final PacketCodec<ByteBuf, KeyPayload> CODEC =
            PacketCodecs.VAR_INT.xmap(KeyPayload::new, KeyPayload::action);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
