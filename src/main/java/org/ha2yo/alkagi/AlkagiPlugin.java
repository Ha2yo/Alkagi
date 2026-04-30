package org.ha2yo.alkagi;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.ha2yo.alkagi.command.AlkagiCommand;
import org.ha2yo.alkagi.game.ArenaData;
import org.ha2yo.alkagi.game.GameManager;
import org.ha2yo.alkagi.listener.GuideRenderer;
import org.ha2yo.alkagi.listener.RemoteGameListener;
import org.ha2yo.alkagi.scoreboard.AlkagiScoreboardManager;

/**
 * Alkagi 플러그인의 메인 진입점이다.
 */
public final class AlkagiPlugin extends JavaPlugin {

    private GameManager gameManager;
    private GuideRenderer guideRenderer;
    private RemoteGameListener remoteGameListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ArenaData arenaData = ArenaData.fromConfig(getConfig());
        AlkagiScoreboardManager scoreboardManager = new AlkagiScoreboardManager(this);
        this.gameManager = new GameManager(this, arenaData, scoreboardManager);
        // 이전 실행에서 남은 말 엔티티를 정리한다.
        gameManager.getBoardManager().cleanupTaggedPieceEntities();
        gameManager.getBoardManager().refreshBoardGridLines();

        AlkagiCommand alkagiCommand = new AlkagiCommand(gameManager);
        PluginCommand command = getCommand("alkagi");
        if (command == null) {
            throw new IllegalStateException("alkagi command is not defined in plugin.yml");
        }

        command.setExecutor(alkagiCommand);
        command.setTabCompleter(alkagiCommand);

        // 리로드 시 접속 중인 플레이어도 대기 참가자로 동기화한다.
        getServer().getOnlinePlayers().forEach(gameManager::join);

        this.guideRenderer = new GuideRenderer(this, gameManager);
        // 조준 및 배치 가이드 렌더링을 시작한다.
        guideRenderer.start();
        this.remoteGameListener = new RemoteGameListener(gameManager);
        getServer().getPluginManager().registerEvents(remoteGameListener, this);
    }

    @Override
    public void onDisable() {
        if (remoteGameListener != null) {
            remoteGameListener.shutdown();
        }
        if (gameManager != null) {
            gameManager.shutdown();
            gameManager.getBoardManager().cleanupTaggedPieceEntities();
        }
        if (guideRenderer != null) {
            guideRenderer.stop();
        }
    }

    /**
     * 현재 메모리에 올라와 있는 경기 설정을 {@code config.yml}에 저장한다.
     */
    public void saveArenaData() {
        if (gameManager == null) {
            return;
        }

        gameManager.getArenaData().save(getConfig());
        saveConfig();
    }
}
