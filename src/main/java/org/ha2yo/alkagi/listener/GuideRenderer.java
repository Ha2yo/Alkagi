package org.ha2yo.alkagi.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.ha2yo.alkagi.game.GameManager;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.BoardManager;
import org.ha2yo.alkagi.game.TeamType;
import org.ha2yo.alkagi.game.model.PieceData;
import org.ha2yo.alkagi.game.model.TeamData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 배치 위치와 발사 방향을 플레이어에게 시각적으로 안내한다.
 */
public final class GuideRenderer {

    private static final double TRACE_DISTANCE = 256.0D;
    private static final double GUIDE_Y_OFFSET = 0.12D;
    private static final double AIM_ARROW_HEIGHT = 0.02D;
    private static final int ARROW_SHAFT_SEGMENTS = 13;
    private static final int ARROW_HEAD_SEGMENTS = 7;
    private static final double ARROW_HEAD_BACK_OFFSET = 0.18D;
    private static final double ARROW_HEAD_BACK_STEP = 0.24D;
    private static final double ARROW_HEAD_SIDE_OFFSET = 0.18D;
    private static final double ARROW_HEAD_SIDE_STEP = 0.16D;
    private static final double ARROW_SHAFT_START_OFFSET = 0.12D;
    private static final double ARM_STAND_Y_OFFSET = -1.1D;
    private static final double ARROW_LATERAL_OFFSET = 0.0D;
    private static final double ARM_HORIZONTAL_PITCH = Math.toRadians(270.0D);
    private static final double ARM_HEAD_YAW = Math.toRadians(24.0D);
    private static final double RING_POINT_SPACING = 0.2D;
    private static final double TURN_PIECE_HITBOX_POINT_SPACING = 0.08D;
    private static final double TURN_PIECE_RING_Y_OFFSET = 0.04D;
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(Color.fromRGB(40, 95, 255), 1.45F);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(Color.fromRGB(230, 45, 45), 1.45F);
    private static final Particle.DustOptions TURN_BLUE_DUST = new Particle.DustOptions(Color.fromRGB(40, 95, 255), 1.6F);
    private static final Particle.DustOptions TURN_RED_DUST = new Particle.DustOptions(Color.fromRGB(230, 45, 45), 1.6F);
    private static final Particle.DustOptions CYAN_DUST = new Particle.DustOptions(Color.fromRGB(105, 220, 235), 1.4F);

    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final Map<UUID, AimArrowMarker> aimMarkers = new HashMap<>();
    private BukkitTask task;

    public GuideRenderer(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    public void start() {
        stop();
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAllAimMarkers();
    }

    /**
     * 현재 게임 단계에 따라 배치 가이드 또는 조준 가이드를 매 틱 갱신한다.
     */
    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            GameSession session = gameManager.getSession(player);
            if (session == null) {
                clearAimMarker(player.getUniqueId());
                continue;
            }
            if (session.isPlacementPhase()) {
                renderPlacementGuide(player, session);
            } else if (session.isPlayingPhase()) {
                renderPlayingGuide(player, session);
                if (!session.isCurrentTurnPlayer(player.getUniqueId()) || session.getSelectedPiece() == null) {
                    session.sendTurnStatusActionBar(player);
                }
            } else {
                clearAimMarker(player.getUniqueId());
            }
        }
    }

    private void renderPlacementGuide(Player player, GameSession session) {
        clearAimMarker(player.getUniqueId());
        TeamType teamType = session.getTeam(player.getUniqueId());
        if (teamType == null || !session.getPlacementPlayers().containsValue(player.getUniqueId())) {
            return;
        }

        Location target = session.getArenaData().projectToBoard(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                TRACE_DISTANCE
        );
        if (target == null) {
            return;
        }

        drawPlacementMarker(player, target.clone().add(0.0D, 0.05D, 0.0D), teamType);
    }

    private void renderPlayingGuide(Player player, GameSession session) {
        if (!session.isCurrentTurnPlayer(player.getUniqueId()) || session.getBoardManager().isActionRunning()) {
            clearAimMarker(player.getUniqueId());
            return;
        }

        PieceData selectedPiece = session.getSelectedPiece();
        if (selectedPiece == null) {
            drawCurrentTurnTeamPieces(player, session);
            clearAimMarker(player.getUniqueId());
            return;
        }

        drawAimGuide(player, selectedPiece, session);
    }

    private void drawCurrentTurnTeamPieces(Player viewer, GameSession session) {
        TeamType teamType = session.getTeam(viewer.getUniqueId());
        if (teamType == null || teamType != session.getCurrentTurnTeam()) {
            return;
        }

        TeamData teamData = session.getTeamDataMap().get(teamType);
        if (teamData == null) {
            return;
        }

        Particle.DustOptions dust = teamType == TeamType.BLUE ? TURN_BLUE_DUST : TURN_RED_DUST;
        for (PieceData pieceData : teamData.getAlivePieces()) {
            drawPrivateHitboxOutline(
                    viewer,
                    pieceData.getLocation().clone().add(0.0D, TURN_PIECE_RING_Y_OFFSET, 0.0D),
                    session.getBoardManager().getSelectionDiameter(pieceData),
                    dust
            );
        }
    }

    private void drawPlacementMarker(Player viewer, Location target, TeamType teamType) {
        Particle.DustOptions dust = teamType == TeamType.BLUE ? BLUE_DUST : RED_DUST;
        double radius = gameManager.getSession(viewer) == null
                ? gameManager.getBoardManager().getPieceRadius()
                : gameManager.getSession(viewer).getBoardManager().getPieceRadius();
        drawRing(viewer, target, radius, dust, 18);
    }

    /**
     * 선택된 말 주변에는 조작 반경 원과 발사 방향 화살표를 함께 표시한다.
     */
    private void drawAimGuide(Player player, PieceData selectedPiece, GameSession session) {
        Location origin = selectedPiece.getLocation().clone().add(0.0D, GUIDE_Y_OFFSET, 0.0D);
        double controlRadius = session.getBoardManager().getLaunchControlRadius();

        drawRing(
                player,
                origin,
                controlRadius,
                CYAN_DUST,
                Math.max(32, (int) Math.ceil((Math.PI * 2.0D * controlRadius) / RING_POINT_SPACING))
        );

        Vector direction = session.getFlatLaunchDirection(player);
        if (direction.lengthSquared() <= 0.0001D) {
            clearAimMarker(player.getUniqueId());
            return;
        }

        double power = session.getLaunchPower(player.getUniqueId());
        double maxGuideLength = Math.max(0.35D, controlRadius);
        double guideLength = Math.max(0.2D, maxGuideLength * (power / BoardManager.MAX_LAUNCH_POWER));
        if (guideLength <= 0.0001D) {
            clearAimMarker(player.getUniqueId());
            return;
        }

        Location arrowOrigin = origin.clone().add(0.0D, AIM_ARROW_HEIGHT, 0.0D);
        Location tip = arrowOrigin.clone().add(direction.clone().multiply(guideLength));
        updateAimMarker(player, arrowOrigin, tip, direction);

        player.sendActionBar(Component.text("세기: " + String.format("%.2f", power), NamedTextColor.GREEN));
    }

    private void drawRing(Player viewer, Location center, double radius, Particle.DustOptions dust, int points) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            spawnDust(viewer, new Location(world, x, center.getY(), z), dust);
        }
    }

    private void drawPrivateHitboxOutline(Player viewer, Location center, double diameter, Particle.DustOptions dust) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double half = diameter * 0.5D;
        int pointsPerSide = Math.max(2, (int) Math.ceil(diameter / TURN_PIECE_HITBOX_POINT_SPACING));
        double minX = center.getX() - half;
        double maxX = center.getX() + half;
        double minZ = center.getZ() - half;
        double maxZ = center.getZ() + half;

        for (int i = 0; i <= pointsPerSide; i++) {
            double ratio = (double) i / pointsPerSide;
            double x = minX + ((maxX - minX) * ratio);
            double z = minZ + ((maxZ - minZ) * ratio);
            spawnPrivateDust(viewer, new Location(world, x, center.getY(), minZ), dust);
            spawnPrivateDust(viewer, new Location(world, x, center.getY(), maxZ), dust);
            spawnPrivateDust(viewer, new Location(world, minX, center.getY(), z), dust);
            spawnPrivateDust(viewer, new Location(world, maxX, center.getY(), z), dust);
        }
    }

    /**
     * 조준 화살표는 재사용 가능한 아머스탠드 묶음을 플레이어별로 관리한다.
     */
    private void updateAimMarker(Player owner, Location arrowOrigin, Location tip, Vector direction) {
        World world = tip.getWorld();
        if (world == null) {
            clearAimMarker(owner.getUniqueId());
            return;
        }

        AimArrowMarker marker = aimMarkers.compute(
                owner.getUniqueId(),
                (playerId, existing) -> existing != null && existing.isValid() ? existing : AimArrowMarker.spawn(world, tip)
        );
        if (marker == null || !marker.isValid()) {
            clearAimMarker(owner.getUniqueId());
            return;
        }

        Vector flatDirection = direction.clone().setY(0.0D);
        if (flatDirection.lengthSquared() <= 0.0001D) {
            clearAimMarker(owner.getUniqueId());
            return;
        }
        flatDirection.normalize();

        Vector sideDirection = new Vector(-flatDirection.getZ(), 0.0D, flatDirection.getX()).normalize();
        Vector lateralOffset = sideDirection.clone().multiply(ARROW_LATERAL_OFFSET);
        Vector shaftStartOffset = flatDirection.clone().multiply(ARROW_SHAFT_START_OFFSET);
        marker.update(
                arrowOrigin.clone().add(lateralOffset).subtract(shaftStartOffset),
                tip.clone().add(lateralOffset),
                flatDirection,
                sideDirection
        );

        marker.showTo(owner, plugin);
    }

    private static void configureArrowStand(ArmorStand stand) {
        stand.setVisible(false);
        stand.setMarker(true);
        stand.setSmall(false);
        stand.setArms(false);
        stand.setBasePlate(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        if (stand.getEquipment() != null) {
            stand.getEquipment().setItemInMainHand(null);
            stand.getEquipment().setHelmet(new ItemStack(Material.LIME_WOOL));
        }
    }

    private void clearAimMarker(UUID playerId) {
        AimArrowMarker marker = aimMarkers.remove(playerId);
        if (marker != null) {
            marker.remove();
        }
    }

    private void clearAllAimMarkers() {
        for (UUID playerId : new ArrayList<>(aimMarkers.keySet())) {
            clearAimMarker(playerId);
        }
    }

    private void spawnDust(Player viewer, Location location, Particle.DustOptions dust) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        world.spawnParticle(Particle.DUST, location, 1, 0.01D, 0.0D, 0.01D, 0.0D, dust, true);
    }

    private void spawnPrivateDust(Player viewer, Location location, Particle.DustOptions dust) {
        viewer.spawnParticle(Particle.DUST, location, 1, 0.01D, 0.0D, 0.01D, 0.0D, dust, true);
    }

    /**
     * 화살표를 구성하는 아머스탠드 묶음을 한 객체로 다룬다.
     */
    private record AimArrowMarker(
            java.util.List<ArmorStand> shaft,
            java.util.List<ArmorStand> leftHead,
            java.util.List<ArmorStand> rightHead
    ) {

        private static AimArrowMarker spawn(World world, Location location) {
            java.util.List<ArmorStand> shaft = new java.util.ArrayList<>();
            for (int i = 0; i < ARROW_SHAFT_SEGMENTS; i++) {
                shaft.add(world.spawn(location, ArmorStand.class, GuideRenderer::configureArrowStand));
            }

            java.util.List<ArmorStand> leftHead = new java.util.ArrayList<>();
            java.util.List<ArmorStand> rightHead = new java.util.ArrayList<>();
            for (int i = 0; i < ARROW_HEAD_SEGMENTS; i++) {
                leftHead.add(world.spawn(location, ArmorStand.class, GuideRenderer::configureArrowStand));
                rightHead.add(world.spawn(location, ArmorStand.class, GuideRenderer::configureArrowStand));
            }
            return new AimArrowMarker(shaft, leftHead, rightHead);
        }

        private boolean isValid() {
            for (ArmorStand stand : shaft) {
                if (!stand.isValid()) {
                    return false;
                }
            }
            for (ArmorStand stand : leftHead) {
                if (!stand.isValid()) {
                    return false;
                }
            }
            for (ArmorStand stand : rightHead) {
                if (!stand.isValid()) {
                    return false;
                }
            }
            return true;
        }

        private void update(Location origin, Location tip, Vector direction, Vector side) {
            for (int i = 0; i < shaft.size(); i++) {
                double progress = shaft.size() == 1 ? 1.0D : i / (double) (shaft.size() - 1);
                Location segment = origin.clone().add(tip.toVector().subtract(origin.toVector()).multiply(progress));
                ArmorStand stand = shaft.get(i);
                Location standLocation = segment.clone().add(0.0D, ARM_STAND_Y_OFFSET, 0.0D);
                standLocation.setDirection(direction);
                stand.teleport(standLocation);
                stand.setHeadPose(new EulerAngle(ARM_HORIZONTAL_PITCH, 0.0D, 0.0D));
            }

            for (int i = 0; i < leftHead.size(); i++) {
                double backOffset = ARROW_HEAD_BACK_OFFSET + (ARROW_HEAD_BACK_STEP * i);
                double sideOffset = ARROW_HEAD_SIDE_OFFSET + (ARROW_HEAD_SIDE_STEP * i);
                Location headBase = tip.clone().subtract(direction.clone().multiply(backOffset));

                ArmorStand left = leftHead.get(i);
                Location leftLocation = headBase.clone().add(side.clone().multiply(sideOffset)).add(0.0D, ARM_STAND_Y_OFFSET, 0.0D);
                leftLocation.setDirection(direction);
                left.teleport(leftLocation);
                left.setHeadPose(new EulerAngle(ARM_HORIZONTAL_PITCH, -ARM_HEAD_YAW, 0.0D));

                ArmorStand right = rightHead.get(i);
                Location rightLocation = headBase.clone().subtract(side.clone().multiply(sideOffset)).add(0.0D, ARM_STAND_Y_OFFSET, 0.0D);
                rightLocation.setDirection(direction);
                right.teleport(rightLocation);
                right.setHeadPose(new EulerAngle(ARM_HORIZONTAL_PITCH, ARM_HEAD_YAW, 0.0D));
            }
        }

        private void showTo(Player player, JavaPlugin plugin) {
            for (ArmorStand stand : shaft) {
                player.showEntity(plugin, stand);
            }
            for (ArmorStand stand : leftHead) {
                player.showEntity(plugin, stand);
            }
            for (ArmorStand stand : rightHead) {
                player.showEntity(plugin, stand);
            }
        }

        private void remove() {
            for (ArmorStand stand : shaft) {
                if (stand.isValid()) {
                    stand.remove();
                }
            }
            for (ArmorStand stand : leftHead) {
                if (stand.isValid()) {
                    stand.remove();
                }
            }
            for (ArmorStand stand : rightHead) {
                if (stand.isValid()) {
                    stand.remove();
                }
            }
        }
    }
}
