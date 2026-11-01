package com.mrmafia33.surnborn.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keeps the player model the normal size inside the inventory box when scaled up (Titan form). */
@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {
    @ModifyVariable(method = "drawEntity", at = @At("HEAD"), argsOnly = true, ordinal = 4, require = 0)
    private static int surnborn$fitModel(int size) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return size;
        float scale = mc.player.getScale();
        if (scale <= 1.0f) return size;
        return Math.max(1, Math.round(size / scale));
    }
}
