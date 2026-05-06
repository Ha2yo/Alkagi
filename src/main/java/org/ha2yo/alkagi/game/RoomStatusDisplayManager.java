package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RoomStatusDisplayManager {

    private static final String STATUS_DISPLAY_TAG = "alkagi_status_display";
    private static final String STATUS_HEAD_TAG = "alkagi_status_head";
    private static final double TEAM_COLUMN_X_OFFSET = 16.0D;
    private static final double TIME_Y_OFFSET = 13.0D;
    private static final double RESULT_Y_OFFSET = 2.5D;
    private static final double HEAD_WALL_OFFSET = 0.03D;

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final Map<Integer, GameSession> sessions;
    private final Map<DisplayKey, TextDisplay> timeDisplays = new java.util.HashMap<>();
    private final Map<DisplayKey, TextDisplay> redDisplays = new java.util.HashMap<>();
    private final Map<DisplayKey, TextDisplay> blueDisplays = new java.util.HashMap<>();
    private BukkitTask updateTask;

    public RoomStatusDisplayManager(JavaPlugin plugin, ArenaData arenaData, Map<Integer, GameSession> sessions) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.sessions = sessions;
    }

    public void start() {
        cleanupTaggedDisplays();
        refreshAll();
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (TextDisplay display : timeDisplays.values()) {
            if (display.isValid()) {
                display.remove();
            }
        }
        timeDisplays.clear();
        for (TextDisplay display : redDisplays.values()) {
            if (display.isValid()) {
                display.remove();
            }
        }
        redDisplays.clear();
        for (TextDisplay display : blueDisplays.values()) {
            if (display.isValid()) {
                display.remove();
            }
        }
        blueDisplays.clear();
    }

    public void refreshAll() {
        for (BoardArenaData boardArena : arenaData.getBoardArenas()) {
            refresh(boardArena);
        }
    }

    private void refresh(BoardArenaData boardArena) {
        int boardId = boardArena.getId();
        List<Location> locations = boardArena.getStatusDisplayLocations();
        if (locations.isEmpty()) {
            removeDisplay(boardId);
            return;
        }

        GameSession session = sessions.get(boardId);
        if (session == null) {
            removeDisplay(boardId);
            return;
        }
        if (session.getGameState() == GameState.WAITING) {
            removeDisplay(boardId);
            return;
        }
        if (session.getGameState() == GameState.ENDING) {
            refreshResultDisplays(boardId, locations, session);
            return;
        }

        TeamRows teamRows = collectTeamRows(session);
        String elapsedTime = formatElapsedTime(session.getElapsedPlayingSeconds());
        Set<DisplayKey> activeKeys = new HashSet<>();
        for (int i = 0; i < locations.size(); i++) {
            Location location = locations.get(i);
            if (location.getWorld() == null) {
                continue;
            }
            DisplayKey key = new DisplayKey(boardId, i);
            activeKeys.add(key);
            refreshStatusDisplay(key, location, teamRows, elapsedTime);
        }
        removeInactiveDisplays(boardId, activeKeys);
    }

    private void refreshResultDisplays(int boardId, List<Location> locations, GameSession session) {
        Set<DisplayKey> activeKeys = new HashSet<>();
        for (int i = 0; i < locations.size(); i++) {
            Location location = locations.get(i);
            if (location.getWorld() == null) {
                continue;
            }
            DisplayKey key = new DisplayKey(boardId, i);
            activeKeys.add(key);
            TextDisplay resultDisplay = getOrCreateTeamDisplay(timeDisplays, key, offsetLocation(location, 0.0D, RESULT_Y_OFFSET));
            configureDisplay(resultDisplay, resultDisplay.getLocation(), 18.0F);
            resultDisplay.text(buildResultText(session));
        }
        removeInactiveBoardDisplays(timeDisplays, boardId, activeKeys);
        removeBoardDisplays(redDisplays, boardId);
        removeBoardDisplays(blueDisplays, boardId);
    }

    private void refreshStatusDisplay(DisplayKey key, Location location, TeamRows teamRows, String elapsedTime) {
        TextDisplay timeDisplay = getOrCreateTeamDisplay(timeDisplays, key, offsetLocation(location, 0.0D, TIME_Y_OFFSET));
        configureDisplay(timeDisplay, timeDisplay.getLocation(), 20.0F);
        timeDisplay.text(buildTimeText(elapsedTime));

        TextDisplay redDisplay = getOrCreateTeamDisplay(redDisplays, key, offsetLocation(location, -TEAM_COLUMN_X_OFFSET, 0.0D));
        configureDisplay(redDisplay, redDisplay.getLocation(), 14.0F);
        redDisplay.text(buildTeamText("RED", NamedTextColor.RED, teamRows.redRemainingPieces(), teamRows.red()));

        TextDisplay blueDisplay = getOrCreateTeamDisplay(blueDisplays, key, offsetLocation(location, TEAM_COLUMN_X_OFFSET, 0.0D));
        configureDisplay(blueDisplay, blueDisplay.getLocation(), 14.0F);
        blueDisplay.text(buildTeamText("BLUE", NamedTextColor.BLUE, teamRows.blueRemainingPieces(), teamRows.blue()));
    }

    private TextDisplay getOrCreateTeamDisplay(Map<DisplayKey, TextDisplay> displays, DisplayKey key, Location location) {
        TextDisplay display = displays.get(key);
        if (display == null || !display.isValid()) {
            display = spawnDisplay(location);
            displays.put(key, display);
        } else if (!sameBlockPosition(display.getLocation(), location)) {
            display.teleport(location);
        }
        return display;
    }

    private TextDisplay spawnDisplay(Location location) {
        TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
        display.addScoreboardTag(STATUS_DISPLAY_TAG);
        display.setPersistent(false);
        return display;
    }

    private void configureDisplay(TextDisplay display, Location location, float scale) {
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDuration(1);
        display.setLineWidth(260);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setTextOpacity((byte) 255);
        display.setViewRange(256.0F);
        display.setRotation(location.getYaw(), location.getPitch());
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f(),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        ));
    }

    private Component buildTimeText(String elapsedTime) {
        return Component.text(elapsedTime, NamedTextColor.WHITE, TextDecoration.BOLD);
    }

    private Component buildResultText(GameSession session) {
        TeamType winner = session.getResultWinner();
        Component resultText;
        if (winner == null) {
            resultText = Component.text("무승부!", NamedTextColor.YELLOW, TextDecoration.BOLD);
        } else {
            resultText = Component.text(winner.getDisplayName() + " 승리!", winner.getColor(), TextDecoration.BOLD);
        }

        UUID mvpPlayerId = session.getMvpPlayerId();
        if (mvpPlayerId != null) {
            TeamType mvpTeam = session.getPlayerTeam(mvpPlayerId);
            NamedTextColor mvpColor = mvpTeam == null ? NamedTextColor.AQUA : mvpTeam.getColor();
            resultText = resultText
                .append(Component.newline())
                .append(Component.text("MVP: ", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(getPlayerName(mvpPlayerId), mvpColor, TextDecoration.BOLD));
        }

        UUID unluckyPlayerId = session.getUnluckyPlayerId();
        if (unluckyPlayerId == null) {
            return resultText;
        }

        TeamType unluckyTeam = session.getPlayerTeam(unluckyPlayerId);
        NamedTextColor unluckyColor = unluckyTeam == null ? NamedTextColor.AQUA : unluckyTeam.getColor();
        return resultText
            .append(Component.newline())
            .append(Component.text("WORST: ", NamedTextColor.GRAY, TextDecoration.BOLD))
            .append(Component.text(getPlayerName(unluckyPlayerId), unluckyColor, TextDecoration.BOLD));
    }

    private Component buildTeamText(String header, NamedTextColor color, int remainingPieces, List<PlayerRow> players) {
        Component text = Component.text(header, color, TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text(remainingPieces + "개 남음", NamedTextColor.WHITE));
        for (PlayerRow player : players) {
            text = text.append(Component.newline())
                .append(Component.text(player.name(), color));
        }
        return text;
    }

    private TeamRows collectTeamRows(GameSession session) {
        List<PlayerRow> redPlayers = getTeamPlayerRows(session, TeamType.RED);
        List<PlayerRow> bluePlayers = getTeamPlayerRows(session, TeamType.BLUE);
        return new TeamRows(
            redPlayers,
            bluePlayers,
            session.getAlivePieceCount(TeamType.RED),
            session.getAlivePieceCount(TeamType.BLUE)
        );
    }

    private String formatElapsedTime(long elapsedSeconds) {
        long minutes = elapsedSeconds / 60L;
        long seconds = elapsedSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private List<PlayerRow> getTeamPlayerRows(GameSession session, TeamType teamType) {
        return session.getTeamDataMap().get(teamType).getPlayers().stream()
            .map(playerId -> new PlayerRow(playerId, getPlayerName(playerId)))
            .sorted(Comparator.comparing(PlayerRow::name))
            .toList();
    }

    private String getPlayerName(UUID playerId) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerId);
        String name = player.getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    private Location offsetLocation(Location baseLocation, double xOffset, double yOffset) {
        Vector forward = baseLocation.getDirection().normalize();
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        if (right.lengthSquared() < 0.0001D) {
            right = new Vector(1.0D, 0.0D, 0.0D);
        } else {
            right.normalize();
        }
        Vector up = new Vector(0.0D, 1.0D, 0.0D);
        return baseLocation.clone()
            .add(right.multiply(xOffset))
            .add(up.multiply(yOffset))
            .add(forward.multiply(HEAD_WALL_OFFSET));
    }

    private void removeDisplay(int boardId) {
        removeBoardDisplays(timeDisplays, boardId);
        removeBoardDisplays(redDisplays, boardId);
        removeBoardDisplays(blueDisplays, boardId);
    }

    private void removeInactiveDisplays(int boardId, Set<DisplayKey> activeKeys) {
        removeInactiveBoardDisplays(timeDisplays, boardId, activeKeys);
        removeInactiveBoardDisplays(redDisplays, boardId, activeKeys);
        removeInactiveBoardDisplays(blueDisplays, boardId, activeKeys);
    }

    private void removeBoardDisplays(Map<DisplayKey, TextDisplay> displays, int boardId) {
        displays.entrySet().removeIf(entry -> {
            if (entry.getKey().boardId() != boardId) {
                return false;
            }
            TextDisplay display = entry.getValue();
            if (display.isValid()) {
                display.remove();
            }
            return true;
        });
    }

    private void removeInactiveBoardDisplays(Map<DisplayKey, TextDisplay> displays, int boardId, Set<DisplayKey> activeKeys) {
        displays.entrySet().removeIf(entry -> {
            DisplayKey key = entry.getKey();
            if (key.boardId() != boardId || activeKeys.contains(key)) {
                return false;
            }
            TextDisplay display = entry.getValue();
            if (display.isValid()) {
                display.remove();
            }
            return true;
        });
    }

    private boolean sameBlockPosition(Location first, Location second) {
        return first.getWorld() != null
            && second.getWorld() != null
            && first.getWorld().getUID().equals(second.getWorld().getUID())
            && first.distanceSquared(second) < 0.0001D
            && Math.abs(first.getYaw() - second.getYaw()) < 0.001F
            && Math.abs(first.getPitch() - second.getPitch()) < 0.001F;
    }

    private void cleanupTaggedDisplays() {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(STATUS_DISPLAY_TAG)
                        || entity.getScoreboardTags().contains(STATUS_HEAD_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private record PlayerRow(UUID playerId, String name) {
    }

    private record DisplayKey(int boardId, int index) {
    }

    private record TeamRows(List<PlayerRow> red, List<PlayerRow> blue, int redRemainingPieces, int blueRemainingPieces) {

        private int rowCount() {
            return Math.max(red.size(), blue.size());
        }
    }
}
