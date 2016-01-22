package com.earth2me.mcperf;

import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;

import java.util.stream.Stream;


public class Util {
    private static final String ALERT_PREFIX = ChatColor.RED + "/!\\ MCPerf /!\\ " + ChatColor.LIGHT_PURPLE;

    private Util() {
        throw new UnsupportedOperationException("Static class");
    }

    public static boolean denyPermission(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Permission denied.");
        return true;
    }

    public static void sendAlert(Server server, String format, Object... args) {
        sendAlert(server, ALERT_PREFIX + String.format(format, args));
    }

    public static void sendAlert(Server server, String message) {
        Stream.concat(
                Stream.of(server.getConsoleSender()),
                server.getOnlinePlayers().stream().filter(p -> p.hasPermission("mcperf.receivealerts"))
        ).distinct().forEach(s -> s.sendMessage(message));
    }

    public static void println(Server server, String format, Object... args) {
        println(server, String.format(format, args));
    }

    public static void println(Server server, String message) {
        final String substitute = ChatColor.GRAY.toString();
        final ChatColor[] replace = {
                ChatColor.BLACK,
                ChatColor.DARK_GRAY,
        };

        for (ChatColor color : replace) {
            message = message.replace(color.toString(), substitute);
        }

        server.getConsoleSender().sendMessage(message);
    }
}