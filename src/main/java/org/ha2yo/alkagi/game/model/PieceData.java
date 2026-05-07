package org.ha2yo.alkagi.game.model;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 보드 위 말 하나의 상태와 연결된 엔티티 참조를 함께 보관한다.
 */
public final class PieceData {

    private static final double HITBOX_Y_OFFSET = 1.35D;
    private static final double INTERACTION_Y_OFFSET = 0.02D;
    private static final double DISPLAY_Y_OFFSET = -0.18D;
    private static final double DEFAULT_PIECE_SIZE = 2.35D;
    private static final double DISPLAY_HEIGHT_SCALE_MULTIPLIER = 3.0D;
    private static final double PIECE_MODEL_MIN_Y = 0.5D;
    private static final double PIECE_MODEL_MAX_Y = 2.5D;
    private static final double MODEL_UNIT_SIZE = 16.0D;
    private static final double LABEL_SURFACE_GAP = 0.02D;

    private final int pieceId;
    private final TeamType teamType;
    private final double pieceSize;
    private double heightScale;
    private String labelText;
    private float labelYawOffset;
    private Location location;
    private boolean alive;
    private ArmorStand entity;
    private Interaction interactionEntity;
    private ItemDisplay displayEntity;
    private final List<TextDisplay> labelEntities = new ArrayList<>();

    public PieceData(int pieceId, TeamType teamType, Location location, double pieceSize) {
        this(pieceId, teamType, location, pieceSize, null);
    }

    public PieceData(int pieceId, TeamType teamType, Location location, double pieceSize, @Nullable String labelText) {
        this(pieceId, teamType, location, pieceSize, 1.0D, labelText);
    }

    public PieceData(
            int pieceId,
            TeamType teamType,
            Location location,
            double pieceSize,
            double heightScale,
            @Nullable String labelText
    ) {
        this.pieceId = pieceId;
        this.teamType = teamType;
        this.pieceSize = Math.max(0.5D, pieceSize);
        this.heightScale = Math.max(0.1D, heightScale);
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

    public double getHeightScale() {
        return heightScale;
    }

    public void setHeightScale(double heightScale) {
        this.heightScale = Math.max(0.1D, heightScale);
        updateLabelLocations();
    }

    public String getLabelText() {
        return labelText;
    }

    public void setLabelText(@Nullable String labelText) {
        this.labelText = normalizeLabelText(teamType, labelText);
    }

    public void setLabelYawOffset(float labelYawOffset) {
        this.labelYawOffset = labelYawOffset;
        updateLabelLocations();
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
        updateLabelLocations();
    }

    public boolean isAlive() {
        return alive;
    }

    /**
     * 말 생존 여부를 바꾸고 탈락 시 연결된 엔티티도 정리한다.
     */
    public void setAlive(boolean alive) {
        setAlive(alive, null, 0L, null);
    }

    public void setAlive(boolean alive, @Nullable JavaPlugin plugin, long visualRemovalDelayTicks) {
        setAlive(alive, plugin, visualRemovalDelayTicks, null);
    }

    public void setAlive(boolean alive, @Nullable JavaPlugin plugin, long visualRemovalDelayTicks, @Nullable List<Vector> visualOffsets) {
        this.alive = alive;
        if (alive) {
            return;
        }

        if (!alive && entity != null) {
            entity.remove();
            entity = null;
        }
        if (!alive && interactionEntity != null) {
            interactionEntity.remove();
            interactionEntity = null;
        }
        if (plugin != null && visualRemovalDelayTicks > 0L) {
            ItemDisplay delayedDisplay = displayEntity;
            List<TextDisplay> delayedLabels = new ArrayList<>(labelEntities);
            displayEntity = null;
            labelEntities.clear();
            scheduleDelayedVisualRemoval(plugin, visualRemovalDelayTicks, visualOffsets, delayedDisplay, delayedLabels);
            return;
        }

        if (displayEntity != null) {
            displayEntity.remove();
            displayEntity = null;
        }
        for (TextDisplay labelEntity : labelEntities) {
            labelEntity.remove();
        }
        labelEntities.clear();
    }

    private void scheduleDelayedVisualRemoval(
            JavaPlugin plugin,
            long visualRemovalDelayTicks,
            @Nullable List<Vector> visualOffsets,
            @Nullable ItemDisplay delayedDisplay,
            List<TextDisplay> delayedLabels
    ) {
        if (visualOffsets != null && !visualOffsets.isEmpty()) {
            Location displayStartLocation = delayedDisplay == null ? null : delayedDisplay.getLocation();
            List<Location> labelStartLocations = delayedLabels.stream()
                .map(TextDisplay::getLocation)
                .toList();
            int movementTicks = (int) Math.min(visualRemovalDelayTicks - 1L, visualOffsets.size());
            for (int tick = 1; tick <= movementTicks; tick++) {
                Vector visualOffset = visualOffsets.get(tick - 1);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (delayedDisplay != null && delayedDisplay.isValid() && displayStartLocation != null) {
                        delayedDisplay.teleport(displayStartLocation.clone().add(visualOffset));
                    }
                    for (int i = 0; i < delayedLabels.size(); i++) {
                        TextDisplay labelEntity = delayedLabels.get(i);
                        if (labelEntity.isValid()) {
                            labelEntity.teleport(labelStartLocations.get(i).clone().add(visualOffset));
                        }
                    }
                }, (long) tick);
            }
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (delayedDisplay != null && delayedDisplay.isValid()) {
                delayedDisplay.remove();
            }
            for (TextDisplay labelEntity : delayedLabels) {
                if (labelEntity.isValid()) {
                    labelEntity.remove();
                }
            }
        }, visualRemovalDelayTicks);
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

    public List<TextDisplay> getLabelEntities() {
        return Collections.unmodifiableList(labelEntities);
    }

    public void addLabelEntity(TextDisplay labelEntity) {
        labelEntities.add(labelEntity);
        labelEntity.teleport(getLabelLocation());
    }

    public void clearLabelEntities() {
        for (TextDisplay labelEntity : labelEntities) {
            labelEntity.remove();
        }
        labelEntities.clear();
    }

    private Location getLabelLocation() {
        Location labelLocation = location.clone().add(0.0D, getLabelYOffset(), 0.0D);
        labelLocation.setYaw(getLabelYaw());
        labelLocation.setPitch(0.0F);
        return labelLocation;
    }

    public float getLabelYaw() {
        return (teamType == TeamType.BLUE ? 90.0F : -90.0F) + labelYawOffset;
    }

    private double getLabelYOffset() {
        double defaultDisplayHeightScale = DEFAULT_PIECE_SIZE * DISPLAY_HEIGHT_SCALE_MULTIPLIER;
        double heightScale = defaultDisplayHeightScale * this.heightScale;
        double baseLift = Math.max(3.1D, DEFAULT_PIECE_SIZE * 0.635D);
        double modelBottomOffset = 0.5D - (PIECE_MODEL_MIN_Y / MODEL_UNIT_SIZE);
        double modelTopOffset = (PIECE_MODEL_MAX_Y / MODEL_UNIT_SIZE) - 0.5D;
        double lift = baseLift + ((heightScale - defaultDisplayHeightScale) * modelBottomOffset);
        return -DISPLAY_Y_OFFSET + lift + (modelTopOffset * heightScale) + LABEL_SURFACE_GAP;
    }

    private void updateLabelLocations() {
        for (TextDisplay labelEntity : labelEntities) {
            labelEntity.teleport(getLabelLocation());
        }
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
