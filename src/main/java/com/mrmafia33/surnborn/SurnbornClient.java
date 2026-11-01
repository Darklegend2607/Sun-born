package com.mrmafia33.surnborn;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class SurnbornClient implements ClientModInitializer {
    private static final String CATEGORY = "key.categories.surnborn"; // "Surnborn Origin Keys"
    private static final KeyBinding[] KEYS = new KeyBinding[7];

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
    }
}
