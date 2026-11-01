package com.mrmafia33.surnborn;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class SurnbornClient implements ClientModInitializer {
    private static final String CATEGORY = "key.categories.surnborn"; // "Surnborn Origin Keys"
    private static final KeyBinding[] KEYS = new KeyBinding[7];

    private static int solar = 100;
    private static int flight = 999;
    private static long lastMs = 0;

    @Override
    public void onInitializeClient() {
        String[] names = {"titan_shift_key", "release_projectile_key", "calamity_fireball_key",
                "meteor_strike_key", "whitelist_mark_key", "whitelist_unmark_key", "sun_beam_key"};
        int[] defaults = {GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_X,
                GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_R};
        for (int i = 0; i < KEYS.length; i++) {
            KEYS[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.surnborn." + names[i], InputUtil.Type.KEYSYM, defaults[i], CATEGORY));
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (int i = 0; i < KEYS.length; i++) {
                while (KEYS[i].wasPressed()) {
                    if (client.player != null) ClientPlayNetworking.send(new KeyPayload(i));
                }
            }
        });

        // Solar Reserve energy bar
        ClientPlayNetworking.registerGlobalReceiver(SolarPayload.ID, (payload, ctx) -> {
            solar = Math.max(0, Math.min(100, payload.solar()));
            flight = payload.flight();
            lastMs = System.currentTimeMillis();
        });
        HudRenderCallback.EVENT.register((g, tickCounter) -> {
            if (System.currentTimeMillis() - lastMs > 1500) return;   // hidden if you are not the Surnborn
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden || mc.player == null) return;
            int w = g.getScaledWindowWidth(), h = g.getScaledWindowHeight();
            int bw = 100, bh = 7;
            int x = w / 2 - bw / 2, y = h - 60;
            int fw = bw * solar / 100;
            g.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, 0xFF000000);   // border
            g.fill(x, y, x + bw, y + bh, 0xFF3A2A00);                   // empty part
            g.fill(x, y, x + fw, y + bh, 0xFFFFAA00);                   // orange fill
            g.fill(x, y, x + fw, y + 2, 0xFFFFD866);                    // shine
            String label = "Solar " + solar + (flight < 999 ? "  |  Flight " + flight + "s" : "");
            g.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(label), w / 2, y - 11, 0xFFFFAA00);
        });
    }
}
