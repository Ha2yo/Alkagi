package org.ha2yo.alkagi.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.GameState;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.Nullable;

/**
 * 알까기 게임에서 사용하는 사이드바 스코어보드를 관리한다.
 */
public final class AlkagiScoreboardManager {

    private static final String OBJECTIVE_NAME = "alkagi";
    private static final Component TITLE = Component.text("알까기", NamedTextColor.GOLD);
    private static final String[] LINE_KEYS = {
        "\u00A70",
        "\u00A71",
        "\u00A72",
        "\u00A73",
        "\u00A74",
        "\u00A75",
        "\u00A76",
        "\u00A77",
        "\u00A78",
        "\u00A79",
        "\u00A7a",
        "\u00A7b",
        "\u00A7c",
        "\u00A7d",
        "\u00A7e"
    };

    private final JavaPlugin plugin;

    public AlkagiScoreboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateGameBoard(GameSession session) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (session.getGameState() != GameState.PLAYING) {
                clear(player);
                continue;
            }

            Scoreboard scoreboard = manager.getNewScoreboard();
            session.copyTabTeams(scoreboard);

            Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, TITLE);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());

            addLine(scoreboard, objective, 7, 0, buildPieceCountLine(session));
            addLine(scoreboard, objective, 6, 1, Component.empty());
            addLine(scoreboard, objective, 5, 2, buildTeamLine(session, player));
            addLine(scoreboard, objective, 4, 3, buildStatusLine(session, player));
            addLine(scoreboard, objective, 3, 4, Component.empty());
            addLine(scoreboard, objective, 2, 5, Component.text("현재 턴", NamedTextColor.WHITE));
            addLine(scoreboard, objective, 1, 6, Component.text(session.getCurrentTurnDisplayText(), NamedTextColor.YELLOW));
            addLine(scoreboard, objective, 0, 7, buildTimerLine(session));

            player.setScoreboard(scoreboard);
        }
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

    private Component buildPieceCountLine(GameSession session) {
        return Component.text()
            .append(Component.text("흑 ", NamedTextColor.GRAY))
            .append(Component.text(session.getAlivePieceCount(TeamType.BLACK), NamedTextColor.WHITE))
            .append(Component.text(" vs ", NamedTextColor.DARK_GRAY))
            .append(Component.text("백 ", NamedTextColor.GRAY))
            .append(Component.text(session.getAlivePieceCount(TeamType.WHITE), NamedTextColor.WHITE))
            .build();
    }

    private Component buildTeamLine(GameSession session, Player player) {
        TeamType teamType = session.getPlayerTeam(player.getUniqueId());
        NamedTextColor teamColor = switch (teamType) {
            case BLACK -> NamedTextColor.GRAY;
            case WHITE -> NamedTextColor.WHITE;
            case null -> NamedTextColor.DARK_GRAY;
        };
        return Component.text()
            .append(Component.text("당신은 ", NamedTextColor.WHITE))
            .append(Component.text(session.getPlayerTeamStatusText(player.getUniqueId()), teamColor))
            .build();
    }

    private Component buildStatusLine(GameSession session, Player player) {
        NamedTextColor color = session.isCurrentTurnPlayer(player.getUniqueId())
            ? NamedTextColor.YELLOW
            : session.getPlayerTeam(player.getUniqueId()) == null
                ? NamedTextColor.GRAY
                : NamedTextColor.GOLD;
        return Component.text(session.getTurnStatusText(player.getUniqueId()), color);
    }

    private Component buildTimerLine(GameSession session) {
        return Component.text()
            .append(Component.text("남은 시간 ", NamedTextColor.RED))
            .append(Component.text(session.getRemainingTurnSeconds() + "초", NamedTextColor.WHITE))
            .build();
    }

    private void addLine(Scoreboard scoreboard, Objective objective, int score, int lineIndex, Component component) {
        String entry = LINE_KEYS[lineIndex];
        Team team = scoreboard.registerNewTeam("line_" + lineIndex);
        team.addEntry(entry);
        team.prefix(component);
        objective.getScore(entry).setScore(score);
    }
}
