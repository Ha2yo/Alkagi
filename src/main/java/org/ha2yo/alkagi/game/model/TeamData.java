package org.ha2yo.alkagi.game.model;

import org.ha2yo.alkagi.game.TeamType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 팀 소속 플레이어, 턴 순서, 말 목록을 보관한다.
 */
public final class TeamData {

    private final TeamType teamType;
    private final List<UUID> players = new ArrayList<>();
    private final Deque<UUID> turnQueue = new ArrayDeque<>();
    private final List<PieceData> pieces = new ArrayList<>();

    public TeamData(TeamType teamType) {
        this.teamType = teamType;
    }

    public TeamType getTeamType() {
        return teamType;
    }

    public List<UUID> getPlayers() {
        return List.copyOf(players);
    }

    public void addPlayer(UUID playerId) {
        if (!players.contains(playerId)) {
            players.add(playerId);
            turnQueue.addLast(playerId);
        }
    }

    public void removePlayer(UUID playerId) {
        players.remove(playerId);
        turnQueue.removeIf(id -> id.equals(playerId));
    }

    public UUID pollNextPlayer() {
        return turnQueue.pollFirst();
    }

    public void pushBackPlayer(UUID playerId) {
        if (players.contains(playerId)) {
            turnQueue.addLast(playerId);
        }
    }

    public boolean hasQueuedPlayers() {
        return !turnQueue.isEmpty();
    }

    public List<UUID> getTurnQueueSnapshot() {
        return List.copyOf(turnQueue);
    }

    public List<PieceData> getPieces() {
        return pieces;
    }

    public List<PieceData> getAlivePieces() {
        return pieces.stream().filter(PieceData::isAlive).toList();
    }

    public int getAlivePieceCount() {
        return (int) pieces.stream().filter(PieceData::isAlive).count();
    }

    public void clearPieces() {
        pieces.clear();
    }

    public void clearPlayers() {
        players.clear();
        turnQueue.clear();
    }
}
