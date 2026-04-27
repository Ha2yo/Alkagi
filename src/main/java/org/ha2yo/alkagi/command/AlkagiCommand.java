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
import org.ha2yo.alkagi.game.PresetData;
import org.ha2yo.alkagi.game.PresetEditor;
import org.ha2yo.alkagi.game.TeamType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AlkagiCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "alkagi.admin";
    private static final List<String> SUBCOMMANDS = List.of(
        "status", "start", "forcestart", "stop", "reset",
        "setboardpos1", "setboardpos2", "setlobby", "setspectator",
        "setblueplace", "setredplace", "setturntime", "setpiecesize", "setcontrolradius",
        "kick", "preset"
    );
    private static final List<String> PRESET_SUBCOMMANDS = List.of(
        "list", "edit", "team", "label", "relabel", "save", "clear", "cancel", "delete"
    );
    private static final List<String> PRESET_LABEL_SUGGESTIONS = List.of(
        "楚", "士", "车", "包", "马", "象", "卒",
            "漢", "士", "車", "包", "馬", "象", "兵"
    );

    private final GameManager gameManager;

    public AlkagiCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/alkagi " + String.join("|", SUBCOMMANDS), NamedTextColor.YELLOW));
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "start" -> handleStart(sender, args, false);
            case "forcestart" -> handleStart(sender, args, true);
            case "stop" -> handleStop(sender);
            case "reset" -> handleReset(sender);
            case "setboardpos1" -> handleSetBoard(sender, true);
            case "setboardpos2" -> handleSetBoard(sender, false);
            case "setlobby" -> handleSetLobby(sender);
            case "setspectator" -> handleSetSpectator(sender);
            case "setblueplace", "setblackplace" -> handleSetPlacement(sender, TeamType.BLUE);
            case "setredplace", "setwhiteplace" -> handleSetPlacement(sender, TeamType.RED);
            case "setturntime" -> handleSetTurnTime(sender, args);
            case "setpiecesize" -> handleSetPieceSize(sender, args);
            case "setcontrolradius" -> handleSetControlRadius(sender);
            case "kick" -> handleKick(sender, args);
            case "preset" -> handlePreset(sender, args);
            default -> {
                sender.sendMessage(Component.text("알 수 없는 하위 명령입니다.", NamedTextColor.RED));
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
        sender.sendMessage(Component.text(
            "말 크기: " + String.format("%.2f", gameManager.getArenaData().getPieceSize()),
            NamedTextColor.AQUA
        ));
        sender.sendMessage(Component.text(
            "조작 반경: "
                + String.format("%.2f", gameManager.getArenaData().getControlRadius())
                + " (말 크기 x "
                + String.format("%.1f", gameManager.getArenaData().getControlRadiusMultiplier())
                + ")",
            NamedTextColor.AQUA
        ));

        for (Map.Entry<TeamType, UUID> entry : session.getPlacementPlayers().entrySet()) {
            Player player = gameManager.getPlugin().getServer().getPlayer(entry.getValue());
            String playerName = player == null ? "없음" : player.getName();
            sender.sendMessage(Component.text(
                entry.getKey().getDisplayName() + " 배치 담당: " + playerName,
                entry.getKey().getColor()
            ));
        }

        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (presetEditor.isEditing()) {
            sender.sendMessage(Component.text(
                "프리셋 편집 중: " + presetEditor.getPresetName()
                    + " / 팀 " + presetEditor.getSelectedTeam().getDisplayName()
                    + " / 크기 " + String.format("%.2f", presetEditor.getPieceSize()),
                NamedTextColor.YELLOW
            ));
        }
        return true;
    }

    private boolean handleStart(CommandSender sender, String[] args, boolean force) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (gameManager.getPresetEditor().isEditing()) {
            sender.sendMessage(Component.text("프리셋 편집 중에는 게임을 시작할 수 없습니다. 먼저 /alkagi preset save 또는 /alkagi preset cancel 을 사용해 주세요.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                "/alkagi " + (force ? "forcestart" : "start") + " <말개수> [플레이어수] 또는 <프리셋이름> [플레이어수]",
                NamedTextColor.YELLOW
            ));
            return true;
        }

        if (isInteger(args[1])) {
            return handleManualStart(sender, args, force);
        }
        return handlePresetStart(sender, args, force);
    }

    private boolean handleManualStart(CommandSender sender, String[] args, boolean force) {
        int count = parseInt(args[1], -1);
        if (count <= 0) {
            sender.sendMessage(Component.text("말 개수는 1 이상의 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        Integer playerCount = null;
        if (args.length >= 3) {
            playerCount = parseInt(args[2], -1);
            if (playerCount <= 0) {
                sender.sendMessage(Component.text("플레이어 수는 1 이상의 숫자여야 합니다.", NamedTextColor.RED));
                return true;
            }
        }

        if (!force && !isManualArenaReady()) {
            sender.sendMessage(Component.text("로비, 보드, 관전 위치와 양 팀 배치 위치를 모두 먼저 설정해야 합니다.", NamedTextColor.RED));
            return true;
        }

        if (gameManager.start(count, force, playerCount)) {
            Component startMessage = Component.text(
                "자유모드 선택, 인원: "
                    + (playerCount == null ? gameManager.getSession().getParticipants().size() : playerCount)
                    + "명",
                NamedTextColor.GREEN
            );
            gameManager.getPlugin().getServer().broadcast(startMessage);
        } else {
            sender.sendMessage(Component.text("게임을 시작할 수 없습니다. 참가 인원이나 현재 상태를 확인해 주세요.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handlePresetStart(CommandSender sender, String[] args, boolean force) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                "/alkagi " + (force ? "forcestart" : "start") + " <프리셋이름> [플레이어수]",
                NamedTextColor.YELLOW
            ));
            return true;
        }

        String presetName = args[1];
        PresetData presetData = gameManager.getPresetRepository().loadPreset(presetName);
        if (presetData == null) {
            sender.sendMessage(Component.text("해당 프리셋을 찾을 수 없습니다: " + presetName, NamedTextColor.RED));
            return true;
        }

        Integer playerCount = null;
        if (args.length >= 3) {
            playerCount = parseInt(args[2], -1);
            if (playerCount <= 0) {
                sender.sendMessage(Component.text("플레이어 수는 1 이상의 숫자여야 합니다.", NamedTextColor.RED));
                return true;
            }
        }
        if (!force && !isPresetArenaReady()) {
            sender.sendMessage(Component.text("로비, 보드, 관전 위치를 먼저 설정해야 프리셋 게임을 시작할 수 있습니다.", NamedTextColor.RED));
            return true;
        }

        if (gameManager.startPreset(presetData, force, playerCount)) {
            Component startMessage = Component.text(
                presetName + " 선택, 인원: "
                    + (playerCount == null ? gameManager.getSession().getParticipants().size() : playerCount)
                    + "명",
                NamedTextColor.GREEN
            );
            gameManager.getPlugin().getServer().broadcast(startMessage);
        } else {
            sender.sendMessage(Component.text("프리셋 게임을 시작할 수 없습니다. 프리셋 좌표나 현재 상태를 확인해 주세요.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleStop(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }

        gameManager.stop();
        sender.sendMessage(Component.text("진행 중인 게임을 중단했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleReset(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }

        gameManager.reset();
        gameManager.getPresetEditor().endEditing();
        sender.sendMessage(Component.text("게임 상태를 초기화했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleSetBoard(CommandSender sender, boolean first) {
        if (!requireAdmin(sender)) {
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

        saveArenaData();
        sender.sendMessage(Component.text("보드 좌표를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetLobby(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setLobbyLocation(player.getLocation());
        saveArenaData();
        sender.sendMessage(Component.text("로비 위치를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetSpectator(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setSpectatorLocation(player.getLocation());
        saveArenaData();
        sender.sendMessage(Component.text("관전 위치를 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetPlacement(CommandSender sender, TeamType teamType) {
        if (!requireAdmin(sender)) {
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        gameManager.getArenaData().setPlacementLocation(teamType, player.getLocation());
        saveArenaData();
        sender.sendMessage(Component.text(teamType.getDisplayName() + " 배치 위치를 저장했습니다.", teamType.getColor()));
        return true;
    }

    private boolean handleSetTurnTime(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi setturntime <seconds>", NamedTextColor.YELLOW));
            return true;
        }

        int seconds = parseInt(args[1], -1);
        if (seconds <= 0) {
            sender.sendMessage(Component.text("초는 1 이상의 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.getArenaData().setTurnTimeSeconds(seconds);
        gameManager.getSession().refreshTurnTimerConfiguration();
        saveArenaData();
        sender.sendMessage(Component.text("턴 시간을 " + seconds + "초로 설정했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSetPieceSize(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi setpiecesize <size>", NamedTextColor.YELLOW));
            return true;
        }

        double size = parseDouble(args[1], -1.0D);
        if (size <= 0.0D) {
            sender.sendMessage(Component.text("크기는 0보다 큰 숫자여야 합니다.", NamedTextColor.RED));
            return true;
        }

        gameManager.getArenaData().setPieceSize(size);
        gameManager.getBoardManager().refreshPieceDisplays();
        saveArenaData();
        sender.sendMessage(Component.text(
            "말 크기를 " + String.format("%.2f", gameManager.getArenaData().getPieceSize()) + "로 설정했습니다.",
            NamedTextColor.GREEN
        ));
        return true;
    }

    private boolean handleSetControlRadius(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }

        sender.sendMessage(Component.text(
            "조작 반경은 이제 말 크기에 따라 자동 계산됩니다. /alkagi setpiecesize <size> 를 사용해 주세요.",
            NamedTextColor.YELLOW
        ));
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi kick <닉네임>", NamedTextColor.YELLOW));
            return true;
        }

        Player target = gameManager.getPlugin().getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("해당 플레이어를 찾을 수 없습니다: " + args[1], NamedTextColor.RED));
            return true;
        }

        TeamType teamType = gameManager.getSession().getPlayerTeam(target.getUniqueId());
        if (!gameManager.getSession().kickParticipant(target)) {
            sender.sendMessage(Component.text("현재 게임 참가자가 아닙니다: " + target.getName(), NamedTextColor.RED));
            return true;
        }

        Component message = Component.text(target.getName() + " 님을 게임에서 추방했습니다.", NamedTextColor.YELLOW);
        if (teamType != null) {
            message = Component.text(target.getName() + " 님을 ", NamedTextColor.YELLOW)
                .append(Component.text(teamType.getDisplayName(), teamType.getColor()))
                .append(Component.text(" 팀에서 추방했습니다.", NamedTextColor.YELLOW));
        }
        gameManager.getPlugin().getServer().broadcast(message);
        return true;
    }

    private boolean handlePreset(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/alkagi preset " + String.join("|", PRESET_SUBCOMMANDS), NamedTextColor.YELLOW));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "list" -> handlePresetList(sender);
            case "edit" -> handlePresetEdit(sender, args);
            case "team" -> handlePresetTeam(sender, args);
            case "label" -> handlePresetLabel(sender, args);
            case "relabel" -> handlePresetRelabel(sender, args);
            case "save" -> handlePresetSave(sender);
            case "clear" -> handlePresetClear(sender);
            case "cancel" -> handlePresetCancel(sender);
            case "delete" -> handlePresetDelete(sender, args);
            default -> {
                sender.sendMessage(Component.text("알 수 없는 preset 하위 명령입니다.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean handlePresetList(CommandSender sender) {
        List<String> presetNames = gameManager.getPresetRepository().listPresetNames();
        if (presetNames.isEmpty()) {
            sender.sendMessage(Component.text("저장된 프리셋이 없습니다.", NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text("프리셋 목록: " + String.join(", ", presetNames), NamedTextColor.AQUA));
        return true;
    }

    private boolean handlePresetEdit(CommandSender sender, String[] args) {
        if (gameManager.getSession().getGameState() != org.ha2yo.alkagi.game.GameState.WAITING) {
            sender.sendMessage(Component.text("게임이 진행 중일 때는 프리셋 편집을 시작할 수 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/alkagi preset edit <이름>", NamedTextColor.YELLOW));
            return true;
        }

        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!gameManager.getArenaData().isBoardConfigured()) {
            sender.sendMessage(Component.text("프리셋 편집 전에 보드 좌표를 먼저 설정해야 합니다.", NamedTextColor.RED));
            return true;
        }

        boolean started = gameManager.getPresetEditor().beginEditing(player, args[2]);
        if (!started) {
            sender.sendMessage(Component.text("다른 관리자가 이미 프리셋을 편집 중입니다.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("프리셋 편집을 시작했습니다: " + args[2], NamedTextColor.GREEN));
        sender.sendMessage(Component.text("블레이즈 막대를 들고 우클릭하면 현재 팀 말이 배치되고, 좌클릭하면 마지막 말이 삭제됩니다.", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
            "현재 팀: " + gameManager.getPresetEditor().getSelectedTeam().getDisplayName()
                + " / 글자: " + gameManager.getPresetEditor().getLabelText(),
            NamedTextColor.YELLOW
        ));
        return true;
    }

    private boolean handlePresetTeam(CommandSender sender, String[] args) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("먼저 /alkagi preset edit <이름> 으로 편집을 시작해 주세요.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/alkagi preset team <blue|red> [size] [label]", NamedTextColor.YELLOW));
            return true;
        }

        TeamType teamType = parseTeamType(args[2]);
        if (teamType == null) {
            sender.sendMessage(Component.text("팀은 blue 또는 red 로 입력해 주세요.", NamedTextColor.RED));
            return true;
        }

        presetEditor.setSelectedTeam(teamType);
        if (args.length >= 4) {
            double size = parseDouble(args[3], -1.0D);
            if (size <= 0.0D) {
                sender.sendMessage(Component.text("말 크기는 0보다 큰 숫자여야 합니다.", NamedTextColor.RED));
                return true;
            }
            presetEditor.setPieceSize(size);
        }
        if (args.length >= 5) {
            presetEditor.setLabelText(joinArgs(args, 4));
        }

        sender.sendMessage(Component.text(
            "현재 프리셋 팀을 " + teamType.getDisplayName() + " 으로 변경했습니다. 말 크기: "
                + String.format("%.2f", presetEditor.getPieceSize())
                + " / 글자: " + presetEditor.getLabelText(),
            teamType.getColor()
        ));
        return true;
    }

    private boolean handlePresetLabel(CommandSender sender, String[] args) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("먼저 /alkagi preset edit <이름> 으로 편집을 시작해 주세요.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/alkagi preset label <글자>", NamedTextColor.YELLOW));
            return true;
        }

        presetEditor.setLabelText(joinArgs(args, 2));
        sender.sendMessage(Component.text(
            "현재 프리셋 말 글자를 " + presetEditor.getLabelText()
                + " 로 지정했습니다. 블레이즈 막대로 말을 우클릭하면 해당 말에 적용됩니다.",
            presetEditor.getSelectedTeam().getColor()
        ));
        return true;
    }

    private boolean handlePresetRelabel(CommandSender sender, String[] args) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("먼저 /alkagi preset edit <이름> 으로 편집을 시작해 주세요.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/alkagi preset relabel <글자>", NamedTextColor.YELLOW));
            return true;
        }

        boolean updated = presetEditor.relabelLastPlacedPiece(joinArgs(args, 2));
        if (!updated) {
            sender.sendMessage(Component.text("현재 선택된 팀에 수정할 말이 없습니다.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text(
            "마지막으로 배치한 " + presetEditor.getSelectedTeam().getDisplayName()
                + " 말 글자를 " + presetEditor.getLabelText() + " 로 변경했습니다.",
            presetEditor.getSelectedTeam().getColor()
        ));
        return true;
    }

    private boolean handlePresetSave(CommandSender sender) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        int blueCount = presetEditor.getPlacedCount(TeamType.BLUE);
        int redCount = presetEditor.getPlacedCount(TeamType.RED);
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("저장할 프리셋 편집 세션이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (blueCount <= 0 || blueCount != redCount) {
            sender.sendMessage(Component.text("청홍 돌 수가 같고 최소 1개 이상 있어야 프리셋으로 저장할 수 있습니다.", NamedTextColor.RED));
            return true;
        }

        PresetData savedPreset = presetEditor.saveCurrentPreset();
        if (savedPreset == null) {
            sender.sendMessage(Component.text("저장할 프리셋 편집 세션이 없습니다.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text(
            "프리셋 " + savedPreset.getName() + " 저장 완료. 팀별 말 개수: " + savedPreset.getPieceCount()
                + " (개별 말 크기 포함)",
            NamedTextColor.GREEN
        ));
        return true;
    }

    private boolean handlePresetClear(CommandSender sender) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("지금 편집 중인 프리셋이 없습니다.", NamedTextColor.RED));
            return true;
        }

        presetEditor.clearCurrentPieces();
        sender.sendMessage(Component.text("현재 프리셋에 배치한 돌을 모두 지웠습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handlePresetCancel(CommandSender sender) {
        PresetEditor presetEditor = gameManager.getPresetEditor();
        if (!presetEditor.isEditing()) {
            sender.sendMessage(Component.text("취소할 프리셋 편집 세션이 없습니다.", NamedTextColor.RED));
            return true;
        }

        presetEditor.endEditing();
        sender.sendMessage(Component.text("프리셋 편집을 취소했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handlePresetDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("/alkagi preset delete <이름>", NamedTextColor.YELLOW));
            return true;
        }

        if (gameManager.getPresetRepository().deletePreset(args[2])) {
            sender.sendMessage(Component.text("프리셋을 삭제했습니다: " + args[2], NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("삭제할 프리셋을 찾을 수 없습니다: " + args[2], NamedTextColor.RED));
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }

        sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
        return false;
    }

    private void saveArenaData() {
        ((AlkagiPlugin) gameManager.getPlugin()).saveArenaData();
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }

        sender.sendMessage(Component.text("플레이어만 사용할 수 있는 명령입니다.", NamedTextColor.RED));
        return null;
    }

    private boolean isManualArenaReady() {
        ArenaData arenaData = gameManager.getArenaData();
        return arenaData.getLobbyLocation() != null
            && arenaData.getBoardPos1() != null
            && arenaData.getBoardPos2() != null
            && arenaData.getSpectatorLocation() != null
            && arenaData.getPlacementLocation(TeamType.BLUE) != null
            && arenaData.getPlacementLocation(TeamType.RED) != null;
    }

    private boolean isPresetArenaReady() {
        ArenaData arenaData = gameManager.getArenaData();
        return arenaData.getLobbyLocation() != null
            && arenaData.getBoardPos1() != null
            && arenaData.getBoardPos2() != null
            && arenaData.getSpectatorLocation() != null;
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private @Nullable TeamType parseTeamType(String value) {
        return switch (value.toLowerCase()) {
            case "blue", "청", "blueteam", "black", "흑", "blackteam" -> TeamType.BLUE;
            case "red", "홍", "redteam", "white", "백", "whiteteam" -> TeamType.RED;
            default -> null;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                .filter(option -> option.startsWith(args[0].toLowerCase()))
                .toList();
        }

        if (args.length == 2 && ("start".equalsIgnoreCase(args[0]) || "forcestart".equalsIgnoreCase(args[0]))) {
            List<String> options = new ArrayList<>(List.of("10", "12", "15", "20"));
            options.addAll(gameManager.getPresetRepository().listPresetNames());
            return options.stream()
                .filter(option -> option.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
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

        if (args.length == 2 && "kick".equalsIgnoreCase(args[0])) {
            return gameManager.getSession().getParticipants().stream()
                .map(playerId -> gameManager.getPlugin().getServer().getPlayer(playerId))
                .filter(java.util.Objects::nonNull)
                .map(Player::getName)
                .filter(option -> option.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }

        if (args.length == 2 && "preset".equalsIgnoreCase(args[0])) {
            return PRESET_SUBCOMMANDS.stream()
                .filter(option -> option.startsWith(args[1].toLowerCase()))
                .toList();
        }

        if (args.length == 3 && "preset".equalsIgnoreCase(args[0])) {
            return switch (args[1].toLowerCase()) {
                case "edit", "delete" -> gameManager.getPresetRepository().listPresetNames().stream()
                    .filter(option -> option.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
                case "team" -> List.of("blue", "red");
                case "label", "relabel" -> PRESET_LABEL_SUGGESTIONS;
                default -> new ArrayList<>();
            };
        }

        if (args.length == 4 && "preset".equalsIgnoreCase(args[0]) && "team".equalsIgnoreCase(args[1])) {
            return List.of("1.8", "2.0", "2.35", "2.6", "3.0");
        }

        return new ArrayList<>();
    }

    private String joinArgs(String[] args, int startIndex) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, startIndex, args.length));
    }
}
