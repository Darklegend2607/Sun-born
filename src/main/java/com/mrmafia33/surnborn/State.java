package com.mrmafia33.surnborn;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** World-saved state: origin owner lock, Helios whitelist, cooling craters. */
public final class State {
    private State() {}

    public record Crater(RegistryKey<World> dim, long pos, long expiry) {}

    public static UUID owner;
    public static final Set<UUID> whitelist = new HashSet<>();
    public static final List<Crater> craters = new ArrayList<>();

    public static boolean isOwner(PlayerEntity p) {
        return owner != null && owner.equals(p.getUuid());
    }

    private static Path file(MinecraftServer s) {
        return s.getSavePath(WorldSavePath.ROOT).resolve("surnborn_state.txt");
    }

    private static String dimName(RegistryKey<World> k) {
        if (k == World.OVERWORLD) return "overworld";
        if (k == World.NETHER) return "nether";
        if (k == World.END) return "end";
        return null;
    }

    private static RegistryKey<World> dimKey(String n) {
        return switch (n) {
            case "overworld" -> World.OVERWORLD;
            case "nether" -> World.NETHER;
            case "end" -> World.END;
            default -> null;
        };
    }

    public static void load(MinecraftServer s) {
        owner = null;
        whitelist.clear();
        craters.clear();
        Path f = file(s);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] a = line.split("\\|");
                try {
                    switch (a[0]) {
                        case "owner" -> owner = UUID.fromString(a[1]);
                        case "wl" -> whitelist.add(UUID.fromString(a[1]));
                        case "crater" -> {
                            RegistryKey<World> k = dimKey(a[1]);
                            if (k != null) craters.add(new Crater(k, Long.parseLong(a[2]), Long.parseLong(a[3])));
                        }
                        default -> {}
                    }
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException e) {
            Surnborn.LOG.error("Could not read Surnborn state", e);
        }
    }

    public static void save(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        if (owner != null) out.add("owner|" + owner);
        for (UUID u : whitelist) out.add("wl|" + u);
        for (Crater c : craters) {
            String d = dimName(c.dim());
            if (d != null) out.add("crater|" + d + "|" + c.pos() + "|" + c.expiry());
        }
        try {
            Files.write(file(s), out);
        } catch (IOException e) {
            Surnborn.LOG.error("Could not write Surnborn state", e);
        }
    }
}
