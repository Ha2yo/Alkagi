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
    private static final String TAB_TEAM_BLACK = "00_alkagi_black";
    private static final String TAB_TEAM_WHITE = "01_alkagi_white";
    private static final String TAB_TEAM_SPECTATOR = "02_alkagi_spectator";

    private static final long LAUNCH_GUARD_MILLIS = 500L;
    private static final String GAME_MUSIC_SOUND_KEY = "alkagi.ingame";
    private static final float GAME_MUSIC_VOLUME = 0.1225F;
    private static final long GAME_MUSIC_LOOP_SECONDS = 120L;
    private static final NamedTextColor DEFAULT_PLAYER_COLOR = NamedTextColor.BLUE;

    private static final PotionEffect TURN_SPEED_EFFECT =
        new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 4, false, false, false);
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
    private final Map<UUID, Integer> benchGameStreaks = new java.util.HashMap<>();

    private final Map<UUID, ItemStack[]> inventoryBackupMap = new java.util.HashMap<>();
    private final Map<UUID, ItemStack[]> armorBackupMap = new java.util.HashMap<>();
    private final Map<UUID, Integer> heldSlotBackupMap = new java.util.HashMap<>();

    private final BossBar turnTimerBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);

    private GameState gameState = GameState.WAITING;
    private TeamType currentTurnTeam = TeamType.BLACK;
    private UUID currentTurnPlayer;
    private int configuredPieceCount;
    private PieceData selectedPiece;
    private UUID lastSelectedPlayerId;
    private long lastSelectedAtMillis;
    private int remainingTurnSeconds;
    private boolean openingTurnTeleportDone;
    private BukkitTask turnTimerTask;
    private BukkitTask gameMusicTask;

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
        teamDataMap.put(TeamType.BLACK, new TeamData(TeamType.BLACK));
        teamDataMap.put(TeamType.WHITE, new TeamData(TeamType.WHITE));
        placedCountMap.put(TeamType.BLACK, 0);
        placedCountMap.put(TeamType.WHITE, 0);
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

        List<UUID> selectedParticipants = selectParticipantsForNextGame(availableParticipants, selectedPlayerCount);
        participants.clear();
        participants.addAll(selectedParticipants);
        updateBenchGameStreaks(availableParticipants, selectedParticipants);

        configuredPieceCount = pieceCount;
        gameState = GameState.TEAM_ASSIGNING;
        assignTeams();
        applySpectatorStateToNonParticipants();
        selectPlacementPlayers();
        startPlacingPhase();
        return true;
    }

    /**
     * 진행 중인 게임을 종료 상태로 전환하고 잠시 뒤 전체 상태를 리셋한다.
     */
    public void stop() {
        stopTurnTimer();
        stopGameMusic();
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
        gameState = GameState.WAITING;
        currentTurnPlayer = null;
        currentTurnTeam = TeamType.BLACK;
        configuredPieceCount = 0;
        selectedPiece = null;
        lastSelectedPlayerId = null;
        lastSelectedAtMillis = 0L;
        openingTurnTeleportDone = false;
        playerTeamMap.clear();
        placementPlayers.clear();
        placedCountMap.put(TeamType.BLACK, 0);
        placedCountMap.put(TeamType.WHITE, 0);
        eliminatedPiecesByPlayer.clear();

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
            clearSpectatorState(onlinePlayer);
            sendToLobby(onlinePlayer);
        }

        clearTurnIndicators();
        scoreboardManager.clearAll();
    }

    /**
     * 현재 참가자들을 섞어서 흑팀과 백팀으로 균형 있게 나눈다.
     */
    public void assignTeams() {
        playerTeamMap.clear();
        teamDataMap.values().forEach(TeamData::clearPlayers);

        List<UUID> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled);

        int blackSize = 0;
        int whiteSize = 0;
        int targetBlackSize = shuffled.size() / 2;
        int targetWhiteSize = shuffled.size() - targetBlackSize;
        for (UUID playerId : shuffled) {
            TeamType teamType;
            if (blackSize >= targetBlackSize) {
                teamType = TeamType.WHITE;
            } else if (whiteSize >= targetWhiteSize) {
                teamType = TeamType.BLACK;
            } else if (blackSize <= whiteSize) {
                teamType = TeamType.BLACK;
            } else {
                teamType = TeamType.WHITE;
            }
            playerTeamMap.put(playerId, teamType);
            teamDataMap.get(teamType).addPlayer(playerId);
            if (teamType == TeamType.BLACK) {
                blackSize++;
            } else {
                whiteSize++;
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
        placedCountMap.put(TeamType.BLACK, 0);
        placedCountMap.put(TeamType.WHITE, 0);

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

        startGameMusic();
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
            endGame(currentTurnTeam.opposite());
            return;
        }

        currentTurnPlayer = next;
        scoreboardManager.updateGameBoard(this);
        refreshTurnIndicators();
        Player player = plugin.getServer().getPlayer(next);
        if (player != null) {
            if (!openingTurnTeleportDone) {
                Location spectatorLocation = arenaData.getSpectatorLocation();
                if (spectatorLocation != null) {
                    player.teleport(spectatorLocation);
                }
                openingTurnTeleportDone = true;
            }
            applyTurnBuff(player);
            giveRemoteController(player);
            showCurrentTurnTitle(player);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.35F);
            player.sendMessage(Component.text("지금 당신 차례입니다. 블레이즈 막대로 말을 우클릭해 주세요.", NamedTextColor.YELLOW));
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
        TeamData currentTeamData = teamDataMap.get(currentTurnTeam);
        currentTeamData.pushBackPlayer(currentTurnPlayer);
        currentTurnPlayer = null;
        selectedPiece = null;

        if (isDraw()) {
            endGame(null);
            return;
        }

        TeamType winner = checkWinner();
        if (winner != null) {
            endGame(winner);
            return;
        }

        currentTurnTeam = currentTurnTeam.opposite();
        startNextTurn();
    }

    /**
     * 현재 살아남은 말 수를 기준으로 승리 팀을 판정한다.
     */
    public @Nullable TeamType checkWinner() {
        if (teamDataMap.get(TeamType.BLACK).getAlivePieceCount() <= 0 && configuredPieceCount > 0) {
            return TeamType.WHITE;
        }
        if (teamDataMap.get(TeamType.WHITE).getAlivePieceCount() <= 0 && configuredPieceCount > 0) {
            return TeamType.BLACK;
        }
        return null;
    }

    private boolean isDraw() {
        return configuredPieceCount > 0
            && teamDataMap.get(TeamType.BLACK).getAlivePieceCount() <= 0
            && teamDataMap.get(TeamType.WHITE).getAlivePieceCount() <= 0;
    }

    /**
     * 승자 정보를 알리고 결과 화면을 보여 준 뒤 게임을 정리한다.
     */
    public void endGame(
            @Nullable TeamType winner
    ) {
        stopTurnTimer();
        stopGameMusic();
        gameState = GameState.ENDING;
        clearAllPlayerInventories();
        sendAllOnlinePlayersToLobby();
        scoreboardManager.showResult(this, winner);
        broadcastWinnerTitle(winner);
        resetAfterDelay();
    }

    /**
     * 게임 중 오프라인이 된 플레이어를 참가 목록과 턴 흐름에서 제거한다.
     */
    public void removeOfflinePlayer(
            UUID playerId
    ) {
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

    public boolean isPlacementPhase() {
        return gameState == GameState.PLACING;
    }

    public boolean isPlayingPhase() {
        return gameState == GameState.PLAYING;
    }

    public int getPlacedCount(TeamType teamType) {
        return placedCountMap.getOrDefault(teamType, 0);
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

        selectedPiece = pieceData;
        lastSelectedPlayerId = player.getUniqueId();
        lastSelectedAtMillis = System.currentTimeMillis();
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
        return true;
    }

    /**
     * 선택된 말을 목표 지점을 향해 발사하고 물리 처리 종료 후 턴을 넘긴다.
     */
    public boolean launchSelectedPiece(
            Player player,
            Location targetLocation
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
        int opponentAliveBefore = teamDataMap.get(targetTeam).getAlivePieceCount();
        playSoundToParticipants(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9F, 1.15F);
        stopTurnTimer();
        boardManager.launchPiece(selectedPiece, targetLocation, teamDataMap, () -> {
            int opponentAliveAfter = teamDataMap.get(targetTeam).getAlivePieceCount();
            int eliminatedCount = Math.max(0, opponentAliveBefore - opponentAliveAfter);
            if (eliminatedCount > 0) {
                eliminatedPiecesByPlayer.merge(actingPlayerId, eliminatedCount, Integer::sum);
            }
            endTurn();
        });
        return true;
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

        Scoreboard scoreboard = scoreboardManager.getMainScoreboard();
        String entry = player.getName();
        removeEntryFromTabTeams(scoreboard, entry);
        getOrCreateTabTeam(scoreboard, teamType).addEntry(entry);
        player.setScoreboard(scoreboard);
    }

    private void removeEntryFromTabTeams(Scoreboard scoreboard, String entry) {
        for (String teamName : List.of(TAB_TEAM_BLACK, TAB_TEAM_WHITE, TAB_TEAM_SPECTATOR)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private Team getOrCreateTabTeam(Scoreboard scoreboard, @Nullable TeamType teamType) {
        String teamName = switch (teamType) {
            case BLACK -> TAB_TEAM_BLACK;
            case WHITE -> TAB_TEAM_WHITE;
            case null -> TAB_TEAM_SPECTATOR;
        };

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
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
        plugin.getServer().getScheduler().runTaskLater(plugin, this::reset, 100L);
    }

    private void decideOpeningTeam() {
        TeamType finalTeam = Math.random() < 0.5D ? TeamType.BLACK : TeamType.WHITE;
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
        Component subtitle = createSharedMvpSubtitle();
        if (winner == null) {
            title = Title.title(
                Component.text("\uBB34\uC2B9\uBD80\uC785\uB2C8\uB2E4!", NamedTextColor.YELLOW),
                subtitle,
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))
            );
        } else {
            title = Title.title(
                Component.text(winner.getDisplayName() + " 승리!", winner.getColor()),
                subtitle,
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))
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

    private Component createSharedMvpSubtitle() {
        List<UUID> mvpPlayerIds = getSharedMvpPlayerIds();
        if (mvpPlayerIds.isEmpty()) {
            return Component.empty();
        }

        int eliminatedCount = eliminatedPiecesByPlayer.getOrDefault(mvpPlayerIds.get(0), 0);
        var subtitle = Component.text();
        subtitle.append(Component.text(mvpPlayerIds.size() > 1 ? "공동 MVP: " : "MVP: ", NamedTextColor.YELLOW));

        for (int index = 0; index < mvpPlayerIds.size(); index++) {
            UUID playerId = mvpPlayerIds.get(index);
            Player mvpPlayer = plugin.getServer().getPlayer(playerId);
            String playerName = mvpPlayer == null ? "알 수 없음" : mvpPlayer.getName();
            TeamType teamType = playerTeamMap.get(playerId);
            NamedTextColor color = teamType == null ? NamedTextColor.AQUA : teamType.getColor();
            if (index > 0) {
                subtitle.append(Component.text(", ", NamedTextColor.GRAY));
            }
            subtitle.append(Component.text(playerName, color));
        }

        subtitle.append(Component.text(" (" + eliminatedCount + "개)", NamedTextColor.WHITE));
        return subtitle.build();
    }

    private List<UUID> getSharedMvpPlayerIds() {
        int maxEliminatedCount = 0;
        for (Map.Entry<UUID, Integer> entry : eliminatedPiecesByPlayer.entrySet()) {
            if (entry.getValue() > maxEliminatedCount) {
                maxEliminatedCount = entry.getValue();
            }
        }
        if (maxEliminatedCount <= 0) {
            return List.of();
        }

        List<UUID> mvpPlayerIds = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : eliminatedPiecesByPlayer.entrySet()) {
            if (entry.getValue() == maxEliminatedCount) {
                mvpPlayerIds.add(entry.getKey());
            }
        }
        return mvpPlayerIds;
    }

    private void playResultSound(@Nullable TeamType winner) {
        if (winner == null) {
            playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BELL, 0.9F, 0.9F);
            playSoundToParticipants(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7F, 1.35F);
            return;
        }

        playSoundToParticipants(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.1F, 1.0F);
        playSoundToParticipants(Sound.ENTITY_PLAYER_LEVELUP, 0.9F, winner == TeamType.BLACK ? 0.95F : 1.15F);
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
        long loopTicks = Math.max(20L, getGameMusicLoopSeconds() * 20L);
        playGameMusicOnce();
        gameMusicTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::playGameMusicOnce, loopTicks, loopTicks);
    }

    private void playGameMusicOnce() {
        String soundKey = getGameMusicSoundKey();
        float volume = getGameMusicVolume();
        for (UUID participantId : participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                continue;
            }

            participant.playSound(participant.getLocation(), soundKey, SoundCategory.MASTER, volume, 1.0F);
        }
    }

    private void stopGameMusic() {
        if (gameMusicTask != null) {
            gameMusicTask.cancel();
            gameMusicTask = null;
        }

        String soundKey = getGameMusicSoundKey();
        for (UUID participantId : participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                continue;
            }

            participant.stopSound(soundKey, SoundCategory.MASTER);
        }
    }

    private String getGameMusicSoundKey() {
        return plugin.getConfig().getString("music.sound-key", GAME_MUSIC_SOUND_KEY);
    }

    private float getGameMusicVolume() {
        return (float) plugin.getConfig().getDouble("music.volume", GAME_MUSIC_VOLUME);
    }

    private long getGameMusicLoopSeconds() {
        return plugin.getConfig().getLong("music.loop-seconds", GAME_MUSIC_LOOP_SECONDS);
    }

    private boolean isPlacementComplete() {
        return getPlacedCount(TeamType.BLACK) >= configuredPieceCount
            && getPlacedCount(TeamType.WHITE) >= configuredPieceCount;
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
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setSilent(true);
        player.getInventory().clear();
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
            sendTurnStatusActionBar(player);
        }
    }

    public void sendTurnStatusActionBar(Player player) {
        if (gameState != GameState.PLAYING) {
            return;
        }

        if (currentTurnPlayer != null && currentTurnPlayer.equals(player.getUniqueId())) {
            player.sendActionBar(createActionBarMessage("지금 당신 차례입니다.", NamedTextColor.YELLOW));
            return;
        }

        TeamType teamType = playerTeamMap.get(player.getUniqueId());
        if (teamType == null) {
            player.sendActionBar(createActionBarMessage("관전 중", NamedTextColor.GRAY));
            return;
        }

        TeamData currentTeamData = teamDataMap.get(currentTurnTeam);
        TeamData oppositeTeamData = teamDataMap.get(currentTurnTeam.opposite());
        int turnsRemaining = calculateTurnsRemaining(
            player.getUniqueId(),
            teamType,
            currentTeamData.getTurnQueueSnapshot(),
            oppositeTeamData.getTurnQueueSnapshot()
        );

        if (turnsRemaining < 0) {
            player.sendActionBar(createActionBarMessage("턴 순서를 계산 중입니다.", NamedTextColor.GRAY));
            return;
        }

        player.sendActionBar(createActionBarMessage("내 차례까지 " + turnsRemaining + "턴 남음", NamedTextColor.YELLOW));
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

    private Component createActionBarMessage(String statusText, NamedTextColor statusColor) {
        return Component.text()
            .append(Component.text("흑 " + teamDataMap.get(TeamType.BLACK).getAlivePieceCount(), TeamType.BLACK.getColor()))
            .append(Component.text(" vs ", NamedTextColor.DARK_GRAY))
            .append(Component.text("백 " + teamDataMap.get(TeamType.WHITE).getAlivePieceCount(), TeamType.WHITE.getColor()))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text(statusText, statusColor))
            .build();
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
            player.sendActionBar(Component.empty());
        }
    }

    private void clearAllPlayerInventories() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            inventoryBackupMap.remove(player.getUniqueId());
            heldSlotBackupMap.remove(player.getUniqueId());
            player.getInventory().clear();
            restoreOriginalArmor(player);
        }
    }

    private void clearRemoteControlMode(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setSilent(false);
        player.setGlowing(false);
        removeTurnBuff(player);

        ItemStack[] contents = inventoryBackupMap.remove(player.getUniqueId());
        if (contents != null) {
            player.getInventory().setContents(contents);
        }
        Integer heldSlot = heldSlotBackupMap.remove(player.getUniqueId());
        if (heldSlot != null) {
            player.getInventory().setHeldItemSlot(heldSlot);
        }
        enforceTeamArmor(player);
        if (playerTeamMap.get(player.getUniqueId()) == null && gameState != GameState.WAITING) {
            applySpectatorState(player);
        } else {
            clearSpectatorState(player);
        }
    }

    private void applyTurnBuff(Player player) {
        player.addPotionEffect(TURN_SPEED_EFFECT);
    }

    private void removeTurnBuff(Player player) {
        player.removePotionEffect(PotionEffectType.SPEED);
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

        meta.setColor(teamType == TeamType.BLACK ? Color.fromRGB(30, 30, 30) : Color.WHITE);
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

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.updateInventory();
        player.addPotionEffect(SPECTATOR_INVISIBILITY_EFFECT);
    }

    /**
     * 관전자 보조 상태를 해제하고 일반 플레이어 상태로 돌린다.
     */
    public void clearSpectatorState(Player player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (playerTeamMap.get(player.getUniqueId()) != null) {
            return;
        }

        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCollidable(true);
    }
}
