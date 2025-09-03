package com.example.bbrl;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VoteTrackTabCompleter implements TabCompleter {
    private final Main plugin;

    public VoteTrackTabCompleter(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("add", "remove");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("tracks");
            if (section != null) {
                String typed = args[1].toLowerCase();
                List<String> matches = new ArrayList<>();
                for (String track : section.getKeys(false)) {
                    if (track.toLowerCase().startsWith(typed)) {
                        matches.add(track);
                    }
                }
                return matches;
            }
        }

        return Collections.emptyList();
    }
}
