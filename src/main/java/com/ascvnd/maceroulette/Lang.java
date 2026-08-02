package com.ascvnd.maceroulette;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads configurable messages from config.yml and turns them into Adventure
 * components, supporting both legacy &-codes and &#RRGGBB hex codes.
 */
public class Lang {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Reload config.yml from disk. */
    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
    }

    public FileConfiguration config() {
        return cfg;
    }

    /**
     * Get a raw string from config with placeholder replacement.
     * {@code repl} is a flat list of pairs: token, value, token, value...
     */
    public String rawString(String path, String def, String... repl) {
        String s = cfg.getString(path, def);
        if (s == null) {
            s = def;
        }
        for (int i = 0; i + 1 < repl.length; i += 2) {
            s = s.replace(repl[i], repl[i + 1]);
        }
        return s;
    }

    /** Get a coloured component from config with placeholder replacement. */
    public Component msg(String path, String def, String... repl) {
        return Txt.c(rawString(path, def, repl));
    }
}
