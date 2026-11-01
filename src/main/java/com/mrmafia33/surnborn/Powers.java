package com.mrmafia33.surnborn;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/** All Surnborn powers. Only the single owner (State.owner) is ever ticked. */
public final class Powers {
    private Powers() {}

    static final String TAG_RELEASED = "surnborn_released";
    static final String TAG_CALAMITY = "surnborn_calamity";
    static final int CALAMITY_RADIUS = 25;                       // 50x50 sphere
    static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    static boolean titan, flightLocked, busy;
    static double solar = 100;
    static int flightTicks;
    static long titanReady, calamityReady, meteorReady, beamReady;

    record Held(EntityType<?> type, ItemStack stack) {}

    static final class Fb {
        final RegistryKey<World> dim;
        Vec3d last;
        Fb(RegistryKey<World> dim, Vec3d last) { this.dim = dim; this.last = last; }
    }

    static final class Erase {
        final ServerWorld w;
        final BlockPos c;
        int y;
        Erase(ServerWorld w, BlockPos c) { this.w = w; this.c = c; this.y = c.getY() - CALAMITY_RADIUS; }
    }

    static final List<Held> held = new ArrayList<>();
    static final Map<UUID, Fb> fireballs = new HashMap<>();
    static final Map<UUID, Fb> ghast = new HashMap<>();
    static final List<Erase> erases = new ArrayList<>();

    // ------------------------------------------------------------------ helpers

    public static boolean isNight(World w) {
        if (w.getRegistryKey() != World.OVERWORLD) return false;
        long t = w.getTimeOfDay() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    static void msg(ServerPlayerEntity p, String s) {
        p.sendMessage(Text.literal(s).formatted(Formatting.GOLD), true);
    }

    static boolean loaded(ServerWorld w, int x, int z) {
        return w.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    static void eff(ServerPlayerEntity p, RegistryEntry<StatusEffect> e, int amp) {
        p.addStatusEffect(new StatusEffectInstance(e, 30, amp, true, false, false));
    }

    static void safeDamage(LivingEntity e, DamageSource s, float a) {
        boolean old = busy;
        busy = true;
        try { e.damage(s, a); } finally { busy = old; }
    }

    static boolean friendly(ServerPlayerEntity p, LivingEntity e) {
        if (e instanceof ServerPlayerEntity sp
                && (State.whitelist.contains(sp.getUuid()) || sp.isCreative() || sp.isSpectator())) return true;
        if (e instanceof TameableEntity t && t.getOwner() != null) {
            UUID o = t.getOwner().getUuid();
            return o.equals(p.getUuid()) || State.whitelist.contains(o);
        }
        return false;
    }

    public static void reset() {
        titan = false;
        solar = 100;
        flightTicks = 0;
        flightLocked = false;
        held.clear();
        fireballs.clear();
        ghast.clear();
    }

    /** Removes owner-only modifications from a player (used on /surnborn release). */
    public static void strip(ServerPlayerEntity p) {
        EntityAttributeInstance sc = p.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (sc != null) sc.setBaseValue(1.0);
        p.removeStatusEffect(StatusEffects.STRENGTH);
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().allowFlying = false;
            p.getAbilities().flying = false;
            p.sendAbilitiesUpdate();
        }
    }

    // ------------------------------------------------------------------ main tick

    public static void tick(MinecraftServer server) {
        long now = server.getOverworld().getTime();
        tickErase();
        tickFireballs(server);
        tickCraters(server, now);
        if (State.owner != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(State.owner);
            if (p != null && p.isAlive()) tickOwner(p, now);
        }
        if (now % 10 == 0) {
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) fixPotions(sp);
        }
    }

    static void tickOwner(ServerPlayerEntity p, long now) {
        ServerWorld w = p.getServerWorld();
        boolean night = isNight(w);
        boolean nether = w.getRegistryKey() == World.NETHER;
        boolean survival = !p.isCreative() && !p.isSpectator();
        boolean inLava = p.isInLava();

        // Size: normal = 2 blocks tall, titan = 4.5x
        EntityAttributeInstance sc = p.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        double want = titan ? 4.5 : 2.0 / 1.8;
        if (sc != null && Math.abs(sc.getBaseValue() - want) > 1e-6) sc.setBaseValue(want);

        // Passive effects
        if (now % 5 == 0) {
            eff(p, StatusEffects.FIRE_RESISTANCE, 0);
            eff(p, StatusEffects.WATER_BREATHING, 0);
            eff(p, StatusEffects.DOLPHINS_GRACE, 0);
            if (titan) eff(p, StatusEffects.STRENGTH, 126);          // Strength 127
            else if (nether) eff(p, StatusEffects.STRENGTH, 1);      // Strength II in the Nether
            if (night && !titan) eff(p, StatusEffects.WEAKNESS, 1);  // night weakness in normal form
        }

        // Lava swimming + hunger
        if (inLava) {
            p.getHungerManager().setFoodLevel(20);
            p.getHungerManager().setSaturationLevel(20f);
            if (p.isSprinting()) {
                p.setVelocity(p.getRotationVec(1f).multiply(0.6));
                p.velocityModified = true;
                p.fallDistance = 0;
            }
        } else if (now % 80 == 0) {
            p.getHungerManager().add(1, 1.0f);
        }

        // Flight (creative-style), 60s cap at night until recharged by daytime or lava
        if (survival) {
            if (!night || inLava) { flightTicks = 0; flightLocked = false; }
            boolean allow = !flightLocked;
            if (p.getAbilities().allowFlying != allow) {
                p.getAbilities().allowFlying = allow;
                if (!allow) p.getAbilities().flying = false;
                p.sendAbilitiesUpdate();
            }
            if (p.getAbilities().flying) {
                w.spawnParticles(ParticleTypes.SMALL_FLAME, p.getX(), p.getY() + 0.1, p.getZ(), 3, 0.15, 0.05, 0.15, 0.01);
                if (night) {
                    flightTicks++;
                    if (flightTicks >= 1200) { flightLocked = true; msg(p, "Your flight fades with the night..."); }
                }
            }
        }

        // Crown of Helios (daytime / Nether, normal form, not underwater)
        if (!titan && !night && !p.isSubmergedInWater() && now % 2 == 0) {
            int n = nether ? 24 : 12;
            double r = nether ? 0.5 : 0.4;
            double a0 = now * 0.15;
            double y = p.getY() + 2.1;
            for (int i = 0; i < n; i++) {
                double a = a0 + Math.PI * 2 * i / n;
                w.spawnParticles(ParticleTypes.FLAME, p.getX() + Math.cos(a) * r, y, p.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
            if (nether) w.spawnParticles(ParticleTypes.LAVA, p.getX(), y + 0.2, p.getZ(), 1, 0.3, 0.1, 0.3, 0);
        }

        // Solar Reserve (no daylight regen at night)
        if (!night) solar = Math.min(100, solar + 0.25);
        if (now % 10 == 0) {
            String s = "Solar Reserve " + (int) solar + "/100";
            if (night && p.getAbilities().flying) s += "   Flight " + Math.max(0, (1200 - flightTicks) / 20) + "s";
            msg(p, s);
        }

        capture(p, w);
        hover(p, w, now);
        trackGhast(p, w);
    }

    // ------------------------------------------------------------------ Chronostasis

    static boolean canCapture(ServerPlayerEntity p, ProjectileEntity e) {
        if (e.isRemoved() || e instanceof FishingBobberEntity || e instanceof FireworkRocketEntity) return false;
        if (e.getCommandTags().contains(TAG_RELEASED) || e.getCommandTags().contains(TAG_CALAMITY)) return false;
        if (e instanceof FireballEntity f && f.getOwner() instanceof GhastEntity) return false;
        Entity o = e.getOwner();
        if (o != null && (o.getUuid().equals(p.getUuid()) || State.whitelist.contains(o.getUuid()))) return false;
        Vec3d v = e.getVelocity();
        if (v.lengthSquared() < 0.01) return false;
        return v.dotProduct(p.getEyePos().subtract(e.getPos())) > 0;   // incoming only
    }

    static void capture(ServerPlayerEntity p, ServerWorld w) {
        for (ProjectileEntity e : w.getEntitiesByClass(ProjectileEntity.class, p.getBoundingBox().expand(6.0), x -> canCapture(p, x))) {
            Vec3d pos = e.getPos();
            if (held.size() < 8) {
                ItemStack st = e instanceof ThrownItemEntity t ? t.getStack().copy() : ItemStack.EMPTY;
                held.add(new Held(e.getType(), st));
                w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 2f);
            }
            e.discard();
            burst(w, pos);
        }
    }

    static void burst(ServerWorld w, Vec3d pos) {
        w.spawnParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 12, 0.3, 0.3, 0.3, 0.05);
        w.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 16, 0.3, 0.3, 0.3, 0.1);
    }

    /** Local coordinates ^0.3 ^-0.2 ^0.5 (left, up, forward) from the eyes. */
    static Vec3d anchor(ServerPlayerEntity p, int i) {
        Vec3d look = p.getRotationVec(1f).normalize();
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        double s = p.getScale();
        return p.getEyePos()
                .add(right.multiply(-0.3 * s))
                .add(up.multiply((-0.2 + i * 0.12) * s))
                .add(look.multiply(0.5 * s));
    }

    static void hover(ServerPlayerEntity p, ServerWorld w, long now) {
        if (held.isEmpty()) return;
        if (now % 2 == 0) {
            for (int i = 0; i < held.size(); i++) {
                Vec3d a = anchor(p, i);
                w.spawnParticles(ParticleTypes.END_ROD, a.x, a.y, a.z, 2, 0.05, 0.05, 0.05, 0.0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, a.x, a.y, a.z, 1, 0.1, 0.1, 0.1, 0.0);
                w.spawnParticles(ParticleTypes.FLAME, a.x, a.y, a.z, 1, 0.12, 0.12, 0.12, 0.0);
            }
        }
        // Julius Novachrono "Chronostasis" hum: conduit + lodestone ticking
        if (now % 8 == 0)
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CONDUIT_AMBIENT_SHORT, SoundCategory.PLAYERS, 1f, 2f);
        if (now % 4 == 0)
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_LODESTONE_HIT, SoundCategory.PLAYERS, 0.5f, 2f);
    }

    static void release(ServerPlayerEntity p) {
        if (held.isEmpty()) { msg(p, "No projectile held"); return; }
        ServerWorld w = p.getServerWorld();
        Vec3d look = p.getRotationVec(1f);
        int i = 0;
        for (Held h : held) {
            Entity e = h.type().create(w);
            if (!(e instanceof ProjectileEntity pe)) continue;
            pe.setPosition(anchor(p, i++));
            pe.setOwner(p);
            pe.setVelocity(look.x, look.y, look.z, 3.0f, 0f);
            pe.setNoGravity(true);
            pe.addCommandTag(TAG_RELEASED);                       // 5x damage applied in allowDamage
            if (pe instanceof ThrownItemEntity t && !h.stack().isEmpty()) t.setItem(h.stack());
            if (pe instanceof PersistentProjectileEntity ppe) {
                ppe.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
                ppe.setCritical(true);
            }
            w.spawnEntity(pe);
        }
        held.clear();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 1.5f);
    }

    // ------------------------------------------------------------------ Ghast blast multiplier

    static void trackGhast(ServerPlayerEntity p, ServerWorld w) {
        for (FireballEntity f : w.getEntitiesByClass(FireballEntity.class, p.getBoundingBox().expand(24),
                x -> x.getOwner() instanceof GhastEntity)) {
            if (f.getVelocity().dotProduct(p.getPos().subtract(f.getPos())) > 0)
                ghast.put(f.getUuid(), new Fb(w.getRegistryKey(), f.getPos()));
        }
        Iterator<Map.Entry<UUID, Fb>> it = ghast.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Fb> en = it.next();
            Fb fb = en.getValue();
            if (fb.dim != w.getRegistryKey()) { it.remove(); continue; }
            Entity e = w.getEntity(en.getKey());
            if (e != null && !e.isRemoved()) { fb.last = e.getPos(); continue; }
            it.remove();
            if (fb.last.distanceTo(p.getPos()) < 6)
                w.createExplosion(null, fb.last.x, fb.last.y, fb.last.z, 6.0f, World.ExplosionSourceType.MOB);
        }
    }

    // ------------------------------------------------------------------ keys

    public static void onKey(ServerPlayerEntity p, int action) {
        if (!State.isOwner(p) || !p.isAlive()) return;
        long now = p.getServer().getOverworld().getTime();
        switch (action) {
            case 0 -> toggleTitan(p, now);
            case 1 -> release(p);
            case 2 -> calamity(p, now);
            case 3 -> meteor(p, now);
            case 4 -> mark(p, true);
            case 5 -> mark(p, false);
            case 6 -> sunBeam(p, now);
            default -> {}
        }
    }

    static void toggleTitan(ServerPlayerEntity p, long now) {
        if (now < titanReady) return;
        titanReady = now + 20;
        titan = !titan;
        if (titan) eff(p, StatusEffects.STRENGTH, 126);
        else p.removeStatusEffect(StatusEffects.STRENGTH);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.8f, titan ? 0.6f : 1.2f);
        msg(p, titan ? "Titan Form" : "Normal Form");
    }

    static void sunBeam(ServerPlayerEntity p, long now) {
        if (now < beamReady) return;
        ServerWorld w = p.getServerWorld();
        if (isNight(w)) {
            if (solar < 20) { msg(p, "Not enough Solar Reserve"); return; }
            solar -= 20;
        }
        beamReady = now + 6;
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        HitResult hr = p.raycast(48.0, 1f, false);
        double dist = hr.getPos().distanceTo(eye);
        for (double d = 1; d <= dist; d += 0.5) {
            Vec3d pt = eye.add(look.multiply(d)).add(0, -0.2, 0);
            w.spawnParticles(ParticleTypes.END_ROD, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.FLAME, pt.x, pt.y, pt.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
        Box b = new Box(eye, eye.add(look.multiply(dist))).expand(1.5);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, b, x -> x != p && x.isAlive() && !friendly(p, x))) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double proj = to.dotProduct(look);
            if (proj < 0 || proj > dist + 1) continue;
            if (to.subtract(look.multiply(proj)).length() > 1.0 + e.getWidth() / 2) continue;
            safeDamage(e, w.getDamageSources().indirectMagic(p, p), titan ? 40f : 14f);
            e.setOnFireFor(6);
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 2f);
    }

    static void calamity(ServerPlayerEntity p, long now) {
        if (now < calamityReady) { msg(p, "Calamity Fireball recharging: " + ((calamityReady - now) / 20) + "s"); return; }
        calamityReady = now + 2400;                               // 2 minute cooldown
        ServerWorld w = p.getServerWorld();
        Vec3d look = p.getRotationVec(1f);
        FireballEntity f = EntityType.FIREBALL.create(w);
        if (f == null) return;
        f.setOwner(p);
        f.setPosition(p.getEyePos().add(look.multiply(1.5 * p.getScale())));
        f.setVelocity(look.multiply(1.2));
        f.addCommandTag(TAG_CALAMITY);
        w.spawnEntity(f);
        fireballs.put(f.getUuid(), new Fb(w.getRegistryKey(), f.getPos()));
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_GHAST_SHOOT, SoundCategory.PLAYERS, 1.5f, 0.6f);
    }

    static void meteor(ServerPlayerEntity p, long now) {
        if (now < meteorReady) return;
        HitResult hr = p.raycast(160.0, 1f, false);
        if (hr.getType() != HitResult.Type.BLOCK) { msg(p, "No target in range"); return; }
        meteorReady = now + 60;
        ServerWorld w = p.getServerWorld();
        BlockPos tgt = ((BlockHitResult) hr).getBlockPos();

        // Cut a 3x3 shaft from the sky down to the target (through cave roofs)
        int top = Math.min(w.getTopY() - 1, tgt.getY() + 128);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = tgt.getY() + 1; y <= top; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int x = tgt.getX() + dx, z = tgt.getZ() + dz;
                    if (!loaded(w, x, z)) continue;
                    m.set(x, y, z);
                    BlockState s = w.getBlockState(m);
                    if (!s.isAir() && !s.isOf(Blocks.BEDROCK)) w.setBlockState(m, Blocks.AIR.getDefaultState(), FLAGS);
                }
        for (int y = top; y >= tgt.getY(); y -= 2) {
            w.spawnParticles(ParticleTypes.FLAME, tgt.getX() + 0.5, y, tgt.getZ() + 0.5, 4, 0.5, 0.3, 0.5, 0.02);
            w.spawnParticles(ParticleTypes.END_ROD, tgt.getX() + 0.5, y, tgt.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.0);
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, tgt.getX() + 0.5, tgt.getY() + 1, tgt.getZ() + 0.5, 3, 2, 1, 2, 0);
        w.playSound(null, tgt.getX(), tgt.getY(), tgt.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 4f, 0.5f);
        w.playSound(null, tgt.getX(), tgt.getY(), tgt.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 4f, 0.6f);

        // 600 true magic damage (owner, whitelisted players and their pets are immune)
        Box b = new Box(tgt).expand(8);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, b, x -> x != p && x.isAlive() && !friendly(p, x))) {
            safeDamage(e, w.getDamageSources().indirectMagic(p, p), 600f);
            if (e.isAlive()) e.kill();
        }

        // Magma crater with fire; cools to stone after exactly 2 minutes (see tickCraters)
        int R = 5;
        for (int dx = -R; dx <= R; dx++)
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > R * R) continue;
                int x = tgt.getX() + dx, z = tgt.getZ() + dz;
                if (!loaded(w, x, z)) continue;
                for (int y = tgt.getY() + 3; y >= tgt.getY() - 3; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = w.getBlockState(pos);
                    if (s.isAir() || !s.getFluidState().isEmpty() || s.isOf(Blocks.BEDROCK) || !s.isSolidBlock(w, pos)) continue;
                    BlockPos up = pos.up();
                    if (!w.getBlockState(up).isAir()) continue;
                    w.setBlockState(pos, Blocks.MAGMA_BLOCK.getDefaultState());
                    w.setBlockState(up, Blocks.FIRE.getDefaultState());
                    State.craters.add(new State.Crater(w.getRegistryKey(), pos.asLong(), now + 2400));
                    break;
                }
            }
        State.save(p.getServer());
    }

    static void mark(ServerPlayerEntity p, boolean add) {
        Vec3d s = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        Vec3d e = s.add(look.multiply(50));
        EntityHitResult r = ProjectileUtil.raycast(p, s, e, p.getBoundingBox().stretch(look.multiply(50)).expand(1.0),
                x -> x instanceof ServerPlayerEntity && x != p && !x.isSpectator(), 50.0 * 50.0);
        if (r == null || !(r.getEntity() instanceof ServerPlayerEntity t)) { msg(p, "Look at a player"); return; }
        ServerWorld w = t.getServerWorld();
        String name = t.getName().getString();
        if (add) {
            State.whitelist.add(t.getUuid());
            w.spawnParticles(ParticleTypes.HEART, t.getX(), t.getY() + t.getHeight() + 0.3, t.getZ(), 10, 0.4, 0.3, 0.4, 0.0);
            msg(p, name + " is now meteor-immune");
        } else {
            State.whitelist.remove(t.getUuid());
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, t.getX(), t.getY() + t.getHeight() + 0.3, t.getZ(), 10, 0.4, 0.3, 0.4, 0.02);
            msg(p, name + " removed from the whitelist");
        }
        State.save(p.getServer());
    }

    // ------------------------------------------------------------------ calamity / craters

    static void tickFireballs(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Fb>> it = fireballs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Fb> en = it.next();
            Fb fb = en.getValue();
            ServerWorld w = server.getWorld(fb.dim);
            Entity e = w == null ? null : w.getEntity(en.getKey());
            if (e != null && !e.isRemoved()) { fb.last = e.getPos(); continue; }
            it.remove();
            if (w != null) erases.add(new Erase(w, BlockPos.ofFloored(fb.last)));
        }
    }

    /** Erases the 25-radius sphere a few layers per tick to avoid a lag spike. */
    static void tickErase() {
        Iterator<Erase> it = erases.iterator();
        while (it.hasNext()) {
            Erase t = it.next();
            for (int n = 0; n < 3 && t.y <= t.c.getY() + CALAMITY_RADIUS; n++, t.y++) eraseLayer(t);
            if (t.y > t.c.getY() + CALAMITY_RADIUS) it.remove();
        }
    }

    static void eraseLayer(Erase t) {
        int R = CALAMITY_RADIUS;
        int dy = t.y - t.c.getY();
        int rem = R * R - dy * dy;
        if (rem < 0 || t.y < t.w.getBottomY() || t.y >= t.w.getTopY()) return;
        BlockPos.Mutable m = new BlockPos.Mutable();
        int lcx = Integer.MIN_VALUE, lcz = Integer.MIN_VALUE;
        boolean ok = false;
        for (int x = -R; x <= R; x++)
            for (int z = -R; z <= R; z++) {
                if (x * x + z * z > rem) continue;
                int bx = t.c.getX() + x, bz = t.c.getZ() + z;
                int cx = bx >> 4, cz = bz >> 4;
                if (cx != lcx || cz != lcz) { lcx = cx; lcz = cz; ok = t.w.getChunkManager().isChunkLoaded(cx, cz); }
                if (!ok) continue;
                m.set(bx, t.y, bz);
                BlockState s = t.w.getBlockState(m);
                if (s.isAir() || s.isOf(Blocks.BEDROCK)) continue;
                t.w.setBlockState(m, Blocks.AIR.getDefaultState(), FLAGS);
            }
    }

    /** Replaces the old .mcfunction tick loop: magma -> stone after exactly 2 minutes. */
    static void tickCraters(MinecraftServer server, long now) {
        if (State.craters.isEmpty()) return;
        boolean changed = false;
        Iterator<State.Crater> it = State.craters.iterator();
        while (it.hasNext()) {
            State.Crater c = it.next();
            if (now < c.expiry()) continue;
            ServerWorld w = server.getWorld(c.dim());
            if (w == null) continue;
            BlockPos pos = BlockPos.fromLong(c.pos());
            if (!loaded(w, pos.getX(), pos.getZ())) continue;
            if (w.getBlockState(pos).isOf(Blocks.MAGMA_BLOCK)) w.setBlockState(pos, Blocks.STONE.getDefaultState());
            if (w.getBlockState(pos.up()).isOf(Blocks.FIRE)) w.setBlockState(pos.up(), Blocks.AIR.getDefaultState());
            w.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.01);
            it.remove();
            changed = true;
        }
        if (changed) State.save(server);
    }

    // ------------------------------------------------------------------ Lava Potion stacking

    static boolean isLavaPotion(ItemStack s) {
        if (!s.isOf(Items.POTION)) return false;
        PotionContentsComponent pc = s.get(DataComponentTypes.POTION_CONTENTS);
        return pc != null && pc.potion().isPresent() && pc.potion().get().matchesId(Identifier.of(Surnborn.ID, "lava_potion"));
    }

    /** Makes Lava Potions gold-named and stackable to 64. */
    static void fixPotions(ServerPlayerEntity sp) {
        PlayerInventory inv = sp.getInventory();
        ItemStack first = null;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!isLavaPotion(s)) continue;
            if (!s.contains(DataComponentTypes.MAX_STACK_SIZE)) {
                s.set(DataComponentTypes.MAX_STACK_SIZE, 64);
                s.set(DataComponentTypes.CUSTOM_NAME,
                        Text.literal("Lava Potion").setStyle(Style.EMPTY.withColor(Formatting.GOLD).withItalic(false)));
            }
            if (first == null || first.getCount() >= 64) { first = s; continue; }
            int mv = Math.min(64 - first.getCount(), s.getCount());
            first.increment(mv);
            s.decrement(mv);
        }
    }

    // ------------------------------------------------------------------ damage rules

    static boolean isMagicFire(DamageSource s) {
        String n = s.getName().toLowerCase(Locale.ROOT);
        return n.contains("fire") || n.contains("magic") || n.contains("sun") || n.contains("spell");
    }

    static void redo(LivingEntity e, DamageSource s, float a) {
        boolean old = busy;
        busy = true;
        try { e.damage(s, a); } finally { busy = old; }
    }

    public static boolean allowDamage(LivingEntity entity, DamageSource src, float amount) {
        if (busy || State.owner == null) return true;
        Entity atk = src.getAttacker();
        Entity direct = src.getSource();

        // Outgoing: released projectiles x5, fire/magic/sun/spell damage x10
        if (atk instanceof ServerPlayerEntity ap && State.isOwner(ap) && entity != ap) {
            float mult = 1f;
            if (direct != null && direct.getCommandTags().contains(TAG_RELEASED)) mult *= 5f;
            if (isMagicFire(src)) mult *= 10f;
            if (mult > 1f) { redo(entity, src, amount * mult); return false; }
        }

        // Incoming: owner protections
        if (entity instanceof ServerPlayerEntity pl && State.isOwner(pl)) {
            if (src.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;

            if (direct instanceof ProjectileEntity pe
                    && !(pe instanceof FireballEntity f && f.getOwner() instanceof GhastEntity)) {
                Entity o = pe.getOwner();
                if (o == null || !o.getUuid().equals(pl.getUuid())) {
                    burst(pl.getServerWorld(), pl.getPos().add(0, pl.getHeight() / 2, 0));
                    return false;                                  // 360 degree projectile immunity
                }
            }
            if (atk != null && atk.getUuid().equals(pl.getUuid()) && src.isIn(DamageTypeTags.IS_EXPLOSION)) return false;

            float r = titan ? 0.99f : 0.24f;
            redo(pl, src, amount * (1f - r));
            return false;
        }
        return true;
    }
}
