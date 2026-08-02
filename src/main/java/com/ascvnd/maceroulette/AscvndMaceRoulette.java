package com.ascvnd.maceroulette;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AscvndMaceRoulette extends JavaPlugin {

    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Lang lang = new Lang(this);
        this.game = new GameManager(this, lang);

        MaceCommand command = new MaceCommand(game, lang);
        PluginCommand mace = getCommand("mace");
        if (mace != null) {
            mace.setExecutor(command);
            mace.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'mace' is missing from plugin.yml! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new GameListener(game), this);
        getLogger().info("AscvndMaceRoulette enabled.");
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
        getLogger().info("AscvndMaceRoulette disabled.");
    }
}
