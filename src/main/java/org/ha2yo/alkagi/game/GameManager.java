package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.ha2yo.alkagi.scoreboard.AlkagiScoreboardManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 게임 관련 핵심 객체를 묶어서 외부에 제공하는 진입점이다.
 */
public final class GameManager {

    public enum ForceRoomStartResult {
        SUCCESS,
        ROOM_NOT_FOUND,
        EMPTY_ROOM,
        PRESET_MISSING,
        SESSION_NOT_WAITING,
        SESSION_REJECTED
    }

    public static final String DEFAULT_GAME_PRESET_NAME = "jongsok2";
    private static final int ROOM_MAX_PLAYERS = 8;
    private static final int ROOM_START_COUNTDOWN_SECONDS = 15;
    private static final int ROOM_SELECTOR_SLOT = 4;
    private static final String ROOM_SELECTOR_KEY = "room_selector";
    private static final String ROOM_BUTTON_KEY = "room_button";
    private static final String ROOM_LEAVE_KEY = "room_leave";
    private static final String ROOM_MENU_TITLE = "Alkagi Rooms";
    private static final List<String> ALKAGI_TAB_TEAM_NAMES = List.of(
        "00_alkagi_blue",
        "01_alkagi_red",
        "00_alkagi_black",
        "01_alkagi_white",
        "02_alkagi_spectator"
    );

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final AlkagiScoreboardManager scoreboardManager;
    private final Map<Integer, ArenaData> arenaDataByBoard = new LinkedHashMap<>();
    private final Map<Integer, BoardManager> boardManagers = new LinkedHashMap<>();
    private final Map<Integer, GameSession> sessions = new LinkedHashMap<>();
    private final Map<Integer, Set<UUID>> waitingRooms = new LinkedHashMap<>();
    private final Map<Integer, BukkitTask> roomCountdownTasks = new LinkedHashMap<>();
    private final Map<UUID, GameSession> sessionByPlayer = new java.util.HashMap<>();
    private final Map<UUID, Integer> waitingRoomByPlayer = new java.util.HashMap<>();
    private final NamespacedKey roomSelectorKey;
    private final NamespacedKey roomButtonKey;
    private final NamespacedKey roomLeaveKey;
    private final BoardManager boardManager;
    private final RoomStatusDisplayManager roomStatusDisplayManager;
    private final PresetRepository presetRepository;
    private final PresetEditor presetEditor;
    private final GameSession session;

    public GameManager(
            JavaPlugin plugin,
            ArenaData arenaData,
            AlkagiScoreboardManager scoreboardManager
    ) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.scoreboardManager = scoreboardManager;
        this.roomSelectorKey = new NamespacedKey(plugin, ROOM_SELECTOR_KEY);
        this.roomButtonKey = new NamespacedKey(plugin, ROOM_BUTTON_KEY);
        this.roomLeaveKey = new NamespacedKey(plugin, ROOM_LEAVE_KEY);
        List<BoardArenaData> configuredBoards = arenaData.getConfiguredBoardArenas();
        if (configuredBoards.isEmpty()) {
            BoardArenaData primaryBoard = arenaData.getOrCreateBoardArena(1);
            primaryBoard.setBoardPos1(arenaData.getBoardPos1());
            primaryBoard.setBoardPos2(arenaData.getBoardPos2());
            primaryBoard.setSpectatorLocation(arenaData.getSpectatorLocation());
            primaryBoard.setPlacementLocation(TeamType.BLUE, arenaData.getPlacementLocation(TeamType.BLUE));
            primaryBoard.setPlacementLocation(TeamType.RED, arenaData.getPlacementLocation(TeamType.RED));
            configuredBoards = List.of(primaryBoard);
        }

        for (BoardArenaData boardArena : configuredBoards) {
            ArenaData boardArenaData = ArenaData.forBoard(arenaData, boardArena);
            BoardManager boardManager = new BoardManager(plugin, boardArenaData);
            GameSession session = new GameSession(plugin, boardArenaData, scoreboardManager, boardManager);
            arenaDataByBoard.put(boardArena.getId(), boardArenaData);
            boardManagers.put(boardArena.getId(), boardManager);
            sessions.put(boardArena.getId(), session);
            waitingRooms.put(boardArena.getId(), new LinkedHashSet<>());
        }

        this.boardManager = boardManagers.values().iterator().next();
        this.roomStatusDisplayManager = new RoomStatusDisplayManager(plugin, arenaData, sessions);
        this.roomStatusDisplayManager.start();
        this.presetRepository = new PresetRepository(plugin, arenaData);
        this.presetEditor = new PresetEditor(plugin, arenaData, boardManager, presetRepository);
        this.session = sessions.values().iterator().next();
    }

    /**
     * 대기 중인 게임에 플레이어를 참가자로 등록한다.
     */
    public boolean join(
            Player player
    ) {
        clearPlayerGameState(player);
        resetPlayerToLobbyState(player);
        Location lobbyLocation = arenaData.getLobbyLocation();
        if (lobbyLocation != null) {
            player.teleport(lobbyLocation);
        }
        giveRoomSelector(player);
        session.applyWaitingSpeed(player);
        refreshRoomPlayerListName(player);
        return true;
    }

    /**
     * 새 게임 시작 요청을 현재 게임 세션으로 전달한다.
     */
    public boolean start(
            int pieceCount,
            boolean force,
            @org.jetbrains.annotations.Nullable Integer playerCount
    ) {
        PresetData presetData = getRequiredGamePreset();
        if (presetData == null) {
            return false;
        }

        boolean started = session.startWithPreset(force, transformPresetForSession(presetData, session, getBoardId(session)), playerCount);
        if (started) {
            rememberSessionParticipants(session);
            refreshRoomPlayerListNames();
        }
        return started;
    }

    public boolean startAll(
            int pieceCount,
            boolean force,
            @Nullable Integer playerCount
    ) {
        PresetData presetData = getRequiredGamePreset();
        if (presetData == null) {
            return false;
        }

        List<UUID> selectedPlayers = selectOnlinePlayers(playerCount);
        if (selectedPlayers.isEmpty()) {
            return false;
        }

        List<GameSession> availableSessions = sessions.values().stream()
            .filter(candidate -> candidate.getGameState() == GameState.WAITING)
            .toList();
        if (availableSessions.isEmpty()) {
            return false;
        }

        int sessionCount = Math.min(availableSessions.size(), selectedPlayers.size());
        if (!force) {
            sessionCount = Math.min(sessionCount, selectedPlayers.size() / 2);
        }
        if (sessionCount <= 0) {
            return false;
        }

        List<List<UUID>> groups = new ArrayList<>();
        for (int i = 0; i < sessionCount; i++) {
            groups.add(new ArrayList<>());
        }
        for (int i = 0; i < selectedPlayers.size(); i++) {
            groups.get(i % sessionCount).add(selectedPlayers.get(i));
        }

        List<GameSession> startedSessions = new ArrayList<>();
        for (int i = 0; i < sessionCount; i++) {
            GameSession targetSession = availableSessions.get(i);
            PresetData sessionPresetData = transformPresetForSession(presetData, targetSession, getBoardId(targetSession));
            if (!targetSession.startAssignedWithPreset(force, sessionPresetData, null, groups.get(i))) {
                for (GameSession startedSession : startedSessions) {
                    startedSession.reset();
                }
                rebuildPlayerSessionIndex();
                refreshRoomPlayerListNames();
                return false;
            }
            startedSessions.add(targetSession);
        }

        rebuildPlayerSessionIndex();
        refreshRoomPlayerListNames();
        return true;
    }

    public boolean startPreset(
            PresetData presetData,
            boolean force,
            @org.jetbrains.annotations.Nullable Integer playerCount
    ) {
        PresetData requiredPresetData = getRequiredGamePreset();
        if (requiredPresetData == null) {
            return false;
        }

        boolean started = session.startWithPreset(force, transformPresetForSession(requiredPresetData, session, getBoardId(session)), playerCount);
        if (started) {
            rememberSessionParticipants(session);
            refreshRoomPlayerListNames();
        }
        return started;
    }

    /**
     * 진행 중인 게임을 중단한다.
     */
    public void stop() {
        for (GameSession session : sessions.values()) {
            if (session.getGameState() != GameState.WAITING) {
                session.stop();
            }
        }
    }

    /**
     * 게임 세션을 대기 상태로 초기화한다.
     */
    public void reset() {
        cancelAllRoomCountdowns();
        waitingRooms.values().forEach(Set::clear);
        waitingRoomByPlayer.clear();
        for (GameSession session : sessions.values()) {
            session.reset();
        }
        sessionByPlayer.clear();
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            giveRoomSelector(player);
            refreshRoomPlayerListName(player);
        });
    }

    public void shutdown() {
        roomStatusDisplayManager.stop();
        reset();
    }

    /**
     * 접속 종료한 플레이어를 현재 게임 상태에서 정리한다.
     */
    public void handleQuit(
            UUID playerId
    ) {
        leaveWaitingRoom(playerId);
        GameSession session = sessionByPlayer.remove(playerId);
        if (session == null) {
            session = findSessionByParticipant(playerId);
        }
        if (session != null) {
            session.removeOfflinePlayer(playerId);
        }
    }

    public GameSession getSession() {
        return session;
    }

    public @Nullable GameSession getSession(Player player) {
        GameSession assignedSession = sessionByPlayer.get(player.getUniqueId());
        if (assignedSession != null) {
            return assignedSession;
        }
        return findSessionByParticipant(player.getUniqueId());
    }

    public @Nullable Integer getRoomId(UUID playerId) {
        Integer waitingRoomId = waitingRoomByPlayer.get(playerId);
        if (waitingRoomId != null) {
            return waitingRoomId;
        }

        GameSession assignedSession = sessionByPlayer.get(playerId);
        if (assignedSession != null) {
            return assignedSession.getGameState() == GameState.WAITING ? null : getBoardId(assignedSession);
        }

        return null;
    }

    public void refreshRoomPlayerListName(Player player) {
        Integer roomId = getRoomId(player.getUniqueId());
        GameSession assignedSession = sessionByPlayer.get(player.getUniqueId());
        if (assignedSession == null) {
            assignedSession = findSessionByParticipant(player.getUniqueId());
        }

        NamedTextColor playerColor = assignedSession == null
            ? NamedTextColor.GRAY
            : assignedSession.getPlayerColor(player.getUniqueId());
        Component name = Component.text(player.getName(), playerColor);
        if (roomId == null) {
            player.playerListName(name);
            return;
        }

        player.playerListName(
            Component.text("[" + roomId + "] ", getRoomPrefixColor(roomId))
                .append(name)
        );
    }

    public void refreshRoomPlayerListNames() {
        plugin.getServer().getOnlinePlayers().forEach(this::refreshRoomPlayerListName);
    }

    public NamedTextColor getRoomPrefixColor(int roomId) {
        return switch (roomId) {
            case 1 -> NamedTextColor.RED;
            case 2 -> NamedTextColor.GOLD;
            case 3 -> NamedTextColor.YELLOW;
            case 4 -> NamedTextColor.GREEN;
            default -> NamedTextColor.GRAY;
        };
    }

    public @Nullable GameSession findSessionByPieceEntity(UUID entityId) {
        for (GameSession session : sessions.values()) {
            if (session.getBoardManager().isPieceSelectionEntity(entityId)) {
                return session;
            }
        }
        return null;
    }

    public @Nullable TeamType getPlayerTeam(Player player) {
        GameSession assignedSession = getSession(player);
        return assignedSession == null ? null : assignedSession.getPlayerTeam(player.getUniqueId());
    }

    public boolean kickPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        clearPlayerGameState(player);
        resetPlayerToLobbyState(player);
        teleportToLobby(player);
        giveRoomSelector(player);
        session.applyWaitingSpeed(player);
        refreshRoomPlayerListName(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && sessionByPlayer.get(playerId) == null) {
                session.applyWaitingSpeed(player);
                refreshRoomPlayerListName(player);
            }
        }, 1L);
        return true;
    }

    public Collection<GameSession> getSessions() {
        return List.copyOf(sessions.values());
    }

    public boolean hasRunningSession() {
        return sessions.values().stream()
            .anyMatch(session -> session.getGameState() != GameState.WAITING);
    }

    public ArenaData getArenaData() {
        return arenaData;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public BoardManager getBoardManager() {
        return boardManager;
    }

    public Collection<BoardManager> getBoardManagers() {
        return List.copyOf(boardManagers.values());
    }

    public boolean isRoomSelector(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return false;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().has(roomSelectorKey, PersistentDataType.BYTE);
    }

    public boolean isRoomMenuTitle(String title) {
        return ROOM_MENU_TITLE.equals(title);
    }

    public void openRoomMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 9, ROOM_MENU_TITLE);
        for (int boardId : sessions.keySet()) {
            inventory.setItem(boardId - 1, createRoomButton(boardId));
        }
        inventory.setItem(8, createLeaveRoomButton());
        player.openInventory(inventory);
    }

    public void handleRoomMenuClick(Player player, @Nullable ItemStack clickedItem) {
        if (isLeaveRoomButton(clickedItem)) {
            leaveWaitingRoom(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(Component.text("방에서 나왔습니다.", NamedTextColor.YELLOW));
            return;
        }

        Integer boardId = getRoomButtonId(clickedItem);
        if (boardId == null) {
            return;
        }
        if (selectRoom(player, boardId)) {
            player.closeInventory();
        }
    }
    private void giveRoomSelector(Player player) {
        player.getInventory().setItem(ROOM_SELECTOR_SLOT, createRoomSelector());
    }

    private void clearPlayerGameState(Player player) {
        UUID playerId = player.getUniqueId();
        Integer waitingRoomId = waitingRoomByPlayer.remove(playerId);
        if (waitingRoomId != null) {
            Set<UUID> waitingRoom = waitingRooms.get(waitingRoomId);
            if (waitingRoom != null) {
                waitingRoom.remove(playerId);
            }
            updateRoomCountdown(waitingRoomId);
        }

        sessionByPlayer.remove(playerId);
        for (GameSession session : sessions.values()) {
            if (!session.hasParticipant(playerId)) {
                continue;
            }

            if (session.getGameState() == GameState.WAITING) {
                session.removeParticipant(player);
                continue;
            }

            if (!session.kickParticipant(player)) {
                session.removeOfflinePlayer(playerId);
            }
        }
    }

    private void resetPlayerToLobbyState(Player player) {
        scoreboardManager.clear(player);
        removePlayerFromAlkagiTabTeams(player);
        player.displayName(Component.text(player.getName(), NamedTextColor.GRAY));
        player.playerListName(Component.text(player.getName(), NamedTextColor.GRAY));
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFlySpeed(0.1F);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setSilent(false);
        player.setGlowing(false);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVisualFire(false);
        player.updateInventory();
    }

    private void removePlayerFromAlkagiTabTeams(Player player) {
        String entry = player.getName();
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            removePlayerFromAlkagiTabTeams(manager.getMainScoreboard(), entry);
        }
        removePlayerFromAlkagiTabTeams(player.getScoreboard(), entry);
    }

    private void removePlayerFromAlkagiTabTeams(Scoreboard scoreboard, String entry) {
        for (String teamName : ALKAGI_TAB_TEAM_NAMES) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private ItemStack createRoomSelector() {
        ItemStack itemStack = new ItemStack(Material.CLOCK);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("게임 참가", NamedTextColor.AQUA));
            meta.getPersistentDataContainer().set(roomSelectorKey, PersistentDataType.BYTE, (byte) 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private ItemStack createRoomButton(int boardId) {
        Set<UUID> waitingPlayers = waitingRooms.getOrDefault(boardId, Set.of());
        GameSession session = sessions.get(boardId);
        boolean available = session != null
            && session.getGameState() == GameState.WAITING
            && waitingPlayers.size() < ROOM_MAX_PLAYERS;
        Material material = available ? Material.LIME_WOOL : Material.RED_WOOL;
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(boardId + "번 방", available ? NamedTextColor.GREEN : NamedTextColor.RED));
            meta.lore(List.of(
                Component.text("인원: " + waitingPlayers.size() + "/" + ROOM_MAX_PLAYERS, NamedTextColor.GRAY),
                Component.text(available ? "클릭해서 입장" : "사용 불가", available ? NamedTextColor.GREEN : NamedTextColor.RED)
            ));
            if (available) {
                meta.getPersistentDataContainer().set(roomButtonKey, PersistentDataType.INTEGER, boardId);
            }
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private ItemStack createLeaveRoomButton() {
        ItemStack itemStack = new ItemStack(Material.BARRIER);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("방 나가기", NamedTextColor.RED));
            meta.lore(List.of(Component.text("현재 대기 중인 방에서 나갑니다.", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(roomLeaveKey, PersistentDataType.BYTE, (byte) 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private boolean isLeaveRoomButton(@Nullable ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return false;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().has(roomLeaveKey, PersistentDataType.BYTE);
    }
    private @Nullable Integer getRoomButtonId(@Nullable ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(roomButtonKey, PersistentDataType.INTEGER);
    }

    private boolean selectRoom(Player player, int boardId) {
        GameSession targetSession = sessions.get(boardId);
        Set<UUID> targetRoom = waitingRooms.get(boardId);
        if (targetSession == null || targetRoom == null || targetSession.getGameState() != GameState.WAITING) {
            player.sendMessage(Component.text("이미 게임이 시작된 방입니다.", NamedTextColor.RED));
            return false;
        }
        UUID playerId = player.getUniqueId();
        Integer currentRoomId = waitingRoomByPlayer.get(playerId);
        if (currentRoomId != null && currentRoomId == boardId) {
            player.sendMessage(Component.text("이미 " + boardId + "번 방에 있습니다.", NamedTextColor.YELLOW));
            return false;
        }
        if (targetRoom.size() >= ROOM_MAX_PLAYERS) {
            player.sendMessage(Component.text("해당 방이 가득 찼습니다.", NamedTextColor.RED));
            return false;
        }

        leaveWaitingRoom(playerId);
        targetRoom.add(playerId);
        waitingRoomByPlayer.put(playerId, boardId);
        targetSession.addParticipant(player);
        sessionByPlayer.put(playerId, targetSession);
        teleportToRoomWaiting(player, boardId);
        targetSession.clearWaitingSpeed(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && waitingRoomByPlayer.get(playerId) != null) {
                targetSession.clearWaitingSpeed(player);
            }
        }, 1L);
        refreshRoomPlayerListName(player);
        broadcastToRoom(boardId, Component.text(
            player.getName() + "님께서 입장하였습니다. (" + targetRoom.size() + "/" + ROOM_MAX_PLAYERS + ")",
            NamedTextColor.GREEN
        ));
        playSoundToRoom(boardId, Sound.UI_BUTTON_CLICK, 0.8F, 1.4F);
        updateRoomCountdown(boardId);
        return true;
    }
    private void leaveWaitingRoom(UUID playerId) {
        Integer roomId = waitingRoomByPlayer.remove(playerId);
        if (roomId == null) {
            return;
        }
        Set<UUID> room = waitingRooms.get(roomId);
        GameSession session = sessions.get(roomId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (room != null) {
            room.remove(playerId);
        }
        if (player != null) {
            int remainingCount = room == null ? 0 : room.size();
            broadcastToRoom(roomId, Component.text(
                player.getName() + "님께서 퇴장하였습니다. (" + remainingCount + "/" + ROOM_MAX_PLAYERS + ")",
                NamedTextColor.RED
            ));
            playSoundToRoom(roomId, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 0.8F);
            playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 0.8F);
        }
        if (session != null && player != null && session.getGameState() == GameState.WAITING) {
            session.removeParticipant(player);
            teleportToLobby(player);
            session.applyWaitingSpeed(player);
        }
        if (player != null) {
            sessionByPlayer.remove(playerId);
            refreshRoomPlayerListName(player);
        }
        updateRoomCountdown(roomId);
    }

    private void teleportToRoomWaiting(Player player, int boardId) {
        Location waitingLocation = getRoomWaitingLocation(boardId);
        if (waitingLocation != null) {
            player.teleport(waitingLocation);
        }
    }

    private @Nullable Location getRoomWaitingLocation(int boardId) {
        BoardArenaData boardArena = arenaData.getBoardArena(boardId);
        if (boardArena != null && boardArena.getWaitingLocation() != null) {
            return boardArena.getWaitingLocation();
        }
        return arenaData.getLobbyLocation();
    }

    private void teleportToLobby(Player player) {
        Location lobbyLocation = arenaData.getLobbyLocation();
        if (lobbyLocation != null) {
            player.teleport(lobbyLocation);
        }
    }
    private void updateRoomCountdown(int boardId) {
        Set<UUID> room = waitingRooms.get(boardId);
        GameSession session = sessions.get(boardId);
        if (room == null || session == null || session.getGameState() != GameState.WAITING) {
            cancelRoomCountdown(boardId);
            return;
        }
        if (room.size() < 2) {
            if (cancelRoomCountdown(boardId)) {
                broadcastToRoom(boardId, Component.text("인원이 부족하여 게임을 시작할 수 없습니다.", NamedTextColor.RED));
            }
            return;
        }
        if (roomCountdownTasks.containsKey(boardId)) {
            return;
        }
        broadcastCountdown(boardId, 15);
        int[] remainingSeconds = {ROOM_START_COUNTDOWN_SECONDS};
        BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> {
                remainingSeconds[0]--;
                if (remainingSeconds[0] == 10) {
                    broadcastCountdown(boardId, 10);
                    return;
                }
                if (remainingSeconds[0] == 5) {
                    broadcastCountdown(boardId, 5);
                    return;
                }
                if (remainingSeconds[0] <= 0) {
                    roomCountdownTasks.remove(boardId);
                    taskRef[0].cancel();
                    startRoomIfReady(boardId);
                }
            },
            20L,
            20L
        );
        roomCountdownTasks.put(boardId, taskRef[0]);
    }

    public ForceRoomStartResult forceStartRoom(int boardId) {
        Set<UUID> room = waitingRooms.get(boardId);
        GameSession targetSession = sessions.get(boardId);
        if (room == null || targetSession == null) {
            return ForceRoomStartResult.ROOM_NOT_FOUND;
        }
        if (room.isEmpty()) {
            return ForceRoomStartResult.EMPTY_ROOM;
        }
        if (targetSession.getGameState() != GameState.WAITING) {
            return ForceRoomStartResult.SESSION_NOT_WAITING;
        }

        PresetData presetData = getRequiredGamePreset();
        if (presetData == null) {
            return ForceRoomStartResult.PRESET_MISSING;
        }

        cancelRoomCountdown(boardId);
        List<UUID> selectedPlayers = new ArrayList<>(room);
        PresetData sessionPresetData = transformPresetForSession(presetData, targetSession, boardId);
        if (!targetSession.startAssignedWithPreset(true, sessionPresetData, null, selectedPlayers)) {
            return ForceRoomStartResult.SESSION_REJECTED;
        }

        for (UUID playerId : selectedPlayers) {
            waitingRoomByPlayer.remove(playerId);
            sessionByPlayer.put(playerId, targetSession);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && isRoomSelector(player.getInventory().getItem(ROOM_SELECTOR_SLOT))) {
                player.getInventory().setItem(ROOM_SELECTOR_SLOT, null);
            }
            if (player != null) {
                refreshRoomPlayerListName(player);
            }
        }
        room.clear();
        return ForceRoomStartResult.SUCCESS;
    }
    private void startRoomIfReady(int boardId) {
        roomCountdownTasks.remove(boardId);
        Set<UUID> room = waitingRooms.get(boardId);
        GameSession targetSession = sessions.get(boardId);
        PresetData presetData = getRequiredGamePreset();
        if (room == null || targetSession == null || presetData == null || room.size() < 2 || targetSession.getGameState() != GameState.WAITING) {
            updateRoomCountdown(boardId);
            return;
        }

        List<UUID> selectedPlayers = new ArrayList<>(room);
        PresetData sessionPresetData = transformPresetForSession(presetData, targetSession, boardId);
        if (!targetSession.startAssignedWithPreset(false, sessionPresetData, null, selectedPlayers)) {
            broadcastToRoom(boardId, Component.text("게임을 시작할 수 없습니다.", NamedTextColor.RED));
            updateRoomCountdown(boardId);
            return;
        }

        for (UUID playerId : selectedPlayers) {
            waitingRoomByPlayer.remove(playerId);
            sessionByPlayer.put(playerId, targetSession);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && isRoomSelector(player.getInventory().getItem(ROOM_SELECTOR_SLOT))) {
                player.getInventory().setItem(ROOM_SELECTOR_SLOT, null);
            }
            if (player != null) {
                refreshRoomPlayerListName(player);
            }
        }
        room.clear();
    }
    private boolean cancelRoomCountdown(int boardId) {
        BukkitTask task = roomCountdownTasks.remove(boardId);
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }

    private void cancelAllRoomCountdowns() {
        for (BukkitTask task : roomCountdownTasks.values()) {
            task.cancel();
        }
        roomCountdownTasks.clear();
    }

    private void broadcastToRoom(int boardId, Component message) {
        Set<UUID> room = waitingRooms.get(boardId);
        if (room == null) {
            return;
        }
        for (UUID playerId : room) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void broadcastCountdown(int boardId, int seconds) {
        broadcastToRoom(boardId, Component.text(seconds + "초 뒤 게임이 시작됩니다.", NamedTextColor.YELLOW));
        playSoundToRoom(boardId, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9F, seconds <= 5 ? 1.8F : 1.3F);
    }

    private void playSoundToRoom(int boardId, Sound sound, float volume, float pitch) {
        Set<UUID> room = waitingRooms.get(boardId);
        if (room == null) {
            return;
        }
        for (UUID playerId : room) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                playSound(player, sound, volume, pitch);
            }
        }
    }

    private void playSound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    public void refreshPrimaryBoardGridLines() {
        boardManagers.values().forEach(BoardManager::refreshBoardGridLines);
    }

    public PresetRepository getPresetRepository() {
        return presetRepository;
    }

    public PresetEditor getPresetEditor() {
        return presetEditor;
    }

    private List<UUID> selectOnlinePlayers(@Nullable Integer playerCount) {
        List<UUID> onlinePlayers = plugin.getServer().getOnlinePlayers().stream()
            .map(Player::getUniqueId)
            .toList();
        if (playerCount == null) {
            return onlinePlayers;
        }
        if (playerCount <= 0 || playerCount > onlinePlayers.size()) {
            return List.of();
        }
        return onlinePlayers.subList(0, playerCount);
    }

    private void rememberSessionParticipants(GameSession session) {
        for (UUID participantId : session.getParticipants()) {
            sessionByPlayer.put(participantId, session);
        }
    }

    private void rebuildPlayerSessionIndex() {
        sessionByPlayer.clear();
        for (GameSession session : sessions.values()) {
            rememberSessionParticipants(session);
        }
    }

    private @Nullable GameSession findSessionByParticipant(UUID playerId) {
        for (GameSession session : sessions.values()) {
            if (session.hasParticipant(playerId)) {
                sessionByPlayer.put(playerId, session);
                return session;
            }
        }
        return null;
    }

    private @Nullable PresetData getRequiredGamePreset() {
        return presetRepository.loadPreset(DEFAULT_GAME_PRESET_NAME);
    }

    private PresetData transformPresetForSession(PresetData presetData, GameSession targetSession, int boardId) {
        ArenaData targetArenaData = targetSession.getArenaData();
        Location sourcePos1 = arenaData.getBoardPos1();
        Location sourcePos2 = arenaData.getBoardPos2();
        Location targetPos1 = targetArenaData.getBoardPos1();
        Location targetPos2 = targetArenaData.getBoardPos2();
        if (sourcePos1 == null || sourcePos2 == null || targetPos1 == null || targetPos2 == null) {
            return presetData;
        }

        double sourceMinX = Math.min(sourcePos1.getX(), sourcePos2.getX());
        double sourceMaxX = Math.max(sourcePos1.getX(), sourcePos2.getX());
        double sourceMinZ = Math.min(sourcePos1.getZ(), sourcePos2.getZ());
        double sourceMaxZ = Math.max(sourcePos1.getZ(), sourcePos2.getZ());
        double targetMinX = Math.min(targetPos1.getX(), targetPos2.getX());
        double targetMaxX = Math.max(targetPos1.getX(), targetPos2.getX());
        double targetMinZ = Math.min(targetPos1.getZ(), targetPos2.getZ());
        double targetMaxZ = Math.max(targetPos1.getZ(), targetPos2.getZ());
        double sourceWidth = sourceMaxX - sourceMinX;
        double sourceDepth = sourceMaxZ - sourceMinZ;
        if (sourceWidth <= 0.0001D || sourceDepth <= 0.0001D) {
            return presetData;
        }

        PresetData transformedPreset = new PresetData(presetData.getName());
        for (TeamType teamType : TeamType.values()) {
            for (PresetData.PresetPiece piece : presetData.getPieces(teamType)) {
                Location transformedLocation = transformPresetLocation(
                    piece.location(),
                    boardId,
                    sourceMinX,
                    sourceMinZ,
                    sourceWidth,
                    sourceDepth,
                    targetMinX,
                    targetMaxX,
                    targetMinZ,
                    targetMaxZ,
                    targetPos1.getWorld(),
                    Math.max(targetPos1.getY(), targetPos2.getY())
                );
                transformedPreset.addPiece(
                    teamType,
                    transformedLocation,
                    piece.pieceSize(),
                    piece.heightScale(),
                    piece.labelText()
                );
            }
        }
        return transformedPreset;
    }

    private Location transformPresetLocation(
            Location sourceLocation,
            int boardId,
            double sourceMinX,
            double sourceMinZ,
            double sourceWidth,
            double sourceDepth,
            double targetMinX,
            double targetMaxX,
            double targetMinZ,
            double targetMaxZ,
            @Nullable World targetWorld,
            double targetY
    ) {
        double normalizedX = (sourceLocation.getX() - sourceMinX) / sourceWidth;
        double normalizedZ = (sourceLocation.getZ() - sourceMinZ) / sourceDepth;
        double rotatedX = normalizedX;
        double rotatedZ = normalizedZ;
        if (boardId == 2) {
            rotatedX = normalizedZ;
            rotatedZ = 1.0D - normalizedX;
        } else if (boardId == 4) {
            rotatedX = 1.0D - normalizedZ;
            rotatedZ = normalizedX;
        }
        double targetX = targetMinX + ((targetMaxX - targetMinX) * rotatedX);
        double targetZ = targetMinZ + ((targetMaxZ - targetMinZ) * rotatedZ);
        return new Location(
            targetWorld == null ? sourceLocation.getWorld() : targetWorld,
            targetX,
            targetY,
            targetZ,
            sourceLocation.getYaw(),
            sourceLocation.getPitch()
        );
    }

    private int getBoardId(GameSession targetSession) {
        for (Map.Entry<Integer, GameSession> entry : sessions.entrySet()) {
            if (entry.getValue() == targetSession) {
                return entry.getKey();
            }
        }
        return 1;
    }
}
