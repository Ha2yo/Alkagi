package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.ha2yo.alkagi.game.model.PieceData;
import org.ha2yo.alkagi.game.model.TeamData;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 말 생성, 배치, 발사, 충돌, 탈락 등 보드 위 물리 처리를 담당한다.
 */
public final class BoardManager {
    private static final NamespacedKey BLUE_PIECE_ITEM_MODEL = NamespacedKey.minecraft("alkagi_mal/white");
    private static final NamespacedKey RED_PIECE_ITEM_MODEL = NamespacedKey.minecraft("alkagi_mal/white");
    private static final String PIECE_ENTITY_MARKER = "piece";
    private static final double LEGACY_PIECE_CLEANUP_HORIZONTAL_MARGIN = 2.0D;
    private static final double LEGACY_PIECE_CLEANUP_VERTICAL_MARGIN = 6.0D;

    // 말 모델의 실제 바닥 면적 비율이다. 충돌 반지름과 선택 히트박스 크기에 영향을 준다.
    private static final double DISPLAY_FOOTPRINT_SCALE = 0.57D;
    private static final double SELECTION_FOOTPRINT_MULTIPLIER = 1.05D;
    private static final double LABEL_FOOTPRINT_SCALE = 3.3D;
    private static final float LABEL_BOLD_OFFSET = 0.018F;
    private static final float LABEL_DEPTH_OFFSET = 0.55F;

    // FRICTION을 낮추거나 ROLLING_RESISTANCE를 높이면 말이 더 빨리 멈춘다.
    private static final double FRICTION = 0.83D;
    private static final double ROLLING_RESISTANCE = 0.045D;
    // 이 속도보다 느리면 멈춘 것으로 처리한다.
    private static final double STOP_THRESHOLD = 0.05D;

    // 플레이어가 조절할 수 있는 발사 세기 범위와 최종 발사 속도 배율이다.
    public static final double MIN_LAUNCH_POWER = 0.5D;
    public static final double MAX_LAUNCH_POWER = 10.0D;
    private static final double LAUNCH_POWER_VELOCITY_SCALE = 1.8D;

    // 기본 말 크기다. 크기 기반 무게와 발사 거리 보정은 이 값을 기준으로 계산된다.
    private static final double DEFAULT_PIECE_SIZE = 2.35D;
    private static final double DISPLAY_HEIGHT_SCALE_MULTIPLIER = 3.0D;
    private static final double PIECE_MODEL_MIN_Y = 0.5D;
    private static final double MODEL_UNIT_SIZE = 16.0D;

    // 값이 높을수록 말끼리 또는 장애물과 부딪혔을 때 더 많이 튕긴다.
    private static final double COLLISION_RESTITUTION = 0.7D;
    private static final double OBSTACLE_RESTITUTION = 0.82D;

    // 값을 낮추면 고속 충돌을 더 정확하게 잡지만 계산량이 늘어난다.
    private static final double MAX_SUBSTEP_DISTANCE = 0.18D;
    private static final int COLLISION_SOLVER_ITERATIONS = 3;

    // 작은 말이 무거운 말에 부딪혔을 때 뒤로 튕기는 반동을 줄인다. 0이면 반동을 제거한다.
    private static final double SMALL_TO_HEAVY_REBOUND_DAMPING = 0.0D;
    // 무거운 말이 가벼운 말을 정타로 쳤을 때, 무거운 말의 전진 속도를 줄인다. 0이면 정면 충돌에서 멈춘다.
    private static final double HEAVY_TO_LIGHT_FORWARD_DAMPING = 0.0D;

    // 값이 높을수록 큰 말이 충돌에서 더 무겁게 작동한다.
    private static final double COLLISION_MASS_EXPONENT = 2.0D;
    private static final double MAX_COLLISION_MASS = 40.0D;

    // 값이 높을수록 큰 말의 발사 속도가 줄어들어 이동 거리가 짧아진다.
    private static final double LAUNCH_MASS_SPEED_EXPONENT = 0.35D;
    private static final double MIN_LAUNCH_MASS_SPEED_MULTIPLIER = 0.55D;

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final NamespacedKey pieceEntityKey;
    private final Map<UUID, PieceData> pieceByEntityId = new HashMap<>();
    private BukkitTask physicsTask;
    private boolean actionRunning;

    public BoardManager(JavaPlugin plugin, ArenaData arenaData) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.pieceEntityKey = new NamespacedKey(plugin, "piece_entity");
    }

    public boolean isActionRunning() {
        return actionRunning;
    }

    /**
     * 현재 세션에서 관리하던 말과 물리 상태를 모두 정리한다.
     */
    public void clearSessionPieces(Map<TeamType, TeamData> teamDataMap) {
        cancelPhysicsTask();
        actionRunning = false;
        pieceByEntityId.clear();
        for (TeamData teamData : teamDataMap.values()) {
            for (PieceData piece : teamData.getPieces()) {
                piece.setAlive(false);
            }
        }
    }

    /**
     * 주어진 위치에 새 말을 배치할 수 있는지 검사한다.
     */
    public boolean canPlacePiece(Location location, Map<TeamType, TeamData> teamDataMap) {
        return canPlacePiece(location, arenaData.getPieceSize(), teamDataMap);
    }

    public boolean canPlacePiece(Location location, double pieceSize, Map<TeamType, TeamData> teamDataMap) {
        if (!arenaData.isInsideBoard(location)) {
            return false;
        }
        if (isBlockedByObstacle(location, pieceSize)) {
            return false;
        }

        double placementRadius = getPieceRadius(pieceSize);
        for (TeamData teamData : teamDataMap.values()) {
            for (PieceData piece : teamData.getAlivePieces()) {
                double requiredDistance = (placementRadius + getPieceRadius(piece)) * 0.925D;
                if (samePlaneDistance(piece.getLocation(), location) < requiredDistance) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 말 표시용 엔티티와 선택용 엔티티를 생성하고 PieceData로 묶는다.
     */
    public PieceData spawnPiece(TeamType teamType, int pieceId, Location location) {
        return spawnPiece(teamType, pieceId, location, arenaData.getPieceSize());
    }

    public PieceData spawnPiece(TeamType teamType, int pieceId, Location location, double pieceSize) {
        return spawnPiece(teamType, pieceId, location, pieceSize, arenaData.getPieceHeightScale(), null);
    }

    public PieceData spawnPiece(TeamType teamType, int pieceId, Location location, double pieceSize, double heightScale) {
        return spawnPiece(teamType, pieceId, location, pieceSize, heightScale, null);
    }

    public PieceData spawnPiece(
            TeamType teamType,
            int pieceId,
            Location location,
            double pieceSize,
            @Nullable String labelText
    ) {
        return spawnPiece(teamType, pieceId, location, pieceSize, arenaData.getPieceHeightScale(), labelText);
    }

    public PieceData spawnPiece(
            TeamType teamType,
            int pieceId,
            Location location,
            double pieceSize,
            double heightScale,
            @Nullable String labelText
    ) {
        Location spawnLocation = normalizePieceLocation(location);
        World world = spawnLocation.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world cannot be null");
        }

        ArmorStand armorStand = (ArmorStand) world.spawnEntity(spawnLocation, EntityType.ARMOR_STAND);
        armorStand.setInvisible(true);
        armorStand.setInvulnerable(true);
        armorStand.setGravity(false);
        armorStand.setMarker(false);
        armorStand.setSmall(false);
        armorStand.setBasePlate(false);
        armorStand.setCustomNameVisible(false);
        armorStand.setArms(false);
        armorStand.setHeadPose(EulerAngle.ZERO);
        markPieceEntity(armorStand);

        Interaction interaction = (Interaction) world.spawnEntity(spawnLocation, EntityType.INTERACTION);
        interaction.setResponsive(true);
        PieceData pieceData = new PieceData(pieceId, teamType, spawnLocation, pieceSize, heightScale, labelText);
        configureInteractionHitbox(interaction, pieceData);
        markPieceEntity(interaction);

        ItemDisplay display = (ItemDisplay) world.spawnEntity(spawnLocation, EntityType.ITEM_DISPLAY);
        display.setItemStack(createPieceItem(teamType));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setInterpolationDuration(1);
        display.setViewRange(256.0F);
        configurePieceDisplay(display, pieceData);
        markPieceEntity(display);

        pieceData.setEntity(armorStand);
        pieceData.setInteractionEntity(interaction);
        pieceData.setDisplayEntity(display);
        spawnPieceLabels(pieceData);
        pieceByEntityId.put(interaction.getUniqueId(), pieceData);
        return pieceData;
    }

    private ItemStack createPieceItem(TeamType teamType) {
        ItemStack itemStack = new ItemStack(Material.SNOWBALL);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(teamType == TeamType.BLUE ? BLUE_PIECE_ITEM_MODEL : RED_PIECE_ITEM_MODEL);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public @Nullable PieceData findPieceByEntity(UUID entityId) {
        return pieceByEntityId.get(entityId);
    }

    public boolean isPieceSelectionEntity(UUID entityId) {
        return pieceByEntityId.containsKey(entityId);
    }

    public void removePiece(PieceData pieceData) {
        UUID entityId = pieceData.getEntityId();
        if (entityId != null) {
            pieceByEntityId.remove(entityId);
        }
        Interaction interaction = pieceData.getInteractionEntity();
        if (interaction != null) {
            pieceByEntityId.remove(interaction.getUniqueId());
        }
        pieceData.setAlive(false);
    }

    /**
     * 선택된 말을 발사하고 물리 시뮬레이션이 끝나면 후속 작업을 실행한다.
     */
    public void launchPiece(PieceData selectedPiece, Location targetLocation, Map<TeamType, TeamData> teamDataMap, Runnable onFinished) {
        launchPiece(selectedPiece, createLaunchVector(selectedPiece, targetLocation), teamDataMap, onFinished);
    }

    public void launchPiece(PieceData selectedPiece, Vector velocity, Map<TeamType, TeamData> teamDataMap, Runnable onFinished) {
        if (actionRunning) {
            return;
        }

        if (velocity.lengthSquared() <= 0.0001D) {
            onFinished.run();
            return;
        }

        actionRunning = true;
        Map<PieceData, Vector> velocities = new HashMap<>();
        velocities.put(selectedPiece, velocity);

        physicsTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tickPhysics(velocities, teamDataMap);
            if (velocities.isEmpty()) {
                cancelPhysicsTask();
                actionRunning = false;
                onFinished.run();
            }
        }, 1L, 1L);
    }

    public double getLaunchControlRadius() {
        return arenaData.getControlRadius();
    }

    public void refreshPieceDisplays() {
        Set<PieceData> pieces = new HashSet<>(pieceByEntityId.values());
        for (PieceData piece : pieces) {
            Interaction interaction = piece.getInteractionEntity();
            if (interaction != null) {
                configureInteractionHitbox(interaction, piece);
            }
            ItemDisplay display = piece.getDisplayEntity();
            if (display != null) {
                configurePieceDisplay(display, piece);
            }
            List<TextDisplay> labels = piece.getLabelEntities();
            for (int i = 0; i < labels.size(); i++) {
                configurePieceLabel(labels.get(i), piece, getLabelOffset(i));
            }
        }
    }

    public void setActivePieceHeightScale(double heightScale) {
        Set<PieceData> pieces = new HashSet<>(pieceByEntityId.values());
        for (PieceData piece : pieces) {
            piece.setHeightScale(heightScale);
        }
        refreshPieceDisplays();
    }

    public void updatePieceLabel(PieceData pieceData, String labelText) {
        pieceData.setLabelText(labelText);
        removePieceLabels(pieceData);
        spawnPieceLabels(pieceData);
    }

    private void spawnPieceLabels(PieceData pieceData) {
        Location location = pieceData.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        for (Vector3f labelOffset : getLabelOffsets()) {
            TextDisplay label = (TextDisplay) world.spawnEntity(location, EntityType.TEXT_DISPLAY);
            configurePieceLabel(label, pieceData, labelOffset);
            markPieceEntity(label);
            pieceData.addLabelEntity(label);
        }
    }

    private void removePieceLabels(PieceData pieceData) {
        pieceData.clearLabelEntities();
    }

    /**
     * 이전 실행에서 남았을 수 있는 말 엔티티를 월드 전체에서 정리한다.
     */
    public void cleanupTaggedPieceEntities() {
        cancelPhysicsTask();
        actionRunning = false;
        pieceByEntityId.clear();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!isTaggedPieceEntity(entity) && !isLegacyPieceEntity(entity)) {
                    continue;
                }
                entity.remove();
            }
        }
    }

    /**
     * 조작 반경을 넘지 않도록 발사 목표 지점을 제한한다.
     */
    public Location clampLaunchTarget(PieceData selectedPiece, Location targetLocation) {
        Location origin = selectedPiece.getLocation();
        Location clamped = targetLocation.clone();
        clamped.setY(origin.getY());

        Vector offset = clamped.toVector().subtract(origin.toVector());
        offset.setY(0.0D);
        double distance = offset.length();
        double controlRadius = getLaunchControlRadius();
        if (distance <= controlRadius || distance <= 0.0001D) {
            return flattenToBoard(clamped);
        }

        Vector limited = offset.multiply(controlRadius / distance);
        return flattenToBoard(origin.clone().add(limited));
    }

    /**
     * 목표 지점 기준으로 실제 발사 속도 벡터를 계산한다.
     */
    public Vector createLaunchVector(PieceData selectedPiece, Location targetLocation) {
        Location clampedTarget = clampLaunchTarget(selectedPiece, targetLocation);
        Vector direction = selectedPiece.getLocation().toVector().subtract(clampedTarget.toVector());
        direction.setY(0.0D);

        if (direction.lengthSquared() <= 0.0001D) {
            return new Vector();
        }

        double normalizedDistance = Math.min(direction.length() / getLaunchControlRadius(), 1.0D);
        double power = normalizedDistance * MAX_LAUNCH_POWER * LAUNCH_POWER_VELOCITY_SCALE;
        double massSpeedMultiplier = getLaunchMassSpeedMultiplier(selectedPiece);

        return direction.normalize().multiply(power * massSpeedMultiplier);
    }

    public Vector createLaunchVector(PieceData selectedPiece, Vector direction, double launchPower) {
        Vector flatDirection = direction.clone();
        flatDirection.setY(0.0D);
        if (flatDirection.lengthSquared() <= 0.0001D) {
            return new Vector();
        }

        double clampedPower = Math.max(MIN_LAUNCH_POWER, Math.min(MAX_LAUNCH_POWER, launchPower));
        double massSpeedMultiplier = getLaunchMassSpeedMultiplier(selectedPiece);
        return flatDirection.normalize().multiply(clampedPower * LAUNCH_POWER_VELOCITY_SCALE * massSpeedMultiplier);
    }

    private double getLaunchMassSpeedMultiplier(PieceData pieceData) {
        double sizeRatio = pieceData.getPieceSize() / DEFAULT_PIECE_SIZE;
        if (sizeRatio <= 1.0D) {
            return 1.0D;
        }

        return Math.max(MIN_LAUNCH_MASS_SPEED_MULTIPLIER, 1.0D / Math.pow(sizeRatio, LAUNCH_MASS_SPEED_EXPONENT));
    }

    private void configurePieceDisplay(ItemDisplay display, PieceData pieceData) {
        float pieceSize = (float) pieceData.getPieceSize();
        float defaultHeightScale = (float) (DEFAULT_PIECE_SIZE * DISPLAY_HEIGHT_SCALE_MULTIPLIER);
        float heightScale = (float) (defaultHeightScale * pieceData.getHeightScale());
        float baseLift = Math.max(3.1F, (float) DEFAULT_PIECE_SIZE * 0.635F);
        float modelBottomOffset = (float) (0.5D - (PIECE_MODEL_MIN_Y / MODEL_UNIT_SIZE));
        float lift = baseLift + ((heightScale - defaultHeightScale) * modelBottomOffset);
        display.setTransformation(new Transformation(
                new Vector3f(0.0F, lift, 0.0F),
                new AxisAngle4f(),
                new Vector3f(pieceSize, heightScale, pieceSize),
                new AxisAngle4f()
        ));
    }

    private List<Vector3f> getLabelOffsets() {
        return List.of(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new Vector3f(-LABEL_BOLD_OFFSET, 0.0F, 0.0F),
                new Vector3f(LABEL_BOLD_OFFSET, 0.0F, 0.0F),
                new Vector3f(0.0F, 0.0F, -LABEL_BOLD_OFFSET),
                new Vector3f(0.0F, 0.0F, LABEL_BOLD_OFFSET)
        );
    }

    private Vector3f getLabelOffset(int index) {
        List<Vector3f> offsets = getLabelOffsets();
        return offsets.get(Math.min(index, offsets.size() - 1));
    }

    private void configurePieceLabel(TextDisplay label, PieceData pieceData, Vector3f labelOffset) {
        float pieceSize = (float) pieceData.getPieceSize();
        float sizeRatio = pieceSize / (float) DEFAULT_PIECE_SIZE;
        float labelScale = (float) (pieceSize * DISPLAY_FOOTPRINT_SCALE * LABEL_FOOTPRINT_SCALE);
        Vector3f scaledLabelOffset = new Vector3f(labelOffset).mul(sizeRatio);
        float scaledDepthOffset = LABEL_DEPTH_OFFSET * sizeRatio;
        label.text(Component.text(
                getPieceLabelText(pieceData),
                getPieceLabelColor(pieceData.getTeamType()),
                TextDecoration.BOLD
        ));
        label.setAlignment(TextDisplay.TextAlignment.CENTER);
        label.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        label.setBillboard(Display.Billboard.FIXED);
        label.setInterpolationDuration(1);
        label.setLineWidth(200);
        label.setSeeThrough(false);
        label.setShadowed(false);
        label.setTextOpacity((byte) 255);
        label.setViewRange(256.0F);
        label.setRotation(getPieceLabelYaw(pieceData.getTeamType()), 0.0F);
        label.setTransformation(new Transformation(
                new Vector3f(scaledLabelOffset.x, 0.0F, scaledDepthOffset + scaledLabelOffset.z),
                new AxisAngle4f((float) Math.toRadians(-90.0D), 1.0F, 0.0F, 0.0F),
                new Vector3f(labelScale, labelScale, labelScale),
                new AxisAngle4f()
        ));
    }

    private String getPieceLabelText(PieceData pieceData) {
        return pieceData.getLabelText();
    }

    private NamedTextColor getPieceLabelColor(TeamType teamType) {
        return teamType == TeamType.BLUE ? NamedTextColor.BLUE : NamedTextColor.RED;
    }

    private float getPieceLabelYaw(TeamType teamType) {
        return teamType == TeamType.BLUE ? 90.0F : -90.0F;
    }

    private void markPieceEntity(Entity entity) {
        entity.getPersistentDataContainer().set(pieceEntityKey, PersistentDataType.STRING, PIECE_ENTITY_MARKER);
    }

    private boolean isTaggedPieceEntity(Entity entity) {
        return PIECE_ENTITY_MARKER.equals(
                entity.getPersistentDataContainer().get(pieceEntityKey, PersistentDataType.STRING)
        );
    }

    private boolean isLegacyPieceEntity(Entity entity) {
        if (!isNearBoard(
                entity.getLocation(),
                LEGACY_PIECE_CLEANUP_HORIZONTAL_MARGIN,
                LEGACY_PIECE_CLEANUP_VERTICAL_MARGIN
        )) {
            return false;
        }
        if (entity instanceof ItemDisplay display) {
            return isPieceDisplayItem(display.getItemStack());
        }
        if (entity instanceof TextDisplay) {
            return true;
        }
        if (entity instanceof ArmorStand armorStand) {
            return armorStand.isInvisible()
                    && armorStand.isInvulnerable()
                    && !armorStand.hasGravity()
                    && !armorStand.hasBasePlate()
                    && !armorStand.hasArms();
        }
        if (entity instanceof Interaction interaction) {
            return interaction.isResponsive()
                    && Math.abs(interaction.getInteractionWidth() - (float) getSelectionDiameter()) < 0.15F
                    && Math.abs(interaction.getInteractionHeight() - (float) getSelectionHeight()) < 0.25F;
        }
        return false;
    }

    private boolean isPieceDisplayItem(@Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        Material type = itemStack.getType();
        if (type != Material.FIRE_CHARGE && type != Material.SNOWBALL) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return false;
        }
        NamespacedKey itemModel = meta.getItemModel();
        return BLUE_PIECE_ITEM_MODEL.equals(itemModel) || RED_PIECE_ITEM_MODEL.equals(itemModel);
    }

    private boolean isNearBoard(Location location, double horizontalMargin, double verticalMargin) {
        Location boardPos1 = arenaData.getBoardPos1();
        Location boardPos2 = arenaData.getBoardPos2();
        if (boardPos1 == null || boardPos2 == null || location.getWorld() == null
                || boardPos1.getWorld() == null || boardPos2.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getUID().equals(boardPos1.getWorld().getUID())
                || !location.getWorld().getUID().equals(boardPos2.getWorld().getUID())) {
            return false;
        }

        double minX = Math.min(boardPos1.getX(), boardPos2.getX()) - horizontalMargin;
        double maxX = Math.max(boardPos1.getX(), boardPos2.getX()) + horizontalMargin;
        double minY = Math.min(boardPos1.getY(), boardPos2.getY()) - verticalMargin;
        double maxY = Math.max(boardPos1.getY(), boardPos2.getY()) + verticalMargin;
        double minZ = Math.min(boardPos1.getZ(), boardPos2.getZ()) - horizontalMargin;
        double maxZ = Math.max(boardPos1.getZ(), boardPos2.getZ()) + horizontalMargin;

        return location.getX() >= minX && location.getX() <= maxX
                && location.getY() >= minY && location.getY() <= maxY
                && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    private void configureInteractionHitbox(Interaction interaction, PieceData pieceData) {
        interaction.setInteractionWidth((float) getSelectionDiameter(pieceData));
        interaction.setInteractionHeight((float) getSelectionHeight(pieceData));
    }

    private void tickPhysics(Map<PieceData, Vector> velocities, Map<TeamType, TeamData> teamDataMap) {
        int substeps = calculateSubsteps(velocities);
        for (int step = 0; step < substeps; step++) {
            movePieces(velocities, 1.0D / substeps);
            resolveCollisions(velocities, teamDataMap);
        }

        applyFriction(velocities);
    }

    private void resolveCollisions(Map<PieceData, Vector> velocities, Map<TeamType, TeamData> teamDataMap) {
        List<PieceData> alivePieces = new ArrayList<>();
        for (TeamData teamData : teamDataMap.values()) {
            alivePieces.addAll(teamData.getAlivePieces());
        }

        for (int iteration = 0; iteration < COLLISION_SOLVER_ITERATIONS; iteration++) {
            boolean anyResolved = false;
            for (int i = 0; i < alivePieces.size(); i++) {
                PieceData first = alivePieces.get(i);
                for (int j = i + 1; j < alivePieces.size(); j++) {
                    PieceData second = alivePieces.get(j);
                    if (resolveCollision(first, second, velocities)) {
                        anyResolved = true;
                    }
                }
            }

            if (!anyResolved) {
                break;
            }
        }
    }

    private boolean resolveCollision(PieceData first, PieceData second, Map<PieceData, Vector> velocities) {
        Vector delta = second.getLocation().toVector().subtract(first.getLocation().toVector());
        delta.setY(0.0D);

        double minDistance = getPieceRadius(first) + getPieceRadius(second);
        double distanceSquared = delta.lengthSquared();
        if (distanceSquared >= minDistance * minDistance) {
            return false;
        }

        double distance = Math.sqrt(Math.max(distanceSquared, 0.0000001D));
        Vector normal = distance <= 0.0001D
                ? new Vector(1.0D, 0.0D, 0.0D)
                : delta.clone().multiply(1.0D / distance);

        double firstMass = getCollisionMass(first);
        double secondMass = getCollisionMass(second);

        Vector firstVelocity = velocities.getOrDefault(first, new Vector());
        Vector secondVelocity = velocities.getOrDefault(second, new Vector());

        double firstAlongNormal = firstVelocity.dot(normal);
        double secondAlongNormal = secondVelocity.dot(normal);
        double relativeAlongNormal = firstAlongNormal - secondAlongNormal;

        double overlap = minDistance - distance;

        if (relativeAlongNormal <= 0.0D) {
            double firstInverseMass = 1.0D / firstMass;
            double secondInverseMass = 1.0D / secondMass;
            double totalInverseMass = firstInverseMass + secondInverseMass;

            Vector firstCorrection = normal.clone().multiply(overlap * (firstInverseMass / totalInverseMass));
            Vector secondCorrection = normal.clone().multiply(overlap * (secondInverseMass / totalInverseMass));

            first.setLocation(flattenToBoard(first.getLocation().clone().subtract(firstCorrection)));
            second.setLocation(flattenToBoard(second.getLocation().clone().add(secondCorrection)));
            return true;
        }

        double firstInverseMass = 1.0D / firstMass;
        double secondInverseMass = 1.0D / secondMass;
        double totalInverseMass = firstInverseMass + secondInverseMass;

        double firstCorrectionRatio = firstInverseMass / totalInverseMass;
        double secondCorrectionRatio = secondInverseMass / totalInverseMass;

        Vector firstCorrection = normal.clone().multiply(overlap * firstCorrectionRatio);
        Vector secondCorrection = normal.clone().multiply(overlap * secondCorrectionRatio);

        first.setLocation(flattenToBoard(first.getLocation().clone().subtract(firstCorrection)));
        second.setLocation(flattenToBoard(second.getLocation().clone().add(secondCorrection)));

        double totalMass = firstMass + secondMass;
        double newFirstAlongNormal = (
                (firstMass - (COLLISION_RESTITUTION * secondMass)) * firstAlongNormal
                        + (1.0D + COLLISION_RESTITUTION) * secondMass * secondAlongNormal
        ) / totalMass;
        double newSecondAlongNormal = (
                (secondMass - (COLLISION_RESTITUTION * firstMass)) * secondAlongNormal
                        + (1.0D + COLLISION_RESTITUTION) * firstMass * firstAlongNormal
        ) / totalMass;

        Vector firstNormalComponent = normal.clone().multiply(firstAlongNormal);
        Vector secondNormalComponent = normal.clone().multiply(secondAlongNormal);
        Vector firstTangentComponent = firstVelocity.clone().subtract(firstNormalComponent);
        Vector secondTangentComponent = secondVelocity.clone().subtract(secondNormalComponent);

        Vector newFirstVelocity = firstTangentComponent.clone().add(normal.clone().multiply(newFirstAlongNormal));
        Vector newSecondVelocity = secondTangentComponent.clone().add(normal.clone().multiply(newSecondAlongNormal));

        boolean firstHitsSecond = firstAlongNormal > 0.0D
                && Math.abs(firstAlongNormal) >= Math.abs(secondAlongNormal);

        boolean secondHitsFirst = secondAlongNormal < 0.0D
                && Math.abs(secondAlongNormal) > Math.abs(firstAlongNormal);

        if (firstHitsSecond && firstMass > secondMass) {
            newFirstVelocity = firstTangentComponent.clone().add(normal.clone().multiply(
                    newFirstAlongNormal * HEAVY_TO_LIGHT_FORWARD_DAMPING
            ));
        }

        if (secondHitsFirst && secondMass > firstMass) {
            newSecondVelocity = secondTangentComponent.clone().add(normal.clone().multiply(
                    newSecondAlongNormal * HEAVY_TO_LIGHT_FORWARD_DAMPING
            ));
        }

        if (firstHitsSecond && firstMass < secondMass && newFirstAlongNormal < 0.0D) {
            newFirstVelocity = firstTangentComponent.clone().add(normal.clone().multiply(
                    newFirstAlongNormal * SMALL_TO_HEAVY_REBOUND_DAMPING
            ));
        }

        if (secondHitsFirst && secondMass < firstMass && newSecondAlongNormal > 0.0D) {
            newSecondVelocity = secondTangentComponent.clone().add(normal.clone().multiply(
                    newSecondAlongNormal * SMALL_TO_HEAVY_REBOUND_DAMPING
            ));
        }

        updateVelocity(first, newFirstVelocity, velocities);
        updateVelocity(second, newSecondVelocity, velocities);
        playPieceCollisionSound(first.getLocation());
        return true;
    }

    private void movePieces(Map<PieceData, Vector> velocities, double scale) {
        for (Iterator<Map.Entry<PieceData, Vector>> iterator = velocities.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<PieceData, Vector> entry = iterator.next();
            PieceData pieceData = entry.getKey();
            if (!pieceData.isAlive()) {
                iterator.remove();
                continue;
            }

            Location previousLocation = pieceData.getLocation().clone();
            Vector stepVelocity = entry.getValue().clone().multiply(scale);
            Location newLocation = pieceData.getLocation().clone().add(stepVelocity);
            Location flattenedLocation = flattenToBoard(newLocation);
            Vector adjustedVelocity = resolveObstacleCollision(previousLocation, flattenedLocation, entry.getValue().clone(), pieceData);
            if (adjustedVelocity != null) {
                adjustedVelocity.setY(0.0D);
                if (adjustedVelocity.lengthSquared() < STOP_THRESHOLD * STOP_THRESHOLD) {
                    iterator.remove();
                    continue;
                }
                entry.setValue(adjustedVelocity);
                flattenedLocation = flattenToBoard(previousLocation.clone().add(adjustedVelocity.clone().multiply(scale)));
            }
            pieceData.setLocation(flattenToBoard(flattenedLocation));

            if (!arenaData.isInsideBoard(pieceData.getLocation())) {
                playPieceFallSound(pieceData.getLocation());
                removePiece(pieceData);
                iterator.remove();
            }
        }
    }

    private @Nullable Vector resolveObstacleCollision(Location previousLocation, Location newLocation, Vector velocity, PieceData pieceData) {
        World world = newLocation.getWorld();
        if (world == null) {
            return null;
        }

        double radius = getPieceRadius(pieceData);
        int minBlockX = (int) Math.floor(newLocation.getX() - radius) - 1;
        int maxBlockX = (int) Math.floor(newLocation.getX() + radius) + 1;
        int minBlockZ = (int) Math.floor(newLocation.getZ() - radius) - 1;
        int maxBlockZ = (int) Math.floor(newLocation.getZ() + radius) + 1;
        int blockY = (int) Math.floor(newLocation.getY());

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                if (!isObstacleSlab(world, x, blockY, z) && !isObstacleSlab(world, x, blockY + 1, z)) {
                    continue;
                }

                double minX = x - radius;
                double maxX = x + 1.0D + radius;
                double minZ = z - radius;
                double maxZ = z + 1.0D + radius;
                if (newLocation.getX() < minX || newLocation.getX() > maxX || newLocation.getZ() < minZ || newLocation.getZ() > maxZ) {
                    continue;
                }

                Vector reflectedVelocity = velocity.clone();
                boolean collided = false;

                boolean wasOutsideX = previousLocation.getX() < minX || previousLocation.getX() > maxX;
                boolean wasOutsideZ = previousLocation.getZ() < minZ || previousLocation.getZ() > maxZ;

                if (wasOutsideX && reflectedVelocity.getX() != 0.0D) {
                    reflectedVelocity.setX(-reflectedVelocity.getX() * OBSTACLE_RESTITUTION);
                    collided = true;
                }
                if (wasOutsideZ && reflectedVelocity.getZ() != 0.0D) {
                    reflectedVelocity.setZ(-reflectedVelocity.getZ() * OBSTACLE_RESTITUTION);
                    collided = true;
                }

                if (!collided) {
                    double overlapX = Math.min(Math.abs(newLocation.getX() - minX), Math.abs(maxX - newLocation.getX()));
                    double overlapZ = Math.min(Math.abs(newLocation.getZ() - minZ), Math.abs(maxZ - newLocation.getZ()));
                    if (overlapX <= overlapZ && reflectedVelocity.getX() != 0.0D) {
                        reflectedVelocity.setX(-reflectedVelocity.getX() * OBSTACLE_RESTITUTION);
                    } else if (reflectedVelocity.getZ() != 0.0D) {
                        reflectedVelocity.setZ(-reflectedVelocity.getZ() * OBSTACLE_RESTITUTION);
                    } else {
                        reflectedVelocity.multiply(-OBSTACLE_RESTITUTION);
                    }
                }

                playObstacleCollisionSound(newLocation);
                return reflectedVelocity;
            }
        }

        return null;
    }

    private boolean isBlockedByObstacle(Location location, double pieceSize) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        double radius = getPieceRadius(pieceSize);
        int minBlockX = (int) Math.floor(location.getX() - radius) - 1;
        int maxBlockX = (int) Math.floor(location.getX() + radius) + 1;
        int minBlockZ = (int) Math.floor(location.getZ() - radius) - 1;
        int maxBlockZ = (int) Math.floor(location.getZ() + radius) + 1;
        int blockY = (int) Math.floor(location.getY());

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                if (!isPlacementObstacle(world, x, blockY, z) && !isPlacementObstacle(world, x, blockY + 1, z)) {
                    continue;
                }

                double minX = x - radius;
                double maxX = x + 1.0D + radius;
                double minZ = z - radius;
                double maxZ = z + 1.0D + radius;
                if (location.getX() >= minX && location.getX() <= maxX
                        && location.getZ() >= minZ && location.getZ() <= maxZ) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isObstacleSlab(World world, int x, int y, int z) {
        return Tag.SLABS.isTagged(world.getBlockAt(x, y, z).getType());
    }

    private boolean isPlacementObstacle(World world, int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        return Tag.SLABS.isTagged(type) || Tag.TRAPDOORS.isTagged(type);
    }

    private void playPieceCollisionSound(Location location) {
        playSoundToParticipants(location, Sound.BLOCK_STONE_HIT, 1.15F, 1.6F);
        playSoundToParticipants(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.85F);
        playSoundToParticipants(location, Sound.BLOCK_CALCITE_BREAK, 0.8F, 1.15F);
    }

    private void playObstacleCollisionSound(Location location) {
        playSoundToParticipants(location, Sound.BLOCK_ANVIL_LAND, 0.38F, 1.7F);
        playSoundToParticipants(location, Sound.BLOCK_METAL_HIT, 0.55F, 1.25F);
        playSoundToParticipants(location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.3F, 1.6F);
    }

    private void playPieceFallSound(Location location) {
        playSoundToParticipants(location, Sound.BLOCK_GLASS_BREAK, 0.55F, 0.95F);
    }

    private void playSoundToParticipants(Location location, Sound sound, float volume, float pitch) {
        for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        }
    }

    private void applyFriction(Map<PieceData, Vector> velocities) {
        for (Iterator<Map.Entry<PieceData, Vector>> iterator = velocities.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<PieceData, Vector> entry = iterator.next();
            Vector velocity = entry.getValue();
            double speed = velocity.length();
            if (speed <= 0.0001D) {
                iterator.remove();
                continue;
            }

            double mass = getCollisionMass(entry.getKey());
            double massResistance = Math.sqrt(mass);

            double reducedSpeed = (speed * FRICTION) - (ROLLING_RESISTANCE * massResistance);
            if (reducedSpeed <= STOP_THRESHOLD) {
                iterator.remove();
                continue;
            }

            velocity.multiply(reducedSpeed / speed);
        }
    }

    private int calculateSubsteps(Map<PieceData, Vector> velocities) {
        double maxDistance = 0.0D;
        for (Vector velocity : velocities.values()) {
            maxDistance = Math.max(maxDistance, velocity.length());
        }
        return Math.max(1, (int) Math.ceil(maxDistance / MAX_SUBSTEP_DISTANCE));
    }

    private void updateVelocity(PieceData pieceData, Vector velocity, Map<PieceData, Vector> velocities) {
        velocity.setY(0.0D);
        if (velocity.lengthSquared() >= STOP_THRESHOLD * STOP_THRESHOLD) {
            velocities.put(pieceData, velocity);
        } else {
            velocities.remove(pieceData);
        }
    }

    private Location flattenToBoard(Location location) {
        Location base = arenaData.getBoardPos1();
        if (base == null) {
            return normalizePieceLocation(location);
        }

        Location flattened = location.clone();
        flattened.setY(findFloorSurfaceY(flattened, base.getY()));
        flattened.setYaw(0.0F);
        flattened.setPitch(0.0F);
        return flattened;
    }

    private Location normalizePieceLocation(Location location) {
        Location normalized = location.clone();
        Location base = arenaData.getBoardPos1();
        if (base != null) {
            normalized.setY(findFloorSurfaceY(normalized, base.getY()));
        }
        normalized.setYaw(0.0F);
        normalized.setPitch(0.0F);
        return normalized;
    }

    private double findFloorSurfaceY(Location location, double fallbackY) {
        World world = location.getWorld();
        if (world == null) {
            return fallbackY;
        }

        int blockX = (int) Math.floor(location.getX());
        int blockZ = (int) Math.floor(location.getZ());
        int baseY = (int) Math.floor(fallbackY);
        double bestY = Double.NEGATIVE_INFINITY;
        for (int y = baseY - 2; y <= baseY + 2; y++) {
            Block block = world.getBlockAt(blockX, y, blockZ);
            if (block.isEmpty() || block.isPassable()) {
                continue;
            }

            BoundingBox boundingBox = block.getBoundingBox();
            if (boundingBox.getVolume() <= 0.0D) {
                continue;
            }

            bestY = Math.max(bestY, boundingBox.getMaxY());
        }

        return bestY == Double.NEGATIVE_INFINITY ? fallbackY : bestY;
    }

    private double samePlaneDistance(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double getPieceRadius() {
        return getPieceRadius(arenaData.getPieceSize());
    }

    public double getPlacementDistance() {
        return getPieceRadius() * 1.85D;
    }

    public double getSelectionDiameter() {
        return getSelectionDiameter(arenaData.getPieceSize());
    }

    public double getSelectionHeight() {
        return getSelectionHeight(arenaData.getPieceSize());
    }

    private double getPieceRadius(PieceData pieceData) {
        return getPieceRadius(pieceData.getPieceSize());
    }

    private double getPieceRadius(double pieceSize) {
        return Math.max(0.14D, (pieceSize * DISPLAY_FOOTPRINT_SCALE) / 2.0D);
    }

    public double getSelectionDiameter(PieceData pieceData) {
        return getSelectionDiameter(pieceData.getPieceSize());
    }

    private double getSelectionDiameter(double pieceSize) {
        return Math.max(0.35D, pieceSize * DISPLAY_FOOTPRINT_SCALE * SELECTION_FOOTPRINT_MULTIPLIER);
    }

    private double getSelectionHeight(PieceData pieceData) {
        return getSelectionHeight(pieceData.getPieceSize());
    }

    private double getSelectionHeight(double pieceSize) {
        return Math.max(0.18D, pieceSize * 0.12D);
    }

    private double getCollisionMass(PieceData pieceData) {
        double normalizedSize = pieceData.getPieceSize() / DEFAULT_PIECE_SIZE;
        return Math.max(0.02D, Math.min(MAX_COLLISION_MASS, Math.pow(normalizedSize, COLLISION_MASS_EXPONENT)));
    }

    private void cancelPhysicsTask() {
        if (physicsTask != null) {
            physicsTask.cancel();
            physicsTask = null;
        }
    }
}
