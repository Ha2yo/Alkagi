package org.ha2yo.alkagi.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.ScoreboardManager;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.Nullable;

/**
 * 알까기 게임에서 사용하는 스코어보드 표시를 관리한다.
 */
public final class AlkagiScoreboardManager {

    private final JavaPlugin plugin;

    public AlkagiScoreboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateGameBoard(GameSession session) {
        clearAll();
    }

    public void showResult(GameSession session, @Nullable TeamType winner) {
        clearAll();
    }

    public void clear(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        player.setScoreboard(manager.getMainScoreboard());
    }

    public void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
        }
    }
}
