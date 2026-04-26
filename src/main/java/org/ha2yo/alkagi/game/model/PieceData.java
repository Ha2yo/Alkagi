package org.ha2yo.alkagi.game.model;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 보드 위 말 하나의 상태와 연결된 엔티티 참조를 함께 보관한다.
 */
public final class PieceData {

    private static final double HITBOX_Y_OFFSET = 1.35D;
    private static final double INTERACTION_Y_OFFSET = 0.02D;
    private static final double DISPLAY_Y_OFFSET = -0.18D;
    private static final double LABEL_Y_OFFSET = 0.6D;

    private final int pieceId;
    private final TeamType teamType;
    private final double pieceSize;
    private String labelText;
    private Location location;
    private boolean alive;
    private ArmorStand entity;
    private Interaction interactionEntity;
    private ItemDisplay displayEntity;
    private TextDisplay labelEntity;
    private TextDisplay labelBoldEntity;

    public PieceData(int pieceId, TeamType teamType, Location location, double pieceSize) {
        this(pieceId, teamType, location, pieceSize, null);
    }

    public PieceData(int pieceId, TeamType teamType, Location location, double pieceSize, @Nullable String labelText) {
        this.pieceId = pieceId;
        this.teamType = teamType;
        this.pieceSize = Math.max(0.5D, pieceSize);
        this.labelText = normalizeLabelText(teamType, labelText);
        this.location = normalizeLocation(location);
        this.alive = true;
    }

    public int getPieceId() {
        return pieceId;
    }

    public TeamType getTeamType() {
        return teamType;
    }

    public double getPieceSize() {
        return pieceSize;
    }

    public String getLabelText() {
        return labelText;
    }

    public void setLabelText(@Nullable String labelText) {
        this.labelText = normalizeLabelText(teamType, labelText);
    }

    public Location getLocation() {
        return location.clone();
    }

    /**
     * 말의 논리 좌표를 갱신하고 연결된 엔티티 위치도 함께 이동시킨다.
     */
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
        if (labelEntity != null) {
            labelEntity.teleport(getLabelLocation());
        }
        if (labelBoldEntity != null) {
            labelBoldEntity.teleport(getLabelLocation());
        }
    }

    public boolean isAlive() {
        return alive;
    }

    /**
     * 말 생존 여부를 바꾸고 탈락 시 연결된 엔티티도 정리한다.
     */
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
        if (!alive && labelEntity != null) {
            labelEntity.remove();
            labelEntity = null;
        }
        if (!alive && labelBoldEntity != null) {
            labelBoldEntity.remove();
            labelBoldEntity = null;
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

    public @Nullable TextDisplay getLabelEntity() {
        return labelEntity;
    }

    public void setLabelEntity(@Nullable TextDisplay labelEntity) {
        this.labelEntity = labelEntity;
        if (labelEntity != null) {
            labelEntity.teleport(getLabelLocation());
        }
    }

    public @Nullable TextDisplay getLabelBoldEntity() {
        return labelBoldEntity;
    }

    public void setLabelBoldEntity(@Nullable TextDisplay labelBoldEntity) {
        this.labelBoldEntity = labelBoldEntity;
        if (labelBoldEntity != null) {
            labelBoldEntity.teleport(getLabelLocation());
        }
    }

    private Location getLabelLocation() {
        Location labelLocation = location.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D);
        labelLocation.setYaw(teamType == TeamType.BLUE ? 90.0F : -90.0F);
        labelLocation.setPitch(0.0F);
        return labelLocation;
    }

    private Location normalizeLocation(Location location) {
        Location normalized = location.clone();
        normalized.setYaw(0.0F);
        normalized.setPitch(0.0F);
        return normalized;
    }

    private String normalizeLabelText(TeamType teamType, @Nullable String labelText) {
        if (labelText != null) {
            String trimmed = labelText.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return teamType == TeamType.BLUE ? "車" : "兵";
    }

    public @Nullable UUID getEntityId() {
        return entity == null ? null : entity.getUniqueId();
    }
}
