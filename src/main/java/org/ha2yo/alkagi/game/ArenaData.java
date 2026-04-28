package org.ha2yo.alkagi.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

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
        arenaData.pieceSize = Math.max(0.5D, config.getDouble("settings.piece-size", DEFAULT_PIECE_SIZE));
        arenaData.pieceHeightScale = Math.max(0.1D, config.getDouble("settings.piece-height-scale", DEFAULT_PIECE_HEIGHT_SCALE));
        arenaData.turnTimeSeconds = Math.max(1, config.getInt("settings.turn-time-seconds", DEFAULT_TURN_TIME_SECONDS));
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
    }

    public @Nullable Location getBoardPos2() {
        return cloneLocation(boardPos2);
    }

    public void setBoardPos2(Location boardPos2) {
        this.boardPos2 = boardPos2.clone();
    }

    public @Nullable Location getSpectatorLocation() {
        return cloneLocation(spectatorLocation);
    }

    public void setSpectatorLocation(Location spectatorLocation) {
        this.spectatorLocation = spectatorLocation.clone();
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
