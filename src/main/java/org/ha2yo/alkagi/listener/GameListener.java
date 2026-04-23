package org.ha2yo.alkagi.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.ha2yo.alkagi.game.GameManager;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.TeamType;
import org.ha2yo.alkagi.game.model.PieceData;

public final class GameListener implements Listener {

    private static final double TRACE_DISTANCE = 256.0D;
    private static final double SELECTION_RAY_SIZE = 0.45D;

    private final GameManager gameManager;

    public GameListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        gameManager.getSession().refreshPlayerFormatting(event.getPlayer());
        gameManager.getSession().refreshTurnTimerViewer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gameManager.handleQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        TeamType teamType = gameManager.getSession().getTeam(player.getUniqueId());
        if (teamType == null) {
            return;
        }

        event.renderer((source, sourceDisplayName, message, viewer) ->
            Component.text()
                .append(Component.text(source.getName(), teamType.getColor()))
                .append(Component.text(": "))
                .append(message)
                .build()
        );
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        GameSession session = gameManager.getSession();

        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (session.isPlayingPhase() && session.isCurrentTurnPlayer(player.getUniqueId()) && session.getSelectedPiece() != null) {
                boolean cancelledSelection = session.cancelSelectedPiece(player);
                if (cancelledSelection) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("말 선택을 취소했습니다.", NamedTextColor.YELLOW));
                }
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        if (session.isPlacementPhase()) {
            if (event.getClickedBlock() == null) {
                return;
            }
            boolean placed = session.placePiece(player, event.getClickedBlock().getLocation().add(0.5D, 0.0D, 0.5D));
            if (placed) {
                event.setCancelled(true);
                TeamType teamType = session.getTeam(player.getUniqueId());
                if (teamType != null) {
                    int count = session.getPlacedCount(teamType);
                    player.sendMessage(Component.text(
                        teamType.getDisplayName() + " 말 배치: " + count + "/" + session.getConfiguredPieceCount(),
                        teamType.getColor()
                    ));
                }
            }
            return;
        }

        if (!session.isPlayingPhase() || !session.isCurrentTurnPlayer(player.getUniqueId())) {
            return;
        }

        if (session.getSelectedPiece() == null) {
            PieceData pieceData = rayTraceSelectablePiece(player, session);
            if (pieceData == null) {
                player.sendActionBar(Component.text("SELECT MISS", NamedTextColor.RED));
                return;
            }

            event.setCancelled(true);
            player.sendActionBar(Component.text("SELECT OK", NamedTextColor.GREEN));
            player.sendMessage(Component.text(
                pieceData.getTeamType().getDisplayName() + " " + pieceData.getPieceId() + "번 말을 선택했습니다. 이제 보드를 우클릭해 방향을 정해 주세요.",
                pieceData.getTeamType().getColor()
            ));
            return;
        }

        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }

        Location target = resolveLaunchTarget(player, event);
        if (target == null) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("보드 쪽을 바라본 상태에서 우클릭해 주세요.", NamedTextColor.YELLOW));
            return;
        }

        boolean launched = session.launchSelectedPiece(player, target);
        if (launched) {
            event.setCancelled(true);
            player.sendMessage(Component.text("말을 발사했습니다.", NamedTextColor.GREEN));
        }
    }

    private PieceData rayTraceSelectablePiece(Player player, GameSession session) {
        RayTraceResult entityTrace = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            TRACE_DISTANCE,
            SELECTION_RAY_SIZE,
            entity -> entity instanceof Interaction
                && gameManager.getBoardManager().isPieceSelectionEntity(entity.getUniqueId())
        );

        if (entityTrace == null || entityTrace.getHitEntity() == null) {
            player.sendActionBar(Component.text("RAY MISS", NamedTextColor.RED));
            return null;
        }

        PieceData pieceData = session.selectPiece(player, entityTrace.getHitEntity().getUniqueId());
        if (pieceData == null) {
            player.sendActionBar(Component.text("RAY HIT / SELECT FAIL", NamedTextColor.YELLOW));
            return null;
        }

        player.sendActionBar(Component.text("RAY HIT / SELECT OK", NamedTextColor.GREEN));
        return pieceData;
    }

    private Location resolveLaunchTarget(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            return event.getClickedBlock().getLocation().add(0.5D, 0.5D, 0.5D);
        }

        return gameManager.getArenaData().projectToBoardPlane(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            TRACE_DISTANCE
        );
    }
}
