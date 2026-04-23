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
 * 플러그인 활성화와 종료 시점에 필요한 핵심 컴포넌트를 초기화하고 정리한다.
 */
public final class AlkagiPlugin extends JavaPlugin {

    private GameManager gameManager;
    private GuideRenderer guideRenderer;

    /**
     * 플러그인 활성화 시 호출된다.
     * 설정을 불러오고 게임 매니저, 명령어, 렌더러, 리스너를 등록한다.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();

        ArenaData arenaData = ArenaData.fromConfig(getConfig());
        AlkagiScoreboardManager scoreboardManager = new AlkagiScoreboardManager(this);
        this.gameManager = new GameManager(this, arenaData, scoreboardManager);
        gameManager.getBoardManager().cleanupTaggedPieceEntities();

        AlkagiCommand alkagiCommand = new AlkagiCommand(gameManager);
        PluginCommand command = getCommand("alkagi");
        if (command == null) {
            throw new IllegalStateException("alkagi command is not defined in plugin.yml");
        }

        command.setExecutor(alkagiCommand);
        command.setTabCompleter(alkagiCommand);

        getServer().getOnlinePlayers().forEach(gameManager::join);

        this.guideRenderer = new GuideRenderer(this, gameManager);
        guideRenderer.start();
        getServer().getPluginManager().registerEvents(new RemoteGameListener(gameManager), this);
    }

    /**
     * 플러그인 비활성화 시 호출된다.
     * 진행 중인 게임 상태와 렌더러 작업을 안전하게 정리한다.
     */
    @Override
    public void onDisable() {
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
