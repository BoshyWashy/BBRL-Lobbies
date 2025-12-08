package com.example.bbrl;

import me.makkuusen.timing.system.api.events.HeatFinishEvent;
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

    /* ===================  ORIGINAL FIELDS  =================== */
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

    /* ===================  NEW FIELDS  =================== */
    // cooldown per player (ticks)
    private int voteCooldownTicks;
    private final Map<UUID, Long> lastVoteTime = new HashMap<>();

    // recent track memory
    private final List<String> recentTracks = new ArrayList<>();
    private int recentTrackMemory;

    // race duration
    private int raceDurationMinutes;

    /* ==================================================== */
    private static final String PREFIX = "&7[&b&lRace Lobby&7] &r";
    private static String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /* ==================================================== */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        trackSection = getConfig().getConfigurationSection("tracks");

        /* --------------  LOAD NEW CONFIG  -------------- */
        voteCooldownTicks = getConfig().getInt("vote-cooldown-seconds", 3) * 20;
        recentTrackMemory = getConfig().getInt("recent-track-memory", 2);
        raceDurationMinutes = getConfig().getInt("race-duration-minutes", 8);

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

        Bukkit.getPluginManager().registerEvents(this, this);

        startVoting();   // first open – broadcasts
    }

    @Override
    public void onDisable() {
        cancelVoting();
    }

    /* ==========================================================
       COMMAND PROCESSING
       ========================================================== */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        /* ---------------  votejoin  --------------- */
        if (name.equals("votejoin")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(colorize(PREFIX + "&cVoting isn't open yet."));
                return true;
            }

            boolean firstJoin = votingPlayers.isEmpty();
            if (votingPlayers.add(p.getUniqueId())) {
                teleportPlayerToLobby(p);
                p.sendMessage(colorize(PREFIX + "&bYou joined the voting lobby."));

                if (votingBoard != null) p.setScoreboard(votingBoard);
                if (votingBar != null) votingBar.addPlayer(p);

                if (firstJoin) startCountdownTasks();
            } else {
                p.sendMessage(colorize(PREFIX + "&cYou're already in the voting lobby."));
            }
            return true;
        }

        /* ---------------  voteleave  --------------- */
        if (name.equals("voteleave")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (votingPlayers.remove(p.getUniqueId())) {
                p.sendMessage(colorize(PREFIX + "&cYou left the voting lobby."));
                if (votingBar != null) votingBar.removePlayer(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            return true;
        }

        /* ---------------  vote  --------------- */
        if (name.equals("vote")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(colorize(PREFIX + "&cVoting isn't open right now."));
                return true;
            }
            if (!votingPlayers.contains(p.getUniqueId())) {
                p.sendMessage(colorize(PREFIX + "&cJoin first with &b/votejoin&c."));
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(colorize(PREFIX + "&cUsage: &b/vote <track>"));
                return true;
            }

            /* --------------  COOLDOWN CHECK  -------------- */
            long now = System.currentTimeMillis();
            Long last = lastVoteTime.get(p.getUniqueId());
            if (last != null && now - last < voteCooldownTicks * 50L) {
                p.sendMessage(colorize(PREFIX + "&cPlease wait before voting again."));
                return true;
            }

            String input = args[0];
            String matched = trackSection.getKeys(false).stream()
                    .filter(k -> k.equalsIgnoreCase(input))
                    .findFirst().orElse(null);

            if (matched == null) {
                p.sendMessage(colorize(PREFIX + "&cThis track isn't available for races."));
                return true;
            }

            /* --------------  RECENT-TRACK BLOCK  -------------- */
            if (recentTracks.contains(matched.toLowerCase(Locale.ROOT))) {
                p.sendMessage(colorize(PREFIX + "&cSorry, this track was voted on within the last " +
                        recentTrackMemory + " race(s)."));
                return true;
            }

            UUID uuid = p.getUniqueId();
            String previous = playerVotes.get(uuid);
            if (matched.equals(previous)) {
                p.sendMessage(colorize(PREFIX + "&cYou're already voting for &b" + matched));
                return true;
            }

            if (previous != null) {
                votes.put(previous, votes.get(previous) - 1);
            }
            int cnt = votes.getOrDefault(matched, 0) + 1;
            votes.put(matched, cnt);
            playerVotes.put(uuid, matched);
            lastVoteTime.put(uuid, now);          // record time

            broadcast(colorize(PREFIX + "&b" + p.getName() + "&a voted for &b" + matched));

            // refresh sidebar
            votingObjective.unregister();
            votingObjective = votingBoard.registerNewObjective(
                    "LobbyVotes", "dummy", colorize("&bLive Votes")
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

        /* ---------------  votelobby  --------------- */
        if (name.equals("votelobby")) {
            if (!sender.hasPermission("bbrl.admin")) {
                sender.sendMessage(colorize(PREFIX + "&cYou don't have permission to run this command."));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(colorize(PREFIX + "&cUsage: &b/votelobby <open|close|start|reopen>"));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "open" -> {
                    startVoting();               // broadcasts
                    sender.sendMessage(colorize(PREFIX + "Voting opened."));
                }
                case "reopen" -> {
                    startVotingSilent();         // silent
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
                default -> sender.sendMessage(colorize(PREFIX + "&cUnknown subcommand."));
            }
            return true;
        }

        /* ---------------  votetrack  --------------- */
        if (name.equals("votetrack")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.isOp()) {
                p.sendMessage(colorize(PREFIX + "&cYou don't have permission to run this command."));
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(colorize(PREFIX + "&cUsage: &b/votetrack <add|remove> ..."));
                return true;
            }

            if (args[0].equalsIgnoreCase("add")) {
                if (args.length != 4) {
                    p.sendMessage(colorize(PREFIX + "&cUsage: &b/votetrack add <Name> <Laps> <Pits>"));
                    return true;
                }
                String nameArg = args[1];
                int laps, pits;
                try {
                    laps = Integer.parseInt(args[2]);
                    pits = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(colorize(PREFIX + "&cLaps and Pits must be numbers."));
                    return true;
                }
                getConfig().set("tracks." + nameArg + ".laps", laps);
                getConfig().set("tracks." + nameArg + ".pits", pits);
                saveConfig();
                trackSection = getConfig().getConfigurationSection("tracks");
                p.sendMessage(colorize(PREFIX + "&aTrack '&b" + nameArg + "&a' added with &b" + laps + " &alaps and &b" + pits + " &apits."));
                return true;
            }

            if (args[0].equalsIgnoreCase("remove")) {
                if (args.length != 2) {
                    p.sendMessage(colorize(PREFIX + "&cUsage: &b/votetrack remove <Name>"));
                    return true;
                }
                String nameArg = args[1];
                if (getConfig().contains("tracks." + nameArg)) {
                    getConfig().set("tracks." + nameArg, null);
                    saveConfig();
                    trackSection = getConfig().getConfigurationSection("tracks");
                    p.sendMessage(colorize(PREFIX + "&aTrack '&b" + nameArg + "&a' removed."));
                } else {
                    p.sendMessage(colorize(PREFIX + "&cTrack not found: &b" + nameArg));
                }
                return true;
            }

            p.sendMessage(colorize(PREFIX + "&cUnknown subcommand. Use &badd&c/&bremove&c."));
            return true;
        }

        return false;
    }

    /* ==========================================================
       VOTING MANAGEMENT
       ========================================================== */

    // ORIGINAL – broadcasts invite
    public void startVoting() {
        if (isVotingOpen) return;
        isVotingOpen = true;

        votes.clear();
        playerVotes.clear();
        votingPlayers.clear();
        voteCountdown = getConfig().getInt("vote-duration", 60);

        votingBar = Bukkit.createBossBar(
                colorize("&bVoting ends in " + formatTime(voteCountdown)),
                BarColor.BLUE, BarStyle.SEGMENTED_10
        );
        votingBar.setProgress(1.0);

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        votingBoard = Objects.requireNonNull(mgr).getNewScoreboard();
        votingObjective = votingBoard.registerNewObjective(
                "LobbyVotes", "dummy", colorize("&bLive Votes")
        );
        votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (trackSection != null) {
            for (String t : trackSection.getKeys(false)) {
                votes.put(t, 0);
            }
        }

        cancelTasks();
        tasksStarted = false;

        /* --------------  BROADCAST INVITE  -------------- */
        String raw = PREFIX + "&rUse &b&l/votejoin &rto join the voting lobby! &b&l<--";
        TextComponent invite = new TextComponent(colorize(raw));
        invite.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!votingPlayers.contains(p.getUniqueId())) {   // only non-members
                p.spigot().sendMessage(invite);
            }
        }
    }

    // NEW – identical but NO broadcast
    private void startVotingSilent() {
        if (isVotingOpen) return;
        isVotingOpen = true;

        votes.clear();
        playerVotes.clear();
        votingPlayers.clear();
        voteCountdown = getConfig().getInt("vote-duration", 60);

        votingBar = Bukkit.createBossBar(
                colorize("&bVoting ends in " + formatTime(voteCountdown)),
                BarColor.BLUE, BarStyle.SEGMENTED_10
        );
        votingBar.setProgress(1.0);

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        votingBoard = Objects.requireNonNull(mgr).getNewScoreboard();
        votingObjective = votingBoard.registerNewObjective(
                "LobbyVotes", "dummy", colorize("&bLive Votes")
        );
        votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (trackSection != null) {
            for (String t : trackSection.getKeys(false)) {
                votes.put(t, 0);
            }
        }

        cancelTasks();
        tasksStarted = false;
        // NO BROADCAST
    }

    /* ---------------------------------------------------------- */
    private void finishVoting() {
        boolean anyVotesCast = votes.values().stream().anyMatch(v -> v > 0);
        if (!anyVotesCast) {
            broadcast(colorize(PREFIX + "&cVoting failed."));
            cancelVoting();
            Bukkit.getScheduler().runTaskLater(this, this::startVotingSilent, 3 * 20L);
            return;
        }

        String winner = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (winner == null) {
            broadcast(colorize(PREFIX + "&cVoting failed."));
            startVotingSilent();
            return;
        }

        int laps = getConfig().getInt("tracks." + winner + ".laps");
        int pits = getConfig().getInt("tracks." + winner + ".pits");
        List<UUID> racers = new ArrayList<>(votingPlayers);

        /* --------------  UPDATE RECENT HISTORY  -------------- */
        recentTracks.add(winner.toLowerCase(Locale.ROOT));
        while (recentTracks.size() > recentTrackMemory) {
            recentTracks.remove(0);
        }

        cancelVoting();

        broadcast(colorize(PREFIX + "&6Voting ended! Winning track: &a" + winner));

        if (!racers.isEmpty()) {
            Player starter = Bukkit.getPlayer(racers.get(0));
            if (starter != null && starter.isOnline()) {
                boolean wasOp = starter.isOp();
                tempOpPlayers.add(starter.getUniqueId());
                starter.setOp(true);
                Bukkit.dispatchCommand(
                        starter,
                        String.format("race create %s %d %d", winner, laps, pits)
                );
                starter.setOp(wasOp);
                tempOpPlayers.remove(starter.getUniqueId());

                new RaceStartHelper(this, starter, racers).start();
                return;
            }
        }

        broadcast(colorize(PREFIX + "&cVoting failed."));
        startVotingSilent();
    }

    /* ---------------------------------------------------------- */
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
                colorize("&b" + String.format("Voting ends in %d:%02d", voteCountdown / 60, voteCountdown % 60))
        );

        if (voteCountdown <= 0) {
            finishVoting();
        }
    }

    private void sendVoteReminder() {
        if (!isVotingOpen) return;
        String raw = PREFIX + "&rUse &b&l/votejoin &rto join the voting lobby! &b&l<--";
        TextComponent reminder = new TextComponent(colorize(raw));
        reminder.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!votingPlayers.contains(p.getUniqueId())) {   // only non-members
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

    /* ---------------------------------------------------------- */
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
            Bukkit.dispatchCommand(p, "race end");
            p.setOp(wasOp);
            tempOpPlayers.remove(p.getUniqueId());
            pendingAutoEnd = false;
            startVotingSilent();               // silent reopen
            return;
        }

        if (pendingAutoStart && pendingStart != null) {
            boolean wasOp = p.isOp();
            tempOpPlayers.add(p.getUniqueId());
            p.setOp(true);
            Bukkit.dispatchCommand(p, "race start");
            p.setOp(wasOp);
            tempOpPlayers.remove(p.getUniqueId());

            int finishId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                    this,
                    () -> {
                        Player ender = p.isOnline() ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                        if (ender != null) {
                            boolean wasOp2 = ender.isOp();
                            tempOpPlayers.add(ender.getUniqueId());
                            ender.setOp(true);
                            Bukkit.dispatchCommand(ender, "race end");
                            ender.setOp(wasOp2);
                            tempOpPlayers.remove(ender.getUniqueId());
                            startVotingSilent();   // silent reopen
                        } else {
                            pendingAutoEnd = true;
                        }
                    },
                    raceDurationMinutes * 60 * 20L
            );
            autoFinishTaskId = finishId;
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
        broadcast(colorize(PREFIX + "&rRejoin the voting lobby with &b&l/votejoin &rto start a new race! &b&l<--"));
        startVotingSilent();                              // silent reopen
    }

    /* ----------------  HELPERS  ---------------- */
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
            e.getPlayer().sendMessage(colorize(PREFIX + "&cPlease wait..."));
        }
    }

    @EventHandler
    public void onTempOpCommand(PlayerCommandPreprocessEvent e) {
        if (tempOpPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(colorize(PREFIX + "&cPlease wait..."));
        }
    }

    private static String formatTime(int totalSeconds) {
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }

    /* ==========================================================
       INNER CLASSES
       ========================================================== */
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
                    colorize("&bRace starting in 0:40"),
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

                            Player starterToUse = null;
                            if (runner != null && runner.isOnline()) {
                                starterToUse = runner;
                            } else {
                                for (UUID u : racers) {
                                    Player p = Bukkit.getPlayer(u);
                                    if (p != null && p.isOnline()) {
                                        starterToUse = p;
                                        break;
                                    }
                                }
                            }

                            final Player finalStarter = starterToUse;
                            if (finalStarter != null) {
                                boolean wasOp = finalStarter.isOp();
                                if (plugin instanceof Main m) {
                                    m.tempOpPlayers.add(finalStarter.getUniqueId());
                                }
                                finalStarter.setOp(true);
                                Bukkit.dispatchCommand(finalStarter, "race start");
                                finalStarter.setOp(wasOp);
                                if (plugin instanceof Main m) {
                                    m.tempOpPlayers.remove(finalStarter.getUniqueId());
                                }

                                int durationMinutes = ((Main) plugin).raceDurationMinutes;
                                int finishId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                                        plugin,
                                        () -> {
                                            Player ender = finalStarter.isOnline()
                                                    ? finalStarter
                                                    : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                                            if (ender != null) {
                                                boolean wasOp2 = ender.isOp();
                                                if (plugin instanceof Main m) {
                                                    m.tempOpPlayers.add(ender.getUniqueId());
                                                }
                                                ender.setOp(true);
                                                Bukkit.dispatchCommand(ender, "race end");
                                                ender.setOp(wasOp2);
                                                if (plugin instanceof Main m) {
                                                    m.tempOpPlayers.remove(ender.getUniqueId());
                                                }
                                                ((Main) plugin).startVotingSilent();
                                            } else {
                                                if (plugin instanceof Main m) {
                                                    m.pendingAutoEnd = true;
                                                }
                                            }
                                        },
                                        durationMinutes * 60 * 20L
                                );
                                if (plugin instanceof Main m) {
                                    m.autoFinishTaskId = finishId;
                                }
                            } else {
                                if (plugin instanceof Main m) {
                                    m.pendingAutoStart = true;
                                    m.pendingStart = new PendingStart(new ArrayList<>(racers));
                                }
                            }
                        } else {
                            bar.setProgress((double) t / 40.0);
                            bar.setTitle(
                                    colorize("&b" + String.format("Race starting in %d:%02d", t / 60, t % 60))
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
