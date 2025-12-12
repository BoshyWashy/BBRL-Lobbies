package com.example.bbrl;

import me.makkuusen.timing.system.api.events.HeatFinishEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class RaceEndListener implements Listener {
    private final Main plugin;
    public RaceEndListener(Main plugin) { this.plugin = plugin; }
    @EventHandler
    public void onRaceFinish(HeatFinishEvent event) {
        // Main handles owned events; this class does nothing to avoid interference.
    }
}
