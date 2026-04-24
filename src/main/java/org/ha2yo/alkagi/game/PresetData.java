package org.ha2yo.alkagi.game;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class PresetData {

    public record PresetPiece(Location location, double pieceSize) {
        public PresetPiece {
            location = location.clone();
            pieceSize = Math.max(0.5D, pieceSize);
        }
    }

    private final String name;
    private final Map<TeamType, List<PresetPiece>> piecesByTeam = new EnumMap<>(TeamType.class);

    public PresetData(String name) {
        this.name = name;
        piecesByTeam.put(TeamType.BLACK, new ArrayList<>());
        piecesByTeam.put(TeamType.WHITE, new ArrayList<>());
    }

    public String getName() {
        return name;
    }

    public void addPiece(TeamType teamType, Location location, double pieceSize) {
        piecesByTeam.get(teamType).add(new PresetPiece(location, pieceSize));
    }

    public List<PresetPiece> getPieces(TeamType teamType) {
        return piecesByTeam.get(teamType).stream()
            .map(piece -> new PresetPiece(piece.location(), piece.pieceSize()))
            .toList();
    }

    public int getPieceCount() {
        return piecesByTeam.get(TeamType.BLACK).size();
    }

    public boolean isBalanced() {
        return piecesByTeam.get(TeamType.BLACK).size() == piecesByTeam.get(TeamType.WHITE).size();
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
}
