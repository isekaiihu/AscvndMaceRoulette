package com.ascvnd.maceroulette;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Core controller for the AscvndMaceRoulette minigame.
 */
public class GameManager {

    private static final String ARENA_NAME = "ascvnd_arena";
    private static final String TEAM_NAME = "ascvndMaceGlow";
    private static final double VOID_Y = 50.0;
    private static final int PLATFORM_Y = 64;
    private static final long WIND_CHARGE_GRACE_MS = 3000L;

    private final JavaPlugin plugin;
    private final Lang lang;
    private final Random rng = new Random();

    // Configurable settings (loaded from config.yml).
    private int minPlayers = 2;
    private int queueSeconds = 60;
    private int rouletteTicks = 200;
    private int platformRadius = 15;

    private GameState state = GameState.IDLE;

    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private List<UUID> alive = new ArrayList<>();
    private final LinkedHashSet<UUID> eliminated = new LinkedHashSet<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();

    private World arena;
    private Team glowTeam;
    private ItemDisplay maceDisplay;
    private UUID currentMacer;

    private int rouletteIndex;   // currently highlighted player
    private int macerIndex;      // predetermined random winner of the spin
    private int rouletteTick;
    private int nextHopTick;
    private float spinAngle;
    private int lastShownSecond;
    private int queueCountdown;
    private boolean resolving;
    private long chargeDepletionDeadline = -1L;

    private BukkitTask queueTask;
    private BukkitTask rouletteTask;
    private BukkitTask combatTask;

    public GameManager(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        reloadSettings();
        setupGlowTeam();
    }

    public void reloadSettings() {
        FileConfiguration c = lang.config();
        minPlayers = Math.max(2, c.getInt("settings.min-players", 2));
        queueSeconds = Math.max(1, c.getInt("settings.queue-countdown-seconds", 60));
        rouletteTicks = Math.max(2, c.getInt("settings.roulette-seconds", 10)) * 20;
        platformRadius = Math.max(5, c.getInt("settings.platform-radius", 15));
    }

    private void setupGlowTeam() {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = board.getTeam(TEAM_NAME);
            if (t == null) {
                t = board.registerNewTeam(TEAM_NAME);
            }
            t.color(NamedTextColor.GOLD);
            this.glowTeam = t;
        } catch (Throwable ignored) {
            // scoreboard not ready - glow still works via setGlowing in the default colour
        }
    }

    // ----------------------------------------------------------------- queue

    public void joinQueue(Player p) {
        if (state != GameState.IDLE && state != GameState.QUEUE_COUNTDOWN) {
            p.sendMessage(lang.msg("queue.game-in-progress",
                    "&cA game is already in progress. Please wait for it to finish."));
            return;
        }
        if (!queue.add(p.getUniqueId())) {
            p.sendMessage(lang.msg("queue.already-queued", "&eYou are already in the queue."));
            return;
        }
        p.sendMessage(lang.msg("queue.joined", "&aYou joined the Mace Roulette queue!"));
        broadcastQueue(lang.msg("queue.joined-broadcast",
                "&a{player} &7joined the queue &8(&a{count}&8 players)",
                "{player}", p.getName(), "{count}", String.valueOf(onlineQueueSize())));
        if (state == GameState.IDLE && onlineQueueSize() >= minPlayers) {
            startQueueCountdown();
        }
    }

    public void leaveQueue(Player p) {
        if (!queue.remove(p.getUniqueId())) {
            p.sendMessage(lang.msg("queue.not-in-queue", "&cYou are not in the queue."));
            return;
        }
        p.clearTitle();
        p.sendMessage(lang.msg("queue.left", "&eYou left the queue."));
        broadcastQueue(lang.msg("queue.left-broadcast",
                "&c{player} &7left the queue &8(&a{count}&8 players)",
                "{player}", p.getName(), "{count}", String.valueOf(onlineQueueSize())));
        if (state == GameState.QUEUE_COUNTDOWN && onlineQueueSize() < minPlayers) {
            cancelQueueCountdown();
        }
    }

    private void startQueueCountdown() {
        state = GameState.QUEUE_COUNTDOWN;
        queueCountdown = queueSeconds;
        broadcastQueue(lang.msg("queue.countdown-started",
                "&aEnough players! The game starts in &e{seconds} &aseconds.",
                "{seconds}", String.valueOf(queueSeconds)));
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (onlineQueueSize() < minPlayers) {
                cancelQueueCountdown();
                return;
            }
            if (queueCountdown <= 0) {
                cancelTask(queueTask);
                queueTask = null;
                beginGame();
                return;
            }
            showCountdownTitle(onlineQueuePlayers(), queueCountdown);
            if (queueCountdown <= 5) {
                playTo(onlineQueuePlayers(), "minecraft:block.note_block.pling", 1.6f);
            }
            queueCountdown--;
        }, 0L, 20L);
    }

    private void cancelQueueCountdown() {
        cancelTask(queueTask);
        queueTask = null;
        for (Player p : onlineQueuePlayers()) {
            p.clearTitle();
        }
        broadcastQueue(lang.msg("queue.countdown-cancelled",
                "&cNot enough players. Countdown cancelled."));
        state = GameState.IDLE;
    }

    // -------------------------------------------------------------- game flow

    private void beginGame() {
        cancelTask(queueTask);
        queueTask = null;

        List<UUID> players = new ArrayList<>();
        for (UUID id : queue) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                players.add(id);
            }
        }
        if (players.size() < 2) {
            broadcastQueue(lang.msg("game.not-enough-online",
                    "&cNot enough players online to start. Queue reset."));
            resetToIdle();
            return;
        }

        snapshots.clear();
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                snapshots.put(id, PlayerSnapshot.of(p));
            }
        }

        arena = getOrCreateArena();
        if (arena == null) {
            broadcastQueue(lang.msg("game.arena-fail", "&cFailed to create the arena world. Aborting."));
            resetToIdle();
            return;
        }
        buildPlatform(arena);
        clearArenaEntities();

        alive = new ArrayList<>(players);
        eliminated.clear();
        lastDamager.clear();
        queue.clear();
        resolving = false;
        currentMacer = null;

        broadcast(lang.msg("game.begin-broadcast",
                "&6&lThe Mace Roulette begins! &7Last one standing wins."));
        startRound();
    }

    private void startRound() {
        if (state == GameState.ENDING) {
            return;
        }
        resolving = false;
        chargeDepletionDeadline = -1L;
        currentMacer = null;
        lastDamager.clear();
        state = GameState.ROULETTE;

        // Reset & position every surviving player.
        List<Location> spawns = computeSpawns(alive.size());
        for (int i = 0; i < alive.size(); i++) {
            Player p = Bukkit.getPlayer(alive.get(i));
            if (p == null) {
                continue;
            }
            preparePlayer(p);
            p.teleport(spawns.get(i % spawns.size()));
        }
        clearArenaEntities();

        if (alive.size() <= 1) {
            endGame(alive.isEmpty() ? null : alive.get(0));
            return;
        }

        // Random, independent starting position AND winner for the spin.
        rouletteIndex = rng.nextInt(alive.size());
        macerIndex = rng.nextInt(alive.size());

        spinAngle = 0f;
        rouletteTick = 0;
        nextHopTick = 0;
        lastShownSecond = -1;
        spawnMaceDisplay();
        setHighlight(alive.get(rouletteIndex));

        rouletteTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRoulette, 1L, 1L);
    }

    private void tickRoulette() {
        if (state != GameState.ROULETTE) {
            return;
        }
        if (alive.size() <= 1) {
            cancelTask(rouletteTask);
            rouletteTask = null;
            maybeAdvance();
            return;
        }
        rouletteTick++;

        // Smoothly spin the floating mace.
        spinAngle += 0.45f;
        updateMaceDisplay();

        // Countdown subtitle, resent each second so the title stays solid.
        int secondsLeft = (int) Math.ceil((rouletteTicks - rouletteTick) / 20.0);
        if (secondsLeft < 0) {
            secondsLeft = 0;
        }
        if (secondsLeft != lastShownSecond) {
            lastShownSecond = secondsLeft;
            if (secondsLeft >= 1) {
                showCountdownTitle(everyone(), secondsLeft);
                playTo(everyone(), "minecraft:block.note_block.hat", 1.5f);
            }
        }

        // Hop the highlight from player to player, slowing down over time.
        if (rouletteTick >= nextHopTick && rouletteTick < rouletteTicks) {
            int gap = Math.min(16, 2 + (rouletteTick / 14));
            int futureHop = rouletteTick + gap;
            if (futureHop >= rouletteTicks) {
                // Final hop: land on the predetermined random winner.
                landOn(macerIndex);
            } else {
                advanceHighlight();
            }
            nextHopTick = futureHop;
        }

        if (rouletteTick >= rouletteTicks) {
            finishRoulette();
        }
    }

    private void finishRoulette() {
        cancelTask(rouletteTask);
        rouletteTask = null;
        removeMaceDisplay();

        if (alive.isEmpty()) {
            endGame(null);
            return;
        }
        // Guarantee the winner is exactly the predetermined random pick.
        if (macerIndex < 0 || macerIndex >= alive.size()) {
            macerIndex = rng.nextInt(alive.size());
        }
        landOn(macerIndex);

        UUID macerId = alive.get(rouletteIndex);
        currentMacer = macerId;
        Player macer = Bukkit.getPlayer(macerId);
        String macerName = macer != null ? macer.getName() : "Player";

        // Announce the chosen player.
        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(2600), Duration.ofMillis(400));
        Title announce = Title.title(
                lang.msg("game.macer-title", "&#006EFF&l{player}", "{player}", macerName),
                lang.msg("game.macer-subtitle", "&fHas the Mace!"),
                times);
        for (Player p : everyone()) {
            p.showTitle(announce);
        }
        playTo(everyone(), "minecraft:entity.ender_dragon.growl", 1.0f);

        // Give the macer their kit and keep them highlighted.
        if (macer != null) {
            macer.getInventory().setItem(0, createMace());
            macer.getInventory().setItemInOffHand(new ItemStack(Material.WIND_CHARGE, 5));
            macer.getInventory().setHeldItemSlot(0);
            macer.updateInventory();
            setHighlight(macerId);
        }

        chargeDepletionDeadline = -1L;
        state = GameState.COMBAT;
        combatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCombat, 5L, 1L);
    }

    private void tickCombat() {
        if (state != GameState.COMBAT) {
            return;
        }
        // Eliminate anyone who fell off the platform or left the arena.
        for (UUID id : new ArrayList<>(alive)) {
            if (resolving) {
                return;
            }
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                removeFromGame(id);
                continue;
            }
            if (p.getWorld() != arena || p.getLocation().getY() < VOID_Y) {
                eliminate(id, lastDamager.get(id), "void");
            }
        }
        if (resolving) {
            return;
        }

        // Wind-charge depletion: the macer must score before running out.
        if (currentMacer != null && alive.contains(currentMacer)) {
            Player macer = Bukkit.getPlayer(currentMacer);
            if (macer != null) {
                int charges = windCharges(macer);
                if (charges <= 0) {
                    if (chargeDepletionDeadline < 0) {
                        chargeDepletionDeadline = System.currentTimeMillis() + WIND_CHARGE_GRACE_MS;
                    } else if (System.currentTimeMillis() >= chargeDepletionDeadline) {
                        eliminate(currentMacer, null, "out-of-charges");
                    }
                } else {
                    chargeDepletionDeadline = -1L;
                }
            }
        }
    }

    // ----------------------------------------------------------- elimination

    /** Called by the listener when damage would be lethal (the damage is cancelled first). */
    public void handleLethal(UUID victim, UUID killer, EntityDamageEvent.DamageCause cause) {
        String reasonKey = (cause == EntityDamageEvent.DamageCause.VOID) ? "void" : "slain";
        eliminate(victim, killer, reasonKey);
    }

    private void eliminate(UUID id, UUID killerId, String reasonKey) {
        if (!alive.remove(id)) {
            return;
        }
        eliminated.add(id);
        lastDamager.remove(id);

        Player p = Bukkit.getPlayer(id);
        String name = p != null ? p.getName() : "A player";
        if (p != null) {
            clearHighlight(id);
            if (p.isDead()) {
                Bukkit.getScheduler().runTask(plugin, () -> applyEliminatedState(p));
            } else {
                applyEliminatedState(p);
            }
        }

        Player killer = killerId != null ? Bukkit.getPlayer(killerId) : null;
        if (killer != null && !killer.getUniqueId().equals(id)) {
            broadcast(lang.msg("elimination.by-killer",
                    "&c{player} &7was eliminated by &c{killer}&7!",
                    "{player}", name, "{killer}", killer.getName()));
        } else {
            broadcast(lang.msg("elimination." + reasonKey,
                    "&c{player} &7was eliminated!", "{player}", name));
        }
        playTo(everyone(), "minecraft:entity.lightning_bolt.thunder", 1.2f);

        maybeAdvance();
    }

    @SuppressWarnings("deprecation")
    private void applyEliminatedState(Player p) {
        p.getInventory().clear();
        p.getInventory().setItemInOffHand(null);
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setFireTicks(0);
        p.setFallDistance(0f);
        try {
            p.setHealth(p.getMaxHealth());
        } catch (Throwable ignored) {
        }
        p.setFoodLevel(20);
        p.setGameMode(GameMode.SPECTATOR);
        if (arena != null) {
            p.teleport(new Location(arena, 0.5, 90, 0.5));
        }
        p.showTitle(Title.title(
                lang.msg("elimination.eliminated-title", "&c&lELIMINATED"),
                lang.msg("elimination.eliminated-subtitle", "&7You are out of the game!"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1800), Duration.ofMillis(400))));
    }

    /** Lightweight removal for players who quit (no teleport on the leaving player). */
    private void removeFromGame(UUID id) {
        if (!alive.remove(id)) {
            return;
        }
        eliminated.add(id);
        lastDamager.remove(id);
        Player p = Bukkit.getPlayer(id);
        String name = p != null ? p.getName() : "A player";
        if (p != null) {
            p.setGlowing(false);
            if (glowTeam != null) {
                glowTeam.removeEntry(p.getName());
            }
        }
        broadcast(lang.msg("elimination.left-game", "&7{player} left the game.", "{player}", name));
        maybeAdvance();
    }

    private void maybeAdvance() {
        if (resolving) {
            return;
        }
        if (state != GameState.COMBAT && state != GameState.ROULETTE) {
            return;
        }
        resolving = true;
        cancelTask(combatTask);
        combatTask = null;
        cancelTask(rouletteTask);
        rouletteTask = null;
        removeMaceDisplay();

        if (alive.size() <= 1) {
            endGame(alive.isEmpty() ? null : alive.get(0));
        } else {
            // Freeze the survivors during the short gap before the next roll.
            state = GameState.ROULETTE;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state != GameState.ENDING) {
                    startRound();
                }
            }, 60L);
        }
    }

    // --------------------------------------------------------------- ending

    private void endGame(UUID winnerId) {
        state = GameState.ENDING;
        resolving = true;
        cancelAllTasks();
        removeMaceDisplay();

        Player winner = winnerId != null ? Bukkit.getPlayer(winnerId) : null;
        String winnerName = winner != null ? winner.getName() : "Nobody";

        Title.Times times = Title.Times.times(
                Duration.ofMillis(300), Duration.ofMillis(5000), Duration.ofMillis(800));

        if (winner != null) {
            clearHighlight(winnerId);
            winner.setGameMode(GameMode.SURVIVAL);
            winner.getInventory().clear();
            winner.getInventory().setItemInOffHand(null);
            winner.showTitle(Title.title(
                    lang.msg("endgame.victory-title", DEFAULT_VICTORY),
                    lang.msg("endgame.victory-subtitle", "&fYou have won!"),
                    times));
            winner.playSound(Sound.sound(Key.key("minecraft:ui.toast.challenge_complete"),
                    Sound.Source.MASTER, 1f, 1f));
        }
        for (UUID id : eliminated) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            p.showTitle(Title.title(
                    lang.msg("endgame.defeat-title", "&#FF0000&lDEFEAT"),
                    lang.msg("endgame.defeat-subtitle", "&f{winner} has won!", "{winner}", winnerName),
                    times));
            p.playSound(Sound.sound(Key.key("minecraft:entity.wither.spawn"),
                    Sound.Source.MASTER, 0.6f, 0.8f));
        }

        broadcast(lang.msg("endgame.winner-broadcast",
                "&6&l{winner} &ehas won AscvndMaceRoulette!", "{winner}", winnerName));

        Bukkit.getScheduler().runTaskLater(plugin, this::resetAfterGame, 160L);
    }

    private void resetAfterGame() {
        for (Map.Entry<UUID, PlayerSnapshot> e : snapshots.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.clearTitle();
                try {
                    e.getValue().restore(p);
                } catch (Throwable ignored) {
                }
            }
        }
        fullCleanup();
    }

    // ----------------------------------------------------------- admin / util

    public void forceStart(CommandSender sender) {
        if (state != GameState.IDLE && state != GameState.QUEUE_COUNTDOWN) {
            sender.sendMessage(lang.msg("admin.already-running", "&cA game is already running."));
            return;
        }
        if (onlineQueueSize() < minPlayers) {
            sender.sendMessage(lang.msg("admin.need-players",
                    "&cNeed at least {min} players in the queue to start.",
                    "{min}", String.valueOf(minPlayers)));
            return;
        }
        cancelTask(queueTask);
        queueTask = null;
        sender.sendMessage(lang.msg("admin.force-starting", "&aForce-starting the game..."));
        beginGame();
    }

    public void forceStop(CommandSender sender) {
        if (state == GameState.IDLE) {
            sender.sendMessage(lang.msg("admin.no-game", "&cNo game is currently running."));
            return;
        }
        broadcast(lang.msg("admin.stop-broadcast", "&cThe game has been stopped by an admin."));
        for (Map.Entry<UUID, PlayerSnapshot> e : snapshots.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.clearTitle();
                try {
                    e.getValue().restore(p);
                } catch (Throwable ignored) {
                }
            }
        }
        for (Player p : onlineQueuePlayers()) {
            p.clearTitle();
        }
        fullCleanup();
        sender.sendMessage(lang.msg("admin.stop-success", "&aGame stopped and players restored."));
    }

    public void info(CommandSender sender) {
        sender.sendMessage(lang.msg("info.header", "&6&lAscvndMaceRoulette"));
        sender.sendMessage(lang.msg("info.state", "&7State: &e{state}", "{state}", state.name()));
        sender.sendMessage(lang.msg("info.queue", "&7In queue: &e{count}",
                "{count}", String.valueOf(onlineQueueSize())));
        sender.sendMessage(lang.msg("info.players", "&7Alive: &e{alive} &7| Eliminated: &e{eliminated}",
                "{alive}", String.valueOf(alive.size()),
                "{eliminated}", String.valueOf(eliminated.size())));
        if (currentMacer != null) {
            Player m = Bukkit.getPlayer(currentMacer);
            sender.sendMessage(lang.msg("info.macer", "&7Mace holder: &e{macer}",
                    "{macer}", m != null ? m.getName() : "?"));
        }
    }

    public void shutdown() {
        cancelAllTasks();
        removeMaceDisplay();
        for (Map.Entry<UUID, PlayerSnapshot> e : snapshots.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.clearTitle();
                try {
                    e.getValue().restore(p);
                } catch (Throwable ignored) {
                }
            }
        }
        if (glowTeam != null) {
            try {
                for (String entry : new HashSet<>(glowTeam.getEntries())) {
                    glowTeam.removeEntry(entry);
                }
            } catch (Throwable ignored) {
            }
        }
        snapshots.clear();
        alive.clear();
        eliminated.clear();
        queue.clear();
        state = GameState.IDLE;
    }

    private void fullCleanup() {
        cancelAllTasks();
        removeMaceDisplay();
        clearArenaEntities();
        if (glowTeam != null) {
            for (String entry : new HashSet<>(glowTeam.getEntries())) {
                glowTeam.removeEntry(entry);
            }
        }
        for (Player p : everyone()) {
            p.setGlowing(false);
        }
        queue.clear();
        alive.clear();
        eliminated.clear();
        snapshots.clear();
        lastDamager.clear();
        currentMacer = null;
        resolving = false;
        chargeDepletionDeadline = -1L;
        state = GameState.IDLE;
    }

    private void resetToIdle() {
        cancelTask(queueTask);
        queueTask = null;
        for (Player p : onlineQueuePlayers()) {
            p.clearTitle();
        }
        queue.clear();
        state = GameState.IDLE;
    }

    // -------------------------------------------------------------- world

    @SuppressWarnings({"removal", "deprecation"})
    private World getOrCreateArena() {
        World existing = Bukkit.getWorld(ARENA_NAME);
        if (existing != null) {
            return existing;
        }
        WorldCreator wc = new WorldCreator(ARENA_NAME);
        wc.generator(new VoidChunkGenerator());
        wc.environment(World.Environment.NORMAL);
        wc.generateStructures(false);
        World w = wc.createWorld();
        if (w == null) {
            return null;
        }
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.FALL_DAMAGE, false);
        w.setGameRule(GameRule.NATURAL_REGENERATION, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.DO_TILE_DROPS, false);
        w.setGameRule(GameRule.DO_MOB_LOOT, false);
        w.setDifficulty(Difficulty.NORMAL);
        w.setTime(6000L);
        w.setStorm(false);
        w.setThundering(false);
        w.setPVP(true);
        w.setSpawnLocation(0, PLATFORM_Y + 1, 0);
        for (int cx = -2; cx <= 1; cx++) {
            for (int cz = -2; cz <= 1; cz++) {
                w.getChunkAt(cx, cz).setForceLoaded(true);
            }
        }
        return w;
    }

    private void buildPlatform(World w) {
        int r = platformRadius;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r * r) {
                    w.getBlockAt(x, PLATFORM_Y, z).setType(Material.STONE, false);
                    w.getBlockAt(x, PLATFORM_Y + 1, z).setType(Material.AIR, false);
                    w.getBlockAt(x, PLATFORM_Y + 2, z).setType(Material.AIR, false);
                    w.getBlockAt(x, PLATFORM_Y + 3, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private List<Location> computeSpawns(int n) {
        List<Location> list = new ArrayList<>();
        if (n <= 0 || arena == null) {
            return list;
        }
        double ring = (n == 1) ? 0 : Math.max(0, Math.min(platformRadius - 3.0, 4.0 + n));
        for (int i = 0; i < n; i++) {
            double ang = (2 * Math.PI * i) / Math.max(1, n);
            double x = 0.5 + ring * Math.cos(ang);
            double z = 0.5 + ring * Math.sin(ang);
            Location loc = new Location(arena, x, PLATFORM_Y + 1, z);
            loc.setDirection(new Vector(-Math.cos(ang), 0, -Math.sin(ang)));
            list.add(loc);
        }
        return list;
    }

    private void clearArenaEntities() {
        if (arena == null) {
            return;
        }
        for (Entity e : arena.getEntities()) {
            if (!(e instanceof Player)) {
                e.remove();
            }
        }
    }

    // ----------------------------------------------------------- mace display

    private void spawnMaceDisplay() {
        if (arena == null || alive.isEmpty()) {
            return;
        }
        Player p = Bukkit.getPlayer(alive.get(rouletteIndex));
        Location loc = (p != null ? p.getLocation() : new Location(arena, 0.5, PLATFORM_Y + 3, 0.5))
                .clone().add(0, 2.6, 0);
        loc.setYaw(0);
        loc.setPitch(0);
        maceDisplay = arena.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.MACE));
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(buildTransform(0f));
        });
    }

    private void updateMaceDisplay() {
        if (maceDisplay == null || maceDisplay.isDead()) {
            return;
        }
        if (rouletteIndex < alive.size()) {
            Player p = Bukkit.getPlayer(alive.get(rouletteIndex));
            if (p != null) {
                Location loc = p.getLocation().clone().add(0, 2.6, 0);
                loc.setYaw(0);
                loc.setPitch(0);
                maceDisplay.teleport(loc);
            }
        }
        maceDisplay.setTransformation(buildTransform(spinAngle));
    }

    private Transformation buildTransform(float angle) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new Quaternionf().rotationY(angle),
                new Vector3f(1.7f, 1.7f, 1.7f),
                new Quaternionf());
    }

    private void removeMaceDisplay() {
        if (maceDisplay != null) {
            try {
                maceDisplay.remove();
            } catch (Throwable ignored) {
            }
            maceDisplay = null;
        }
    }

    private void advanceHighlight() {
        if (alive.isEmpty()) {
            return;
        }
        clearHighlight(alive.get(rouletteIndex));
        rouletteIndex = (rouletteIndex + 1) % alive.size();
        setHighlight(alive.get(rouletteIndex));
        playTo(everyone(), "minecraft:block.note_block.hat", 1.2f);
    }

    /** Jump the highlight directly to a specific index (used for the final landing). */
    private void landOn(int index) {
        if (alive.isEmpty()) {
            return;
        }
        if (rouletteIndex >= 0 && rouletteIndex < alive.size()) {
            clearHighlight(alive.get(rouletteIndex));
        }
        rouletteIndex = ((index % alive.size()) + alive.size()) % alive.size();
        setHighlight(alive.get(rouletteIndex));
        playTo(everyone(), "minecraft:block.note_block.hat", 1.2f);
    }

    private void setHighlight(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            return;
        }
        if (glowTeam != null) {
            glowTeam.addEntry(p.getName());
        }
        p.setGlowing(true);
    }

    private void clearHighlight(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.setGlowing(false);
            if (glowTeam != null) {
                glowTeam.removeEntry(p.getName());
            }
        }
    }

    // ----------------------------------------------------------- player prep

    @SuppressWarnings("deprecation")
    private void preparePlayer(Player p) {
        p.getInventory().clear();
        p.getInventory().setItemInOffHand(null);
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        try {
            p.setHealth(p.getMaxHealth());
        } catch (Throwable ignored) {
        }
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);
        p.setExp(0f);
        p.setLevel(0);
        p.setVelocity(new Vector(0, 0, 0));
        p.setGlowing(false);
        if (glowTeam != null) {
            glowTeam.removeEntry(p.getName());
        }
    }

    private ItemStack createMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        try {
            Enchantment density = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("density"));
            if (density != null) {
                mace.addUnsafeEnchantment(density, 100);
            }
        } catch (Throwable ignored) {
        }
        return mace;
    }

    private int windCharges(Player p) {
        int count = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == Material.WIND_CHARGE) {
                count += it.getAmount();
            }
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() == Material.WIND_CHARGE) {
            count += off.getAmount();
        }
        return count;
    }

    // ----------------------------------------------------------- titles/sound

    private void showCountdownTitle(List<Player> targets, int seconds) {
        Title t = Title.title(
                lang.msg("countdown.title", "&#FF0000&lTIME"),
                lang.msg("countdown.subtitle", "&f{seconds}s", "{seconds}", String.valueOf(seconds)),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ZERO));
        for (Player p : targets) {
            p.showTitle(t);
        }
    }

    private void playTo(List<Player> targets, String key, float pitch) {
        Sound s = Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, pitch);
        for (Player p : targets) {
            p.playSound(s);
        }
    }

    private void broadcast(Component c) {
        for (Player p : everyone()) {
            p.sendMessage(c);
        }
        Bukkit.getConsoleSender().sendMessage(c);
    }

    private void broadcastQueue(Component c) {
        for (Player p : onlineQueuePlayers()) {
            p.sendMessage(c);
        }
    }

    // ----------------------------------------------------------- collections

    private List<Player> everyone() {
        List<Player> list = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        for (UUID id : eliminated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private List<Player> onlineQueuePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : queue) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private int onlineQueueSize() {
        return onlineQueuePlayers().size();
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelAllTasks() {
        cancelTask(queueTask);
        cancelTask(rouletteTask);
        cancelTask(combatTask);
        queueTask = null;
        rouletteTask = null;
        combatTask = null;
    }

    // ----------------------------------------------------------- listener API

    public GameState getState() {
        return state;
    }

    public World getArena() {
        return arena;
    }

    public boolean isAlive(UUID id) {
        return alive.contains(id);
    }

    public boolean isInGame(UUID id) {
        return alive.contains(id) || eliminated.contains(id);
    }

    public void recordDamage(UUID victim, UUID attacker) {
        lastDamager.put(victim, attacker);
    }

    public UUID getLastDamager(UUID victim) {
        return lastDamager.get(victim);
    }

    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        boolean wasQueued = queue.remove(id);
        if (wasQueued && state == GameState.QUEUE_COUNTDOWN && onlineQueueSize() < minPlayers) {
            cancelQueueCountdown();
        }
        if (alive.contains(id)) {
            removeFromGame(id);
        } else if (glowTeam != null) {
            glowTeam.removeEntry(p.getName());
        }
    }

    private static final String DEFAULT_VICTORY =
            "&#FFD900&lV&#FFDF29&lI&#FFE552&lC&#FFEB7B&lT&#FFE552&lO&#FFDF29&lR&#FFD900&lY";
}
