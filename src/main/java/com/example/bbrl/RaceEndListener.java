package com.example.bbrl;

import me.makkuusen.timing.system.api.events.HeatFinishEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class RaceEndListener implements Listener {

    private final Main plugin;

    public RaceEndListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRaceFinish(HeatFinishEvent event) {
        // notify players
        Bukkit.getOnlinePlayers().forEach(p ->
                p.sendMessage(
                        ChatColor.AQUA +
                                "Rejoin the votelobby with /votejoin to start a new race"
                )
        );

        plugin.startVoting();
    }
}
