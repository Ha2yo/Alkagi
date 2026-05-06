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
    private static final Component TITLE = Component.text("현황판", NamedTextColor.GOLD);
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

        for (java.util.UUID participantId : session.getParticipants()) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player == null) {
                continue;
            }
            if (session.getGameState() != GameState.PLAYING) {
                clear(player);
                continue;
            }

            Scoreboard scoreboard = manager.getNewScoreboard();
            session.copyTabTeams(scoreboard);

            Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, TITLE);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());

            addLine(scoreboard, objective, 2, 0, Component.empty());
            addLine(scoreboard, objective, 1, 1, buildPieceCountLine(session));
            addLine(scoreboard, objective, 0, 2, buildTeamLine(session, player));

            player.setScoreboard(scoreboard);
        }
    }

    public void showResult(GameSession session, @Nullable TeamType winner) {
        clearSession(session);
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

    public void clearSession(GameSession session) {
        for (java.util.UUID participantId : session.getParticipants()) {
            Player player = plugin.getServer().getPlayer(participantId);
            if (player != null) {
                clear(player);
            }
        }
    }

    private Component buildPieceCountLine(GameSession session) {
        return Component.text()
            .append(Component.text("청 ", TeamType.BLUE.getColor()))
            .append(Component.text(session.getAlivePieceCount(TeamType.BLUE), NamedTextColor.WHITE))
            .append(Component.text(" vs ", NamedTextColor.DARK_GRAY))
            .append(Component.text("홍 ", TeamType.RED.getColor()))
            .append(Component.text(session.getAlivePieceCount(TeamType.RED), NamedTextColor.WHITE))
            .build();
    }

    private Component buildTeamLine(GameSession session, Player player) {
        TeamType teamType = session.getPlayerTeam(player.getUniqueId());
        if (teamType == null) {
            return Component.text()
                .append(Component.text("당신은 ", NamedTextColor.WHITE))
                .append(Component.text("관전 중", NamedTextColor.GRAY))
                .append(Component.text("입니다", NamedTextColor.WHITE))
                .build();
        }

        return Component.text()
            .append(Component.text("당신은 ", NamedTextColor.WHITE))
            .append(Component.text(teamType.getDisplayName() + "팀", teamType.getColor()))
            .append(Component.text("입니다", NamedTextColor.WHITE))
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
