package org.ha2yo.alkagi.game;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class PresetData {

    public record PresetPiece(Location location, double pieceSize, double heightScale, @Nullable String labelText) {
        public PresetPiece(Location location, double pieceSize) {
            this(location, pieceSize, 1.0D, null);
        }

        public PresetPiece(Location location, double pieceSize, @Nullable String labelText) {
            this(location, pieceSize, 1.0D, labelText);
        }

        public PresetPiece {
            location = location.clone();
            pieceSize = Math.max(0.5D, pieceSize);
            heightScale = Math.max(0.1D, heightScale);
            labelText = normalizeLabelText(labelText);
        }
    }

    private final String name;
    private final Map<TeamType, List<PresetPiece>> piecesByTeam = new EnumMap<>(TeamType.class);

    public PresetData(String name) {
        this.name = name;
        piecesByTeam.put(TeamType.BLUE, new ArrayList<>());
        piecesByTeam.put(TeamType.RED, new ArrayList<>());
    }

    public String getName() {
        return name;
    }

    public void addPiece(TeamType teamType, Location location, double pieceSize) {
        addPiece(teamType, location, pieceSize, null);
    }

    public void addPiece(TeamType teamType, Location location, double pieceSize, @Nullable String labelText) {
        addPiece(teamType, location, pieceSize, 1.0D, labelText);
    }

    public void addPiece(
            TeamType teamType,
            Location location,
            double pieceSize,
            double heightScale,
            @Nullable String labelText
    ) {
        piecesByTeam.get(teamType).add(new PresetPiece(location, pieceSize, heightScale, labelText));
    }

    public List<PresetPiece> getPieces(TeamType teamType) {
        return piecesByTeam.get(teamType).stream()
            .map(piece -> new PresetPiece(piece.location(), piece.pieceSize(), piece.heightScale(), piece.labelText()))
            .toList();
    }

    public int getPieceCount() {
        return piecesByTeam.get(TeamType.BLUE).size();
    }

    public boolean isBalanced() {
        return piecesByTeam.get(TeamType.BLUE).size() == piecesByTeam.get(TeamType.RED).size();
    }

    public boolean isEmpty() {
        return piecesByTeam.values().stream().allMatch(List::isEmpty);
    }

    public @Nullable Location getAnyLocation() {
        for (TeamType teamType : TeamType.values()) {
            List<PresetPiece> pieces = piecesByTeam.get(teamType);
            if (!pieces.isEmpty()) {
                return pieces.get(0).location().clone();
            }
        }
        return null;
    }

    private static @Nullable String normalizeLabelText(@Nullable String labelText) {
        if (labelText == null) {
            return null;
        }

        String trimmed = labelText.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
