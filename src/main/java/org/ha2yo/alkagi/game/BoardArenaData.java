package org.ha2yo.alkagi.game;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BoardArenaData {

    private final int id;
    private Location boardPos1;
    private Location boardPos2;
    private Location spectatorLocation;
    private Location waitingLocation;
    private final List<Location> statusDisplayLocations = new ArrayList<>();
    private Location bluePlacementLocation;
    private Location redPlacementLocation;

    public BoardArenaData(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public @Nullable Location getBoardPos1() {
        return cloneLocation(boardPos1);
    }

    public void setBoardPos1(@Nullable Location boardPos1) {
        this.boardPos1 = cloneLocation(boardPos1);
    }

    public @Nullable Location getBoardPos2() {
        return cloneLocation(boardPos2);
    }

    public void setBoardPos2(@Nullable Location boardPos2) {
        this.boardPos2 = cloneLocation(boardPos2);
    }

    public @Nullable Location getSpectatorLocation() {
        return cloneLocation(spectatorLocation);
    }

    public void setSpectatorLocation(@Nullable Location spectatorLocation) {
        this.spectatorLocation = cloneLocation(spectatorLocation);
    }

    public @Nullable Location getWaitingLocation() {
        return cloneLocation(waitingLocation);
    }

    public void setWaitingLocation(@Nullable Location waitingLocation) {
        this.waitingLocation = cloneLocation(waitingLocation);
    }

    public @Nullable Location getStatusDisplayLocation() {
        return statusDisplayLocations.isEmpty() ? null : cloneLocation(statusDisplayLocations.getFirst());
    }

    public void setStatusDisplayLocation(@Nullable Location statusDisplayLocation) {
        statusDisplayLocations.clear();
        if (statusDisplayLocation != null) {
            statusDisplayLocations.add(statusDisplayLocation.clone());
        }
    }

    public List<Location> getStatusDisplayLocations() {
        return statusDisplayLocations.stream()
            .map(Location::clone)
            .toList();
    }

    public void setStatusDisplayLocations(Collection<Location> locations) {
        statusDisplayLocations.clear();
        for (Location location : locations) {
            if (location != null) {
                statusDisplayLocations.add(location.clone());
            }
        }
    }

    public void addStatusDisplayLocation(Location statusDisplayLocation) {
        statusDisplayLocations.add(statusDisplayLocation.clone());
    }

    public @Nullable Location getPlacementLocation(TeamType teamType) {
        return switch (teamType) {
            case BLUE -> cloneLocation(bluePlacementLocation);
            case RED -> cloneLocation(redPlacementLocation);
        };
    }

    public void setPlacementLocation(TeamType teamType, @Nullable Location location) {
        if (teamType == TeamType.BLUE) {
            this.bluePlacementLocation = cloneLocation(location);
        } else {
            this.redPlacementLocation = cloneLocation(location);
        }
    }

    public boolean isBoardConfigured() {
        return boardPos1 != null && boardPos2 != null;
    }

    public boolean hasManualPlacementLocations() {
        return bluePlacementLocation != null && redPlacementLocation != null;
    }

    private static @Nullable Location cloneLocation(@Nullable Location location) {
        return location == null ? null : location.clone();
    }
}
