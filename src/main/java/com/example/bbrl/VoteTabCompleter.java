package com.example.bbrl;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class VoteTabCompleter implements TabCompleter {
    private final Main plugin;

    public VoteTabCompleter(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) {
            ConfigurationSection section = plugin.getConfig()
                    .getConfigurationSection("tracks");
            if (section == null) return Collections.emptyList();

            String fragment = args[0].toLowerCase();
            return section.getKeys(false).stream()
                    .filter(key -> key.toLowerCase().startsWith(fragment))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
