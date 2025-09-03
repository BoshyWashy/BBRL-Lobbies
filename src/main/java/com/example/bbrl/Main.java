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

    private final Set<UUID> tempOpPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        trackSection = getConfig().getConfigurationSection("tracks");

        PluginCommand joinCmd = Objects.requireNonNull(getCommand("votejoin"));
        joinCmd.setExecutor(this);

        PluginCommand leaveCmd = Objects.requireNonNull(getCommand("voteleave"));
        leaveCmd.setExecutor(this);

        PluginCommand voteCmd = Objects.requireNonNull(getCommand("vote"));
        voteCmd.setExecutor(this);
        voteCmd.setTabCompleter(new VoteTabCompleter(this));

        PluginCommand lobbyCmd = Objects.requireNonNull(getCommand("votelobby"));
        lobbyCmd.setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        startVoting();
    }

    @Override
    public void onDisable() {
        cancelVoting();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("votejoin")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(ChatColor.RED + "Voting isn't open yet.");
                return true;
            }

            boolean firstJoin = votingPlayers.isEmpty();
            if (votingPlayers.add(p.getUniqueId())) {
                teleportPlayerToLobby(p);
                p.sendMessage(ChatColor.AQUA + "You joined the voting lobby.");

                if (votingBoard != null) {
                    p.setScoreboard(votingBoard);
                }
                if (votingBar != null) {
                    votingBar.addPlayer(p);
                }

                if (firstJoin) {
                    startCountdownTasks();
                }
            } else {
                p.sendMessage(ChatColor.RED + "You're already in the voting lobby.");
            }
            return true;
        }

        if (name.equals("voteleave")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (votingPlayers.remove(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You left the voting lobby.");
                if (votingBar != null) {
                    votingBar.removePlayer(p);
                }
                p.setScoreboard(
                        Bukkit.getScoreboardManager().getMainScoreboard()
                );
            }
            return true;
        }

        if (name.equals("vote")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!isVotingOpen) {
                p.sendMessage(ChatColor.RED + "Voting isn't open right now.");
                return true;
            }
            if (!votingPlayers.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "Join first with /votejoin.");
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Usage: /vote <track>");
                return true;
            }

            String input = args[0];
            String matched = trackSection.getKeys(false).stream()
                    .filter(k -> k.equalsIgnoreCase(input))
                    .findFirst()
                    .orElse(null);

            if (matched == null) {
                p.sendMessage(ChatColor.RED + "This track isn't available for races.");
                return true;
            }

            UUID uuid = p.getUniqueId();
            String previous = playerVotes.get(uuid);
            if (matched.equals(previous)) {
                p.sendMessage(ChatColor.RED + "You're already voting for " + matched);
                return true;
            }

            if (previous != null) {
                votes.put(previous, votes.get(previous) - 1);
            }

            int cnt = votes.getOrDefault(matched, 0) + 1;
            votes.put(matched, cnt);
            playerVotes.put(uuid, matched);

            p.sendMessage(ChatColor.AQUA + "You voted for " + matched);
            broadcast(
                    ChatColor.AQUA + p.getName() +
                            ChatColor.GREEN + " voted for " +
                            ChatColor.GREEN + matched
            );

            votingObjective.unregister();
            votingObjective = votingBoard.registerNewObjective(
                    "LobbyVotes", "dummy", ChatColor.AQUA + "Live Votes"
            );
            votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

            int max = votes.values().stream()
                    .max(Integer::compareTo).orElse(0);
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
                sender.sendMessage(ChatColor.RED + "You don't have permission to run this command.");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /votelobby <open|close|start>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "open" -> {
                    startVoting();
                    sender.sendMessage("Voting opened.");
                }
                case "close" -> {
                    cancelVoting();
                    sender.sendMessage("Voting closed.");
                }
                case "start" -> {
                    finishVoting();
                    sender.sendMessage("Force-started race.");
                }
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }

        return false;
    }

    public void startVoting() {
        if (isVotingOpen) return;
        isVotingOpen = true;

        votes.clear();
        playerVotes.clear();
        votingPlayers.clear();
        voteCountdown = getConfig().getInt("vote-duration", 60);

        votingBar = Bukkit.createBossBar(
                ChatColor.AQUA + "Voting ends in " + formatTime(voteCountdown),
                BarColor.BLUE, BarStyle.SEGMENTED_10
        );
        votingBar.setProgress(1.0);

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        votingBoard = Objects.requireNonNull(mgr).getNewScoreboard();
        votingObjective = votingBoard.registerNewObjective(
                "LobbyVotes", "dummy", ChatColor.AQUA + "Live Votes"
        );
        votingObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (trackSection != null) {
            for (String t : trackSection.getKeys(false)) {
                votes.put(t, 0);
            }
        }

        cancelTasks();
        tasksStarted = false;

        TextComponent invite = new TextComponent(
                ChatColor.AQUA + "-> Do /votejoin to vote for the next race! <-"
        );
        invite.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(invite);
        }
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
                ChatColor.AQUA +
                        String.format("Voting ends in %d:%02d", voteCountdown / 60, voteCountdown % 60)
        );

        if (voteCountdown <= 0) {
            finishVoting();
        }
    }

    private void sendVoteReminder() {
        if (!isVotingOpen) return;
        TextComponent reminder = new TextComponent(
                ChatColor.AQUA + "-> Do /votejoin to vote for the next race! <-"
        );
        reminder.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/votejoin"
        ));
        Bukkit.getOnlinePlayers().forEach(p -> p.spigot().sendMessage(reminder));
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
                    p.setScoreboard(
                            Bukkit.getScoreboardManager().getMainScoreboard()
                    );
                }
            }
            votingBoard = null;
            votingObjective = null;
        }
        votingPlayers.clear();
        votes.clear();
        playerVotes.clear();
    }

    private void finishVoting() {
        boolean anyVotesCast = votes.values().stream().anyMatch(v -> v > 0);
        if (!anyVotesCast) {
            broadcast(ChatColor.RED + "No votes cast. Restarting vote!");
            cancelVoting();
            Bukkit.getScheduler().runTaskLater(this, this::startVoting, 3 * 20L);
            return;
        }

        String winner = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (winner == null) {
            broadcast(ChatColor.RED + "Voting failed. Restarting vote!");
            startVoting();
            return;
        }

        int laps = getConfig().getInt("tracks." + winner + ".laps");
        int pits = getConfig().getInt("tracks." + winner + ".pits");
        List<UUID> racers = new ArrayList<>(votingPlayers);

        cancelVoting();

        broadcast(
                ChatColor.GOLD +
                        "Voting ended! Winning track: " +
                        ChatColor.GREEN + winner
        );

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

        broadcast(ChatColor.RED + "No one online to start the race. Restarting vote.");
        startVoting();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (votingPlayers.remove(e.getPlayer().getUniqueId())) {
            if (votingBar != null) {
                votingBar.removePlayer(e.getPlayer());
            }
            e.getPlayer().setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard()
            );
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (pendingAutoEnd) {
            Player p = e.getPlayer();
            boolean wasOp = p.isOp();
            tempOpPlayers.add(p.getUniqueId());
            p.setOp(true);
            Bukkit.dispatchCommand(p, "race end");
            p.setOp(wasOp);
            tempOpPlayers.remove(p.getUniqueId());

            pendingAutoEnd = false;
            startVoting();
        }
    }

    @EventHandler
    public void onHeatFinish(HeatFinishEvent e) {
        if (autoFinishTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoFinishTaskId);
            autoFinishTaskId = -1;
        }
        broadcast(ChatColor.AQUA + "Rejoin the voting lobby with /votejoin to start a new race!");
        startVoting();
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
            e.getPlayer().sendMessage(ChatColor.RED + "Please wait...");
        }
    }

    @EventHandler
    public void onTempOpCommand(PlayerCommandPreprocessEvent e) {
        if (tempOpPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Please wait...");
        }
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
                    ChatColor.AQUA + "Race starting in 0:40",
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

                            // start race (temp-op safety)
                            boolean wasOp = runner.isOp();
                            if (runner.isOnline()) {
                                if (plugin instanceof Main m) {
                                    m.tempOpPlayers.add(runner.getUniqueId());
                                }
                                runner.setOp(true);
                                Bukkit.dispatchCommand(runner, "race start");
                                runner.setOp(wasOp);
                                if (plugin instanceof Main m) {
                                    m.tempOpPlayers.remove(runner.getUniqueId());
                                }
                            }

                            // schedule auto-finish in 8 minutes
                            int finishId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                                    plugin,
                                    () -> {
                                        Player ender = runner.isOnline()
                                                ? runner
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
                                            ((Main) plugin).startVoting();
                                        } else {
                                            // No one online: defer ending until next join
                                            if (plugin instanceof Main m) {
                                                m.pendingAutoEnd = true;
                                            }
                                        }
                                    },
                                    8 * 60 * 20L
                            );
                            if (plugin instanceof Main m) {
                                m.autoFinishTaskId = finishId;
                            }
                        } else {
                            bar.setProgress((double) t / 40.0);
                            bar.setTitle(
                                    ChatColor.AQUA +
                                            String.format("Race starting in %d:%02d", t / 60, t % 60)
                            );
                        }
                    },
                    20L, 20L
            );
            taskRef.set(tid);
        }
    }

    private static String formatTime(int totalSeconds) {
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }
}