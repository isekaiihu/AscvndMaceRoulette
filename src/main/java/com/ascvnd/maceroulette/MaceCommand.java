package com.ascvnd.maceroulette;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the {@code /mace} command and its tab completion.
 */
public class MaceCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERM = "ascvndmaceroulette.admin";

    private final GameManager game;
    private final Lang lang;

    public MaceCommand(GameManager game, Lang lang) {
        this.game = game;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "queue" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(lang.msg("command.only-players", "&cOnly players can do that."));
                    return true;
                }
                game.joinQueue(p);
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(lang.msg("command.only-players", "&cOnly players can do that."));
                    return true;
                }
                game.leaveQueue(p);
            }
            case "start" -> {
                if (!sender.hasPermission(ADMIN_PERM)) {
                    sender.sendMessage(lang.msg("admin.no-permission", "&cYou don't have permission to do that."));
                    return true;
                }
                game.forceStart(sender);
            }
            case "stop" -> {
                if (!sender.hasPermission(ADMIN_PERM)) {
                    sender.sendMessage(lang.msg("admin.no-permission", "&cYou don't have permission to do that."));
                    return true;
                }
                game.forceStop(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission(ADMIN_PERM)) {
                    sender.sendMessage(lang.msg("admin.no-permission", "&cYou don't have permission to do that."));
                    return true;
                }
                lang.reload();
                game.reloadSettings();
                sender.sendMessage(lang.msg("admin.reloaded", "&aConfiguration reloaded."));
            }
            case "info" -> game.info(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang.msg("command.help-header", "&6&lAscvndMaceRoulette &7- commands:"));
        sender.sendMessage(lang.msg("command.help-queue", "&e/mace queue &7- join the game queue"));
        sender.sendMessage(lang.msg("command.help-leave", "&e/mace leave &7- leave the queue"));
        sender.sendMessage(lang.msg("command.help-info", "&e/mace info &7- show game status"));
        if (sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(lang.msg("command.help-start", "&e/mace start &7- force-start the game"));
            sender.sendMessage(lang.msg("command.help-stop", "&e/mace stop &7- stop the current game"));
            sender.sendMessage(lang.msg("command.help-reload", "&e/mace reload &7- reload the config"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("queue", "leave", "info"));
            if (sender.hasPermission(ADMIN_PERM)) {
                options.add("start");
                options.add("stop");
                options.add("reload");
            }
            String prefix = args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (String opt : options) {
                if (opt.startsWith(prefix)) {
                    result.add(opt);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
