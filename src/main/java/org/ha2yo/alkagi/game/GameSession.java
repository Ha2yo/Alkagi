package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.ha2yo.alkagi.game.model.PieceData;
import org.ha2yo.alkagi.game.model.TeamData;
import org.ha2yo.alkagi.scoreboard.AlkagiScoreboardManager;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 참가, 팀 배정, 배치, 턴 진행, 종료까지 게임 세션 전체 상태를 관리한다.
 */
public final class GameSession {

    private static final String REMOTE_CONTROLLER_KEY = "remote_controller";
    private static final String TEAM_ARMOR_KEY = "team_armor";
    private static final String TAB_TEAM_BLUE = "00_alkagi_blue";
    private static final String TAB_TEAM_RED = "01_alkagi_red";
    private static final String LEGACY_TAB_TEAM_BLACK = "00_alkagi_black";
    private static final String LEGACY_TAB_TEAM_WHITE = "01_alkagi_white";
    private static final String TAB_TEAM_SPECTATOR = "02_alkagi_spectator";

    private static final long LAUNCH_GUARD_MILLIS = 500L;
    private static final String GAME_MUSIC_SOUND_KEY = "alkagi.ingame";
    private static final List<String> DEFAULT_GAME_MUSIC_SOUND_KEYS = List.of(
        "alkagi.ingame_1",
        "alkagi.ingame_2",
        "alkagi.ingame_3",
        "alkagi.ingame_4",
        "alkagi.ingame_5"
    );
    private static final String GAME_MUSIC_ANNOUNCE_PREFIX = "\uC7AC\uC0DD \uC911: ";
    private static final float GAME_MUSIC_VOLUME = 0.1225F;
    private static final long GAME_MUSIC_LOOP_SECONDS = 120L;
    private static final long GAME_MUSIC_GAP_SECONDS = 2L;
    private static final NamedTextColor DEFAULT_PLAYER_COLOR = NamedTextColor.GRAY;
    private static final float TURN_CAMERA_FLY_SPEED = 0.6F;
    private static final long NEXT_TURN_DELAY_TICKS = 20L;
    private static final long GAME_END_DELAY_TICKS = 20L;
    private static final Duration RESULT_TITLE_FADE_IN = Duration.ofMillis(300);
    private static final Duration RESULT_TITLE_STAY = Duration.ofSeconds(3);
    private static final Duration RESULT_TITLE_FADE_OUT = Duration.ofMillis(500);
    private static final long RESULT_DISPLAY_TICKS = durationToTicks(
        RESULT_TITLE_FADE_IN.plus(RESULT_TITLE_STAY).plus(RESULT_TITLE_FADE_OUT)
    );
    private static final double SELECTED_PIECE_CAMERA_Y_OFFSET = 1.15D;
    private static final double SELECTED_PIECE_CAMERA_JUMP_Y_OFFSET = 5.0D;
    private static final double SELECTED_PIECE_CAMERA_LIFT_VELOCITY_SCALE = 0.28D;
    private static final double SELECTED_PIECE_CAMERA_MAX_LIFT_VELOCITY = 0.55D;
    private static final double SELECTED_PIECE_CAMERA_LIFT_STOP_DISTANCE = 0.05D;
    private static final double LAUNCH_CAMERA_Y_OFFSET = 1.9D;
    private static final double PIECE_DISPLAY_Y_OFFSET = -0.18D;
    private static final double DEFAULT_PIECE_SIZE = 2.35D;
    private static final double DISPLAY_HEIGHT_SCALE_MULTIPLIER = 3.0D;
    private static final double PIECE_MODEL_MIN_Y = 0.5D;
    private static final double PIECE_MODEL_MAX_Y = 2.5D;
    private static final double MODEL_UNIT_SIZE = 16.0D;
    private static final double DEFAULT_LAUNCH_POWER = 5.0D;
    private static final double LAUNCH_POWER_STEP = 0.44D;
    private static final float SELECTED_PIECE_CAMERA_PITCH = 30.0F;

    private static final PotionEffect TURN_SPEED_EFFECT =
        new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 9, false, false, false);
    private static final PotionEffect SPECTATOR_INVISIBILITY_EFFECT =
        new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false);

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final AlkagiScoreboardManager scoreboardManager;
    private final BoardManager boardManager;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, TeamType> playerTeamMap = new java.util.HashMap<>();
    private final Map<TeamType, TeamData> teamDataMap = new EnumMap<>(TeamType.class);
    private final Map<TeamType, UUID> placementPlayers = new EnumMap<>(TeamType.class);
    private final Map<TeamType, Integer> placedCountMap = new EnumMap<>(TeamType.class);
    private final Map<UUID, Integer> eliminatedPiecesByPlayer = new java.util.HashMap<>();
    private final Map<UUID, Integer> ownPiecesEliminatedByPlayer = new java.util.HashMap<>();
    private final Map<UUID, Integer> benchGameStreaks = new java.util.HashMap<>();

    private final Map<UUID, ItemStack[]> inventoryBackupMap = new java.util.HashMap<>();
    private final Map<UUID, ItemStack[]> armorBackupMap = new java.util.HashMap<>();
    private final Map<UUID, Integer> heldSlotBackupMap = new java.util.HashMap<>();
    private final Map<UUID, FlightState> flightStateMap = new java.util.HashMap<>();
    private final Map<UUID, TurnCameraState> turnCameraStateMap = new java.util.HashMap<>();
    private final Map<UUID, Double> launchPowerMap = new java.util.HashMap<>();
    private final Map<UUID, Location> selectedCameraReturnLocationMap = new java.util.HashMap<>();
    private final Map<UUID, Integer> launchCameraRotationGraceTicks = new java.util.HashMap<>();
    private final Map<UUID, Boolean> selectedCameraLiftedMap = new java.util.HashMap<>();

    private final BossBar turnTimerBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);

    private GameState gameState = GameState.WAITING;
    private TeamType currentTurnTeam = TeamType.BLUE;
    private UUID currentTurnPlayer;
    private int configuredPieceCount;
    private PieceData selectedPiece;
    private UUID lastSelectedPlayerId;
    private UUID selectionReadyFeedbackPlayerId;
    private long lastSelectedAtMillis;
    private int remainingTurnSeconds;
    private BukkitTask turnTimerTask;
    private BukkitTask gameMusicTask;
    private BukkitTask gameMusicGapTask;
    private final List<String> gameMusicQueue = new ArrayList<>();
    private String currentGameMusicSoundKey;
    private BukkitTask launchCameraTask;
    private BukkitTask selectedCameraLiftTask;
    private ArmorStand launchCameraVehicle;

    public GameSession(
            JavaPlugin plugin,
            ArenaData arenaData,
            AlkagiScoreboardManager scoreboardManager,
            BoardManager boardManager
    ) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.scoreboardManager = scoreboardManager;
        this.boardManager = boardManager;
        teamDataMap.put(TeamType.BLUE, new TeamData(TeamType.BLUE));
        teamDataMap.put(TeamType.RED, new TeamData(TeamType.RED));
        placedCountMap.put(TeamType.BLUE, 0);
        placedCountMap.put(TeamType.RED, 0);
        turnTimerBar.setVisible(false);
        turnTimerBar.setProgress(1.0D);
    }

    public boolean addParticipant(
            Player player
    ) {
        if (gameState != GameState.WAITING) {
            return false;
        }

        boolean added = participants.add(player.getUniqueId());
        if (added) {
            sendToLobby(player);
            refreshPlayerFormatting(player);
        }
        return added;
    }

    public boolean removeParticipant(
            Player player
    ) {
        boolean removed = participants.remove(player.getUniqueId());
        playerTeamMap.remove(player.getUniqueId());
        teamDataMap.values().forEach(teamData -> teamData.removePlayer(player.getUniqueId()));
        if (currentTurnPlayer != null && currentTurnPlayer.equals(player.getUniqueId())) {
            currentTurnPlayer = null;
        }
        refreshPlayerFormatting(player);
        return removed;
    }

    private void prepareParticipantsForGame(List<UUID> availableParticipants, int selectedPlayerCount) {
        List<UUID> selectedParticipants = selectParticipantsForNextGame(availableParticipants, selectedPlayerCount);
        participants.clear();
        participants.addAll(selectedParticipants);
        updateBenchGameStreaks(availableParticipants, selectedParticipants);
    }

    /**
     * 참가자를 확정하고 팀 배정부터 배치 단계 시작까지 초기 게임 흐름을 진행한다.
     */
    public boolean start(
            boolean force,
            int pieceCount,
            @Nullable Integer playerCount
    ) {
        syncWaitingParticipants();

        if (gameState != GameState.WAITING) {
            return false;
        }
        if (pieceCount <= 0) {
            return false;
        }

        List<UUID> availableParticipants = new ArrayList<>(participants);
        int selectedPlayerCount = playerCount == null ? availableParticipants.size() : playerCount;
        if (selectedPlayerCount <= 0) {
            return false;
        }
        if (selectedPlayerCount > availableParticipants.size()) {
            return false;
        }
        if (!force && selectedPlayerCount < 2) {
            return false;
        }

        prepareParticipantsForGame(availableParticipants, selectedPlayerCount);
        configuredPieceCount = pieceCount;
        gameState = GameState.TEAM_ASSIGNING;
        assignTeams();
        applySpectatorStateToNonParticipants();
        refreshAllPlayerFormatting();
        selectPlacementPlayers();
        startPlacingPhase();
        return true;
    }

    public boolean startWithPreset(
            boolean force,
            PresetData presetData,
            @Nullable Integer playerCount
    ) {
        syncWaitingParticipants();

        if (gameState != GameState.WAITING || presetData.isEmpty() || !presetData.isBalanced()) {
            return false;
        }

        int pieceCount = presetData.getPieceCount();
        List<UUID> availableParticipants = new ArrayList<>(participants);
        int selectedPlayerCount = playerCount == null ? availableParticipants.size() : playerCount;
        if (pieceCount <= 0 || selectedPlayerCount <= 0 || selectedPlayerCount > availableParticipants.size()) {
            return false;
        }
        if (!force && selectedPlayerCount < 2) {
            return false;
        }
        if (!isPresetValid(presetData)) {
            return false;
        }

        prepareParticipantsForGame(availableParticipants, selectedPlayerCount);
        configuredPieceCount = pieceCount;
        gameState = GameState.TEAM_ASSIGNING;
        assignTeams();
        applySpectatorStateToNonParticipants();
        refreshAllPlayerFormatting();
        applyPresetPieces(presetData);
        startPlayingPhase();
        return true;
    }

    /**
     * 진행 중인 게임을 종료 상태로 전환하고 잠시 뒤 전체 상태를 리셋한다.
     */
    public void stop() {
        stopTurnTimer();
        stopGameMusic();
        stopLaunchCameraFollow();
        stopSelectedCameraLiftTask();
        gameState = GameState.ENDING;
        sendAllOnlinePlayersToLobby();
        scoreboardManager.showResult(this, null);
        resetAfterDelay();
    }

    /**
     * 게임 상태, 말, 플레이어 장비와 표시 정보를 모두 대기 상태로 되돌린다.
     */
    public void reset() {
        stopTurnTimer();
        stopGameMusic();
        stopLaunchCameraFollow();
        stopSelectedCameraLiftTask();
        gameState = GameState.WAITING;
        currentTurnPlayer = null;
        currentTurnTeam = TeamType.BLUE;
        configuredPieceCount = 0;
        selectedPiece = null;
        lastSelectedPlayerId = null;
        selectionReadyFeedbackPlayerId = null;
        lastSelectedAtMillis = 0L;
        launchPowerMap.clear();
        selectedCameraReturnLocationMap.clear();
        launchCameraRotationGraceTicks.clear();
        selectedCameraLiftedMap.clear();
        restoreAllTurnCameras();
        playerTeamMap.clear();
        placementPlayers.clear();
        placedCountMap.put(TeamType.BLUE, 0);
        placedCountMap.put(TeamType.RED, 0);
        eliminatedPiecesByPlayer.clear();
        ownPiecesEliminatedByPlayer.clear();

        boardManager.clearSessionPieces(teamDataMap);
        teamDataMap.values().forEach(teamData -> {
            teamData.clearPieces();
            teamData.clearPlayers();
        });

        for (UUID participant : participants) {
            Player player = plugin.getServer().getPlayer(participant);
            if (player == null) {
                continue;
            }
            clearRemoteControlMode(player);
            restoreOriginalArmor(player);
            refreshPlayerFormatting(player);
            sendToLobby(player);
        }

        refreshAllPlayerFormatting();
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            removeRemoteControllerItems(onlinePlayer);
            clearSpectatorState(onlinePlayer);
            sendToLobby(onlinePlayer);
        }

        clearTurnIndicators();
        scoreboardManager.clearAll();
    }

    /**
     * 현재 참가자들을 섞어서 청팀과 홍팀으로 균형 있게 나눈다.
     */
    public void assignTeams() {
        playerTeamMap.clear();
        teamDataMap.values().forEach(TeamData::clearPlayers);

        List<UUID> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled);

        int blueSize = 0;
        int redSize = 0;
        int targetBlueSize = shuffled.size() / 2;
        int targetRedSize = shuffled.size() - targetBlueSize;
        for (UUID playerId : shuffled) {
            TeamType teamType;
            if (blueSize >= targetBlueSize) {
                teamType = TeamType.RED;
            } else if (redSize >= targetRedSize) {
                teamType = TeamType.BLUE;
            } else if (blueSize <= redSize) {
                teamType = TeamType.BLUE;
            } else {
                teamType = TeamType.RED;
            }
            playerTeamMap.put(playerId, teamType);
            teamDataMap.get(teamType).addPlayer(playerId);
            if (teamType == TeamType.BLUE) {
                blueSize++;
            } else {
                redSize++;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                equipTeamArmor(player, teamType);
            }
        }
    }

    /**
     * 각 팀에서 말 배치를 담당할 플레이어 한 명씩을 뽑는다.
     */
    public void selectPlacementPlayers() {
        placementPlayers.clear();
        for (TeamType teamType : TeamType.values()) {
            List<UUID> teamPlayers = new ArrayList<>(teamDataMap.get(teamType).getPlayers());
            if (teamPlayers.isEmpty()) {
                continue;
            }
            Collections.shuffle(teamPlayers);
            placementPlayers.put(teamType, teamPlayers.get(0));
        }
    }

    /**
     * 배치 담당 플레이어에게 리모컨을 지급하고 말 배치 단계를 시작한다.
     */
    public void startPlacingPhase() {
        gameState = GameState.PLACING;
        placedCountMap.put(TeamType.BLUE, 0);
        placedCountMap.put(TeamType.RED, 0);

        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }

            TeamType teamType = playerTeamMap.get(participantId);
            UUID placementPlayer = teamType == null ? null : placementPlayers.get(teamType);
            if (placementPlayer != null && placementPlayer.equals(participantId)) {
                applyRemoteControlMode(player);
                giveRemoteController(player);
                Location placementLocation = arenaData.getPlacementLocation(teamType);
                if (placementLocation != null) {
                    player.teleport(placementLocation);
                }
                player.sendMessage(Component.text(formatTeamDisplayName(teamType) + " 말을 배치해 주세요.", NamedTextColor.YELLOW));
                continue;
            }

            clearRemoteControlMode(player);
            sendToLobby(player);
        }
    }

    /**
     * 모든 참가자를 플레이 조작 상태로 전환하고 실제 턴 진행을 시작한다.
     */
    public void startPlayingPhase() {
        gameState = GameState.PLAYING;
        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }

            applyRemoteControlMode(player);
            sendToLobby(player);
        }

        decideOpeningTeam();
    }

    /**
     * 현재 팀의 다음 플레이어를 찾아 새 턴을 시작한다.
     */
    public void startNextTurn() {
        if (gameState != GameState.PLAYING) {
            return;
        }

        stopTurnTimer();
        clearControllersForParticipants();

        TeamData teamData = teamDataMap.get(currentTurnTeam);
        UUID next = findNextOnlinePlayer(teamData);
        if (next == null) {
            scheduleEndGame(currentTurnTeam.opposite());
            return;
        }

        currentTurnPlayer = next;
        selectionReadyFeedbackPlayerId = null;
        launchPowerMap.put(next, DEFAULT_LAUNCH_POWER);
        scoreboardManager.updateGameBoard(this);
        refreshTurnIndicators();
        Player player = plugin.getServer().getPlayer(next);
        if (player != null) {
            enterTurnCamera(player);
            applyTurnBuff(player);
            giveRemoteController(player);
            showCurrentTurnTitle(player);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.35F);
            player.sendMessage(Component.text("당신 차례입니다. 블레이즈 막대로 말을 우클릭해 주세요.", NamedTextColor.YELLOW));
        }
        startTurnTimer();
    }

    /**
     * 현재 턴을 마감하고 승패를 확인한 뒤 다음 턴 또는 게임 종료로 넘어간다.
     */
    public void endTurn() {
        if (gameState != GameState.PLAYING || currentTurnPlayer == null) {
            return;
        }

        stopTurnTimer();
        stopSelectedCameraLiftTask();
        UUID finishedTurnPlayer = currentTurnPlayer;
        TeamData currentTeamData = teamDataMap.get(currentTurnTeam);
        currentTeamData.pushBackPlayer(currentTurnPlayer);
        currentTurnPlayer = null;
        selectedPiece = null;
        selectionReadyFeedbackPlayerId = null;
        launchPowerMap.remove(finishedTurnPlayer);
        selectedCameraReturnLocationMap.remove(finishedTurnPlayer);
        launchCameraRotationGraceTicks.remove(finishedTurnPlayer);
        selectedCameraLiftedMap.remove(finishedTurnPlayer);

        if (isDraw()) {
            scheduleEndGame(null);
            return;
        }

        TeamType winner = checkWinner();
        if (winner != null) {
            scheduleEndGame(winner);
            return;
        }

        currentTurnTeam = currentTurnTeam.opposite();
        scheduleNextTurn(finishedTurnPlayer);
    }

    /**
     * 현재 살아남은 말 수를 기준으로 승리 팀을 판정한다.
     */
    public @Nullable TeamType checkWinner() {
        if (teamDataMap.get(TeamType.BLUE).getAlivePieceCount() <= 0 && configuredPieceCount > 0) {
            return TeamType.RED;
        }
        if (teamDataMap.get(TeamType.RED).getAlivePieceCount() <= 0 && configuredPieceCount > 0) {
            return TeamType.BLUE;
        }
        return null;
    }

    private boolean isDraw() {
        return configuredPieceCount > 0
            && teamDataMap.get(TeamType.BLUE).getAlivePieceCount() <= 0
            && teamDataMap.get(TeamType.RED).getAlivePieceCount() <= 0;
    }

    /**
     * 승자 정보를 알리고 결과 화면을 보여 준 뒤 게임을 정리한다.
     */
    public void endGame(
            @Nullable TeamType winner
    ) {
        stopTurnTimer();
        stopGameMusic();
        stopLaunchCameraFollow();
        stopSelectedCameraLiftTask();
        gameState = GameState.ENDING;
        restoreAllTurnCameras();
        scoreboardManager.showResult(this, winner);
        broadcastWinnerTitle(winner);
        broadcastKillRanking();
        resetAfterDelay(RESULT_DISPLAY_TICKS);
    }

    /**
     * 게임 중 오프라인이 된 플레이어를 참가 목록과 턴 흐름에서 제거한다.
     */
    public void removeOfflinePlayer(
            UUID playerId
    ) {
        turnCameraStateMap.remove(playerId);
        flightStateMap.remove(playerId);
        launchPowerMap.remove(playerId);
        selectedCameraReturnLocationMap.remove(playerId);
        launchCameraRotationGraceTicks.remove(playerId);
        selectedCameraLiftedMap.remove(playerId);
        TeamType teamType = playerTeamMap.get(playerId);
        participants.remove(playerId);
        playerTeamMap.remove(playerId);
        teamDataMap.values().forEach(teamData -> teamData.removePlayer(playerId));

        if (gameState == GameState.PLACING && teamType != null) {
            UUID placementPlayer = placementPlayers.get(teamType);
            if (placementPlayer != null && placementPlayer.equals(playerId)) {
                assignReplacementPlacementPlayer(teamType);
            }
        }

        if (currentTurnPlayer != null && currentTurnPlayer.equals(playerId)) {
            currentTurnPlayer = null;
            currentTurnTeam = currentTurnTeam.opposite();
            startNextTurn();
        }
    }

    public boolean kickParticipant(Player player) {
        UUID playerId = player.getUniqueId();
        TeamType teamType = playerTeamMap.get(playerId);
        if (!participants.contains(playerId) || teamType == null || gameState == GameState.WAITING || gameState == GameState.ENDING) {
            return false;
        }

        boolean wasCurrentTurnPlayer = currentTurnPlayer != null && currentTurnPlayer.equals(playerId);
        boolean wasPlacementPlayer = placementPlayers.values().stream().anyMatch(playerId::equals);
        if (wasCurrentTurnPlayer) {
            stopTurnTimer();
            stopLaunchCameraFollow();
            stopSelectedCameraLiftTask();
            removeTurnCameraInvisibility(player);
            currentTurnPlayer = null;
            selectedPiece = null;
            lastSelectedPlayerId = null;
            selectionReadyFeedbackPlayerId = null;
            lastSelectedAtMillis = 0L;
            selectedCameraReturnLocationMap.remove(playerId);
            launchCameraRotationGraceTicks.remove(playerId);
            selectedCameraLiftedMap.remove(playerId);
        }

        participants.remove(playerId);
        playerTeamMap.remove(playerId);
        teamDataMap.values().forEach(teamData -> teamData.removePlayer(playerId));
        placementPlayers.values().removeIf(playerId::equals);
        eliminatedPiecesByPlayer.remove(playerId);
        ownPiecesEliminatedByPlayer.remove(playerId);

        stopGameMusicForPlayer(player);
        clearRemoteControlMode(player);
        applySpectatorState(player);
        Location spectatorLocation = arenaData.getSpectatorLocation();
        if (spectatorLocation != null) {
            player.teleport(spectatorLocation);
        } else {
            sendToLobby(player);
        }
        refreshPlayerFormatting(player);

        if (gameState == GameState.PLACING) {
            if (teamDataMap.get(teamType).getPlayers().isEmpty()) {
                endGame(teamType.opposite());
            } else if (wasPlacementPlayer) {
                assignReplacementPlacementPlayer(teamType);
            }
            return true;
        }

        if (gameState == GameState.PLAYING && teamDataMap.get(teamType).getPlayers().isEmpty()) {
            endGame(teamType.opposite());
            return true;
        }

        if (wasCurrentTurnPlayer) {
            startNextTurn();
        } else {
            scoreboardManager.updateGameBoard(this);
            refreshTurnIndicators();
        }
        return true;
    }

    public Set<UUID> getParticipants() {
        return Set.copyOf(participants);
    }

    public Map<UUID, TeamType> getPlayerTeamMap() {
        return Map.copyOf(playerTeamMap);
    }

    public Map<TeamType, TeamData> getTeamDataMap() {
        return Map.copyOf(teamDataMap);
    }

    public Map<TeamType, UUID> getPlacementPlayers() {
        return Map.copyOf(placementPlayers);
    }

    public @Nullable TeamType getPlayerTeam(UUID playerId) {
        return playerTeamMap.get(playerId);
    }

    public GameState getGameState() {
        return gameState;
    }

    public TeamType getCurrentTurnTeam() {
        return currentTurnTeam;
    }

    public @Nullable UUID getCurrentTurnPlayer() {
        return currentTurnPlayer;
    }

    public int getConfiguredPieceCount() {
        return configuredPieceCount;
    }

    public @Nullable PieceData getSelectedPiece() {
        return selectedPiece;
    }

    public boolean isCurrentTurnPlayer(
            UUID playerId) {
        return currentTurnPlayer != null && currentTurnPlayer.equals(playerId);
    }

    public boolean consumeSelectionReadyFeedback(
            Player player
    ) {
        UUID playerId = player.getUniqueId();
        if (!isCurrentTurnPlayer(playerId)) {
            return false;
        }
        if (playerId.equals(selectionReadyFeedbackPlayerId)) {
            return false;
        }

        selectionReadyFeedbackPlayerId = playerId;
        return true;
    }

    public boolean isPlacementPhase() {
        return gameState == GameState.PLACING;
    }

    public boolean isPlayingPhase() {
        return gameState == GameState.PLAYING;
    }

    public int getPlacedCount(TeamType teamType) {
        return placedCountMap.getOrDefault(teamType, 0);
    }

    public int getAlivePieceCount(TeamType teamType) {
        TeamData teamData = teamDataMap.get(teamType);
        return teamData == null ? 0 : teamData.getAlivePieceCount();
    }

    public int getRemainingTurnSeconds() {
        return remainingTurnSeconds;
    }

    public String getPlayerTeamStatusText(UUID playerId) {
        TeamType teamType = playerTeamMap.get(playerId);
        if (teamType == TeamType.BLUE) {
            return "청팀입니다";
        }
        if (teamType == TeamType.RED) {
            return "홍팀입니다";
        }
        return "관전 중입니다";
    }

    public String getTurnStatusText(UUID playerId) {
        if (gameState != GameState.PLAYING) {
            return "대기 중입니다";
        }
        if (currentTurnPlayer != null && currentTurnPlayer.equals(playerId)) {
            return "당신 차례입니다";
        }

        TeamType teamType = playerTeamMap.get(playerId);
        if (teamType == null) {
            return "관전 중입니다";
        }

        TeamData currentTeamData = teamDataMap.get(currentTurnTeam);
        TeamData oppositeTeamData = teamDataMap.get(currentTurnTeam.opposite());
        int turnsRemaining = calculateTurnsRemaining(
            playerId,
            teamType,
            currentTeamData.getTurnQueueSnapshot(),
            oppositeTeamData.getTurnQueueSnapshot()
        );
        if (turnsRemaining < 0) {
            return "턴 순서를 계산 중입니다";
        }
        return "내 차례까지 " + turnsRemaining + "턴 남음";
    }

    public String getCurrentTurnDisplayText() {
        Player currentPlayer = currentTurnPlayer == null ? null : plugin.getServer().getPlayer(currentTurnPlayer);
        String playerName = currentPlayer == null ? "대기 중" : currentPlayer.getName();
        return currentTurnTeam.getDisplayName() + " - " + playerName;
    }

    public void copyTabTeams(Scoreboard targetScoreboard) {
        org.bukkit.scoreboard.ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null) {
            return;
        }

        Scoreboard mainScoreboard = scoreboardManager.getMainScoreboard();
        for (String teamName : List.of(TAB_TEAM_BLUE, TAB_TEAM_RED, TAB_TEAM_SPECTATOR)) {
            Team mainTeam = mainScoreboard.getTeam(teamName);
            if (mainTeam == null) {
                continue;
            }

            Team targetTeam = targetScoreboard.getTeam(teamName);
            if (targetTeam == null) {
                targetTeam = targetScoreboard.registerNewTeam(teamName);
            }
            targetTeam.color(switch (teamName) {
                case TAB_TEAM_BLUE -> TeamType.BLUE.getColor();
                case TAB_TEAM_RED -> TeamType.RED.getColor();
                default -> DEFAULT_PLAYER_COLOR;
            });
            targetTeam.prefix(mainTeam.prefix());
            targetTeam.suffix(mainTeam.suffix());
            for (String entry : mainTeam.getEntries()) {
                targetTeam.addEntry(entry);
            }
        }
    }

    private boolean isPresetValid(PresetData presetData) {
        EnumMap<TeamType, TeamData> validationTeamDataMap = new EnumMap<>(TeamType.class);
        for (TeamType teamType : TeamType.values()) {
            validationTeamDataMap.put(teamType, new TeamData(teamType));
        }

        for (TeamType teamType : TeamType.values()) {
            TeamData teamData = validationTeamDataMap.get(teamType);
            int pieceId = 1;
            for (PresetData.PresetPiece piece : presetData.getPieces(teamType)) {
                Location spawnLocation = normalizePlacementLocation(piece.location());
                if (!boardManager.canPlacePiece(spawnLocation, piece.pieceSize(), validationTeamDataMap)) {
                    return false;
                }

                teamData.getPieces().add(new PieceData(
                    pieceId++,
                    teamType,
                    spawnLocation,
                    piece.pieceSize(),
                    piece.heightScale(),
                    piece.labelText()
                ));
            }
        }
        return true;
    }

    private void applyPresetPieces(PresetData presetData) {
        placedCountMap.put(TeamType.BLUE, 0);
        placedCountMap.put(TeamType.RED, 0);
        for (TeamType teamType : TeamType.values()) {
            TeamData teamData = teamDataMap.get(teamType);
            int pieceId = 1;
            for (PresetData.PresetPiece piece : presetData.getPieces(teamType)) {
                Location spawnLocation = normalizePlacementLocation(piece.location());
                teamData.getPieces().add(boardManager.spawnPiece(
                    teamType,
                    pieceId++,
                    spawnLocation,
                    piece.pieceSize(),
                    piece.heightScale(),
                    piece.labelText()
                ));
            }
            placedCountMap.put(teamType, teamData.getAlivePieceCount());
        }
    }

    private Location normalizePlacementLocation(Location clickedLocation) {
        Location spawnLocation = clickedLocation.clone();
        Location base = arenaData.getBoardPos1();
        if (base != null) {
            spawnLocation.setY(base.getY());
        }
        spawnLocation.setX(Math.floor(spawnLocation.getX()) + 0.5D);
        spawnLocation.setZ(Math.floor(spawnLocation.getZ()) + 0.5D);
        return spawnLocation;
    }

    /**
     * 배치 단계에서 팀 담당 플레이어가 보드 위에 말을 하나 배치한다.
     */
    public boolean placePiece(
            Player player,
            Location clickedLocation
    ) {
        if (gameState != GameState.PLACING) {
            return false;
        }

        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        if (teamType == null) {
            return false;
        }

        UUID placementPlayer = placementPlayers.get(teamType);
        if (placementPlayer == null || !placementPlayer.equals(player.getUniqueId())) {
            return false;
        }
        if (getPlacedCount(teamType) >= configuredPieceCount) {
            return false;
        }

        Location spawnLocation = clickedLocation.clone();
        Location base = arenaData.getBoardPos1();
        if (base != null) {
            spawnLocation.setY(base.getY());
        }
        spawnLocation.setX(Math.floor(spawnLocation.getX()) + 0.5D);
        spawnLocation.setZ(Math.floor(spawnLocation.getZ()) + 0.5D);

        if (!boardManager.canPlacePiece(spawnLocation, teamDataMap)) {
            return false;
        }

        TeamData teamData = teamDataMap.get(teamType);
        int pieceId = getPlacedCount(teamType) + 1;
        teamData.getPieces().add(boardManager.spawnPiece(teamType, pieceId, spawnLocation));
        placedCountMap.put(teamType, pieceId);

        if (isPlacementComplete()) {
            startPlayingPhase();
        }
        return true;
    }

    /**
     * 현재 턴 플레이어가 자기 팀 말을 선택 상태로 만든다.
     */
    public @Nullable PieceData selectPiece(
            Player player,
            UUID entityId
    ) {
        if (gameState != GameState.PLAYING || !isCurrentTurnPlayer(player.getUniqueId()) || boardManager.isActionRunning()) {
            return null;
        }

        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        PieceData pieceData = boardManager.findPieceByEntity(entityId);
        if (teamType == null || pieceData == null || !pieceData.isAlive() || pieceData.getTeamType() != teamType) {
            return null;
        }

        return selectPieceData(player, pieceData);
    }

    private PieceData selectPieceData(Player player, PieceData pieceData) {
        selectedCameraReturnLocationMap.put(player.getUniqueId(), player.getLocation().clone());
        selectedPiece = pieceData;
        lastSelectedPlayerId = player.getUniqueId();
        lastSelectedAtMillis = System.currentTimeMillis();
        applyTurnCameraInvisibility(player);
        moveTurnCameraToSelectedPiece(player, pieceData);
        startSelectedCameraLiftTask(player);
        return pieceData;
    }

    /**
     * 현재 선택된 말을 취소하고 다시 선택 대기 상태로 돌린다.
     */
    public boolean cancelSelectedPiece(
            Player player
    ) {
        if (gameState != GameState.PLAYING || !isCurrentTurnPlayer(player.getUniqueId()) || selectedPiece == null || boardManager.isActionRunning()) {
            return false;
        }

        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        if (teamType == null || selectedPiece.getTeamType() != teamType) {
            return false;
        }

        selectedPiece = null;
        lastSelectedPlayerId = null;
        lastSelectedAtMillis = 0L;
        stopSelectedCameraLiftTask();
        selectedCameraLiftedMap.remove(player.getUniqueId());
        launchPowerMap.put(player.getUniqueId(), DEFAULT_LAUNCH_POWER);
        restoreSelectedCameraReturnLocation(player);
        removeTurnCameraInvisibility(player);
        player.setFlySpeed(TURN_CAMERA_FLY_SPEED);
        return true;
    }

    /**
     * 선택된 말을 목표 지점을 향해 발사하고 물리 처리 종료 후 턴을 넘긴다.
     */
    public boolean launchSelectedPiece(
            Player player,
            Location targetLocation
    ) {
        if (selectedPiece == null) {
            return false;
        }
        return launchSelectedPiece(player, boardManager.createLaunchVector(selectedPiece, targetLocation));
    }

    public boolean launchSelectedPiece(Player player) {
        if (selectedPiece == null) {
            return false;
        }
        Vector direction = getFlatLaunchDirection(player);
        if (direction.lengthSquared() <= 0.0001D) {
            return false;
        }
        return launchSelectedPiece(
                player,
                boardManager.createLaunchVector(selectedPiece, direction, getLaunchPower(player.getUniqueId()))
        );
    }

    private boolean launchSelectedPiece(
            Player player,
            Vector launchVector
    ) {
        if (gameState != GameState.PLAYING || !isCurrentTurnPlayer(player.getUniqueId()) || selectedPiece == null || boardManager.isActionRunning()) {
            return false;
        }
        if (justSelectedPiece(player.getUniqueId())) {
            return false;
        }

        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        if (teamType == null || selectedPiece.getTeamType() != teamType) {
            return false;
        }

        UUID actingPlayerId = player.getUniqueId();
        TeamType targetTeam = teamType.opposite();
        int ownAliveBefore = teamDataMap.get(teamType).getAlivePieceCount();
        int opponentAliveBefore = teamDataMap.get(targetTeam).getAlivePieceCount();
        stopTurnTimer();
        PieceData launchedPiece = selectedPiece;
        selectedCameraReturnLocationMap.remove(player.getUniqueId());
        stopSelectedCameraLiftTask();
        selectedCameraLiftedMap.remove(player.getUniqueId());
        startLaunchCameraFollow(player, launchedPiece);
        boardManager.launchPiece(selectedPiece, launchVector, teamDataMap, () -> {
            int ownAliveAfter = teamDataMap.get(teamType).getAlivePieceCount();
            int opponentAliveAfter = teamDataMap.get(targetTeam).getAlivePieceCount();
            int ownEliminatedCount = Math.max(0, ownAliveBefore - ownAliveAfter);
            int eliminatedCount = Math.max(0, opponentAliveBefore - opponentAliveAfter);
            if (eliminatedCount > 0) {
                eliminatedPiecesByPlayer.merge(actingPlayerId, eliminatedCount, Integer::sum);
            }
            if (ownEliminatedCount > 0) {
                ownPiecesEliminatedByPlayer.merge(actingPlayerId, ownEliminatedCount, Integer::sum);
            }
            endTurn();
        });
        return true;
    }

    public boolean adjustLaunchPower(Player player, double delta) {
        if (gameState != GameState.PLAYING
                || !isCurrentTurnPlayer(player.getUniqueId())
                || selectedPiece == null
                || boardManager.isActionRunning()) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        double currentPower = getLaunchPower(playerId);
        double updatedPower = Math.max(
                BoardManager.MIN_LAUNCH_POWER,
                Math.min(BoardManager.MAX_LAUNCH_POWER, currentPower + delta)
        );
        launchPowerMap.put(playerId, updatedPower);
        return Math.abs(updatedPower - currentPower) > 0.0001D;
    }

    public boolean increaseLaunchPower(Player player) {
        return increaseLaunchPower(player, 1.0D);
    }

    public boolean increaseLaunchPower(Player player, double multiplier) {
        return adjustLaunchPower(player, LAUNCH_POWER_STEP * multiplier);
    }

    public boolean decreaseLaunchPower(Player player) {
        return decreaseLaunchPower(player, 1.0D);
    }

    public boolean decreaseLaunchPower(Player player, double multiplier) {
        return adjustLaunchPower(player, -LAUNCH_POWER_STEP * multiplier);
    }

    public double getLaunchPower(UUID playerId) {
        return launchPowerMap.getOrDefault(playerId, DEFAULT_LAUNCH_POWER);
    }

    public Vector getFlatLaunchDirection(Player player) {
        Vector direction = player.getEyeLocation().getDirection();
        direction.setY(0.0D);
        if (direction.lengthSquared() <= 0.0001D) {
            return new Vector();
        }
        return direction.normalize();
    }

    private boolean justSelectedPiece(UUID playerId) {
        return lastSelectedPlayerId != null
            && lastSelectedPlayerId.equals(playerId)
            && System.currentTimeMillis() - lastSelectedAtMillis < LAUNCH_GUARD_MILLIS;
    }

    public @Nullable TeamType getTeam(UUID playerId) {
        return playerTeamMap.get(playerId);
    }

    /**
     * 플레이어 이름 색과 탭 팀을 현재 소속 팀 기준으로 갱신한다.
     */
    public void refreshPlayerFormatting(Player player) {
        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        NamedTextColor color = teamType == null ? DEFAULT_PLAYER_COLOR : teamType.getColor();
        player.playerListName(Component.text(player.getName(), color));
        player.displayName(Component.text(player.getName(), color));
        assignPlayerToTabTeam(player, teamType);
    }

    public NamedTextColor getPlayerColor(UUID playerId) {
        TeamType teamType = playerTeamMap.get(playerId);
        return teamType == null ? DEFAULT_PLAYER_COLOR : teamType.getColor();
    }

    /**
     * 모든 온라인 플레이어의 이름색과 탭 정렬을 다시 갱신한다.
     */
    public void refreshAllPlayerFormatting() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayerFormatting(player);
        }
    }

    private void assignPlayerToTabTeam(Player player, @Nullable TeamType teamType) {
        org.bukkit.scoreboard.ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null) {
            return;
        }

        String entry = player.getName();
        Scoreboard mainScoreboard = scoreboardManager.getMainScoreboard();
        syncPlayerTabTeam(mainScoreboard, entry, teamType);

        Scoreboard playerScoreboard = player.getScoreboard();
        if (playerScoreboard != mainScoreboard) {
            syncPlayerTabTeam(playerScoreboard, entry, teamType);
        }
    }

    private void syncPlayerTabTeam(Scoreboard scoreboard, String entry, @Nullable TeamType teamType) {
        removeEntryFromTabTeams(scoreboard, entry);
        getOrCreateTabTeam(scoreboard, teamType).addEntry(entry);
    }

    private void removeEntryFromTabTeams(Scoreboard scoreboard, String entry) {
        for (String teamName : List.of(
                TAB_TEAM_BLUE,
                TAB_TEAM_RED,
                LEGACY_TAB_TEAM_BLACK,
                LEGACY_TAB_TEAM_WHITE,
                TAB_TEAM_SPECTATOR
        )) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private Team getOrCreateTabTeam(Scoreboard scoreboard, @Nullable TeamType teamType) {
        String teamName = switch (teamType) {
            case BLUE -> TAB_TEAM_BLUE;
            case RED -> TAB_TEAM_RED;
            case null -> TAB_TEAM_SPECTATOR;
        };

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.color(teamType == null ? DEFAULT_PLAYER_COLOR : teamType.getColor());
        team.prefix(Component.empty());
        team.suffix(Component.empty());
        return team;
    }

    /**
     * 이미 진행 중인 턴 타이머를 새로 들어온 플레이어에게도 보여 준다.
     */
    public void refreshTurnTimerViewer(Player player) {
        if (turnTimerTask == null || !turnTimerBar.isVisible()) {
            return;
        }
        turnTimerBar.addPlayer(player);
    }

    private void startTurnTimer() {
        stopTurnTimer();
        if (gameState != GameState.PLAYING || currentTurnPlayer == null) {
            return;
        }

        remainingTurnSeconds = getTurnTimeSeconds();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            turnTimerBar.addPlayer(player);
        }
        updateTurnTimerBar();
        turnTimerBar.setVisible(true);

        turnTimerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (gameState != GameState.PLAYING || currentTurnPlayer == null || boardManager.isActionRunning()) {
                stopTurnTimer();
                return;
            }

            remainingTurnSeconds--;
            if (remainingTurnSeconds <= 0) {
                UUID timedOutPlayerId = currentTurnPlayer;
                stopTurnTimer();
                handleTurnTimeout(timedOutPlayerId);
                return;
            }

            updateTurnTimerBar();
        }, 20L, 20L);
    }

    private void stopTurnTimer() {
        if (turnTimerTask != null) {
            turnTimerTask.cancel();
            turnTimerTask = null;
        }
        remainingTurnSeconds = 0;
        turnTimerBar.removeAll();
        turnTimerBar.setVisible(false);
        turnTimerBar.setProgress(1.0D);
    }

    private void updateTurnTimerBar() {
        Player currentPlayer = currentTurnPlayer == null ? null : plugin.getServer().getPlayer(currentTurnPlayer);
        String playerName = currentPlayer == null ? "알 수 없음" : currentPlayer.getName();
        turnTimerBar.setTitle(currentTurnTeam.getDisplayName() + " - " + playerName + " 차례");
        int turnTimeSeconds = getTurnTimeSeconds();
        turnTimerBar.setProgress(Math.max(0.0D, Math.min(1.0D, remainingTurnSeconds / (double) turnTimeSeconds)));
        turnTimerBar.setColor(remainingTurnSeconds <= Math.max(5, turnTimeSeconds / 6) ? BarColor.RED : remainingTurnSeconds <= Math.max(10, turnTimeSeconds / 3) ? BarColor.YELLOW : BarColor.GREEN);
        scoreboardManager.updateGameBoard(this);
    }

    private void handleTurnTimeout(@Nullable UUID timedOutPlayerId) {
        if (gameState != GameState.PLAYING || timedOutPlayerId == null || currentTurnPlayer == null || !currentTurnPlayer.equals(timedOutPlayerId)) {
            return;
        }

        Player timedOutPlayer = plugin.getServer().getPlayer(timedOutPlayerId);
        if (timedOutPlayer != null) {
            timedOutPlayer.sendMessage(Component.text(getTurnTimeSeconds() + "초가 지나 턴이 자동으로 넘어갑니다.", NamedTextColor.RED));
        }
        playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BASS, 0.9F, 0.7F);
        endTurn();
    }

    /**
     * 현재 적용 중인 턴 제한 시간을 반환한다.
     */
    public int getTurnTimeSeconds() {
        return arenaData.getTurnTimeSeconds();
    }

    /**
     * 턴 시간 설정이 바뀌었을 때 현재 진행 중인 타이머에 즉시 반영한다.
     */
    public void refreshTurnTimerConfiguration() {
        if (gameState == GameState.PLAYING && currentTurnPlayer != null && !boardManager.isActionRunning()) {
            startTurnTimer();
        }
    }

    private void assignReplacementPlacementPlayer(TeamType teamType) {
        for (UUID candidate : teamDataMap.get(teamType).getPlayers()) {
            Player player = plugin.getServer().getPlayer(candidate);
            if (player != null && player.isOnline()) {
                placementPlayers.put(teamType, candidate);
                applyRemoteControlMode(player);
                giveRemoteController(player);
                Location placementLocation = arenaData.getPlacementLocation(teamType);
                if (placementLocation != null) {
                    player.teleport(placementLocation);
                }
                player.sendMessage(Component.text(formatTeamDisplayName(teamType) + " 배치 담당으로 지정되었습니다.", NamedTextColor.YELLOW));
                return;
            }
        }

        endGame(teamType.opposite());
    }

    private @Nullable UUID findNextOnlinePlayer(TeamData teamData) {
        while (teamData.hasQueuedPlayers()) {
            UUID next = teamData.pollNextPlayer();
            Player player = plugin.getServer().getPlayer(next);
            if (player == null || !player.isOnline()) {
                teamData.removePlayer(next);
                continue;
            }
            return next;
        }
        return null;
    }

    private List<UUID> selectParticipantsForNextGame(List<UUID> availableParticipants, int selectedPlayerCount) {
        List<UUID> shuffled = new ArrayList<>(availableParticipants);
        Collections.shuffle(shuffled);
        shuffled.sort((left, right) -> Integer.compare(
            benchGameStreaks.getOrDefault(right, 0),
            benchGameStreaks.getOrDefault(left, 0)
        ));
        return new ArrayList<>(shuffled.subList(0, selectedPlayerCount));
    }

    private void updateBenchGameStreaks(List<UUID> availableParticipants, List<UUID> selectedParticipants) {
        Set<UUID> selectedSet = Set.copyOf(selectedParticipants);
        for (UUID participantId : availableParticipants) {
            if (selectedSet.contains(participantId)) {
                benchGameStreaks.put(participantId, 0);
            } else {
                benchGameStreaks.merge(participantId, 1, Integer::sum);
            }
        }
    }

    private void resetAfterDelay() {
        reset();
    }

    private void resetAfterDelay(long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, this::reset, Math.max(0L, delayTicks));
    }

    private static long durationToTicks(Duration duration) {
        return Math.max(1L, (long) Math.ceil(duration.toMillis() / 50.0D));
    }

    private void decideOpeningTeam() {
        TeamType finalTeam = Math.random() < 0.5D ? TeamType.BLUE : TeamType.RED;
        TeamType previewTeam = finalTeam.opposite();
        runOpeningTeamPreview(finalTeam, previewTeam, 0);
    }

    private void runOpeningTeamPreview(TeamType finalTeam, TeamType previewTeam, int step) {
        if (gameState != GameState.PLAYING) {
            return;
        }

        int[] delays = {1, 1, 1, 2, 2, 3, 4, 6, 8, 10, 12};
        if (step >= delays.length) {
            currentTurnTeam = finalTeam;
            showTitleToParticipants(
                Component.text(finalTeam.getDisplayName(), finalTeam.getColor()),
                Component.text("선공 팀 결정!", NamedTextColor.YELLOW),
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                Duration.ofMillis(400)
            );
            startGameMusic();
            plugin.getServer().getScheduler().runTaskLater(plugin, this::startNextTurn, 30L);
            return;
        }

        showTitleToParticipants(
            Component.text(previewTeam.getDisplayName(), previewTeam.getColor()),
                Component.text("선공 팀 추첨 중...", NamedTextColor.YELLOW),
            Duration.ofMillis(0),
            Duration.ofMillis(250),
            Duration.ofMillis(150)
        );
        playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.0F);

        TeamType nextPreviewTeam = previewTeam.opposite();
        plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> runOpeningTeamPreview(finalTeam, nextPreviewTeam, step + 1),
            delays[step]
        );
    }

    private void showTitleToParticipants(
            Component title,
            Component subtitle,
            Duration fadeIn,
            Duration stay,
            Duration fadeOut
    ) {
        Title.Times times = Title.Times.times(fadeIn, stay, fadeOut);
        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player != null) {
                player.showTitle(Title.title(title, subtitle, times));
            }
        }
    }

    private void broadcastWinnerTitle(@Nullable TeamType winner) {
        Title title;
        Component subtitle = createSingleMvpSubtitle();
        if (winner == null) {
            title = Title.title(
                Component.text("무승부입니다!", NamedTextColor.YELLOW),
                subtitle,
                Title.Times.times(RESULT_TITLE_FADE_IN, RESULT_TITLE_STAY, RESULT_TITLE_FADE_OUT)
            );
        } else {
            title = Title.title(
                Component.text(winner.getDisplayName() + " 승리!", winner.getColor()),
                subtitle,
                Title.Times.times(RESULT_TITLE_FADE_IN, RESULT_TITLE_STAY, RESULT_TITLE_FADE_OUT)
            );
        }

        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player != null) {
                player.showTitle(title);
            }
        }
        playResultSound(winner);
    }

    private Component createSingleMvpSubtitle() {
        UUID topKillerId = getTopKillerId();
        if (topKillerId == null) {
            return Component.empty();
        }

        TeamType teamType = playerTeamMap.get(topKillerId);
        NamedTextColor color = teamType == null ? NamedTextColor.AQUA : teamType.getColor();
        return Component.text()
            .append(Component.text("MVP: ", NamedTextColor.YELLOW))
            .append(Component.text(resolvePlayerName(topKillerId), color))
            .build();
    }

    private @Nullable UUID getTopKillerId() {
        return getEliminationRankingEntries().stream()
            .filter(stats -> stats.killCount() > 0)
            .findFirst()
            .map(PlayerEliminationStats::playerId)
            .orElse(null);
    }

    private void broadcastKillRanking() {
        List<PlayerEliminationStats> rankingEntries = getEliminationRankingEntries();
        if (rankingEntries.isEmpty()) {
            return;
        }

        List<Component> rankingLines = new ArrayList<>();
        rankingLines.add(Component.text("========================================", NamedTextColor.DARK_GRAY));
        rankingLines.add(Component.text("            TOP", NamedTextColor.WHITE));

        int displayedCount = Math.min(10, rankingEntries.size());
        int previousRank = 0;
        int previousKillCount = Integer.MIN_VALUE;
        int previousDeathCount = Integer.MIN_VALUE;
        for (int index = 0; index < displayedCount; index++) {
            PlayerEliminationStats entry = rankingEntries.get(index);
            int killCount = entry.killCount();
            int deathCount = entry.deathCount();
            int rank = killCount == previousKillCount && deathCount == previousDeathCount ? previousRank : index + 1;
            rankingLines.add(createKillRankingLine(rank, entry.playerId(), killCount, entry.deathCount()));
            previousRank = rank;
            previousKillCount = killCount;
            previousDeathCount = deathCount;
        }

        rankingLines.add(Component.text("========================================", NamedTextColor.DARK_GRAY));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(Component.empty());
            for (Component line : rankingLines) {
                player.sendMessage(line);
            }
        }
    }

    private List<PlayerEliminationStats> getEliminationRankingEntries() {
        Set<UUID> playerIds = new LinkedHashSet<>();
        playerIds.addAll(eliminatedPiecesByPlayer.keySet());
        playerIds.addAll(ownPiecesEliminatedByPlayer.keySet());

        List<PlayerEliminationStats> entries = new ArrayList<>();
        for (UUID playerId : playerIds) {
            entries.add(new PlayerEliminationStats(playerId, getKillCount(playerId), getDeathCount(playerId)));
        }
        entries.removeIf(entry -> entry.totalCount() <= 0);
        entries.sort((left, right) -> {
            int killCompare = Integer.compare(right.killCount(), left.killCount());
            if (killCompare != 0) {
                return killCompare;
            }
            int deathCompare = Integer.compare(left.deathCount(), right.deathCount());
            if (deathCompare != 0) {
                return deathCompare;
            }
            return resolvePlayerName(left.playerId()).compareToIgnoreCase(resolvePlayerName(right.playerId()));
        });
        return entries;
    }

    private int getKillCount(UUID playerId) {
        return eliminatedPiecesByPlayer.getOrDefault(playerId, 0);
    }

    private int getDeathCount(UUID playerId) {
        return ownPiecesEliminatedByPlayer.getOrDefault(playerId, 0);
    }

    private Component createKillRankingLine(int rank, UUID playerId, int killCount, int deathCount) {
        TeamType teamType = playerTeamMap.get(playerId);
        NamedTextColor nameColor = teamType == null ? NamedTextColor.WHITE : teamType.getColor();
        String playerName = resolvePlayerName(playerId);
        String rankLabel = rank + ".";
        return Component.text()
            .append(Component.text(rankLabel + " ", getRankColor(rank)))
            .append(Component.text(playerName, nameColor))
            .append(Component.text(createDotLeader(rankLabel, playerName, killCount, deathCount), NamedTextColor.DARK_GRAY))
            .append(Component.text(killCount + " 킬", NamedTextColor.YELLOW))
            .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
            .append(Component.text(deathCount + " 팀킬", NamedTextColor.RED))
            .build();
    }

    private String resolvePlayerName(UUID playerId) {
        Player onlinePlayer = plugin.getServer().getPlayer(playerId);
        if (onlinePlayer != null) {
            return onlinePlayer.getName();
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        String playerName = offlinePlayer.getName();
        return playerName == null ? "알 수 없음" : playerName;
    }

    private NamedTextColor getRankColor(int rank) {
        return switch (rank) {
            case 1 -> NamedTextColor.YELLOW;
            case 2 -> NamedTextColor.GRAY;
            case 3 -> NamedTextColor.GOLD;
            default -> NamedTextColor.GOLD;
        };
    }

    private String createDotLeader(String rankLabel, String playerName, int killCount, int deathCount) {
        return " ..... ";
    }

    private record PlayerEliminationStats(UUID playerId, int killCount, int deathCount) {
        public int totalCount() {
            return killCount + deathCount;
        }
    }

    private record TurnCameraState(Location location, boolean allowFlight, boolean flying, float flySpeed, double fixedY) {
    }

    private record FlightState(boolean allowFlight, boolean flying, float flySpeed) {
    }

    private void playResultSound(@Nullable TeamType winner) {
        if (winner == null) {
            playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BELL, 0.9F, 0.9F);
            playSoundToParticipants(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7F, 1.35F);
            return;
        }

        playSoundToParticipants(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.1F, 1.0F);
        playSoundToParticipants(Sound.ENTITY_PLAYER_LEVELUP, 0.9F, winner == TeamType.BLUE ? 0.95F : 1.15F);
    }

    private void playSoundToParticipants(Sound sound, float volume, float pitch) {
        for (UUID participantId : participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                continue;
            }

            participant.playSound(participant.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }

    private void startGameMusic() {
        stopGameMusic();
        refillGameMusicQueue();
        playNextGameMusic();
    }

    private void scheduleNextGameMusic() {
        if (gameMusicGapTask != null) {
            gameMusicGapTask.cancel();
            gameMusicGapTask = null;
        }
        if (currentGameMusicSoundKey != null) {
            stopGameMusicSound(currentGameMusicSoundKey);
            currentGameMusicSoundKey = null;
        }

        long gapTicks = Math.max(0L, getGameMusicGapSeconds() * 20L);
        gameMusicGapTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            gameMusicGapTask = null;
            if (gameState != GameState.WAITING && gameState != GameState.ENDING) {
                playNextGameMusic();
            }
        }, gapTicks);
    }

    private void playNextGameMusic() {
        String soundKey = pollNextGameMusicSoundKey();
        if (currentGameMusicSoundKey != null && !currentGameMusicSoundKey.equals(soundKey)) {
            stopGameMusicSound(currentGameMusicSoundKey);
        }
        currentGameMusicSoundKey = soundKey;
        float volume = getGameMusicVolume();
        Component musicTitle = Component.text(GAME_MUSIC_ANNOUNCE_PREFIX + getGameMusicTitle(soundKey), NamedTextColor.AQUA);
        for (UUID participantId : participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                continue;
            }

            participant.playSound(participant.getLocation(), soundKey, SoundCategory.RECORDS, volume, 1.0F);
            participant.sendMessage(musicTitle);
        }
        long durationTicks = Math.max(20L, getGameMusicDurationSeconds(soundKey) * 20L);
        gameMusicTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::scheduleNextGameMusic, durationTicks);
    }

    private String pollNextGameMusicSoundKey() {
        if (gameMusicQueue.isEmpty()) {
            refillGameMusicQueue();
        }
        if (gameMusicQueue.isEmpty()) {
            return getGameMusicSoundKey();
        }
        String soundKey = gameMusicQueue.remove(0);
        gameMusicQueue.add(soundKey);
        return soundKey;
    }

    private void refillGameMusicQueue() {
        gameMusicQueue.clear();
        gameMusicQueue.addAll(getGameMusicSoundKeys());
        Collections.shuffle(gameMusicQueue);
    }

    private void stopGameMusic() {
        if (gameMusicTask != null) {
            gameMusicTask.cancel();
            gameMusicTask = null;
        }
        if (gameMusicGapTask != null) {
            gameMusicGapTask.cancel();
            gameMusicGapTask = null;
        }

        for (String soundKey : getGameMusicSoundKeys()) {
            stopGameMusicSound(soundKey);
        }
        if (currentGameMusicSoundKey != null) {
            stopGameMusicSound(currentGameMusicSoundKey);
            currentGameMusicSoundKey = null;
        }
        gameMusicQueue.clear();
    }

    private void stopGameMusicSound(String soundKey) {
        for (UUID participantId : participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                continue;
            }

            participant.stopSound(soundKey, SoundCategory.RECORDS);
        }
    }

    private void stopGameMusicForPlayer(Player player) {
        for (String soundKey : getGameMusicSoundKeys()) {
            player.stopSound(soundKey, SoundCategory.RECORDS);
        }
        if (currentGameMusicSoundKey != null) {
            player.stopSound(currentGameMusicSoundKey, SoundCategory.RECORDS);
        }
    }

    private String getGameMusicSoundKey() {
        return plugin.getConfig().getString("music.sound-key", GAME_MUSIC_SOUND_KEY);
    }

    private List<String> getGameMusicSoundKeys() {
        List<String> trackSoundKeys = plugin.getConfig().getMapList("music.tracks").stream()
            .map(track -> track.get("sound-key"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(String::trim)
            .filter(soundKey -> !soundKey.isEmpty())
            .toList();
        if (!trackSoundKeys.isEmpty()) {
            return trackSoundKeys;
        }

        List<String> soundKeys = plugin.getConfig().getStringList("music.sound-keys").stream()
            .map(String::trim)
            .filter(soundKey -> !soundKey.isEmpty())
            .toList();
        if (!soundKeys.isEmpty()) {
            return soundKeys;
        }
        if (GAME_MUSIC_SOUND_KEY.equals(getGameMusicSoundKey())) {
            return DEFAULT_GAME_MUSIC_SOUND_KEYS;
        }
        return List.of(getGameMusicSoundKey());
    }

    private String getGameMusicTitle(String soundKey) {
        for (Map<?, ?> track : plugin.getConfig().getMapList("music.tracks")) {
            Object trackSoundKey = track.get("sound-key");
            if (!(trackSoundKey instanceof String trackSoundKeyText) || !soundKey.equals(trackSoundKeyText.trim())) {
                continue;
            }

            Object title = track.get("title");
            if (title instanceof String titleText && !titleText.trim().isEmpty()) {
                return titleText.trim();
            }
            return soundKey;
        }

        ConfigurationSection titlesSection = plugin.getConfig().getConfigurationSection("music.titles");
        if (titlesSection == null) {
            return soundKey;
        }

        Object title = titlesSection.getValues(false).get(soundKey);
        if (title == null) {
            return soundKey;
        }
        String titleText = title.toString().trim();
        return titleText.isEmpty() ? soundKey : titleText;
    }

    private float getGameMusicVolume() {
        return (float) plugin.getConfig().getDouble("music.volume", GAME_MUSIC_VOLUME);
    }

    private long getGameMusicLoopSeconds() {
        return plugin.getConfig().getLong("music.loop-seconds", GAME_MUSIC_LOOP_SECONDS);
    }

    private long getGameMusicDurationSeconds(String soundKey) {
        for (Map<?, ?> track : plugin.getConfig().getMapList("music.tracks")) {
            Object trackSoundKey = track.get("sound-key");
            if (!(trackSoundKey instanceof String trackSoundKeyText) || !soundKey.equals(trackSoundKeyText.trim())) {
                continue;
            }

            Object duration = track.get("duration-seconds");
            if (duration instanceof Number durationNumber) {
                return Math.max(1L, durationNumber.longValue());
            }
            if (duration instanceof String durationText) {
                try {
                    return Math.max(1L, Long.parseLong(durationText.trim()));
                } catch (NumberFormatException ignored) {
                    return getGameMusicLoopSeconds();
                }
            }
            return getGameMusicLoopSeconds();
        }
        return getGameMusicLoopSeconds();
    }

    private long getGameMusicGapSeconds() {
        return plugin.getConfig().getLong("music.gap-seconds", GAME_MUSIC_GAP_SECONDS);
    }

    private boolean isPlacementComplete() {
        return getPlacedCount(TeamType.BLUE) >= configuredPieceCount
            && getPlacedCount(TeamType.RED) >= configuredPieceCount;
    }

    private void sendToLobby(Player player) {
        Location lobbyLocation = arenaData.getLobbyLocation();
        if (lobbyLocation != null) {
            player.teleport(lobbyLocation);
        }
    }

    private void sendAllOnlinePlayersToLobby() {
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            sendToLobby(onlinePlayer);
        }
    }

    private void applySpectatorStateToNonParticipants() {
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            if (participants.contains(onlinePlayer.getUniqueId())) {
                clearSpectatorState(onlinePlayer);
                continue;
            }

            restoreOriginalArmor(onlinePlayer);
            applySpectatorState(onlinePlayer);
            sendToLobby(onlinePlayer);
            refreshPlayerFormatting(onlinePlayer);
        }
    }

    private void syncWaitingParticipants() {
        if (gameState != GameState.WAITING) {
            return;
        }

        participants.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            participants.add(player.getUniqueId());
        }
    }

    private void applyRemoteControlMode(Player player) {
        inventoryBackupMap.putIfAbsent(player.getUniqueId(), player.getInventory().getContents().clone());
        heldSlotBackupMap.putIfAbsent(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        backupFlightState(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(TURN_CAMERA_FLY_SPEED);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setSilent(true);
        player.getInventory().clear();
        applyTurnBuff(player);
        enforceTeamArmor(player);
    }

    private void giveRemoteController(Player player) {
        player.getInventory().setItem(4, createRemoteController());
        player.getInventory().setHeldItemSlot(4);
    }

    private void clearControllersForParticipants() {
        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }
            player.getInventory().setItem(4, null);
        }
    }

    private void refreshTurnIndicators() {
        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }

            player.setGlowing(currentTurnPlayer != null && currentTurnPlayer.equals(participantId));
        }
    }

    public void sendTurnStatusActionBar(Player player) {
        NamedTextColor color = isCurrentTurnPlayer(player.getUniqueId())
            ? NamedTextColor.YELLOW
            : getPlayerTeam(player.getUniqueId()) == null
                ? NamedTextColor.GRAY
                : NamedTextColor.GOLD;
        player.sendActionBar(Component.text(getTurnStatusText(player.getUniqueId()), color));
    }

    private void showCurrentTurnTitle(Player player) {
        player.showTitle(Title.title(
            Component.text("당신의 차례입니다", NamedTextColor.YELLOW),
            Component.empty(),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(300))
        ));
    }

    private String formatTeamDisplayName(TeamType teamType) {
        String name = teamType.getDisplayName();
        return name.endsWith("팀") ? name : name + "팀";
    }

    private int calculateTurnsRemaining(UUID participantId, TeamType teamType, List<UUID> currentQueue, List<UUID> oppositeQueue) {
        if (currentTurnPlayer != null && currentTurnPlayer.equals(participantId)) {
            return 0;
        }

        if (teamType == currentTurnTeam) {
            int index = currentQueue.indexOf(participantId);
            return index < 0 ? -1 : (index * 2) + 2;
        }

        int index = oppositeQueue.indexOf(participantId);
        return index < 0 ? -1 : (index * 2) + 1;
    }

    private void clearTurnIndicators() {
        for (UUID participantId : participants) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }

            player.setGlowing(false);
            removeTurnBuff(player);
            removeTurnCameraInvisibility(player);
            player.sendActionBar(Component.empty());
        }
    }

    private void clearAllPlayerInventories() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            inventoryBackupMap.remove(player.getUniqueId());
            heldSlotBackupMap.remove(player.getUniqueId());
            player.getInventory().clear();
            removeTurnBuff(player);
            removeTurnCameraInvisibility(player);
            restoreFlightState(player);
            restoreOriginalArmor(player);
        }
    }

    private void clearRemoteControlMode(Player player) {
        restoreTurnCamera(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        restoreFlightState(player);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setSilent(false);
        player.setGlowing(false);
        removeTurnBuff(player);
        removeTurnCameraInvisibility(player);

        ItemStack[] contents = inventoryBackupMap.remove(player.getUniqueId());
        if (contents != null) {
            player.getInventory().setContents(contents);
        }
        Integer heldSlot = heldSlotBackupMap.remove(player.getUniqueId());
        if (heldSlot != null) {
            player.getInventory().setHeldItemSlot(heldSlot);
        }
        removeRemoteControllerItems(player);
        enforceTeamArmor(player);
        if (playerTeamMap.get(player.getUniqueId()) == null && gameState != GameState.WAITING) {
            applySpectatorState(player);
        } else {
            clearSpectatorState(player);
        }
    }

    private void removeRemoteControllerItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (!isRemoteController(contents[i])) {
                continue;
            }
            contents[i] = null;
            changed = true;
        }
        if (changed) {
            player.getInventory().setContents(contents);
            player.updateInventory();
        }
    }

    private void applyTurnBuff(Player player) {
        player.addPotionEffect(TURN_SPEED_EFFECT);
    }

    private void removeTurnBuff(Player player) {
        player.removePotionEffect(PotionEffectType.SPEED);
    }

    private void applyTurnCameraInvisibility(Player player) {
        player.addPotionEffect(SPECTATOR_INVISIBILITY_EFFECT);
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.setGlowing(false);
        player.updateInventory();
    }

    private void removeTurnCameraInvisibility(Player player) {
        if (playerTeamMap.get(player.getUniqueId()) == null && gameState != GameState.WAITING) {
            return;
        }

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        enforceTeamArmor(player);
        player.setGlowing(isCurrentTurnPlayer(player.getUniqueId()));
    }

    private void scheduleNextTurn(UUID finishedTurnPlayer) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            stopLaunchCameraFollow();
            restoreTurnCamera(finishedTurnPlayer);
            Player finishedPlayer = plugin.getServer().getPlayer(finishedTurnPlayer);
            if (finishedPlayer != null) {
                removeTurnCameraInvisibility(finishedPlayer);
            }
            startNextTurn();
        }, NEXT_TURN_DELAY_TICKS);
    }

    private void scheduleEndGame(@Nullable TeamType winner) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> endGame(winner), GAME_END_DELAY_TICKS);
    }

    private void backupFlightState(Player player) {
        flightStateMap.putIfAbsent(player.getUniqueId(), new FlightState(
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed()
        ));
    }

    private void restoreFlightState(Player player) {
        FlightState state = flightStateMap.remove(player.getUniqueId());
        if (state == null) {
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setFlySpeed(0.1F);
            return;
        }

        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.flying());
        player.setFlySpeed(state.flySpeed());
    }

    public @Nullable Location enforceTurnCameraY(Player player, Location targetLocation) {
        TurnCameraState state = turnCameraStateMap.get(player.getUniqueId());
        if (state == null || selectedPiece != null || boardManager.isActionRunning()) {
            return null;
        }
        if (Math.abs(targetLocation.getY() - state.fixedY()) <= 0.0001D) {
            return null;
        }

        Location fixedLocation = targetLocation.clone();
        fixedLocation.setY(state.fixedY());
        return fixedLocation;
    }

    public void noteLaunchCameraRotation(Player player, Location from, Location to) {
        if (launchCameraTask == null || !boardManager.isActionRunning()) {
            return;
        }
        if (Math.abs(from.getYaw() - to.getYaw()) <= 0.001F
                && Math.abs(from.getPitch() - to.getPitch()) <= 0.001F) {
            return;
        }

        launchCameraRotationGraceTicks.put(player.getUniqueId(), 2);
    }

    private void enterTurnCamera(Player player) {
        UUID playerId = player.getUniqueId();
        Location cameraLocation = resolveTurnCameraLocation(player);
        turnCameraStateMap.putIfAbsent(playerId, new TurnCameraState(
                player.getLocation().clone(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                cameraLocation.getY()
        ));

        player.teleport(cameraLocation);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(TURN_CAMERA_FLY_SPEED);
    }

    private void moveTurnCameraToSelectedPiece(Player player, PieceData pieceData) {
        Location currentLocation = player.getLocation();
        Location cameraLocation = createSelectedPieceCameraLocation(pieceData, 0.0D);
        cameraLocation.setYaw(currentLocation.getYaw());
        cameraLocation.setPitch(SELECTED_PIECE_CAMERA_PITCH);
        player.teleport(cameraLocation);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.0F);
    }

    public void setSelectedPieceCameraLift(Player player, boolean lifted) {
        if (gameState != GameState.PLAYING
                || !isCurrentTurnPlayer(player.getUniqueId())
                || selectedPiece == null
                || boardManager.isActionRunning()) {
            selectedCameraLiftedMap.remove(player.getUniqueId());
            return;
        }

        selectedCameraLiftedMap.put(player.getUniqueId(), lifted);
    }

    private void updateSelectedPieceCameraLift(Player player) {
        if (gameState != GameState.PLAYING
                || !isCurrentTurnPlayer(player.getUniqueId())
                || selectedPiece == null
                || boardManager.isActionRunning()) {
            return;
        }

        boolean lifted = selectedCameraLiftedMap.getOrDefault(player.getUniqueId(), false);
        double liftOffset = lifted ? SELECTED_PIECE_CAMERA_JUMP_Y_OFFSET : 0.0D;
        Location currentLocation = player.getLocation();
        Location targetLocation = createSelectedPieceCameraLocation(selectedPiece, liftOffset);

        double yDelta = targetLocation.getY() - currentLocation.getY();
        if (Math.abs(yDelta) <= SELECTED_PIECE_CAMERA_LIFT_STOP_DISTANCE) {
            player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            return;
        }

        double yVelocity = Math.max(
                -SELECTED_PIECE_CAMERA_MAX_LIFT_VELOCITY,
                Math.min(SELECTED_PIECE_CAMERA_MAX_LIFT_VELOCITY, yDelta * SELECTED_PIECE_CAMERA_LIFT_VELOCITY_SCALE)
        );
        player.setVelocity(new Vector(0.0D, yVelocity, 0.0D));
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.0F);
    }

    private Location createSelectedPieceCameraLocation(PieceData pieceData, double liftOffset) {
        return pieceData.getLocation().clone().add(
                0.0D,
                getSelectedPieceCameraYOffset(pieceData) + liftOffset,
                0.0D
        );
    }

    private double getSelectedPieceCameraYOffset(PieceData pieceData) {
        return SELECTED_PIECE_CAMERA_Y_OFFSET
            + (pieceData.getPieceSize() * 0.15D)
            + getPieceHeightCameraDelta(pieceData);
    }

    private double getLaunchCameraYOffset(PieceData pieceData) {
        return LAUNCH_CAMERA_Y_OFFSET
            + (pieceData.getPieceSize() * 0.12D)
            + getPieceHeightCameraDelta(pieceData);
    }

    private double getPieceHeightCameraDelta(PieceData pieceData) {
        return getPieceVisualTopYOffset(pieceData.getHeightScale()) - getPieceVisualTopYOffset(1.0D);
    }

    private double getPieceVisualTopYOffset(double pieceHeightScale) {
        double defaultDisplayHeightScale = DEFAULT_PIECE_SIZE * DISPLAY_HEIGHT_SCALE_MULTIPLIER;
        double displayHeightScale = defaultDisplayHeightScale * Math.max(0.1D, pieceHeightScale);
        double baseLift = Math.max(3.1D, DEFAULT_PIECE_SIZE * 0.635D);
        double modelBottomOffset = 0.5D - (PIECE_MODEL_MIN_Y / MODEL_UNIT_SIZE);
        double modelTopOffset = (PIECE_MODEL_MAX_Y / MODEL_UNIT_SIZE) - 0.5D;
        double lift = baseLift + ((displayHeightScale - defaultDisplayHeightScale) * modelBottomOffset);
        return -PIECE_DISPLAY_Y_OFFSET + lift + (modelTopOffset * displayHeightScale);
    }

    private void startSelectedCameraLiftTask(Player player) {
        stopSelectedCameraLiftTask();
        selectedCameraLiftedMap.put(player.getUniqueId(), false);
        selectedCameraLiftTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()
                    || selectedPiece == null
                    || !isCurrentTurnPlayer(player.getUniqueId())
                    || boardManager.isActionRunning()) {
                stopSelectedCameraLiftTask();
                return;
            }

            updateSelectedPieceCameraLift(player);
        }, 1L, 1L);
    }

    private void stopSelectedCameraLiftTask() {
        if (selectedCameraLiftTask == null) {
            return;
        }

        selectedCameraLiftTask.cancel();
        selectedCameraLiftTask = null;
    }

    private void restoreSelectedCameraReturnLocation(Player player) {
        Location returnLocation = selectedCameraReturnLocationMap.remove(player.getUniqueId());
        if (returnLocation == null) {
            return;
        }

        player.teleport(returnLocation);
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    private void startLaunchCameraFollow(Player player, PieceData pieceData) {
        stopLaunchCameraFollow();
        player.setFlySpeed(0.0F);
        launchCameraVehicle = spawnLaunchCameraVehicle(player.getLocation());
        if (launchCameraVehicle != null) {
            launchCameraVehicle.addPassenger(player);
            player.setFlying(false);
        }
        launchCameraTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopLaunchCameraFollow();
                return;
            }

            moveTurnCameraAboveLaunchedPiece(player, pieceData);
        }, 1L, 1L);
    }

    private ArmorStand spawnLaunchCameraVehicle(Location location) {
        if (location.getWorld() == null) {
            return null;
        }

        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setSmall(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setCollidable(false);
        });
    }

    private void moveTurnCameraAboveLaunchedPiece(Player player, PieceData pieceData) {
        Location currentLocation = launchCameraVehicle != null && launchCameraVehicle.isValid()
                ? launchCameraVehicle.getLocation()
                : player.getLocation();
        Location targetLocation = pieceData.getLocation().clone()
                .add(0.0D, getLaunchCameraYOffset(pieceData), 0.0D);

        Location nextLocation = targetLocation.clone();
        nextLocation.setYaw(currentLocation.getYaw());
        nextLocation.setPitch(currentLocation.getPitch());
        if (launchCameraVehicle != null && launchCameraVehicle.isValid()) {
            launchCameraVehicle.teleport(nextLocation);
        } else {
            player.teleport(nextLocation);
        }
        player.setAllowFlight(true);
        player.setFlySpeed(0.0F);
    }

    private void stopLaunchCameraFollow() {
        if (launchCameraTask == null) {
            return;
        }

        launchCameraTask.cancel();
        launchCameraTask = null;
        launchCameraRotationGraceTicks.clear();
        if (launchCameraVehicle != null) {
            if (launchCameraVehicle.isValid()) {
                launchCameraVehicle.eject();
                launchCameraVehicle.remove();
            }
            launchCameraVehicle = null;
        }
    }

    private Location resolveTurnCameraLocation(Player player) {
        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        Location teamLocation = teamType == null ? null : arenaData.getPlacementLocation(teamType);
        if (teamLocation != null) {
            return teamLocation;
        }
        return player.getLocation().clone();
    }

    private void restoreTurnCamera(UUID playerId) {
        TurnCameraState state = turnCameraStateMap.remove(playerId);
        if (state == null) {
            return;
        }

        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return;
        }

        player.teleport(state.location());
        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.flying());
        player.setFlySpeed(state.flySpeed());
    }

    private void restoreAllTurnCameras() {
        for (UUID playerId : new ArrayList<>(turnCameraStateMap.keySet())) {
            restoreTurnCamera(playerId);
        }
        turnCameraStateMap.clear();
    }

    /**
     * 플레이어가 현재 리모컨 아이템을 들고 있는지 확인한다.
     */
    public boolean isUsingRemoteController(Player player) {
        return isRemoteController(player.getInventory().getItemInMainHand());
    }

    /**
     * 아이템이 알까기 리모컨인지 판별한다.
     */
    public boolean isRemoteController(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        return meta.getPersistentDataContainer().has(
            new NamespacedKey(plugin, REMOTE_CONTROLLER_KEY),
            PersistentDataType.BYTE
        );
    }

    /**
     * 아이템이 팀 전용 보호 장비인지 판별한다.
     */
    public boolean isTeamArmor(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        return meta.getPersistentDataContainer().has(
            new NamespacedKey(plugin, TEAM_ARMOR_KEY),
            PersistentDataType.BYTE
        );
    }

    private ItemStack createRemoteController() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("원격 말 컨트롤러", NamedTextColor.AQUA));
            meta.lore(List.of(
                Component.text("멀리서 말을 바라보고 우클릭해 주세요.", NamedTextColor.GRAY)
            ));
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, REMOTE_CONTROLLER_KEY),
                PersistentDataType.BYTE,
                (byte) 1
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private void enforceTeamArmor(Player player) {
        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        if (teamType == null) {
            return;
        }

        equipTeamArmor(player, teamType);
    }

    private void equipTeamArmor(Player player, TeamType teamType) {
        armorBackupMap.putIfAbsent(player.getUniqueId(), player.getInventory().getArmorContents().clone());
        player.getInventory().setChestplate(createTeamArmorPiece(Material.LEATHER_CHESTPLATE, teamType));
        player.updateInventory();
    }

    private void restoreOriginalArmor(Player player) {
        ItemStack[] armorContents = armorBackupMap.remove(player.getUniqueId());
        if (armorContents != null) {
            player.getInventory().setArmorContents(armorContents);
            player.updateInventory();
            return;
        }

        player.getInventory().setArmorContents(new ItemStack[4]);
        player.updateInventory();
    }

    private ItemStack createTeamArmorPiece(Material material, TeamType teamType) {
        ItemStack item = new ItemStack(material);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof LeatherArmorMeta meta)) {
            return item;
        }

        meta.setColor(teamType == TeamType.BLUE ? Color.fromRGB(40, 95, 255) : Color.fromRGB(230, 45, 45));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, TEAM_ARMOR_KEY),
            PersistentDataType.BYTE,
            (byte) 1
        );
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 비참가 플레이어를 관전자처럼 보이도록 비행 및 은신 상태로 전환한다.
     */
    public void applySpectatorState(Player player) {
        if (playerTeamMap.get(player.getUniqueId()) != null) {
            return;
        }

        backupFlightState(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(TURN_CAMERA_FLY_SPEED);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.updateInventory();
        player.addPotionEffect(SPECTATOR_INVISIBILITY_EFFECT);
        applyTurnBuff(player);
    }

    /**
     * 관전자 보조 상태를 해제하고 일반 플레이어 상태로 돌린다.
     */
    public void clearSpectatorState(Player player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (playerTeamMap.get(player.getUniqueId()) != null) {
            return;
        }

        removeTurnBuff(player);
        restoreFlightState(player);
        player.setInvulnerable(false);
        player.setCollidable(true);
    }
}
