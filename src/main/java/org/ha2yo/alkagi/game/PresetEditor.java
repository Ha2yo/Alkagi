package org.ha2yo.alkagi.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.ha2yo.alkagi.game.model.PieceData;
import org.ha2yo.alkagi.game.model.TeamData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PresetEditor {

    private final JavaPlugin plugin;
    private final ArenaData arenaData;
    private final BoardManager boardManager;
    private final PresetRepository presetRepository;
    private final Map<TeamType, TeamData> teamDataMap = new EnumMap<>(TeamType.class);

    private UUID editorId;
    private String presetName;
    private TeamType selectedTeam = TeamType.BLUE;
    private double pieceSize;
    private double pieceHeightScale = 1.0D;
    private double previousPieceSize;
    private String labelText = getDefaultLabelText(TeamType.BLUE);

    public PresetEditor(JavaPlugin plugin, ArenaData arenaData, BoardManager boardManager, PresetRepository presetRepository) {
        this.plugin = plugin;
        this.arenaData = arenaData;
        this.boardManager = boardManager;
        this.presetRepository = presetRepository;
        for (TeamType teamType : TeamType.values()) {
            teamDataMap.put(teamType, new TeamData(teamType));
        }
        this.pieceSize = arenaData.getPieceSize();
    }

    public boolean isEditing() {
        return editorId != null;
    }

    public boolean isEditing(UUID playerId) {
        return editorId != null && editorId.equals(playerId);
    }

    public @Nullable String getPresetName() {
        return presetName;
    }

    public TeamType getSelectedTeam() {
        return selectedTeam;
    }

    public double getPieceSize() {
        return pieceSize;
    }

    public double getPieceHeightScale() {
        return pieceHeightScale;
    }

    public int getPlacedCount(TeamType teamType) {
        return teamDataMap.get(teamType).getAlivePieceCount();
    }

    public boolean beginEditing(Player player, String presetName) {
        if (isEditing() && !isEditing(player.getUniqueId())) {
            return false;
        }

        clearPreviewPieces();
        this.editorId = player.getUniqueId();
        this.presetName = presetName;
        this.selectedTeam = TeamType.BLUE;
        this.labelText = getDefaultLabelText(selectedTeam);
        this.previousPieceSize = arenaData.getPieceSize();

        PresetData presetData = presetRepository.loadPreset(presetName);
        this.pieceSize = arenaData.getPieceSize();
        this.pieceHeightScale = 1.0D;

        if (presetData != null) {
            loadPresetPreview(presetData);
            PresetData.PresetPiece firstPiece = presetData.getPieces(TeamType.BLUE).stream().findFirst()
                .orElseGet(() -> presetData.getPieces(TeamType.RED).stream().findFirst().orElse(null));
            if (firstPiece != null) {
                this.pieceSize = firstPiece.pieceSize();
                this.pieceHeightScale = firstPiece.heightScale();
                if (firstPiece.labelText() != null) {
                    this.labelText = firstPiece.labelText();
                }
            }
        }
        prepareEditorPlayer(player);
        return true;
    }

    public void setSelectedTeam(TeamType selectedTeam) {
        this.selectedTeam = selectedTeam;
        this.labelText = getDefaultLabelText(selectedTeam);
    }

    public void setPieceSize(double pieceSize) {
        this.pieceSize = Math.max(0.5D, pieceSize);
    }

    public void setPieceHeightScale(double pieceHeightScale) {
        this.pieceHeightScale = Math.max(0.1D, pieceHeightScale);
    }

    public String getLabelText() {
        return labelText;
    }

    public void setLabelText(String labelText) {
        String trimmed = labelText.trim();
        this.labelText = trimmed.isEmpty() ? getDefaultLabelText(selectedTeam) : trimmed;
    }

    public boolean placePiece(Player player, Location clickedLocation) {
        if (!isEditing(player.getUniqueId())) {
            return false;
        }

        Location spawnLocation = clickedLocation.clone();
        Location base = arenaData.getBoardPos1();
        if (base != null) {
            spawnLocation.setY(base.getY());
        }
        spawnLocation.setX(Math.floor(spawnLocation.getX()) + 0.5D);
        spawnLocation.setZ(Math.floor(spawnLocation.getZ()) + 0.5D);

        if (!boardManager.canPlacePiece(spawnLocation, pieceSize, teamDataMap)) {
            return false;
        }

        TeamData teamData = teamDataMap.get(selectedTeam);
        int pieceId = teamData.getPieces().size() + 1;
        teamData.getPieces().add(boardManager.spawnPiece(
            selectedTeam,
            pieceId,
            spawnLocation,
            pieceSize,
            pieceHeightScale,
            labelText
        ));
        return true;
    }

    public boolean removeLastPlacedPiece(Player player) {
        if (!isEditing(player.getUniqueId())) {
            return false;
        }

        TeamData teamData = teamDataMap.get(selectedTeam);
        List<PieceData> pieces = teamData.getPieces();
        if (pieces.isEmpty()) {
            return false;
        }

        PieceData pieceData = pieces.remove(pieces.size() - 1);
        boardManager.removePiece(pieceData);
        return true;
    }

    public boolean relabelLastPlacedPiece(String labelText) {
        if (!isEditing()) {
            return false;
        }

        List<PieceData> pieces = teamDataMap.get(selectedTeam).getPieces();
        if (pieces.isEmpty()) {
            return false;
        }

        PieceData pieceData = pieces.get(pieces.size() - 1);
        boardManager.updatePieceLabel(pieceData, labelText);
        this.labelText = pieceData.getLabelText();
        return true;
    }

    public boolean relabelPiece(PieceData pieceData) {
        if (!isEditing() || !ownsPiece(pieceData)) {
            return false;
        }

        boardManager.updatePieceLabel(pieceData, labelText);
        return true;
    }

    public int resizeCurrentPieces(double scale) {
        if (!isEditing() || scale <= 0.0D) {
            return -1;
        }

        List<PreviewPiece> previewPieces = new ArrayList<>();
        for (TeamType teamType : TeamType.values()) {
            for (PieceData pieceData : teamDataMap.get(teamType).getAlivePieces()) {
                previewPieces.add(new PreviewPiece(
                    teamType,
                    pieceData.getLocation(),
                    Math.max(0.5D, pieceData.getPieceSize() * scale),
                    pieceData.getHeightScale(),
                    pieceData.getLabelText()
                ));
            }
        }

        clearPreviewPieces();
        this.pieceSize = Math.max(0.5D, this.pieceSize * scale);
        for (TeamType teamType : TeamType.values()) {
            TeamData teamData = teamDataMap.get(teamType);
            int pieceId = 1;
            for (PreviewPiece previewPiece : previewPieces) {
                if (previewPiece.teamType() != teamType) {
                    continue;
                }
                teamData.getPieces().add(boardManager.spawnPiece(
                    teamType,
                    pieceId++,
                    previewPiece.location(),
                    previewPiece.pieceSize(),
                    previewPiece.heightScale(),
                    previewPiece.labelText()
                ));
            }
        }
        return previewPieces.size();
    }

    public int setCurrentPiecesHeight(double heightScale) {
        if (!isEditing() || heightScale <= 0.0D) {
            return -1;
        }

        List<PreviewPiece> previewPieces = new ArrayList<>();
        double normalizedHeightScale = Math.max(0.1D, heightScale);
        for (TeamType teamType : TeamType.values()) {
            for (PieceData pieceData : teamDataMap.get(teamType).getAlivePieces()) {
                previewPieces.add(new PreviewPiece(
                    teamType,
                    pieceData.getLocation(),
                    pieceData.getPieceSize(),
                    normalizedHeightScale,
                    pieceData.getLabelText()
                ));
            }
        }

        clearPreviewPieces();
        this.pieceHeightScale = normalizedHeightScale;
        for (TeamType teamType : TeamType.values()) {
            TeamData teamData = teamDataMap.get(teamType);
            int pieceId = 1;
            for (PreviewPiece previewPiece : previewPieces) {
                if (previewPiece.teamType() != teamType) {
                    continue;
                }
                teamData.getPieces().add(boardManager.spawnPiece(
                    teamType,
                    pieceId++,
                    previewPiece.location(),
                    previewPiece.pieceSize(),
                    previewPiece.heightScale(),
                    previewPiece.labelText()
                ));
            }
        }
        return previewPieces.size();
    }

    public void clearCurrentPieces() {
        clearPreviewPieces();
    }

    public @Nullable PresetData saveCurrentPreset() {
        if (!isEditing() || presetName == null) {
            return null;
        }

        PresetData presetData = new PresetData(presetName);
        for (TeamType teamType : TeamType.values()) {
            for (PieceData pieceData : teamDataMap.get(teamType).getAlivePieces()) {
                presetData.addPiece(
                    teamType,
                    pieceData.getLocation(),
                    pieceData.getPieceSize(),
                    pieceData.getHeightScale(),
                    pieceData.getLabelText()
                );
            }
        }

        presetRepository.savePreset(presetData);
        endEditing();
        return presetData;
    }

    public void endEditing() {
        if (!isEditing()) {
            return;
        }

        clearPreviewPieces();
        arenaData.setPieceSize(previousPieceSize);
        cleanupEditorPlayer();
        editorId = null;
        presetName = null;
        selectedTeam = TeamType.BLUE;
        labelText = getDefaultLabelText(selectedTeam);
        pieceHeightScale = 1.0D;
    }

    private void loadPresetPreview(PresetData presetData) {
        for (TeamType teamType : TeamType.values()) {
            TeamData teamData = teamDataMap.get(teamType);
            int pieceId = 1;
            for (PresetData.PresetPiece piece : presetData.getPieces(teamType)) {
                teamData.getPieces().add(boardManager.spawnPiece(
                    teamType,
                    pieceId++,
                    piece.location(),
                    piece.pieceSize(),
                    piece.heightScale(),
                    piece.labelText()
                ));
            }
        }
    }

    private String getDefaultLabelText(TeamType teamType) {
        return teamType == TeamType.BLUE ? "車" : "兵";
    }

    private boolean ownsPiece(PieceData pieceData) {
        TeamData teamData = teamDataMap.get(pieceData.getTeamType());
        return teamData != null && teamData.getPieces().contains(pieceData);
    }

    private void clearPreviewPieces() {
        boardManager.clearSessionPieces(teamDataMap);
        for (TeamData teamData : teamDataMap.values()) {
            teamData.clearPieces();
        }
    }

    private void prepareEditorPlayer(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.1F);
        player.getInventory().addItem(new ItemStack(Material.BLAZE_ROD));
    }

    private void cleanupEditorPlayer() {
        if (editorId == null) {
            return;
        }

        Player player = plugin.getServer().getPlayer(editorId);
        if (player == null) {
            return;
        }

        player.getInventory().remove(Material.BLAZE_ROD);
        player.updateInventory();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFlySpeed(0.1F);

        Location lobbyLocation = arenaData.getLobbyLocation();
        if (lobbyLocation != null) {
            player.teleport(lobbyLocation);
        }
    }

    private record PreviewPiece(
        TeamType teamType,
        Location location,
        double pieceSize,
        double heightScale,
        @Nullable String labelText
    ) {
    }
}
