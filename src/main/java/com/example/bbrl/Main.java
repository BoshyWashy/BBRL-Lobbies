package com.example.bbrl;

import me.makkuusen.timing.system.api.TimingSystemAPI;
import me.makkuusen.timing.system.api.events.HeatFinishEvent;
import me.makkuusen.timing.system.api.events.driver.DriverFinishHeatEvent;
import me.makkuusen.timing.system.database.EventDatabase;
import me.makkuusen.timing.system.event.Event;
import me.makkuusen.timing.system.heat.Heat;
import me.makkuusen.timing.system.heat.HeatState;
import me.makkuusen.timing.system.participant.DriverState;
import me.makkuusen.timing.system.round.Round;
import me.makkuusen.timing.system.round.RoundType;
import me.makkuusen.timing.system.track.Track;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main extends JavaPlugin implements CommandExecutor, Listener {

    private final Set<UUID> votingPlayers = new LinkedHashSet<>();
    private final Map<String, Integer> votes = new LinkedHashMap<>();
    private final Map<UUID, String> playerVotes = new HashMap<>();

    private BossBar votingBar;
    private Scoreboard votingBoard;
    private Objective votingObjective;

    private int voteCountdown;
    private int voteTaskId     = -1;
    private int reminderTaskId = -1;
    private boolean tasksStarted = false;
    private boolean isVotingOpen = false;

    private ConfigurationSection trackSection;
    public int autoFinishTaskId = -1;

    private boolean pendingAutoEnd = false;
    private boolean pendingAutoStart = false;
    private PendingStart pendingStart = null;
    private final Set<UUID> tempOpPlayers = new HashSet<>();

    private int voteCooldownTicks;
    private final Map<UUID, Long> lastVoteTime = new HashMap<>();

    private final List<String> recentTracks = new ArrayList<>();
    private int recentTrackMemory;

    private int raceDurationMinutes;

    private Event lobbyEvent;
    private Round lobbyRound;
    private Heat lobbyHeat;
    private boolean raceJoinOpen = false;
    private int joinWindowTaskId = -1;
    private BossBar raceEndOverrideBar;
    private boolean overrideTimerArmed = false;
    private int overrideSeconds;
    private int autoEndPercent;

    private final Set<UUID> lobbyJoinedPlayers = new HashSet<>();

    private int preRaceCountdownTaskId = -1;
    private int preRaceCountdownSeconds = 45;

    private long autoFinishScheduledAt = -1L;

    private static final String PREFIX = "§7[§b§lRace Lobby§7] §r";
    private static String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('§', raw);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        trackSection = getConfig().getConfigurationSection("tracks");

        voteCooldownTicks   = getConfig().getInt("vote-cooldown-seconds", 3) * 20;
        recentTrackMemory   = getConfig().getInt("recent-track-memory", 2);
        raceDurationMinutes = getConfig().getInt("race-duration-minutes", 8);
        int joinWindowSecs  = getConfig().getInt("race-join-window-seconds", 30);
        overrideSeconds     = getConfig().getInt("race-end-override-seconds", 60);
        autoEndPercent      = Math.max(0, Math.min(100, getConfig().getInt("race-auto-end-percent", 80)));

        PluginCommand joinCmd = Objects.requireNonNull(getCommand("votejoin"));
        joinCmd.setExecutor(this);

        PluginCommand leaveCmd = Objects.requireNonNull(getCommand("voteleave"));
        leaveCmd.setExecutor(this);

        PluginCommand voteCmd = Objects.requireNonNull(getCommand("vote"));
        voteCmd.setExecutor(this);
        voteCmd.setTabCompleter(new VoteTabCompleter(this));

        PluginCommand lobbyCmd = Objects.requireNonNull(getCommand("votelobby"));
        lobbyCmd.setExecutor(this);

        PluginCommand trackCmd = Objects.requireNonNull(getCommand("votetrack"));
        trackCmd.setExecutor(this);
        trackCmd.setTabCompleter(new VoteTrackTabCompleter(this));

        PluginCommand voteraceCmd = Objects.requireNonNull(getCommand("voterace"));
        voteraceCmd.setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        startVoting();
    }

    @Override
    public void onDisable() {
        cancelVoting();
        cleanupLobbyOwnedEvent();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("votejoin")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(colorize(PREFIX + "§cVoting isn't open yet."));
                return true;
            }

            boolean firstJoin = votingPlayers.isEmpty();
            if (votingPlayers.add(p.getUniqueId())) {
                teleportPlayerToLobby(p);
                p.sendMessage(colorize(PREFIX + "§bYou joined the voting lobby."));

                if (votingBoard != null) p.setScoreboard(votingBoard);
                if (votingBar != null) votingBar.addPlayer(p);

                p.sendMessage("§b----------------------");
                p.sendMessage(" ");
                p.sendMessage("§b§lWelcome to the votelobby!");
                p.sendMessage(" ");
                p.sendMessage("- §bUse §r/vote <track>§b to vote for a track!");
                p.sendMessage("- §bOnce a track is picked, join by clicking the message in chat or by using §r/voterace§b!");
                p.sendMessage(" ");
                p.sendMessage("§b----------------------");

                if (firstJoin) startCountdownTasks();
            } else {
                p.sendMessage(colorize(PREFIX + "§cYou're already in the voting lobby."));
            }
            return true;
        }

        if (name.equals("voteleave")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (votingPlayers.remove(p.getUniqueId())) {
                p.sendMessage(colorize(PREFIX + "§cYou left the voting lobby."));
                if (votingBar != null) votingBar.removePlayer(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            return true;
        }

        if (name.equals("vote")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(colorize(PREFIX + "§cVoting isn't open right now."));
                return true;
            }
            if (!votingPlayers.contains(p.getUniqueId())) {
                p.sendMessage(colorize(PREFIX + "§cJoin first with §b/votejoin§c."));
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(colorize(PREFIX + "§cUsage: §b/vote <track>"));
                return true;
            }

            long now = System.currentTimeMillis();
            Long last = lastVoteTime.get(p.getUniqueId());
            if (last != null && now - last < voteCooldownTicks * 50L) {
                p.sendMessage(colorize(PREFIX + "§cPlease wait before voting again."));
                return true;
            }

            String input = args[0];
            String matched = trackSection.getKeys(false).stream()
                    .filter(k -> k.equalsIgnoreCase(input))
                    .findFirst().orElse(null);

            if (matched == null) {
                p.sendMessage(colorize(PREFIX + "§cThis track isn't available for races."));
                return true;
            }

            if (recentTracks.contains(matched.toLowerCase(Locale.ROOT))) {
                p.sendMessage(colorize(PREFIX + "§cSorry, this track was voted on within the last " +
                        recentTrackMemory + " race(s)."));
                return true;
            }

            UUID uuid = p.getUniqueId();
            String previous = playerVotes.get(uuid);
            if (matched.equals(previous)) {
                p.sendMessage(colorize(PREFIX + "§cYou're already voting for §b" + matched));
                return true;
            }

            if (previous != null) {
                votes.put(previous, votes.get(previous) - 1);
            }
            int cnt = votes.getOrDefault(matched, 0) + 1;
            votes.put(matched, cnt);
            playerVotes.put(uuid, matched);
            lastVoteTime.put(uuid, now);

            broadcast(colorize(PREFIX + "§b" + p.getName() + "§a voted for §b" + matched));

            votingObjective.unregister();
            votingObjective = votingBoard.registerNewObjective(
                    "LobbyVotes", "dummy", colorize("§bLive Votes")
            );
            votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

            int max = votes.values().stream().max(Integer::compareTo).orElse(0);
            votes.forEach((track, count) -> {
                if (count > 0) {
                    String entry = (count == max
                            ? ChatColor.GREEN + track
                            : ChatColor.GRAY + track
                    );
                    votingObjective.getScore(entry).setScore(count);
                }
            });
            return true;
        }

        if (name.equals("votelobby")) {
            if (!sender.hasPermission("bbrl.admin")) {
                sender.sendMessage(colorize(PREFIX + "§cYou don't have permission to run this command."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(colorize(PREFIX + "§cUsage: §b/votelobby <open|close|start|reopen|debug>"));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "open" -> {
                    startVoting();
                    sender.sendMessage(colorize(PREFIX + "Voting opened."));
                }
                case "reopen" -> {
                    startVotingSilent();
                    sender.sendMessage(colorize(PREFIX + "Voting reopened silently."));
                }
                case "close" -> {
                    cancelVoting();
                    sender.sendMessage(colorize(PREFIX + "Voting closed."));
                }
                case "start" -> {
                    finishVoting();
                    sender.sendMessage(colorize(PREFIX + "Force-started race."));
                }
                case "debug" -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("This debug command is player-only.");
                        return true;
                    }
                    handleDebugCommand(p);
                }
                default -> sender.sendMessage(colorize(PREFIX + "§cUnknown subcommand."));
            }
            return true;
        }

        if (name.equals("votetrack")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.isOp()) {
                p.sendMessage(colorize(PREFIX + "§cYou don't have permission to run this command."));
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(colorize(PREFIX + "§cUsage: §b/votetrack <add|remove> ..."));
                return true;
            }

            if (args[0].equalsIgnoreCase("add")) {
                if (args.length != 4) {
                    p.sendMessage(colorize(PREFIX + "§cUsage: §b/votetrack add <Name> <Laps> <Pits>"));
                    return true;
                }
                String nameArg = args[1];
                int laps, pits;
                try {
                    laps = Integer.parseInt(args[2]);
                    pits = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(colorize(PREFIX + "§cLaps and Pits must be numbers."));
                    return true;
                }
                getConfig().set("tracks." + nameArg + ".laps", laps);
                getConfig().set("tracks." + nameArg + ".pits", pits);
                saveConfig();
                trackSection = getConfig().getConfigurationSection("tracks");
                p.sendMessage(colorize(PREFIX + "§aTrack '§b" + nameArg + "§a' added with §b" + laps + " §alaps and §b" + pits + " §apits."));
                return true;
            }

            if (args[0].equalsIgnoreCase("remove")) {
                if (args.length != 2) {
                    p.sendMessage(colorize(PREFIX + "§cUsage: §b/votetrack remove <Name>"));
                    return true;
                }
                String nameArg = args[1];
                if (getConfig().contains("tracks." + nameArg)) {
                    getConfig().set("tracks." + nameArg, null);
                    saveConfig();
                    trackSection = getConfig().getConfigurationSection("tracks");
                    p.sendMessage(colorize(PREFIX + "§aTrack '§b" + nameArg + "§a' removed."));
                } else {
                    p.sendMessage(colorize(PREFIX + "§cTrack not found: §b" + nameArg));
                }
                return true;
            }

            p.sendMessage(colorize(PREFIX + "§cUnknown subcommand. Use §badd§c/§bremove§c."));
            return true;
        }

        if (name.equals("voterace")) {
            if (!(sender instanceof Player p)) return true;
            if (!raceJoinOpen || lobbyHeat == null || lobbyEvent == null) {
                p.sendMessage("§cSorry, you can not join the race currently.");
                return true;
            }

            if (lobbyJoinedPlayers.contains(p.getUniqueId())) {
                boolean actuallyInThisHeat = lobbyHeat.getDrivers().values().stream()
                        .anyMatch(d -> d.getTPlayer().getUniqueId().equals(p.getUniqueId()));
                if (!actuallyInThisHeat) {
                    lobbyJoinedPlayers.remove(p.getUniqueId());
                } else {
                    p.sendMessage(colorize(PREFIX + "§cYou have already joined this heat."));
                    return true;
                }
            }

            var maybeDriverRunning = TimingSystemAPI.getDriverFromRunningHeat(p.getUniqueId());
            if (maybeDriverRunning.isPresent()) {
                p.sendMessage("§cYou are already in a heat.");
                return true;
            }

            int nextPos = lobbyHeat.getDrivers().size() + 1;
            EventDatabase.heatDriverNew(p.getUniqueId(), lobbyHeat, nextPos);
            lobbyJoinedPlayers.add(p.getUniqueId());

            boolean resetOk = lobbyHeat.resetHeat();
            getLogger().info("[BBRL-Lobby] resetHeat() after join returned: " + resetOk);

            getLogger().info("[BBRL-Lobby] Added driver " + p.getName()
                    + " to heat #" + lobbyHeat.getHeatNumber()
                    + " at position " + nextPos
                    + " (drivers now " + lobbyHeat.getDrivers().size() + ")");

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (lobbyHeat != null) {
                    boolean ok = lobbyHeat.loadHeat();
                    getLogger().info("[BBRL-Lobby] loadHeat() after 4 ticks returned: " + ok);
                }
            }, 2L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (lobbyHeat != null) {
                    boolean ok = lobbyHeat.loadHeat();
                    getLogger().info("[BBRL-Lobby] loadHeat() after 3 seconds returned: " + ok);
                }
            }, 60L);

            p.sendMessage(colorize(PREFIX + "§aYou joined the race heat."));
            return true;
        }

        return false;
    }

    private void handleDebugCommand(Player p) {
        p.sendMessage("§b[BBRL Debug] §7Heat debug info:");

        if (lobbyHeat == null || lobbyEvent == null) {
            p.sendMessage("§7  - No active lobby-owned heat/event.");
        } else {
            p.sendMessage("§7  - Heat: §f" + lobbyHeat.getHeatNumber() + " §7State: §f" + lobbyHeat.getHeatState());
            p.sendMessage("§7  - Drivers: §f" + lobbyHeat.getDrivers().size());
            p.sendMessage("§7  - Override timer armed: §f" + overrideTimerArmed);
        }

        if (overrideTimerArmed && raceEndOverrideBar != null) {
            p.sendMessage("§7  - Override auto-end is currently active (from first finisher).");
        } else {
            p.sendMessage("§7  - Override auto-end is not active.");
        }

        if (autoFinishTaskId != -1 && autoFinishScheduledAt > 0) {
            long now = System.currentTimeMillis();
            long totalMillis = raceDurationMinutes * 60L * 1000L;
            long elapsed = now - autoFinishScheduledAt;
            long remaining = Math.max(0L, totalMillis - elapsed);
            int secs = (int) (remaining / 1000L);
            p.sendMessage(String.format("§7  - Baseline auto-end in: §f%d:%02d", secs / 60, secs % 60));
        } else {
            p.sendMessage("§7  - Baseline auto-end timer is not scheduled.");
        }
    }

    /* ==========================================================
       VOTING MANAGEMENT
       ========================================================== */

    public void startVoting() {
        if (isVotingOpen) return;
        isVotingOpen = true;

        votes.clear();
        playerVotes.clear();
        votingPlayers.clear();
        voteCountdown = getConfig().getInt("vote-duration", 60);

        votingBar = Bukkit.createBossBar(
                colorize("§bVoting ends in " + formatTime(voteCountdown)),
                BarColor.BLUE, BarStyle.SEGMENTED_10
        );
        votingBar.setProgress(1.0);

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        votingBoard = Objects.requireNonNull(mgr).getNewScoreboard();
        votingObjective = votingBoard.registerNewObjective(
                "LobbyVotes", "dummy", colorize("§bLive Votes")
        );
        votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (trackSection != null) {
            for (String t : trackSection.getKeys(false)) {
                votes.put(t, 0);
            }
        }

        cancelTasks();
        tasksStarted = false;

        String raw = PREFIX + "§rUse §b§l/votejoin §rto join the voting lobby! §b§l<--";
        TextComponent invite = new TextComponent(colorize(raw));
        invite.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!votingPlayers.contains(p.getUniqueId())) {
                p.spigot().sendMessage(invite);
            }
        }
    }

    private void startVotingSilent() {
        if (isVotingOpen) return;
        isVotingOpen = true;

        votes.clear();
        playerVotes.clear();
        votingPlayers.clear();
        voteCountdown = getConfig().getInt("vote-duration", 60);

        votingBar = Bukkit.createBossBar(
                colorize("§bVoting ends in " + formatTime(voteCountdown)),
                BarColor.BLUE, BarStyle.SEGMENTED_10
        );
        votingBar.setProgress(1.0);

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        votingBoard = Objects.requireNonNull(mgr).getNewScoreboard();
        votingObjective = votingBoard.registerNewObjective(
                "LobbyVotes", "dummy", colorize("§bLive Votes")
        );
        votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (trackSection != null) {
            for (String t : trackSection.getKeys(false)) {
                votes.put(t, 0);
            }
        }

        cancelTasks();
        tasksStarted = false;
    }

    private void finishVoting() {
        boolean anyVotesCast = votes.values().stream().anyMatch(v -> v > 0);
        if (!anyVotesCast) {
            broadcast(colorize(PREFIX + "§cVoting failed. Rejoin the votelobby with /votejoin!"));
            cancelVoting();
            Bukkit.getScheduler().runTaskLater(this, this::startVotingSilent, 3 * 20L);
            return;
        }

        String winner = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (winner == null) {
            broadcast(colorize(PREFIX + "§cVoting failed."));
            startVotingSilent();
            return;
        }

        int laps = getConfig().getInt("tracks." + winner + ".laps");
        int pits = getConfig().getInt("tracks." + winner + ".pits");
        List<UUID> racers = new ArrayList<>(votingPlayers);

        recentTracks.add(winner.toLowerCase(Locale.ROOT));
        while (recentTracks.size() > recentTrackMemory) {
            recentTracks.remove(0);
        }

        cancelVoting();
        broadcast(colorize(PREFIX + "§6Voting ended! Winning track: §a" + winner));

        Player starter = racers.isEmpty() ? null : Bukkit.getPlayer(racers.get(0));
        if (starter == null || !starter.isOnline()) {
            starter = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        }
        if (starter == null) {
            broadcast(colorize(PREFIX + "§cNo online player to initialize the race."));
            Bukkit.getScheduler().runTaskLater(this, this::startVotingSilent, 3 * 20L);
            return;
        }

        if (!createLobbyOwnedEvent(starter, winner, laps, pits)) {
            broadcast(colorize(PREFIX + "§cFailed to initialize the race event."));
            Bukkit.getScheduler().runTaskLater(this, this::startVotingSilent, 3 * 20L);
            return;
        }

        sendClickableJoinMessage(winner);

        raceJoinOpen = true;
        int joinWindowSecs = getConfig().getInt("race-join-window-seconds", 30);

        if (joinWindowTaskId != -1) {
            Bukkit.getScheduler().cancelTask(joinWindowTaskId);
        }

        startPreRaceCountdown();

        joinWindowTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                this,
                () -> {
                    raceJoinOpen = false;

                    if (lobbyHeat != null) {
                        getLogger().info("[BBRL-Lobby] Starting countdown for heat " + lobbyHeat.getHeatNumber()
                                + " with " + lobbyHeat.getDrivers().size() + " drivers.");

                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            if (lobbyHeat != null) {
                                lobbyHeat.startCountdown(10);
                            }
                        }, 20L);
                    }
                },
                joinWindowSecs * 20L
        );

        if (autoFinishTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoFinishTaskId);
            autoFinishTaskId = -1;
        }
        autoFinishScheduledAt = System.currentTimeMillis();
        autoFinishTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                this,
                this::endLobbyOwnedRace,
                raceDurationMinutes * 60 * 20L
        );
    }

    private boolean createLobbyOwnedEvent(Player initiator, String trackName, int laps, int pits) {
        cleanupLobbyOwnedEvent();

        Track selectedTrack = resolveTrack(trackName);
        if (selectedTrack == null) {
            getLogger().warning("Track not found: " + trackName + ". Available tracks:");
            for (Track t : TimingSystemAPI.getTracks()) {
                getLogger().warning(" - " + bestTrackIdentifier(t));
            }
            return false;
        }

        UUID uuid = initiator.getUniqueId();
        String displayName = "Racing";
        Optional<Event> maybeEvent = EventDatabase.eventNew(uuid, displayName);
        if (maybeEvent.isEmpty()) return false;
        lobbyEvent = maybeEvent.get();

        lobbyEvent.setTrack(selectedTrack);

        if (!EventDatabase.roundNew(lobbyEvent, RoundType.FINAL, 1)) {
            cleanupLobbyOwnedEvent();
            return false;
        }
        Optional<Round> maybeRound = lobbyEvent.eventSchedule.getRound(1);
        if (maybeRound.isEmpty()) {
            cleanupLobbyOwnedEvent();
            return false;
        }
        lobbyRound = maybeRound.get();

        lobbyRound.createHeat(1);
        Optional<Heat> maybeHeat = lobbyRound.getHeat("R1F1");
        if (maybeHeat.isEmpty()) {
            cleanupLobbyOwnedEvent();
            return false;
        }
        lobbyHeat = maybeHeat.get();

        laps = Math.max(1, laps);
        pits = Math.max(0, pits);
        if (selectedTrack.isStage()) {
            lobbyHeat.setTotalLaps(1);
            lobbyHeat.setTotalPits(0);
        } else {
            lobbyHeat.setTotalLaps(laps);
            lobbyHeat.setTotalPits(pits);
        }

        HeatState state = lobbyHeat.getHeatState();
        if (state != HeatState.SETUP && !lobbyHeat.resetHeat()) {
            cleanupLobbyOwnedEvent();
            return false;
        }

        lobbyHeat.loadHeat();

        overrideTimerArmed = false;
        if (raceEndOverrideBar != null) {
            raceEndOverrideBar.removeAll();
            raceEndOverrideBar = null;
        }
        lobbyJoinedPlayers.clear();
        return true;
    }

    private Track resolveTrack(String trackName) {
        for (Track t : TimingSystemAPI.getTracks()) {
            if (matchesTrackName(t, trackName)) {
                return t;
            }
        }
        return null;
    }

    private boolean matchesTrackName(Track track, String name) {
        if (name == null) return false;
        String needle = normalizeName(name);
        if (needle.isEmpty()) return false;

        String[] candidates = new String[] {
                safeInvokeString(track, "getName"),
                safeInvokeString(track, "getDisplayName"),
                safeInvokeString(track, "getTrackName"),
                safeInvokeString(track, "name"),
                safeInvokeString(track, "getId"),
                safeInvokeString(track, "getIdentifier"),
                track.toString()
        };

        for (String s : candidates) {
            if (s != null && normalizeName(s).equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private String bestTrackIdentifier(Track track) {
        String[] candidates = new String[] {
                safeInvokeString(track, "getName"),
                safeInvokeString(track, "getDisplayName"),
                safeInvokeString(track, "getTrackName"),
                safeInvokeString(track, "name"),
                safeInvokeString(track, "getId"),
                safeInvokeString(track, "getIdentifier"),
                track.toString()
        };
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "<unknown>";
    }

    private String normalizeName(String s) {
        return s.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private String safeInvokeString(Object target, String methodName) {
        try {
            var m = target.getClass().getMethod(methodName);
            Object val = m.invoke(target);
            return (val instanceof String) ? (String) val : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void sendClickableJoinMessage(String trackName) {
        String[] lines = new String[] {
                "§b----------------------------",
                " ",
                "§b§lClick to join the race on §r§l" + trackName + "§b§l!",
                "§6{⚠ OpenBoatUtils mod may be required ⚠}",
                " ",
                "§b----------------------------"
        };
        for (String line : lines) {
            TextComponent component = new TextComponent(line);
            component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/voterace"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.spigot().sendMessage(component);
            }
        }
    }

    private void endLobbyOwnedRace() {
        final Heat heatRef  = lobbyHeat;
        final Round roundRef = lobbyRound;
        final Event eventRef = lobbyEvent;

        if (heatRef == null || roundRef == null || eventRef == null) {
            cleanupLobbyOwnedEvent();
            startVotingSilent();
            return;
        }

        try {
            heatRef.finishHeat();

            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    roundRef.finish(eventRef);
                } catch (Exception ex) {
                    getLogger().warning("Failed to finish round: " + ex.getMessage());
                }
            }, 20L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    eventRef.finish();
                } catch (Exception ex) {
                    getLogger().warning("Failed to finish event: " + ex.getMessage());
                }
            }, 40L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    EventDatabase.removeEventHard(eventRef);
                } catch (Exception ex) {
                    getLogger().warning("Failed to remove event: " + ex.getMessage());
                } finally {
                    cleanupLobbyOwnedEvent();
                    startVotingSilent();
                }
            }, 60L);

        } catch (Exception e) {
            getLogger().warning("Failed to end lobby-owned race cleanly: " + e.getMessage());
            cleanupLobbyOwnedEvent();
            startVotingSilent();
        }
    }

    private void cleanupLobbyOwnedEvent() {
        raceJoinOpen = false;
        if (joinWindowTaskId != -1) {
            Bukkit.getScheduler().cancelTask(joinWindowTaskId);
            joinWindowTaskId = -1;
        }
        if (autoFinishTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoFinishTaskId);
            autoFinishTaskId = -1;
        }
        if (preRaceCountdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(preRaceCountdownTaskId);
            preRaceCountdownTaskId = -1;
        }
        overrideTimerArmed = false;
        if (raceEndOverrideBar != null) {
            raceEndOverrideBar.removeAll();
            raceEndOverrideBar = null;
        }
        lobbyJoinedPlayers.clear();
        lobbyHeat = null;
        lobbyRound = null;
        lobbyEvent = null;
        autoFinishScheduledAt = -1L;
    }

    private void startCountdownTasks() {
        if (tasksStarted) return;
        tasksStarted = true;

        voteTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this, this::onVoteTick, 20L, 20L
        );
        reminderTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this, this::sendVoteReminder, 0L, 20L * 20
        );
    }

    private void onVoteTick() {
        voteCountdown--;
        double progress = (double) voteCountdown / getConfig().getInt("vote-duration", 60);
        votingBar.setProgress(progress);
        votingBar.setTitle(
                colorize("§b" + String.format("Voting ends in %d:%02d", voteCountdown / 60, voteCountdown % 60))
        );

        if (voteCountdown <= 0) {
            finishVoting();
        }
    }

    private void sendVoteReminder() {
        if (!isVotingOpen) return;
        String raw = PREFIX + "§rUse §b§l/votejoin §rto join the voting lobby! §b§l<--";
        TextComponent reminder = new TextComponent(colorize(raw));
        reminder.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!votingPlayers.contains(p.getUniqueId())) {
                p.spigot().sendMessage(reminder);
            }
        }
    }

    private void cancelTasks() {
        if (voteTaskId != -1) {
            Bukkit.getScheduler().cancelTask(voteTaskId);
            voteTaskId = -1;
        }
        if (reminderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reminderTaskId);
            reminderTaskId = -1;
        }
    }

    private void cancelVoting() {
        isVotingOpen = false;
        cancelTasks();

        if (votingBar != null) {
            votingBar.removeAll();
            votingBar = null;
        }
        if (votingBoard != null) {
            for (UUID u : votingPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
            votingBoard = null;
            votingObjective = null;
        }
        votingPlayers.clear();
        votes.clear();
        playerVotes.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (votingPlayers.remove(e.getPlayer().getUniqueId())) {
            if (votingBar != null) votingBar.removePlayer(e.getPlayer());
            e.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (pendingAutoEnd) {
            boolean wasOp = p.isOp();
            tempOpPlayers.add(p.getUniqueId());
            p.setOp(true);
            p.setOp(wasOp);
            tempOpPlayers.remove(p.getUniqueId());
            pendingAutoEnd = false;
            startVotingSilent();
            return;
        }

        if (pendingAutoStart && pendingStart != null) {
            pendingAutoStart = false;
            pendingStart = null;
        }
    }

    @EventHandler
    public void onHeatFinish(HeatFinishEvent e) {
        if (autoFinishTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoFinishTaskId);
            autoFinishTaskId = -1;
        }
        if (lobbyHeat != null && e.getHeat() == lobbyHeat) {
            endLobbyOwnedRace();
        }
    }

    @EventHandler
    public void onDriverFinish(DriverFinishHeatEvent event) {
        if (lobbyHeat == null || event.getDriver().getHeat() != lobbyHeat) {
            return;
        }

        int totalDrivers = lobbyHeat.getDrivers().size();
        int finishedCount = (int) lobbyHeat.getDrivers().values().stream()
                .filter(d -> d.getState() == DriverState.FINISHED)
                .count();

        int requiredFinished;
        if (autoEndPercent <= 0 || totalDrivers <= 0) {
            requiredFinished = Integer.MAX_VALUE;
        } else if (autoEndPercent >= 100) {
            requiredFinished = totalDrivers;
        } else {
            requiredFinished = (int) Math.ceil((totalDrivers * autoEndPercent) / 100.0);
        }

        if (finishedCount >= requiredFinished && requiredFinished != Integer.MAX_VALUE) {
            lobbyHeat.finishHeat();
            return;
        }

        if (event.getDriver().getPosition() == 1 && !overrideTimerArmed) {
            overrideTimerArmed = true;

            raceEndOverrideBar = Bukkit.createBossBar(
                    ChatColor.RED + "Race ends in " + overrideSeconds + " seconds",
                    BarColor.RED, BarStyle.SOLID
            );
            lobbyHeat.getDrivers().values().forEach(driver -> {
                Player member = driver.getTPlayer().getPlayer();
                if (member != null) {
                    raceEndOverrideBar.addPlayer(member);
                    member.sendMessage("§aA player finished the race, you have " + overrideSeconds + "s to finish!");
                }
            });

            AtomicInteger timeLeft = new AtomicInteger(overrideSeconds);
            AtomicInteger taskRef = new AtomicInteger(-1);

            int tid = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    this,
                    () -> {
                        int t = timeLeft.decrementAndGet();
                        if (t <= 0) {
                            raceEndOverrideBar.removeAll();
                            Bukkit.getScheduler().cancelTask(taskRef.get());
                            if (lobbyHeat != null) {
                                lobbyHeat.finishHeat();
                            }
                        } else {
                            raceEndOverrideBar.setProgress(Math.max(0.0, (double) t / (double) overrideSeconds));
                            raceEndOverrideBar.setTitle(ChatColor.RED + "Race ends in " + t + " seconds");
                        }
                    },
                    20L, 20L
            );
            taskRef.set(tid);
        }
    }

    private void startPreRaceCountdown() {
        if (preRaceCountdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(preRaceCountdownTaskId);
        }
        preRaceCountdownSeconds = 45;

        preRaceCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                () -> {
                    if (lobbyHeat == null) {
                        Bukkit.getScheduler().cancelTask(preRaceCountdownTaskId);
                        preRaceCountdownTaskId = -1;
                        return;
                    }

                    for (UUID uuid : lobbyJoinedPlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            boolean actuallyInThisHeat = lobbyHeat.getDrivers().values().stream()
                                    .anyMatch(d -> d.getTPlayer().getUniqueId().equals(uuid));
                            if (!actuallyInThisHeat) {
                                continue;
                            }

                            if (preRaceCountdownSeconds > 11) {
                                p.sendTitle(
                                        ChatColor.WHITE.toString() + preRaceCountdownSeconds,
                                        "",
                                        0, 20, 0
                                );
                            } else if (preRaceCountdownSeconds == 11) {
                                p.sendTitle(ChatColor.WHITE.toString() + "11", "", 0, 20, 0);
                            }
                        }
                    }

                    preRaceCountdownSeconds--;

                    if (preRaceCountdownSeconds <= 11) {
                        Bukkit.getScheduler().cancelTask(preRaceCountdownTaskId);
                        preRaceCountdownTaskId = -1;
                    }
                },
                20L, 20L
        );
    }

    private void broadcast(String msg) {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
    }

    private void teleportPlayerToLobby(Player p) {
        World w = Bukkit.getWorld(getConfig().getString("lobby.world", ""));
        if (w == null) {
            getLogger().warning("World for vote-lobby not found.");
            return;
        }
        double x = getConfig().getDouble("lobby.x");
        double y = getConfig().getDouble("lobby.y");
        double z = getConfig().getDouble("lobby.z");
        float yaw   = (float) getConfig().getDouble("lobby.yaw");
        float pitch = (float) getConfig().getDouble("lobby.pitch");
        p.teleport(new Location(w, x, y, z, yaw, pitch));
    }

    @EventHandler
    public void onTempOpChat(AsyncPlayerChatEvent e) {
        if (tempOpPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(colorize(PREFIX + "§cPlease wait..."));
        }
    }

    @EventHandler
    public void onTempOpCommand(PlayerCommandPreprocessEvent e) {
        if (tempOpPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(colorize(PREFIX + "§cPlease wait..."));
        }
    }

    private static String formatTime(int totalSeconds) {
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }

    private static class RaceStartHelper {
        private final JavaPlugin plugin;
        private final Player runner;
        private final List<UUID> racers;

        RaceStartHelper(JavaPlugin plugin, Player runner, List<UUID> racers) {
            this.plugin = plugin;
            this.runner = runner;
            this.racers = racers;
        }

        void start() {
            BossBar bar = Bukkit.createBossBar(
                    colorize("§bRace starting in 0:40"),
                    BarColor.BLUE, BarStyle.SOLID
            );
            bar.setProgress(1.0);
            racers.forEach(u -> {
                Player p = Bukkit.getPlayer(u);
                if (p != null) bar.addPlayer(p);
            });

            AtomicInteger timeLeft = new AtomicInteger(40);
            AtomicInteger taskRef = new AtomicInteger(-1);

            int tid = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    plugin,
                    () -> {
                        int t = timeLeft.decrementAndGet();
                        if (t <= 0) {
                            bar.removeAll();
                            Bukkit.getScheduler().cancelTask(taskRef.get());
                        } else {
                            bar.setProgress((double) t / 40.0);
                            bar.setTitle(
                                    colorize("§b" + String.format("Race starting in %d:%02d", t / 60, t % 60))
                            );
                        }
                    },
                    20L, 20L
            );
            taskRef.set(tid);
        }
    }

    public static class VoteTabCompleter implements TabCompleter {
        private final Main plugin;

        public VoteTabCompleter(Main plugin) {
            this.plugin = plugin;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player)) return Collections.emptyList();
            if (args.length == 1) {
                ConfigurationSection section = plugin.getConfig().getConfigurationSection("tracks");
                if (section == null) return Collections.emptyList();
                String typed = args[0].toLowerCase();
                List<String> matches = new ArrayList<>();
                for (String track : section.getKeys(false)) {
                    if (track.toLowerCase().startsWith(typed)) {
                        matches.add(track);
                    }
                }
                return matches;
            }
            return Collections.emptyList();
        }
    }

    private class VoteTrackTabCompleter implements TabCompleter {
        private final Main pluginRef;

        VoteTrackTabCompleter(Main plugin) {
            this.pluginRef = plugin;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player p) || !p.isOp()) return Collections.emptyList();
            if (!command.getName().equalsIgnoreCase("votetrack")) return Collections.emptyList();

            if (args.length == 1) {
                return Arrays.asList("add", "remove");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                ConfigurationSection section = pluginRef.getConfig().getConfigurationSection("tracks");
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

    private static class PendingStart {
        final List<UUID> racers;
        PendingStart(List<UUID> racers) { this.racers = racers; }
    }
}
