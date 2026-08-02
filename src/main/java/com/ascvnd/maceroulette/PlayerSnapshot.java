package com.ascvnd.maceroulette;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;

/**
 * A snapshot of a player's important state taken before they enter a game,
 * so it can be fully restored when the game ends.
 */
public class PlayerSnapshot {

    private final ItemStack[] inventory;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final GameMode gameMode;
    private final Location location;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exp;
    private final int level;
    private final boolean allowFlight;
    private final boolean flying;
    private final Collection<PotionEffect> potionEffects;

    private PlayerSnapshot(Player p) {
        PlayerInventory inv = p.getInventory();
        this.inventory = inv.getContents().clone();
        this.armor = inv.getArmorContents().clone();
        this.offHand = inv.getItemInOffHand().clone();
        this.gameMode = p.getGameMode();
        this.location = p.getLocation().clone();
        this.health = p.getHealth();
        this.foodLevel = p.getFoodLevel();
        this.saturation = p.getSaturation();
        this.exp = p.getExp();
        this.level = p.getLevel();
        this.allowFlight = p.getAllowFlight();
        this.flying = p.isFlying();
        this.potionEffects = p.getActivePotionEffects();
    }

    public static PlayerSnapshot of(Player p) {
        return new PlayerSnapshot(p);
    }

    @SuppressWarnings("deprecation")
    public void restore(Player p) {
        // Clear current effects/items first.
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setContents(inventory);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offHand);

        p.setGameMode(gameMode);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setGlowing(false);

        double max = 20.0;
        try {
            max = p.getMaxHealth();
        } catch (Throwable ignored) {
            // fall back to the vanilla default
        }
        p.setHealth(Math.max(1.0, Math.min(health, max)));
        p.setFoodLevel(foodLevel);
        p.setSaturation(saturation);
        p.setExp(exp);
        p.setLevel(level);
        p.setAllowFlight(allowFlight);
        p.setFlying(flying);

        for (PotionEffect e : potionEffects) {
            p.addPotionEffect(e);
        }

        if (location != null && location.getWorld() != null) {
            p.teleport(location);
        }
        p.updateInventory();
    }
}
