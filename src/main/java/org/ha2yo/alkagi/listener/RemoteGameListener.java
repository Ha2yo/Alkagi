package org.ha2yo.alkagi.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.ha2yo.alkagi.game.GameManager;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.GameState;
import org.ha2yo.alkagi.game.TeamType;
import org.ha2yo.alkagi.game.model.PieceData;

public final class RemoteGameListener implements Listener {

    private static final double REMOTE_TRACE_DISTANCE = 256.0D;
    private static final double SELECTION_RAY_SIZE = 0.45D;

    private final GameManager gameManager;

    public RemoteGameListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        GameSession session = gameManager.getSession();
        if (session.getGameState() != GameState.WAITING) {
            Location lobbyLocation = gameManager.getArenaData().getLobbyLocation();
            if (lobbyLocation != null) {
                player.teleport(lobbyLocation);
            }
            session.applySpectatorState(player);
            session.refreshPlayerFormatting(player);
            session.refreshTurnTimerViewer(player);
            return;
        }

        gameManager.join(player);
        session.refreshPlayerFormatting(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gameManager.handleQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        GameSession session = gameManager.getSession();
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (session.isRemoteController(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        GameSession session = gameManager.getSession();
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (session.isRemoteController(ingredient)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (gameManager.getSession().isRemoteController(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        GameSession session = gameManager.getSession();
        if (session.getTeam(player.getUniqueId()) == null) {
            return;
        }

        if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            return;
        }

        if (session.isTeamArmor(event.getCurrentItem()) || session.isTeamArmor(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        GameSession session = gameManager.getSession();
        if (session.getTeam(player.getUniqueId()) == null) {
            return;
        }

        if (session.isTeamArmor(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (event.getView().getSlotType(rawSlot) == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
                event.setCancelled(true);
                return;
            }
        }
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
            if (session.isUsingRemoteController(player)
                && session.isPlayingPhase()
                && session.isCurrentTurnPlayer(player.getUniqueId())
                && session.getSelectedPiece() != null) {
                boolean cancelledSelection = session.cancelSelectedPiece(player);
                if (cancelledSelection) {
                    event.setCancelled(true);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.7F, 0.85F);
                    player.sendMessage(Component.text("말 선택을 취소했습니다.", NamedTextColor.YELLOW));
                }
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (!session.isUsingRemoteController(player)) {
            return;
        }

        if (session.isPlacementPhase()) {
            Location target = gameManager.getArenaData().projectToBoard(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                REMOTE_TRACE_DISTANCE
            );
            if (target == null) {
                return;
            }

            boolean placed = session.placePiece(player, target);
            if (placed) {
                event.setCancelled(true);
                TeamType teamType = session.getTeam(player.getUniqueId());
                if (teamType != null) {
                    int count = session.getPlacedCount(teamType);
                    player.sendMessage(Component.text(
                        formatTeamDisplayName(teamType) + " 말 배치: " + count + "/" + session.getConfiguredPieceCount(),
                        NamedTextColor.YELLOW
                    ));
                }
            }
            return;
        }

        if (!session.isPlayingPhase() || !session.isCurrentTurnPlayer(player.getUniqueId())) {
            return;
        }
        if (session.getSelectedPiece() == null) {
            PieceData pieceData = selectPieceByRay(player, session);
            if (pieceData == null) {
                return;
            }

            event.setCancelled(true);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.8F, 1.25F);
            player.sendMessage(Component.text("이제 마우스를 움직여 방향과 세기를 정해 주세요.", NamedTextColor.YELLOW));
            return;
        }

        Location target = resolveLaunchTarget(player);
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

    @EventHandler
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        GameSession session = gameManager.getSession();

        if (!session.isUsingRemoteController(player)
            || !session.isPlayingPhase()
            || !session.isCurrentTurnPlayer(player.getUniqueId())
            || session.getSelectedPiece() != null) {
            return;
        }

        if (!(event.getRightClicked() instanceof Interaction interaction)
            || !gameManager.getBoardManager().isPieceSelectionEntity(interaction.getUniqueId())) {
            return;
        }

        PieceData pieceData = session.selectPiece(player, interaction.getUniqueId());
        if (pieceData == null) {
            return;
        }

        event.setCancelled(true);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.8F, 1.25F);
        player.sendMessage(Component.text("이제 마우스를 움직여 방향과 세기를 정해 주세요.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof Interaction interaction)) {
            return;
        }

        GameSession session = gameManager.getSession();
        if (!session.isUsingRemoteController(player)
            || !session.isPlayingPhase()
            || !session.isCurrentTurnPlayer(player.getUniqueId())
            || session.getSelectedPiece() == null
            || !gameManager.getBoardManager().isPieceSelectionEntity(interaction.getUniqueId())) {
            return;
        }

        boolean cancelledSelection = session.cancelSelectedPiece(player);
        if (!cancelledSelection) {
            return;
        }

        event.setCancelled(true);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.7F, 0.85F);
        player.sendMessage(Component.text("말 선택을 취소했습니다.", NamedTextColor.YELLOW));
    }

    private Location resolveLaunchTarget(Player player) {
        return gameManager.getArenaData().projectToBoardPlane(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            REMOTE_TRACE_DISTANCE
        );
    }

    private PieceData selectPieceByRay(Player player, GameSession session) {
        RayTraceResult entityTrace = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            REMOTE_TRACE_DISTANCE,
            SELECTION_RAY_SIZE,
            entity -> entity instanceof Interaction
                && gameManager.getBoardManager().isPieceSelectionEntity(entity.getUniqueId())
        );

        if (entityTrace == null || entityTrace.getHitEntity() == null) {
            return null;
        }

        return session.selectPiece(player, entityTrace.getHitEntity().getUniqueId());
    }

    private String formatTeamDisplayName(TeamType teamType) {
        String name = teamType.getDisplayName();
        return name.endsWith("팀") ? name : name + "팀";
    }
}
