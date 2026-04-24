package org.ha2yo.alkagi.game;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.ha2yo.alkagi.scoreboard.AlkagiScoreboardManager;

import java.util.UUID;

/**
 * 게임 관련 핵심 객체를 묶어서 외부에 제공하는 진입점이다.
 */
public final class GameManager {

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final BoardManager boardManager;
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
        this.boardManager = new BoardManager(plugin, arenaData);
        this.presetRepository = new PresetRepository(plugin);
        this.presetEditor = new PresetEditor(arenaData, boardManager, presetRepository);
        this.session = new GameSession(plugin, arenaData, scoreboardManager, boardManager);
    }

    /**
     * 대기 중인 게임에 플레이어를 참가자로 등록한다.
     */
    public boolean join(
            Player player
    ) {
        return session.addParticipant(player);
    }

    /**
     * 새 게임 시작 요청을 현재 게임 세션으로 전달한다.
     */
    public boolean start(
            int pieceCount,
            boolean force,
            @org.jetbrains.annotations.Nullable Integer playerCount
    ) {
        return session.start(force, pieceCount, playerCount);
    }

    public boolean startPreset(
            PresetData presetData,
            boolean force,
            @org.jetbrains.annotations.Nullable Integer playerCount
    ) {
        return session.startWithPreset(force, presetData, playerCount);
    }

    /**
     * 진행 중인 게임을 중단한다.
     */
    public void stop() {
        session.stop();
    }

    /**
     * 게임 세션을 대기 상태로 초기화한다.
     */
    public void reset() {
        session.reset();
    }

    public void shutdown() {
        reset();
    }

    /**
     * 접속 종료한 플레이어를 현재 게임 상태에서 정리한다.
     */
    public void handleQuit(
            UUID playerId
    ) {
        session.removeOfflinePlayer(playerId);
    }

    public GameSession getSession() {
        return session;
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

    public PresetRepository getPresetRepository() {
        return presetRepository;
    }

    public PresetEditor getPresetEditor() {
        return presetEditor;
    }
}