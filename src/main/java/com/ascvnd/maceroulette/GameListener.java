package com.ascvnd.maceroulette;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/**
 * Bridges Bukkit events to the {@link GameManager}.
 */
public class GameListener implements Listener {

    private final GameManager game;

    public GameListener(GameManager game) {
        this.game = game;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (game.getState() != GameState.ROULETTE || !game.isAlive(p.getUniqueId())) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            // Freeze position but still allow the player to look around.
            Location frozen = from.clone();
            frozen.setYaw(to.getYaw());
            frozen.setPitch(to.getPitch());
            e.setTo(frozen);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) {
            return; // handled in onDamageByEntity
        }
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        handleVictimDamage(e, p, null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Player attacker = resolveAttacker(e.getDamager());

        if (e.getEntity() instanceof Player victim) {
            // Block any combat during the frozen roulette phase.
            if (game.getState() == GameState.ROULETTE) {
                e.setCancelled(true);
                return;
            }
            if (attacker != null
                    && game.getState() == GameState.COMBAT
                    && game.isAlive(victim.getUniqueId())
                    && !attacker.getUniqueId().equals(victim.getUniqueId())) {
                game.recordDamage(victim.getUniqueId(), attacker.getUniqueId());
            }
            handleVictimDamage(e, victim, attacker);
            return;
        }

        // Stop a frozen player from attacking other entities during roulette.
        if (attacker != null && game.getState() == GameState.ROULETTE
                && game.isAlive(attacker.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    private void handleVictimDamage(EntityDamageEvent e, Player victim, Player attacker) {
        UUID id = victim.getUniqueId();
        if (!game.isInGame(id)) {
            return;
        }
        GameState st = game.getState();
        if (st == GameState.ROULETTE || st == GameState.ENDING) {
            e.setCancelled(true);
            return;
        }
        if (st != GameState.COMBAT) {
            return;
        }
        if (!game.isAlive(id)) {
            // Eliminated spectators take no damage.
            e.setCancelled(true);
            return;
        }
        if (victim.getHealth() - e.getFinalDamage() <= 0.0) {
            e.setCancelled(true);
            UUID killer = attacker != null ? attacker.getUniqueId() : game.getLastDamager(id);
            game.handleLethal(id, killer, e.getCause());
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player pl) {
            return pl;
        }
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player pl) {
                return pl;
            }
        }
        return null;
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && game.isInGame(p.getUniqueId())) {
            e.setCancelled(true);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (game.isInGame(e.getPlayer().getUniqueId()) && game.getState() != GameState.IDLE) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isInArena(e.getPlayer()) && game.isInGame(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isInArena(e.getPlayer()) && game.isInGame(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (!game.isInGame(victim.getUniqueId())) {
            return;
        }
        // Safety net: this normally never fires because lethal damage is cancelled.
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        UUID killer = victim.getKiller() != null
                ? victim.getKiller().getUniqueId()
                : game.getLastDamager(victim.getUniqueId());
        game.handleLethal(victim.getUniqueId(), killer, EntityDamageEvent.DamageCause.CUSTOM);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (game.isInGame(p.getUniqueId()) && game.getArena() != null) {
            e.setRespawnLocation(new Location(game.getArena(), 0.5, 90, 0.5));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        game.onQuit(e.getPlayer());
    }

    private boolean isInArena(Player p) {
        return game.getArena() != null && p.getWorld().equals(game.getArena());
    }
}
