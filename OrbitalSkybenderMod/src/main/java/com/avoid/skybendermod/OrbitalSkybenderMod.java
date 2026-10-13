package com.avoid.skybendermod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class OrbitalSkybenderMod implements ModInitializer {
    public static final String MOD_ID = "orbital_skybender";
    private static final String OWNER = "avoidmyexplosive";
    private final Set<String> allowed = new HashSet<>();
    private final Map<UUID, AwakeningState> awakenings = new HashMap<>();
    private final Map<UUID, Long> awakeningCooldowns = new HashMap<>();
    private final Map<UUID, OrbitState> bends = new HashMap<>();
    private final Map<UUID, GravityState> gravity = new HashMap<>();
    private final Map<UUID, WaterState> water = new HashMap<>();
    private final Map<UUID, LaserState> lasers = new HashMap<>();
    private boolean cooldownBypass;
    private boolean awakeningDurationBypass;
    private Path allowFile;

    @Override
    public void onInitialize() {
        loadAllowed();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("skybender")
                .executes(ctx -> { sendHelp(ctx.getSource()); return 1; })
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> suggest(ctx.getSource(), builder))
                    .executes(ctx -> execute(ctx.getSource(),
                        StringArgumentType.getString(ctx, "arguments"))))));

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp && gravity.containsKey(sp.getUUID())) {
                launchNextGravityBlock(sp);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || !isOwner(p)) return true;
            UUID id = p.getUUID();
            if (!bends.containsKey(id) && !lasers.containsKey(id)) return true;
            return !(source.getDirectEntity() instanceof PrimedTnt || source.getEntity() instanceof PrimedTnt);
        });
        System.out.println("[OrbitalSkybender] Fabric mod loaded.");
    }

    private void loadAllowed() {
        allowed.add(OWNER);
        allowFile = FabricLoader.getInstance().getConfigDir().resolve("orbital-skybender-allowed.txt");
        try {
            if (Files.exists(allowFile)) {
                for (String line : Files.readAllLines(allowFile, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) allowed.add(line.trim().toLowerCase(Locale.ROOT));
                }
            } else saveAllowed();
        } catch (IOException e) {
            System.err.println("[OrbitalSkybender] Could not load allowed players: " + e.getMessage());
        }
    }

    private void saveAllowed() {
        if (allowFile == null) return;
        try {
            Files.createDirectories(allowFile.getParent());
            List<String> names = new ArrayList<>(allowed);
            names.sort(String.CASE_INSENSITIVE_ORDER);
            Files.write(allowFile, names, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[OrbitalSkybender] Could not save allowed players: " + e.getMessage());
        }
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            CommandSourceStack source, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase(Locale.ROOT);
        String[] parts = input.split(" ", -1);
        List<String> options = new ArrayList<>();
        if (parts.length <= 1) {
            // Keep admin and awakening executable, but intentionally hide them from root suggestions.
            options.addAll(List.of("bend", "fire", "target", "allow", "remove", "list", "permission",
                "info", "laser", "gravity", "ultra-nuke", "ultra-stab", "ultra-laser", "ultra-wall", "water"));
        } else if (parts[0].equals("fire")) {
            options.addAll(List.of("fire nuke", "fire stab", "fire laser", "fire wall"));
        } else if (parts[0].equals("target")) {
            options.addAll(List.of("target nuke", "target stab"));
        } else if (parts[0].equals("bend")) {
            options.add("bend cinematic");
        } else if (parts[0].equals("admin") && source.getPlayer() != null && isOwner(source.getPlayer())) {
            options.addAll(List.of("admin enable", "admin cooldownbypass", "admin awakeningdurationbypass"));
        }
        for (String option : options) if (option.startsWith(input)) builder.suggest(option);
        return builder.buildFuture();
    }

    private int execute(CommandSourceStack source, String raw) {
        if (raw.isBlank()) { sendHelp(source); return 1; }
        String[] args = raw.trim().split("\\s+");
        String cmd = args[0].toLowerCase(Locale.ROOT);
        ServerPlayer player = source.getPlayer();

        if (cmd.equals("admin")) {
            if (player == null || !isOwner(player)) { fail(source, "Only AvoidMyExplosive can use OBS admin commands."); return 0; }
            if (args.length < 2) { fail(source, "Usage: /skybender admin <enable|cooldownbypass|awakeningdurationbypass>"); return 0; }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "enable" -> sendAdminCommandList(source);
                case "cooldownbypass" -> { cooldownBypass = !cooldownBypass; tell(source, "Cooldown bypass: " + onOff(cooldownBypass)); }
                case "awakeningdurationbypass" -> { awakeningDurationBypass = !awakeningDurationBypass; tell(source, "Awakening duration bypass: " + onOff(awakeningDurationBypass)); }
                default -> fail(source, "Usage: /skybender admin <enable|cooldownbypass|awakeningdurationbypass>");
            }
            return 1;
        }

        if (player == null) { fail(source, "This command requires a player."); return 0; }
        if (!allowed.contains(player.getName().getString().toLowerCase(Locale.ROOT))) {
            fail(source, "The Orbital Skybender does not answer to you."); return 0;
        }
        switch (cmd) {
            case "awakening" -> toggleAwakening(player);
            case "laser" -> startLaser(player, 100.0D, 100);
            case "bend" -> toggleBend(player, args.length > 1 && args[1].equalsIgnoreCase("cinematic"));
            case "fire" -> {
                if (args.length < 2) { fail(source, "Usage: /skybender fire <nuke|stab|laser|wall>"); return 0; }
                runNormalAbility(player, args[1].toLowerCase(Locale.ROOT));
            }
            case "target" -> {
                if (args.length < 3) { fail(source, "Usage: /skybender target <nuke|stab> <player>"); return 0; }
                ServerPlayer target = findPlayer(player.serverLevel().getServer(), args[2]);
                if (target == null) { fail(source, "Player not found or offline."); return 0; }
                Vec3 point = Vec3.atCenterOf(target.blockPosition());
                if (args[1].equalsIgnoreCase("nuke")) spawnNuke(target.serverLevel(), point, player, 1.0D);
                else if (args[1].equalsIgnoreCase("stab")) spawnStab(target.serverLevel(), point, player, false);
                else { fail(source, "Use target nuke or target stab."); return 0; }
                tell(source, "Target locked: " + target.getName().getString());
            }
            case "gravity" -> { if (!requireAwakening(player)) return 0; toggleGravity(player); }
            case "ultra-nuke" -> {
                if (!requireAwakening(player)) return 0;
                spawnNuke(player.serverLevel(), lookTarget(player, 55.0D), player, 2.0D);
                tell(player, "ULTRA NUKE launched: double-radius pattern.");
            }
            case "ultra-stab" -> { if (!requireAwakening(player)) return 0; spawnStab(player.serverLevel(), lookTarget(player, 45.0D), player, true); }
            case "ultra-laser" -> { if (!requireAwakening(player)) return 0; startLaser(player, 100.0D, 100); }
            case "ultra-wall" -> { if (!requireAwakening(player)) return 0; spawnWall(player, true); }
            case "water" -> { if (!requireAwakening(player)) return 0; toggleWater(player); }
            case "allow" -> {
                if (!isOwner(player)) { fail(source, "Only AvoidMyExplosive can change the allowed list."); return 0; }
                if (args.length < 2) { fail(source, "Usage: /skybender allow <player>"); return 0; }
                allowed.add(args[1].toLowerCase(Locale.ROOT)); saveAllowed(); tell(source, args[1] + " can now use OBS.");
            }
            case "remove" -> {
                if (!isOwner(player)) { fail(source, "Only AvoidMyExplosive can change the allowed list."); return 0; }
                if (args.length < 2) { fail(source, "Usage: /skybender remove <player>"); return 0; }
                if (args[1].equalsIgnoreCase(OWNER)) { fail(source, "AvoidMyExplosive cannot be removed."); return 0; }
                allowed.remove(args[1].toLowerCase(Locale.ROOT)); saveAllowed(); tell(source, args[1] + " was removed from OBS.");
            }
            case "list", "permission" -> tell(source, "Allowed: " + String.join(", ", allowed));
            case "info" -> tell(source, "OrbitalSkybender Fabric Mod 4.0.0 for Minecraft 1.21.11.");
            default -> { fail(source, "Unknown command. Use /skybender for the command list."); return 0; }
        }
        return 1;
    }

    private void sendHelp(CommandSourceStack source) {
        tell(source, "§6=== OrbitalSkybender ===");
        tell(source, "/skybender bend [cinematic]");
        tell(source, "/skybender laser — aimable beam, 100-block range, 5 seconds");
        tell(source, "/skybender fire <nuke|stab|laser|wall>");
        tell(source, "/skybender target <nuke|stab> <player>");
        tell(source, "/skybender gravity — lift blocks; left-click to launch");
        tell(source, "/skybender ultra-nuke | ultra-stab | ultra-laser | ultra-wall");
        tell(source, "/skybender water — orbiting axolotls");
        tell(source, "/skybender awakening — OBS transformation");
        tell(source, "/skybender allow/remove <player>, /skybender list, /skybender permission");
        tell(source, "Owner admin commands: /skybender admin enable");
    }

    private void sendAdminCommandList(CommandSourceStack source) {
        tell(source, "§c=== ALL OBS FABRIC COMMANDS ===");
        tell(source, "/skybender bend [cinematic]");
        tell(source, "/skybender laser — aimable beam, 100-block range, 5 seconds");
        tell(source, "/skybender fire <nuke|stab|laser|wall>");
        tell(source, "/skybender target <nuke|stab> <player>");
        tell(source, "/skybender awakening");
        tell(source, "/skybender gravity");
        tell(source, "/skybender ultra-nuke");
        tell(source, "/skybender ultra-stab");
        tell(source, "/skybender ultra-laser");
        tell(source, "/skybender ultra-wall");
        tell(source, "/skybender water");
        tell(source, "/skybender allow <player> | remove <player> | list | permission | info");
        tell(source, "/skybender admin cooldownbypass");
        tell(source, "/skybender admin awakeningdurationbypass");
    }

    private void runNormalAbility(ServerPlayer player, String ability) {
        switch (ability) {
            case "nuke" -> spawnNuke(player.serverLevel(), lookTarget(player, 45.0D), player, 1.0D);
            case "stab" -> spawnStab(player.serverLevel(), lookTarget(player, 35.0D), player, false);
            case "laser" -> startLaser(player, 100.0D, 100);
            case "wall" -> spawnWall(player, false);
            default -> tell(player, "Usage: /skybender fire <nuke|stab|laser|wall>");
        }
    }

    private boolean requireAwakening(ServerPlayer player) {
        if (awakenings.containsKey(player.getUUID())) return true;
        tell(player, "Activate /skybender awakening first.");
        return false;
    }

    private void toggleAwakening(ServerPlayer player) {
        UUID id = player.getUUID();
        if (awakenings.containsKey(id)) {
            endAwakening(id, true, player);
            tell(player, "Awakening ended. Cooldown starts now.");
            return;
        }
        long now = System.currentTimeMillis();
        long ready = awakeningCooldowns.getOrDefault(id, 0L);
        if (!(cooldownBypass && isOwner(player)) && now < ready) {
            tell(player, "Awakening recharging: " + ((ready - now + 999L) / 1000L) + "s.");
            return;
        }
        ServerLevel level = player.serverLevel();
        List<BlockDisplay> displays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2.0D;
            BlockDisplay display = spawnTntDisplay(level, player.getX() + Math.cos(angle) * 1.7D,
                player.getY() + 1.1D, player.getZ() + Math.sin(angle) * 1.7D);
            if (display != null) displays.add(display);
        }
        awakenings.put(id, new AwakeningState(level, displays, 1200));
        level.playSound(null, player.blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.8F, 0.65F);
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 1.0D, player.getZ(),
            55, 0.8D, 0.8D, 0.8D, 0.05D);
        tell(player, "§c§lOBS AWAKENING §6activated for 60 seconds!");
    }

    private void endAwakening(UUID id, boolean applyCooldown, ServerPlayer player) {
        AwakeningState state = awakenings.remove(id);
        if (state != null) removeDisplays(state.level, state.displays);
        cleanupGravity(id);
        cleanupWater(id);
        if (applyCooldown && !(cooldownBypass && player != null && isOwner(player))) {
            awakeningCooldowns.put(id, System.currentTimeMillis() + 120_000L);
        }
    }

    private void toggleBend(ServerPlayer player, boolean cinematic) {
        UUID id = player.getUUID();
        OrbitState old = bends.remove(id);
        if (old != null) {
            removeDisplays(old.level, old.displays);
            tell(player, "TNT orbit disabled.");
            return;
        }
        ServerLevel level = player.serverLevel();
        List<BlockDisplay> displays = new ArrayList<>();
        int count = cinematic ? 12 : 8;
        double radius = cinematic ? 3.0D : 1.8D;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            BlockDisplay display = spawnTntDisplay(level, player.getX() + Math.cos(angle) * radius,
                player.getY() + 1.2D, player.getZ() + Math.sin(angle) * radius);
            if (display != null) displays.add(display);
        }
        bends.put(id, new OrbitState(level, displays, cinematic));
        tell(player, cinematic ? "Cinematic TNT orbit enabled." : "TNT orbit enabled.");
    }

    private void spawnNuke(ServerLevel level, Vec3 target, ServerPlayer owner, double scale) {
        double topY = Math.min(target.y + 34.0D, level.getMaxY() - 3.0D);
        for (int i = 0; i < 6; i++) {
            spawnTnt(level, target.x, Math.min(topY + i, level.getMaxY() - 2.0D), target.z,
                owner, 65, new Vec3(0.0D, -1.5D, 0.0D));
        }
        for (int ring = 1; ring <= 10; ring++) {
            double radius = ring * 4.0D * scale;
            int count = Math.max(8, (int) Math.round(Math.PI * 2.0D * radius / 2.5D));
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2.0D * i / count;
                spawnTnt(level, target.x + Math.cos(angle) * radius, topY,
                    target.z + Math.sin(angle) * radius, owner, 65 + ring * 2,
                    new Vec3(0.0D, -1.5D, 0.0D));
            }
        }
        level.playSound(null, BlockPos.containing(target.x, target.y, target.z),
            SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 5.0F, 0.55F);
    }

    private void spawnStab(ServerLevel level, Vec3 target, ServerPlayer owner, boolean ultra) {
        int count = ultra ? 64 : 24;
        double top = Math.min(target.y + (ultra ? 30.0D : 20.0D), level.getMaxY() - 2.0D);
        for (int i = 0; i < count; i++) {
            int layer = i % (ultra ? 16 : 12);
            int ring = i / (ultra ? 16 : 12);
            double angle = i * 2.399963229728653D;
            double radius = ultra ? 0.6D + ring * 0.7D : ring * 0.45D;
            double x = target.x + Math.cos(angle) * radius;
            double z = target.z + Math.sin(angle) * radius;
            double y = Math.max(level.getMinY() + 3.0D, top - layer * (ultra ? 1.6D : 2.0D));
            spawnTnt(level, x, y, z, owner, ultra ? 55 : 65, new Vec3(0.0D, -1.3D, 0.0D));
        }
        level.playSound(null, BlockPos.containing(target.x, target.y, target.z),
            SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 3.0F, 0.7F);
        tell(owner, ultra ? "ULTRA STAB: enhanced TNT barrage launched." : "TNT stab barrage launched.");
    }

    private void startLaser(ServerPlayer player, double range, int durationTicks) {
        lasers.put(player.getUUID(), new LaserState(range, durationTicks));
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT,
            SoundSource.PLAYERS, 2.0F, 0.8F);
        tell(player, (range > 50 ? "ULTRA LASER" : "Laser") + " active: " + (durationTicks / 20)
            + " seconds, " + (int) range + " blocks. Aim to steer it.");
    }

    private void spawnWall(ServerPlayer player, boolean ultra) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 right = new Vec3(-direction.z, 0.0D, direction.x);
        if (right.lengthSqr() < 1.0E-4D) right = new Vec3(1.0D, 0.0D, 0.0D);
        right = right.normalize();
        int halfWidth = ultra ? 4 : 2;
        int height = ultra ? 5 : 3;
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int y = 0; y < height; y++) {
                Vec3 at = eye.add(direction.scale(3.0D)).add(right.scale(x * 1.15D)).add(0.0D, y * 1.1D, 0.0D);
                PrimedTnt tnt = spawnTnt(level, at.x, at.y, at.z, player, 55,
                    direction.scale(ultra ? 1.25D : 0.9D));
                tnt.setNoGravity(true);
            }
        }
        tell(player, ultra ? "ULTRA WALL launched." : "TNT wall launched.");
    }

    private void toggleWater(ServerPlayer player) {
        UUID id = player.getUUID();
        WaterState old = water.remove(id);
        if (old != null) {
            for (Axolotl axolotl : old.axolotls) if (axolotl.isAlive()) axolotl.discard();
            tell(player, "Axolotl orbit disabled.");
            return;
        }
        ServerLevel level = player.serverLevel();
        List<Axolotl> axolotls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Axolotl axolotl = EntityType.AXOLOTL.create(level, EntitySpawnReason.COMMAND);
            if (axolotl == null) continue;
            axolotl.setPos(player.getX(), player.getY() + 0.8D, player.getZ());
            axolotl.setNoAi(true);
            axolotl.setNoGravity(true);
            axolotl.setInvulnerable(true);
            axolotl.setSilent(true);
            level.addFreshEntity(axolotl);
            axolotls.add(axolotl);
        }
        water.put(id, new WaterState(level, axolotls));
        tell(player, "Four axolotls are orbiting you. Run /skybender water to stop them.");
    }

    private void toggleGravity(ServerPlayer player) {
        UUID id = player.getUUID();
        if (gravity.containsKey(id)) {
            cleanupGravity(id);
            tell(player, "Gravity ended; unused blocks have been restored.");
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        List<GravityBlock> blocks = new ArrayList<>();
        search:
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz < 4 || dx * dx + dz * dz > 20) continue;
                for (int y = base.getY() - 1; y >= Math.max(level.getMinY(), base.getY() - 4); y--) {
                    BlockPos pos = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.blocksMotion() || state.isAir() || state.hasBlockEntity()
                            || state.getDestroySpeed(level, pos) < 0.0F || state.is(Blocks.BEDROCK)
                            || !level.getBlockState(pos.above()).isAir()) continue;
                    BlockDisplay display = spawnBlockDisplay(level, pos.getX() + 0.5D,
                        pos.getY(), pos.getZ() + 0.5D, state);
                    if (display == null) continue;
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    blocks.add(new GravityBlock(level, new BlockPos(pos.getX(), pos.getY(), pos.getZ()), state, display));
                    if (blocks.size() >= 8) break search;
                    break;
                }
            }
        }
        if (blocks.isEmpty()) { tell(player, "No safe ground blocks found nearby."); return; }
        gravity.put(id, new GravityState(level, blocks));
        tell(player, blocks.size() + " blocks orbit you. Left-click a block to launch them toward your crosshair.");
    }

    private boolean launchNextGravityBlock(ServerPlayer player) {
        GravityState state = gravity.get(player.getUUID());
        if (state == null) return false;
        for (GravityBlock block : state.blocks) {
            if (block.launched || !block.display.isAlive()) continue;
            block.launched = true;
            block.direction = player.getLookAngle().normalize();
            restoreOrigin(block);
            tell(player, "Gravity block launched. " + state.blocks.stream().filter(b -> !b.launched).count() + " left.");
            return true;
        }
        tell(player, "All gravity blocks have already been launched.");
        return true;
    }

    private void tick(MinecraftServer server) {
        tickAwakenings(server);
        tickBends(server);
        tickGravity(server);
        tickWater(server);
        tickLasers(server);
    }

    private void tickAwakenings(MinecraftServer server) {
        for (Map.Entry<UUID, AwakeningState> entry : new ArrayList<>(awakenings.entrySet())) {
            UUID id = entry.getKey();
            AwakeningState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.serverLevel() != state.level) {
                endAwakening(id, true, player);
                continue;
            }
            if (!(awakeningDurationBypass && isOwner(player))) state.remainingTicks--;
            if (state.remainingTicks <= 0) {
                endAwakening(id, true, player);
                tell(player, "§6OBS Awakening ended. Cooldown: 2 minutes.");
                continue;
            }
            double phase = (1200 - state.remainingTicks) * 0.105D;
            for (int i = 0; i < state.displays.size(); i++) {
                BlockDisplay display = state.displays.get(i);
                double angle = phase + i * Math.PI / 2.0D;
                display.setPos(player.getX() + Math.cos(angle) * 1.7D,
                    player.getY() + 1.1D + Math.sin(phase * 0.7D) * 0.15D,
                    player.getZ() + Math.sin(angle) * 1.7D);
                state.level.sendParticles(ParticleTypes.FLAME, display.getX(), display.getY(), display.getZ(),
                    1, 0.05D, 0.05D, 0.05D, 0.005D);
            }
            if (state.remainingTicks % 100 == 0) {
                player.displayClientMessage(Component.literal("§cAWAKENING §7" + (state.remainingTicks / 20) + "s"), true);
            }
        }
    }

    private void tickBends(MinecraftServer server) {
        for (Map.Entry<UUID, OrbitState> entry : new ArrayList<>(bends.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            OrbitState state = entry.getValue();
            if (player == null || player.serverLevel() != state.level) {
                bends.remove(entry.getKey());
                removeDisplays(state.level, state.displays);
                continue;
            }
            state.ticks++;
            double radius = state.cinematic ? 3.0D : 1.8D;
            double speed = state.cinematic ? 0.13D : 0.08D;
            for (int i = 0; i < state.displays.size(); i++) {
                double angle = state.ticks * speed + i * Math.PI * 2.0D / state.displays.size();
                state.displays.get(i).setPos(player.getX() + Math.cos(angle) * radius,
                    player.getY() + 1.0D + Math.sin(angle * 0.6D) * 0.3D,
                    player.getZ() + Math.sin(angle) * radius);
            }
        }
    }

    private void tickGravity(MinecraftServer server) {
        for (Map.Entry<UUID, GravityState> entry : new ArrayList<>(gravity.entrySet())) {
            UUID id = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            GravityState state = entry.getValue();
            if (player == null || player.serverLevel() != state.level || !awakenings.containsKey(id)) {
                cleanupGravity(id);
                continue;
            }
            int orbitIndex = 0;
            Iterator<GravityBlock> iterator = state.blocks.iterator();
            while (iterator.hasNext()) {
                GravityBlock block = iterator.next();
                if (!block.display.isAlive()) {
                    restoreOrigin(block);
                    iterator.remove();
                    continue;
                }
                if (!block.launched) {
                    double angle = state.ticks * 0.075D + orbitIndex * Math.PI * 2.0D / Math.max(1, state.blocks.size());
                    block.display.setPos(player.getX() + Math.cos(angle) * 2.2D,
                        player.getY() + 1.2D + (orbitIndex % 3) * 0.32D,
                        player.getZ() + Math.sin(angle) * 2.2D);
                    orbitIndex++;
                    continue;
                }
                Vec3 next = block.display.position().add(block.direction.scale(1.25D));
                block.distance += 1.25D;
                boolean hitBlock = state.level.getBlockState(BlockPos.containing(next)).blocksMotion();
                boolean hitEntity = false;
                AABB area = new AABB(next.x - 0.7D, next.y - 0.7D, next.z - 0.7D,
                    next.x + 0.7D, next.y + 0.7D, next.z + 0.7D);
                for (LivingEntity entity : state.level.getEntitiesOfClass(LivingEntity.class, area,
                        e -> e != player && e.isAlive())) {
                    entity.hurtServer(state.level, state.level.damageSources().playerAttack(player), 7.0F);
                    hitEntity = true;
                    break;
                }
                if (hitBlock || hitEntity || block.distance >= 36.0D) {
                    state.level.sendParticles(ParticleTypes.CLOUD, next.x, next.y, next.z,
                        10, 0.2D, 0.2D, 0.2D, 0.04D);
                    block.display.discard();
                    iterator.remove();
                } else {
                    block.display.setPos(next.x, next.y, next.z);
                    state.level.sendParticles(ParticleTypes.CRIT, next.x, next.y, next.z,
                        1, 0.04D, 0.04D, 0.04D, 0.01D);
                }
            }
            state.ticks++;
            if (state.blocks.isEmpty()) {
                gravity.remove(id);
                tell(player, "All gravity blocks have been used.");
            }
        }
    }

    private void tickWater(MinecraftServer server) {
        for (Map.Entry<UUID, WaterState> entry : new ArrayList<>(water.entrySet())) {
            UUID id = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            WaterState state = entry.getValue();
            if (player == null || player.serverLevel() != state.level || !awakenings.containsKey(id)) {
                cleanupWater(id);
                continue;
            }
            state.ticks++;
            for (int i = 0; i < state.axolotls.size(); i++) {
                Axolotl axolotl = state.axolotls.get(i);
                if (!axolotl.isAlive()) continue;
                double angle = state.ticks * 0.09D + i * Math.PI / 2.0D;
                double x = player.getX() + Math.cos(angle) * 1.8D;
                double y = player.getY() + 0.7D + Math.sin(state.ticks * 0.07D) * 0.2D;
                double z = player.getZ() + Math.sin(angle) * 1.8D;
                axolotl.setPos(x, y, z);
                state.level.sendParticles(ParticleTypes.SPLASH, x, y, z, 1, 0.04D, 0.04D, 0.04D, 0.01D);
            }
        }
    }

    private void tickLasers(MinecraftServer server) {
        for (Map.Entry<UUID, LaserState> entry : new ArrayList<>(lasers.entrySet())) {
            UUID id = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            LaserState state = entry.getValue();
            if (player == null || state.remainingTicks-- <= 0) {
                lasers.remove(id);
                continue;
            }
            ServerLevel level = player.serverLevel();
            Vec3 eye = player.getEyePosition();
            Vec3 direction = player.getLookAngle().normalize();
            for (double distance = 1.0D; distance <= state.range; distance += 2.5D) {
                Vec3 point = eye.add(direction.scale(distance));
                if (!level.getBlockState(BlockPos.containing(point)).isAir()) {
                    level.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y, point.z, 1, 0, 0, 0, 0);
                    break;
                }
                level.sendParticles(ParticleTypes.FLAME, point.x, point.y, point.z, 1, 0.02D, 0.02D, 0.02D, 0.005D);
                if (distance % 10.0D < 2.5D) {
                    level.sendParticles(ParticleTypes.SMOKE, point.x, point.y, point.z, 1, 0.03D, 0.03D, 0.03D, 0.005D);
                }
            }
            if (state.remainingTicks % 5 == 0) {
                AABB area = new AABB(eye.x - state.range, eye.y - state.range, eye.z - state.range,
                    eye.x + state.range, eye.y + state.range, eye.z + state.range);
                for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, area,
                        entity -> entity != player && entity.isAlive())) {
                    Vec3 offset = living.position().add(0, living.getBbHeight() * 0.5D, 0).subtract(eye);
                    double along = offset.dot(direction);
                    if (along < 0 || along > state.range) continue;
                    double perpendicular = offset.subtract(direction.scale(along)).lengthSqr();
                    if (perpendicular <= 1.8D) {
                        living.hurtServer(level, level.damageSources().playerAttack(player), 8.0F);
                        living.setSecondsOnFire(2);
                    }
                }
            }
        }
    }

    private void cleanupGravity(UUID id) {
        GravityState state = gravity.remove(id);
        if (state == null) return;
        for (GravityBlock block : state.blocks) {
            restoreOrigin(block);
            block.display.discard();
        }
    }

    private void restoreOrigin(GravityBlock block) {
        if (block.restored) return;
        block.restored = true;
        if (block.level.getBlockState(block.origin).isAir()) {
            block.level.setBlock(block.origin, block.original, 3);
        }
    }

    private void cleanupWater(UUID id) {
        WaterState state = water.remove(id);
        if (state != null) for (Axolotl axolotl : state.axolotls) axolotl.discard();
    }

    private BlockDisplay spawnTntDisplay(ServerLevel level, double x, double y, double z) {
        return spawnBlockDisplay(level, x, y, z, Blocks.TNT.defaultBlockState());
    }

    private BlockDisplay spawnBlockDisplay(ServerLevel level, double x, double y, double z, BlockState state) {
        BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) return null;
        display.setBlockState(state);
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setPos(x, y, z);
        level.addFreshEntity(display);
        return display;
    }

    private static void removeDisplays(ServerLevel level, List<BlockDisplay> displays) {
        for (BlockDisplay display : displays) display.discard();
    }

    private PrimedTnt spawnTnt(ServerLevel level, double x, double y, double z,
                               ServerPlayer owner, int fuse, Vec3 velocity) {
        PrimedTnt tnt = new PrimedTnt(level, x, y, z, owner);
        tnt.setFuse(fuse);
        tnt.setDeltaMovement(velocity);
        level.addFreshEntity(tnt);
        return tnt;
    }

    private Vec3 lookTarget(ServerPlayer player, double maxDistance) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getLookAngle().normalize();
        for (double distance = 1.0D; distance <= maxDistance; distance += 1.0D) {
            Vec3 point = eye.add(direction.scale(distance));
            if (level.getBlockState(BlockPos.containing(point)).blocksMotion()) {
                return Vec3.atCenterOf(BlockPos.containing(point));
            }
        }
        return eye.add(direction.scale(maxDistance));
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.getName().getString().equalsIgnoreCase(name)) return candidate;
        }
        return null;
    }

    private static boolean isOwner(ServerPlayer player) {
        return player.getName().getString().equalsIgnoreCase(OWNER);
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static void tell(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), false);
    }
    private static void tell(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
    private static void fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    private static final class AwakeningState {
        final ServerLevel level;
        final List<BlockDisplay> displays;
        int remainingTicks;
        AwakeningState(ServerLevel level, List<BlockDisplay> displays, int remainingTicks) {
            this.level = level; this.displays = displays; this.remainingTicks = remainingTicks;
        }
    }

    private static final class OrbitState {
        final ServerLevel level;
        final List<BlockDisplay> displays;
        final boolean cinematic;
        int ticks;
        OrbitState(ServerLevel level, List<BlockDisplay> displays, boolean cinematic) {
            this.level = level; this.displays = displays; this.cinematic = cinematic;
        }
    }

    private static final class GravityState {
        final ServerLevel level;
        final List<GravityBlock> blocks;
        int ticks;
        GravityState(ServerLevel level, List<GravityBlock> blocks) {
            this.level = level; this.blocks = blocks;
        }
    }

    private static final class GravityBlock {
        final ServerLevel level;
        final BlockPos origin;
        final BlockState original;
        final BlockDisplay display;
        boolean launched;
        boolean restored;
        double distance;
        Vec3 direction = Vec3.ZERO;
        GravityBlock(ServerLevel level, BlockPos origin, BlockState original, BlockDisplay display) {
            this.level = level; this.origin = origin; this.original = original; this.display = display;
        }
    }

    private static final class WaterState {
        final ServerLevel level;
        final List<Axolotl> axolotls;
        int ticks;
        WaterState(ServerLevel level, List<Axolotl> axolotls) {
            this.level = level; this.axolotls = axolotls;
        }
    }

    private static final class LaserState {
        final double range;
        int remainingTicks;
        LaserState(double range, int remainingTicks) { this.range = range; this.remainingTicks = remainingTicks; }
    }
}
