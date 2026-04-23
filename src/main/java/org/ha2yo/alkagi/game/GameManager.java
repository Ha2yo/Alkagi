package org.ha2yo.alkagi.game;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.ha2yo.alkagi.scoreboard.AlkagiScoreboardManager;

import java.util.UUID;

/**
 * 게임 관련 주요 객체를 묶어 외부에 제공하는 진입점이다.
 */
public final class GameManager {

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final AlkagiScoreboardManager scoreboardManager;
    private final BoardManager boardManager;
    private final GameSession session;

    public GameManager(
            JavaPlugin plugin,
            ArenaData arenaData,
            AlkagiScoreboardManager scoreboardManager
    ) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.scoreboardManager = scoreboardManager;
        this.boardManager = new BoardManager(plugin, arenaData);
        this.session = new GameSession(plugin, arenaData, scoreboardManager, boardManager);
    }

    public boolean join(
            Player player
    ) {
        return session.addParticipant(player);
    }

    public boolean leave(
            Player player
    ) {
        scoreboardManager.clear(player);
        return session.removeParticipant(player);
    }

    public boolean start(
            int pieceCount,
            boolean force,
            @org.jetbrains.annotations.Nullable Integer playerCount
    ) {
        boolean started = session.start(force, pieceCount, playerCount);
        if (started) {
            applyTeamFormatting();
        }
        return started;
    }

    public void stop() {
        session.stop();
    }

    public void reset() {
        session.reset();
        clearTeamFormatting();
    }

    public void shutdown() {
        reset();
    }

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

    public void applyTeamFormatting() {
        session.refreshAllPlayerFormatting();
    }

    public void clearTeamFormatting() {
        session.refreshAllPlayerFormatting();
    }
}
