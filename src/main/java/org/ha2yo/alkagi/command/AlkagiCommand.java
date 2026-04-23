package org.ha2yo.alkagi.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.ha2yo.alkagi.AlkagiPlugin;
import org.ha2yo.alkagi.game.ArenaData;
import org.ha2yo.alkagi.game.GameManager;
import org.ha2yo.alkagi.game.GameSession;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AlkagiCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "alkagi.admin";

    private final GameManager gameManager;

    public AlkagiCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/alkagi status|start|forcestart|stop|reset|setboardpos1|setboardpos2|setlobby|setspectator|setblackplace|setwhiteplace|setturntime|setpiecesize", NamedTextColor.YELLOW));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> handleStatus(sender);
            case "start" -> handleStart(sender, args, false);
            case "forcestart" -> handleStart(sender, args, true);
            case "stop" -> handleStop(sender);
            case "reset" -> handleReset(sender);
            case "setboardpos1" -> handleSetBoard(sender, true);
            case "setboardpos2" -> handleSetBoard(sender, false);
            case "setlobby" -> handleSetLobby(sender);
            case "setspectator" -> handleSetSpectator(sender);
            case "setblackplace" -> handleSetPlacement(sender, TeamType.BLACK);
            case "setwhiteplace" -> handleSetPlacement(sender, TeamType.WHITE);
            case "setturntime" -> handleSetTurnTime(sender, args);
            case "setpiecesize" -> handleSetPieceSize(sender, args);
            case "setcontrolradius" -> handleSetControlRadius(sender, args);
            default -> {
                sender.sendMessage(Component.text("알 수 없는 하위 명령어입니다.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender) {
        GameSession session = gameManager.getSession();
        sender.sendMessage(Component.text("상태: " + session.getGameState(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("참가자 수: " + session.getParticipants().size(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("말 개수: " + session.getConfiguredPieceCount(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("턴 시간: " + session.getTurnTimeSeconds() + "초", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("말 크기: " + String.format("%.2f", gameManager.getArenaData().getPieceSize()), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(
            "조작 반경: "
                + String.format("%.2f", gameManager.getArenaData().getControlRadius())
                + " (말 크기 x "
                + String.format("%.1f", gameManager.getArenaData().getControlRadiusMultiplier())
                + ")",
            NamedTextColor.AQUA
        ));
        for (Map.Entry<TeamType, UUID> entry : session.getPlacementPlayers().entrySet()) {
            String playerName = "없음";
            Player player = gameManager.getPlugin().getServer().getPlayer(entry.getValue());
            if (player != null) {
                playerName = player.getName();
            }
            sender.sendMessage(Component.text(entry.getKey().getDisplayName() + " 배치 담당: " + playerName, entry.getKey().getColor()));
        }
        return true;
    }

    private boolean handleStart(CommandSender sender, String[] args, boolean force) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi " + (force ? "forcestart" : "start") + " <count> [players]", NamedTextColor.YELLOW));
            return true;
        }

        int count;
        try {
            count = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("말 개수는 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        if (count <= 0) {
            sender.sendMessage(Component.text("말 개수는 1 이상이어야 합니다.", NamedTextColor.RED));
            return true;
        }

        Integer playerCount = null;
        if (args.length >= 3) {
            try {
                playerCount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(Component.text("플레이어 수는 숫자여야 합니다.", NamedTextColor.RED));
                return true;
            }

            if (playerCount <= 0) {
                sender.sendMessage(Component.text("플레이어 수는 1 이상이어야 합니다.", NamedTextColor.RED));
                return true;
            }
        }

        if (!force && !isArenaReady()) {
            sender.sendMessage(Component.text("로비, 보드, 관전 위치, 흑/백 배치 위치를 먼저 모두 설정해야 합니다.", NamedTextColor.RED));
            return true;
        }

        if (gameManager.start(count, force, playerCount)) {
            String playerCountText = playerCount == null ? "전체 참가자" : playerCount + "명";
            sender.sendMessage(Component.text("알까기 게임을 시작했습니다. 팀별 말 개수: " + count + ", 플레이어 수: " + playerCountText, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("게임을 시작할 수 없습니다. 참가 인원이나 현재 상태를 확인해 주세요.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleStop(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.stop();
        sender.sendMessage(Component.text("진행 중인 게임을 중단했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleReset(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.reset();
        sender.sendMessage(Component.text("게임 상태를 초기화했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleSetBoard(CommandSender sender, boolean first) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        ArenaData arenaData = gameManager.getArenaData();
        if (first) {
            arenaData.setBoardPos1(player.getLocation());
        } else {
            arenaData.setBoardPos2(player.getLocation());
        }

        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text("보드 좌표를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetLobby(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setLobbyLocation(player.getLocation());
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text("로비 위치를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetSpectator(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setSpectatorLocation(player.getLocation());
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text("관전 위치를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetPlacement(CommandSender sender, TeamType teamType) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setPlacementLocation(teamType, player.getLocation());
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text(teamType.getDisplayName() + " 배치 위치를 저장했습니다.", teamType.getColor()));
        return true;
    }

    private boolean handleSetTurnTime(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi setturntime <seconds>", NamedTextColor.YELLOW));
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("초는 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        if (seconds <= 0) {
            sender.sendMessage(Component.text("초는 1 이상이어야 합니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.getArenaData().setTurnTimeSeconds(seconds);
        gameManager.getSession().refreshTurnTimerConfiguration();
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text("턴 시간을 " + seconds + "초로 설정했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetPieceSize(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi setpiecesize <size>", NamedTextColor.YELLOW));
            return true;
        }

        double size;
        try {
            size = Double.parseDouble(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("크기는 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        if (size <= 0.0D) {
            sender.sendMessage(Component.text("크기는 0보다 커야 합니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.getArenaData().setPieceSize(size);
        gameManager.getBoardManager().refreshPieceDisplays();
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
        sender.sendMessage(Component.text("말 크기를 " + String.format("%.2f", gameManager.getArenaData().getPieceSize()) + "로 설정했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetControlRadius(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text(
            "조작 반경은 이제 말 크기에 따라 자동 계산됩니다. /alkagi setpiecesize <size> 를 사용해 주세요.",
            NamedTextColor.YELLOW
        ));
        return true;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("플레이어만 사용할 수 있는 명령어입니다.", NamedTextColor.RED));
        return null;
    }

    private boolean isArenaReady() {
        ArenaData arenaData = gameManager.getArenaData();
        return arenaData.getLobbyLocation() != null
            && arenaData.getBoardPos1() != null
            && arenaData.getBoardPos2() != null
            && arenaData.getSpectatorLocation() != null
            && arenaData.getPlacementLocation(TeamType.BLACK) != null
            && arenaData.getPlacementLocation(TeamType.WHITE) != null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("status", "start", "forcestart", "stop", "reset", "setboardpos1", "setboardpos2", "setlobby", "setspectator", "setblackplace", "setwhiteplace", "setturntime", "setpiecesize")
                .stream()
                .filter(option -> option.startsWith(args[0].toLowerCase()))
                .toList();
        }

        if (args.length == 2 && ("start".equalsIgnoreCase(args[0]) || "forcestart".equalsIgnoreCase(args[0]))) {
            return List.of("10", "12", "15", "20");
        }

        if (args.length == 3 && ("start".equalsIgnoreCase(args[0]) || "forcestart".equalsIgnoreCase(args[0]))) {
            return List.of("2", "4", "6", "8");
        }

        if (args.length == 2 && "setturntime".equalsIgnoreCase(args[0])) {
            return List.of("15", "20", "30", "45", "60");
        }

        if (args.length == 2 && "setpiecesize".equalsIgnoreCase(args[0])) {
            return List.of("1.8", "2.0", "2.35", "2.6", "3.0");
        }

        return new ArrayList<>();
    }
}
