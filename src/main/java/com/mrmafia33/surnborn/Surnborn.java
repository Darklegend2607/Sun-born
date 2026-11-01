package com.mrmafia33.surnborn;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistryBuilder;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Surnborn implements ModInitializer {
    public static final String ID = "surnborn";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static RegistryEntry<StatusEffect> LAVA_EFFECT;
    public static RegistryEntry<Potion> LAVA_POTION;

    @Override
    public void onInitialize() {
        // Networking (custom keybinds -> server)
        PayloadTypeRegistry.playC2S().register(KeyPayload.ID, KeyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(KeyPayload.ID,
                (payload, ctx) -> Powers.onKey(ctx.player(), payload.action()));

        // Lava Potion: Awkward Potion + Lava Bucket
        LAVA_EFFECT = Registry.registerReference(Registries.STATUS_EFFECT,
                Identifier.of(ID, "lava_potion_effect"), new LavaEffect());
        LAVA_POTION = Registry.registerReference(Registries.POTION,
                Identifier.of(ID, "lava_potion"),
                new Potion("surnborn_lava", new StatusEffectInstance(LAVA_EFFECT, 5, 0)));
        FabricBrewingRecipeRegistryBuilder.BUILD.register(
                b -> b.registerPotionRecipe(Potions.AWKWARD, Items.LAVA_BUCKET, LAVA_POTION));

        // State + tick + damage
        ServerLifecycleEvents.SERVER_STARTED.register(State::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(State::save);
        ServerTickEvents.END_SERVER_TICK.register(Powers::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Powers::allowDamage);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
            if (State.isOwner(newP)) Powers.reset();
        });

        CommandRegistrationCallback.EVENT.register((d, access, env) -> registerCommands(d));
    }

    /** Drinking the Lava Potion: owner at night refills Solar Reserve, anyone else gets punished. */
    static class LavaEffect extends StatusEffect {
        LavaEffect() {
            super(StatusEffectCategory.NEUTRAL, 0xFFAA00);
        }

        @Override
        public boolean canApplyUpdateEffect(int duration, int amplifier) {
            return true;
        }

        @Override
        public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
            if (!entity.getWorld().isClient && entity instanceof ServerPlayerEntity p) {
                if (State.isOwner(p)) {
                    if (Powers.isNight(p.getServerWorld())) Powers.solar = 100;
                } else {
                    p.setOnFireFor(15);
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 300, 1));
                }
            }
            return true;
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("surnborn")
                .then(CommandManager.literal("choose").executes(c -> choose(c.getSource())))
                .then(CommandManager.literal("release").executes(c -> release(c.getSource())))
                .then(CommandManager.literal("status").executes(c -> status(c.getSource()))));
    }

    private static int choose(ServerCommandSource s) throws CommandSyntaxException {
        ServerPlayerEntity p = s.getPlayerOrThrow();
        if (State.owner != null) {
            if (State.owner.equals(p.getUuid())) {
                s.sendFeedback(() -> Text.literal("You are already the Surnborn."), false);
            } else {
                s.sendError(Text.literal("The Surnborn origin is already taken on this server."));
            }
            return 0;
        }
        State.owner = p.getUuid();
        Powers.reset();
        State.save(s.getServer());
        s.getServer().getPlayerManager().broadcast(
                Text.literal(p.getName().getString() + " has become the Surnborn!").formatted(Formatting.GOLD), false);
        return 1;
    }

    private static int release(ServerCommandSource s) {
        if (State.owner == null) {
            s.sendError(Text.literal("Nobody holds the Surnborn origin."));
            return 0;
        }
        boolean allowed = s.getEntity() == null || State.owner.equals(s.getEntity().getUuid()) || s.hasPermissionLevel(3);
        if (!allowed) {
            s.sendError(Text.literal("Only the Surnborn or an admin can release the origin."));
            return 0;
        }
        ServerPlayerEntity op = s.getServer().getPlayerManager().getPlayer(State.owner);
        if (op != null) Powers.strip(op);
        State.owner = null;
        Powers.reset();
        State.save(s.getServer());
        s.sendFeedback(() -> Text.literal("Surnborn origin released."), true);
        return 1;
    }

    private static int status(ServerCommandSource s) {
        s.sendFeedback(() -> Text.literal(State.owner == null ? "Surnborn origin is free." : "Surnborn origin is taken."), false);
        return 1;
    }
}
