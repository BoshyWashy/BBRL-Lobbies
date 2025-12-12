package com.example.bbrl;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

public class VoteTabCompleter implements TabCompleter {
    private final Main plugin;
    public VoteTabCompleter(Main plugin) { this.plugin = plugin; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        if (args.length == 1) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("tracks");
            if (section == null) return Collections.emptyList();
            String typed = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String track : section.getKeys(false))
                if (track.toLowerCase().startsWith(typed)) matches.add(track);
            return matches;
        }
        return Collections.emptyList();
    }
}
