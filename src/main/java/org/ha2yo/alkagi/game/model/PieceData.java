package org.ha2yo.alkagi.game.model;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 보드 위 말 하나의 상태와 연결된 엔티티 참조를 보관한다.
 */
public final class PieceData {

    private static final double HITBOX_Y_OFFSET = 1.35D;
    private static final double INTERACTION_Y_OFFSET = 0.02D;
    private static final double DISPLAY_Y_OFFSET = -0.18D;

    private final int pieceId;
    private final TeamType teamType;
    private Location location;
    private boolean alive;
    private ArmorStand entity;
    private Interaction interactionEntity;
    private ItemDisplay displayEntity;

    public PieceData(int pieceId, TeamType teamType, Location location) {
        this.pieceId = pieceId;
        this.teamType = teamType;
        this.location = normalizeLocation(location);
        this.alive = true;
    }

    public int getPieceId() {
        return pieceId;
    }

    public TeamType getTeamType() {
        return teamType;
    }

    public Location getLocation() {
        return location.clone();
    }

    public void setLocation(Location location) {
        this.location = normalizeLocation(location);
        if (entity != null) {
            entity.teleport(this.location.clone().subtract(0.0D, HITBOX_Y_OFFSET, 0.0D));
        }
        if (interactionEntity != null) {
            interactionEntity.teleport(this.location.clone().add(0.0D, INTERACTION_Y_OFFSET, 0.0D));
        }
        if (displayEntity != null) {
            displayEntity.teleport(this.location.clone().subtract(0.0D, DISPLAY_Y_OFFSET, 0.0D));
        }
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
        if (!alive && entity != null) {
            entity.remove();
            entity = null;
        }
        if (!alive && interactionEntity != null) {
            interactionEntity.remove();
            interactionEntity = null;
        }
        if (!alive && displayEntity != null) {
            displayEntity.remove();
            displayEntity = null;
        }
    }

    public @Nullable ArmorStand getEntity() {
        return entity;
    }

    public void setEntity(@Nullable ArmorStand entity) {
        this.entity = entity;
        if (entity != null) {
            entity.teleport(location.clone().subtract(0.0D, HITBOX_Y_OFFSET, 0.0D));
        }
    }

    public @Nullable Interaction getInteractionEntity() {
        return interactionEntity;
    }

    public void setInteractionEntity(@Nullable Interaction interactionEntity) {
        this.interactionEntity = interactionEntity;
        if (interactionEntity != null) {
            interactionEntity.teleport(location.clone().add(0.0D, INTERACTION_Y_OFFSET, 0.0D));
        }
    }

    public @Nullable ItemDisplay getDisplayEntity() {
        return displayEntity;
    }

    public void setDisplayEntity(@Nullable ItemDisplay displayEntity) {
        this.displayEntity = displayEntity;
        if (displayEntity != null) {
            displayEntity.teleport(location.clone().subtract(0.0D, DISPLAY_Y_OFFSET, 0.0D));
        }
    }

    private Location normalizeLocation(Location location) {
        Location normalized = location.clone();
        normalized.setYaw(0.0F);
        normalized.setPitch(0.0F);
        return normalized;
    }

    public @Nullable UUID getEntityId() {
        return entity == null ? null : entity.getUniqueId();
    }
}
