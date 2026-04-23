package org.ha2yo.alkagi.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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
 * 알까기 말 생성, 배치, 발사, 충돌, 낙하 처리를 담당한다.
 */
public final class BoardManager {

    private static final NamespacedKey BLACK_PIECE_ITEM_MODEL = NamespacedKey.minecraft("alkagi_mal/black");
    private static final NamespacedKey WHITE_PIECE_ITEM_MODEL = NamespacedKey.minecraft("alkagi_mal/white");
    private static final String PIECE_ENTITY_MARKER = "piece";

    private static final double DISPLAY_FOOTPRINT_SCALE = 0.57D;
    private static final double FRICTION = 0.86D;
    private static final double ROLLING_RESISTANCE = 0.035D;
    private static final double STOP_THRESHOLD = 0.05D;
    private static final double MAX_POWER = 12.5D;
    private static final double COLLISION_RESTITUTION = 0.86D;
    private static final double OBSTACLE_RESTITUTION = 0.82D;
    private static final double MAX_SUBSTEP_DISTANCE = 0.18D;
    private static final int COLLISION_SOLVER_ITERATIONS = 3;

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

    public boolean canPlacePiece(Location location, Map<TeamType, TeamData> teamDataMap) {
        if (!arenaData.isInsideBoard(location)) {
            return false;
        }

        for (TeamData teamData : teamDataMap.values()) {
            for (PieceData piece : teamData.getAlivePieces()) {
                if (samePlaneDistance(piece.getLocation(), location) < getPlacementDistance()) {
                    return false;
                }
            }
        }
        return true;
    }

    public PieceData spawnPiece(TeamType teamType, int pieceId, Location location) {
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
        configureInteractionHitbox(interaction);
        markPieceEntity(interaction);

        ItemDisplay display = (ItemDisplay) world.spawnEntity(spawnLocation, EntityType.ITEM_DISPLAY);
        display.setItemStack(createPieceItem(teamType));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setInterpolationDuration(1);
        display.setViewRange(256.0F);
        configurePieceDisplay(display);
        markPieceEntity(display);

        PieceData pieceData = new PieceData(pieceId, teamType, spawnLocation);
        pieceData.setEntity(armorStand);
        pieceData.setInteractionEntity(interaction);
        pieceData.setDisplayEntity(display);
        pieceByEntityId.put(interaction.getUniqueId(), pieceData);
        return pieceData;
    }

    private ItemStack createPieceItem(TeamType teamType) {
        Material material = teamType == TeamType.BLACK ? Material.FIRE_CHARGE : Material.SNOWBALL;
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(teamType == TeamType.BLACK ? BLACK_PIECE_ITEM_MODEL : WHITE_PIECE_ITEM_MODEL);
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

    public void launchPiece(PieceData selectedPiece, Location targetLocation, Map<TeamType, TeamData> teamDataMap, Runnable onFinished) {
        if (actionRunning) {
            return;
        }

        Vector velocity = createLaunchVector(selectedPiece, targetLocation);
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
                configureInteractionHitbox(interaction);
            }
            ItemDisplay display = piece.getDisplayEntity();
            if (display != null) {
                configurePieceDisplay(display);
            }
        }
    }

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

    public Vector createLaunchVector(PieceData selectedPiece, Location targetLocation) {
        Location clampedTarget = clampLaunchTarget(selectedPiece, targetLocation);
        Vector direction = selectedPiece.getLocation().toVector().subtract(clampedTarget.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() <= 0.0001D) {
            return new Vector();
        }

        double normalizedDistance = Math.min(direction.length() / getLaunchControlRadius(), 1.0D);
        double power = applyLaunchPowerCurve(normalizedDistance * MAX_POWER);
        return direction.normalize().multiply(power);
    }

    private double applyLaunchPowerCurve(double linearPower) {
        double softPowerLimit = MAX_POWER / 2.0D;
        if (linearPower > softPowerLimit) {
            return linearPower;
        }

        double normalizedSoftPower = linearPower / softPowerLimit;
        return normalizedSoftPower * normalizedSoftPower * normalizedSoftPower * softPowerLimit;
    }

    private void configurePieceDisplay(ItemDisplay display) {
        float pieceSize = (float) arenaData.getPieceSize();
        float lift = Math.max(0.10F, pieceSize * 0.18F);
        display.setTransformation(new Transformation(
            new Vector3f(0.0F, lift, 0.0F),
            new AxisAngle4f(),
            new Vector3f(pieceSize, pieceSize * 0.42F, pieceSize),
            new AxisAngle4f()
        ));
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
        if (!isNearBoard(entity.getLocation(), 2.0D, 3.0D)) {
            return false;
        }
        if (entity instanceof ItemDisplay display) {
            return isPieceDisplayItem(display.getItemStack());
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
        return BLACK_PIECE_ITEM_MODEL.equals(itemModel) || WHITE_PIECE_ITEM_MODEL.equals(itemModel);
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

    private void configureInteractionHitbox(Interaction interaction) {
        interaction.setInteractionWidth((float) getSelectionDiameter());
        interaction.setInteractionHeight((float) getSelectionHeight());
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

        double minDistance = getPieceRadius() * 2.0D;
        double distanceSquared = delta.lengthSquared();
        if (distanceSquared >= minDistance * minDistance) {
            return false;
        }

        double distance = Math.sqrt(Math.max(distanceSquared, 0.0000001D));
        Vector normal = distance <= 0.0001D
            ? new Vector(1.0D, 0.0D, 0.0D)
            : delta.clone().multiply(1.0D / distance);

        double overlap = minDistance - distance;
        Vector correction = normal.clone().multiply(overlap / 2.0D);
        first.setLocation(flattenToBoard(first.getLocation().clone().subtract(correction)));
        second.setLocation(flattenToBoard(second.getLocation().clone().add(correction)));

        Vector firstVelocity = velocities.getOrDefault(first, new Vector());
        Vector secondVelocity = velocities.getOrDefault(second, new Vector());

        double firstAlongNormal = firstVelocity.dot(normal);
        double secondAlongNormal = secondVelocity.dot(normal);
        double relativeAlongNormal = firstAlongNormal - secondAlongNormal;
        if (relativeAlongNormal <= 0.0D) {
            return true;
        }

        double newFirstAlongNormal = ((1.0D - COLLISION_RESTITUTION) * firstAlongNormal
            + (1.0D + COLLISION_RESTITUTION) * secondAlongNormal) / 2.0D;
        double newSecondAlongNormal = ((1.0D + COLLISION_RESTITUTION) * firstAlongNormal
            + (1.0D - COLLISION_RESTITUTION) * secondAlongNormal) / 2.0D;

        Vector firstNormalComponent = normal.clone().multiply(firstAlongNormal);
        Vector secondNormalComponent = normal.clone().multiply(secondAlongNormal);
        Vector firstTangentComponent = firstVelocity.clone().subtract(firstNormalComponent);
        Vector secondTangentComponent = secondVelocity.clone().subtract(secondNormalComponent);

        Vector newFirstVelocity = firstTangentComponent.add(normal.clone().multiply(newFirstAlongNormal));
        Vector newSecondVelocity = secondTangentComponent.add(normal.clone().multiply(newSecondAlongNormal));

        updateVelocity(first, newFirstVelocity, velocities);
        updateVelocity(second, newSecondVelocity, velocities);
        playCollisionSound(first.getLocation());
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
            Vector adjustedVelocity = resolveObstacleCollision(previousLocation, flattenedLocation, entry.getValue().clone());
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
                playEliminationSound(pieceData.getLocation());
                removePiece(pieceData);
                iterator.remove();
            }
        }
    }

    private @Nullable Vector resolveObstacleCollision(Location previousLocation, Location newLocation, Vector velocity) {
        World world = newLocation.getWorld();
        if (world == null) {
            return null;
        }

        double radius = getPieceRadius();
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

                playCollisionSound(newLocation);
                return reflectedVelocity;
            }
        }

        return null;
    }

    private boolean isObstacleSlab(World world, int x, int y, int z) {
        return Tag.SLABS.isTagged(world.getBlockAt(x, y, z).getType());
    }

    private void playCollisionSound(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        world.playSound(location, Sound.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 0.7F, 1.6F);
        world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.35F, 1.85F);
        world.playSound(location, Sound.BLOCK_CALCITE_BREAK, SoundCategory.PLAYERS, 0.45F, 1.15F);
    }

    private void playEliminationSound(Location location) {
        for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.2F, 0.75F);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1.0F, 0.8F);
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

            double reducedSpeed = (speed * FRICTION) - ROLLING_RESISTANCE;
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
        flattened.setY(base.getY());
        flattened.setYaw(0.0F);
        flattened.setPitch(0.0F);
        return flattened;
    }

    private Location normalizePieceLocation(Location location) {
        Location normalized = location.clone();
        normalized.setYaw(0.0F);
        normalized.setPitch(0.0F);
        return normalized;
    }

    private double samePlaneDistance(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double getPieceRadius() {
        return Math.max(0.14D, (arenaData.getPieceSize() * DISPLAY_FOOTPRINT_SCALE) / 2.0D);
    }

    public double getPlacementDistance() {
        return getPieceRadius() * 1.85D;
    }

    public double getSelectionDiameter() {
        return Math.max(0.42D, arenaData.getPieceSize() * DISPLAY_FOOTPRINT_SCALE * 1.3D);
    }

    public double getSelectionHeight() {
        return Math.max(1.0D, arenaData.getPieceSize() * 1.35D);
    }

    private void cancelPhysicsTask() {
        if (physicsTask != null) {
            physicsTask.cancel();
            physicsTask = null;
        }
    }
}
