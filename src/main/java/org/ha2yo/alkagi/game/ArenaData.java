package org.ha2yo.alkagi.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 경기장 좌표와 게임 설정값을 보관하고 config와 직렬화한다.
 */
public final class ArenaData {

    private static final double DEFAULT_PIECE_SIZE = 2.35D;
    private static final double DEFAULT_PIECE_HEIGHT_SCALE = 1.0D;
    private static final double CONTROL_RADIUS_MULTIPLIER = 2.5D;
    private static final int DEFAULT_TURN_TIME_SECONDS = 30;

    private Location lobbyLocation;
    private Location boardPos1;
    private Location boardPos2;
    private Location spectatorLocation;
    private Location bluePlacementLocation;
    private Location redPlacementLocation;
    private final Map<Integer, BoardArenaData> boards = new LinkedHashMap<>();
    private double pieceSize = DEFAULT_PIECE_SIZE;
    private double pieceHeightScale = DEFAULT_PIECE_HEIGHT_SCALE;
    private int turnTimeSeconds = DEFAULT_TURN_TIME_SECONDS;

    /**
     * config.yml에 저장된 경기장 설정을 읽어 메모리 객체로 복원한다.
     */
    public static ArenaData fromConfig(FileConfiguration config) {
        ArenaData arenaData = new ArenaData();
        arenaData.lobbyLocation = readLocation(config, "arena.lobby");
        arenaData.boardPos1 = readLocation(config, "arena.board.pos1");
        arenaData.boardPos2 = readLocation(config, "arena.board.pos2");
        arenaData.spectatorLocation = readLocation(config, "arena.spectator");
        arenaData.bluePlacementLocation = readLocationWithFallback(config, "arena.placement.blue", "arena.placement.black");
        arenaData.redPlacementLocation = readLocationWithFallback(config, "arena.placement.red", "arena.placement.white");
        arenaData.readBoards(config);
        arenaData.syncPrimaryBoardFromFirstConfiguredBoard();
        arenaData.pieceSize = Math.max(0.5D, config.getDouble("settings.piece-size", DEFAULT_PIECE_SIZE));
        arenaData.pieceHeightScale = Math.max(0.1D, config.getDouble("settings.piece-height-scale", DEFAULT_PIECE_HEIGHT_SCALE));
        arenaData.turnTimeSeconds = Math.max(1, config.getInt("settings.turn-time-seconds", DEFAULT_TURN_TIME_SECONDS));
        return arenaData;
    }

    public static ArenaData forBoard(ArenaData source, BoardArenaData boardArena) {
        ArenaData arenaData = new ArenaData();
        arenaData.lobbyLocation = cloneLocation(source.lobbyLocation);
        arenaData.boardPos1 = boardArena.getBoardPos1();
        arenaData.boardPos2 = boardArena.getBoardPos2();
        arenaData.spectatorLocation = boardArena.getSpectatorLocation() != null
            ? boardArena.getSpectatorLocation()
            : cloneLocation(source.spectatorLocation);
        arenaData.bluePlacementLocation = boardArena.getPlacementLocation(TeamType.BLUE);
        arenaData.redPlacementLocation = boardArena.getPlacementLocation(TeamType.RED);
        arenaData.pieceSize = source.pieceSize;
        arenaData.pieceHeightScale = source.pieceHeightScale;
        arenaData.turnTimeSeconds = source.turnTimeSeconds;
        arenaData.boards.put(boardArena.getId(), boardArena);
        return arenaData;
    }

    /**
     * 현재 경기장 설정을 config.yml에 다시 기록한다.
     */
    public void save(FileConfiguration config) {
        writeLocation(config, "arena.lobby", lobbyLocation);
        writeLocation(config, "arena.board.pos1", boardPos1);
        writeLocation(config, "arena.board.pos2", boardPos2);
        writeLocation(config, "arena.spectator", spectatorLocation);
        writeLocation(config, "arena.placement.blue", bluePlacementLocation);
        writeLocation(config, "arena.placement.red", redPlacementLocation);
        config.set("arena.placement.black", null);
        config.set("arena.placement.white", null);
        writeBoards(config);
        config.set("settings.piece-size", pieceSize);
        config.set("settings.piece-height-scale", pieceHeightScale);
        config.set("settings.control-radius", null);
        config.set("settings.turn-time-seconds", turnTimeSeconds);
    }

    public @Nullable Location getLobbyLocation() {
        return cloneLocation(lobbyLocation);
    }

    public void setLobbyLocation(Location lobbyLocation) {
        this.lobbyLocation = lobbyLocation.clone();
    }

    public @Nullable Location getBoardPos1() {
        return cloneLocation(boardPos1);
    }

    public void setBoardPos1(Location boardPos1) {
        this.boardPos1 = boardPos1.clone();
        getOrCreateBoardArena(1).setBoardPos1(boardPos1);
    }

    public @Nullable Location getBoardPos2() {
        return cloneLocation(boardPos2);
    }

    public void setBoardPos2(Location boardPos2) {
        this.boardPos2 = boardPos2.clone();
        getOrCreateBoardArena(1).setBoardPos2(boardPos2);
    }

    public @Nullable Location getSpectatorLocation() {
        return cloneLocation(spectatorLocation);
    }

    public void setSpectatorLocation(Location spectatorLocation) {
        this.spectatorLocation = spectatorLocation.clone();
        getOrCreateBoardArena(1).setSpectatorLocation(spectatorLocation);
    }

    public @Nullable Location getPlacementLocation(TeamType teamType) {
        return switch (teamType) {
            case BLUE -> cloneLocation(bluePlacementLocation);
            case RED -> cloneLocation(redPlacementLocation);
        };
    }

    public void setPlacementLocation(TeamType teamType, Location location) {
        if (teamType == TeamType.BLUE) {
            this.bluePlacementLocation = location.clone();
        } else {
            this.redPlacementLocation = location.clone();
        }
        getOrCreateBoardArena(1).setPlacementLocation(teamType, location);
    }

    public @Nullable Location getPlacementOrBoardCenterLocation(TeamType teamType) {
        Location placementLocation = getPlacementLocation(teamType);
        if (placementLocation != null) {
            return placementLocation;
        }

        return getBoardCenterViewLocation();
    }

    public List<BoardArenaData> getBoardArenas() {
        return boards.values().stream()
            .sorted(Comparator.comparingInt(BoardArenaData::getId))
            .toList();
    }

    public List<BoardArenaData> getConfiguredBoardArenas() {
        return getBoardArenas().stream()
            .filter(BoardArenaData::isBoardConfigured)
            .toList();
    }

    public @Nullable BoardArenaData getBoardArena(int boardId) {
        return boards.get(boardId);
    }

    public BoardArenaData getOrCreateBoardArena(int boardId) {
        return boards.computeIfAbsent(boardId, BoardArenaData::new);
    }

    public double getPieceSize() {
        return pieceSize;
    }

    public void setPieceSize(double pieceSize) {
        this.pieceSize = Math.max(0.5D, pieceSize);
    }

    public double getPieceHeightScale() {
        return pieceHeightScale;
    }

    public void setPieceHeightScale(double pieceHeightScale) {
        this.pieceHeightScale = Math.max(0.1D, pieceHeightScale);
    }

    public double getControlRadius() {
        return pieceSize * CONTROL_RADIUS_MULTIPLIER;
    }

    public double getControlRadiusMultiplier() {
        return CONTROL_RADIUS_MULTIPLIER;
    }

    public int getTurnTimeSeconds() {
        return turnTimeSeconds;
    }

    public void setTurnTimeSeconds(int turnTimeSeconds) {
        this.turnTimeSeconds = Math.max(1, turnTimeSeconds);
    }

    public boolean isBoardConfigured() {
        return boardPos1 != null && boardPos2 != null;
    }

    public @Nullable Location getBoardCenterViewLocation() {
        if (!isBoardConfigured() || boardPos1.getWorld() == null || boardPos2.getWorld() == null) {
            return null;
        }
        if (!boardPos1.getWorld().getUID().equals(boardPos2.getWorld().getUID())) {
            return null;
        }

        double minX = Math.min(boardPos1.getX(), boardPos2.getX());
        double maxX = Math.max(boardPos1.getX(), boardPos2.getX());
        double maxY = Math.max(boardPos1.getY(), boardPos2.getY());
        double minZ = Math.min(boardPos1.getZ(), boardPos2.getZ());
        double maxZ = Math.max(boardPos1.getZ(), boardPos2.getZ());
        double boardWidth = maxX - minX;
        double boardDepth = maxZ - minZ;
        double heightOffset = Math.max(8.0D, Math.max(boardWidth, boardDepth) * 0.45D);

        return new Location(
            boardPos1.getWorld(),
            (minX + maxX) * 0.5D,
            maxY + heightOffset,
            (minZ + maxZ) * 0.5D,
            0.0F,
            90.0F
        );
    }

    /**
     * 주어진 좌표가 보드 영역 안에 포함되는지 확인한다.
     */
    public boolean isInsideBoard(Location location) {
        if (!isBoardConfigured() || location.getWorld() == null || boardPos1.getWorld() == null || boardPos2.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getUID().equals(boardPos1.getWorld().getUID())
            || !location.getWorld().getUID().equals(boardPos2.getWorld().getUID())) {
            return false;
        }

        double minX = Math.min(boardPos1.getX(), boardPos2.getX());
        double maxX = Math.max(boardPos1.getX(), boardPos2.getX());
        double minY = Math.min(boardPos1.getY(), boardPos2.getY());
        double maxY = Math.max(boardPos1.getY(), boardPos2.getY());
        double minZ = Math.min(boardPos1.getZ(), boardPos2.getZ());
        double maxZ = Math.max(boardPos1.getZ(), boardPos2.getZ());

        return location.getX() >= minX && location.getX() <= maxX
            && location.getY() >= minY && location.getY() <= maxY
            && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    /**
     * 시선 방향을 보드 평면에 투영한 뒤, 실제 보드 범위 안에 들어올 때만 좌표를 반환한다.
     */
    public @Nullable Location projectToBoard(Location origin, Vector direction, double maxDistance) {
        Location projected = projectToBoardPlane(origin, direction, maxDistance);
        if (projected == null || boardPos1 == null || boardPos2 == null) {
            return null;
        }

        double minX = Math.min(boardPos1.getX(), boardPos2.getX());
        double maxX = Math.max(boardPos1.getX(), boardPos2.getX());
        double minZ = Math.min(boardPos1.getZ(), boardPos2.getZ());
        double maxZ = Math.max(boardPos1.getZ(), boardPos2.getZ());
        if (projected.getX() < minX || projected.getX() > maxX || projected.getZ() < minZ || projected.getZ() > maxZ) {
            return null;
        }

        return projected;
    }

    /**
     * 시선 방향과 보드 평면의 교차 지점을 계산한다.
     */
    public @Nullable Location projectToBoardPlane(Location origin, Vector direction, double maxDistance) {
        if (!isBoardConfigured() || origin.getWorld() == null || boardPos1 == null || boardPos2 == null) {
            return null;
        }
        if (!origin.getWorld().getUID().equals(boardPos1.getWorld().getUID())) {
            return null;
        }
        if (Math.abs(direction.getY()) <= 0.0001D) {
            return null;
        }

        double surfaceY = Math.max(boardPos1.getY(), boardPos2.getY());
        double distance = (surfaceY - origin.getY()) / direction.getY();
        if (distance < 0.0D || distance > maxDistance) {
            return null;
        }

        Location projected = origin.clone().add(direction.clone().multiply(distance));
        projected.setY(surfaceY);
        projected.setYaw(0.0F);
        projected.setPitch(0.0F);

        return projected;
    }

    private void readBoards(FileConfiguration config) {
        ConfigurationSection boardsSection = config.getConfigurationSection("arena.boards");
        if (boardsSection != null) {
            for (String key : boardsSection.getKeys(false)) {
                int boardId;
                try {
                    boardId = Integer.parseInt(key);
                } catch (NumberFormatException exception) {
                    continue;
                }

                BoardArenaData boardArena = getOrCreateBoardArena(boardId);
                String path = "arena.boards." + key;
                boardArena.setBoardPos1(readLocationWithFallback(config, path + ".pos1", path + ".board.pos1"));
                boardArena.setBoardPos2(readLocationWithFallback(config, path + ".pos2", path + ".board.pos2"));
                boardArena.setSpectatorLocation(readLocation(config, path + ".spectator"));
                boardArena.setWaitingLocation(readLocation(config, path + ".waiting"));
                boardArena.setStatusDisplayLocations(readStatusDisplayLocations(config, path));
                boardArena.setPlacementLocation(
                    TeamType.BLUE,
                    readLocationWithFallback(config, path + ".placement.blue", path + ".placement.black")
                );
                boardArena.setPlacementLocation(
                    TeamType.RED,
                    readLocationWithFallback(config, path + ".placement.red", path + ".placement.white")
                );
                normalizeKnownBoardCoordinates(boardArena);
            }
        }

        if (boards.isEmpty() && (boardPos1 != null || boardPos2 != null || bluePlacementLocation != null || redPlacementLocation != null)) {
            BoardArenaData boardArena = getOrCreateBoardArena(1);
            boardArena.setBoardPos1(boardPos1);
            boardArena.setBoardPos2(boardPos2);
            boardArena.setSpectatorLocation(spectatorLocation);
            boardArena.setWaitingLocation(lobbyLocation);
            boardArena.setStatusDisplayLocation(null);
            boardArena.setPlacementLocation(TeamType.BLUE, bluePlacementLocation);
            boardArena.setPlacementLocation(TeamType.RED, redPlacementLocation);
        }
    }

    private void normalizeKnownBoardCoordinates(BoardArenaData boardArena) {
        if (boardArena.getId() != 4) {
            return;
        }

        Location pos1 = boardArena.getBoardPos1();
        Location pos2 = boardArena.getBoardPos2();
        if (pos1 == null || pos2 == null) {
            return;
        }
        double width = Math.abs(pos2.getX() - pos1.getX());
        double depth = Math.abs(pos2.getZ() - pos1.getZ());
        if (pos1.getX() > 0.0D && pos2.getX() < 0.0D && width > depth * 2.0D) {
            pos2.setX(Math.abs(pos2.getX()));
            boardArena.setBoardPos2(pos2);
        }
    }

    private void writeBoards(FileConfiguration config) {
        config.set("arena.boards", null);
        for (BoardArenaData boardArena : getBoardArenas()) {
            String path = "arena.boards." + boardArena.getId();
            writeLocation(config, path + ".pos1", boardArena.getBoardPos1());
            writeLocation(config, path + ".pos2", boardArena.getBoardPos2());
            writeLocation(config, path + ".spectator", boardArena.getSpectatorLocation());
            writeLocation(config, path + ".waiting", boardArena.getWaitingLocation());
            writeLocation(config, path + ".status-display", boardArena.getStatusDisplayLocation());
            writeStatusDisplayLocations(config, path, boardArena.getStatusDisplayLocations());
            writeLocation(config, path + ".placement.blue", boardArena.getPlacementLocation(TeamType.BLUE));
            writeLocation(config, path + ".placement.red", boardArena.getPlacementLocation(TeamType.RED));
        }
    }

    private void syncPrimaryBoardFromFirstConfiguredBoard() {
        List<BoardArenaData> sortedBoards = new ArrayList<>(getBoardArenas());
        sortedBoards.sort(Comparator.comparingInt(BoardArenaData::getId));
        for (BoardArenaData boardArena : sortedBoards) {
            if (!boardArena.isBoardConfigured()) {
                continue;
            }

            boardPos1 = boardArena.getBoardPos1();
            boardPos2 = boardArena.getBoardPos2();
            if (boardArena.getSpectatorLocation() != null) {
                spectatorLocation = boardArena.getSpectatorLocation();
            }
            if (boardArena.getPlacementLocation(TeamType.BLUE) != null) {
                bluePlacementLocation = boardArena.getPlacementLocation(TeamType.BLUE);
            }
            if (boardArena.getPlacementLocation(TeamType.RED) != null) {
                redPlacementLocation = boardArena.getPlacementLocation(TeamType.RED);
            }
            return;
        }
    }

    private static @Nullable Location readLocation(FileConfiguration config, String path) {
        String worldName = config.getString(path + ".world");
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
            world,
            config.getDouble(path + ".x"),
            config.getDouble(path + ".y"),
            config.getDouble(path + ".z"),
            (float) config.getDouble(path + ".yaw"),
            (float) config.getDouble(path + ".pitch")
        );
    }

    private static @Nullable Location readLocationWithFallback(FileConfiguration config, String path, String legacyPath) {
        Location location = readLocation(config, path);
        return location != null ? location : readLocation(config, legacyPath);
    }

    private static List<Location> readStatusDisplayLocations(FileConfiguration config, String boardPath) {
        List<Location> locations = new ArrayList<>();
        ConfigurationSection displaysSection = config.getConfigurationSection(boardPath + ".status-displays");
        if (displaysSection != null) {
            displaysSection.getKeys(false).stream()
                .sorted(Comparator.comparingInt(ArenaData::parseDisplayIndex))
                .forEach(key -> {
                    Location location = readLocation(config, boardPath + ".status-displays." + key);
                    if (location != null) {
                        locations.add(location);
                    }
                });
            return locations;
        }

        Location legacyLocation = readLocation(config, boardPath + ".status-display");
        if (legacyLocation != null) {
            locations.add(legacyLocation);
        }
        return locations;
    }

    private static int parseDisplayIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static void writeStatusDisplayLocations(FileConfiguration config, String boardPath, List<Location> locations) {
        config.set(boardPath + ".status-displays", null);
        if (locations.size() <= 1) {
            return;
        }

        for (int i = 0; i < locations.size(); i++) {
            writeLocation(config, boardPath + ".status-displays." + (i + 1), locations.get(i));
        }
    }

    private static void writeLocation(FileConfiguration config, String path, @Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            config.set(path, null);
            return;
        }

        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", location.getX());
        config.set(path + ".y", location.getY());
        config.set(path + ".z", location.getZ());
        config.set(path + ".yaw", location.getYaw());
        config.set(path + ".pitch", location.getPitch());
    }

    private static @Nullable Location cloneLocation(@Nullable Location location) {
        return location == null ? null : location.clone();
    }
}
