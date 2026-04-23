package org.ha2yo.alkagi.game;

/**
 * 게임 세션의 진행 단계를 나타낸다.
 */
public enum GameState {
    WAITING,
    TEAM_ASSIGNING,
    PLACING,
    PLAYING,
    ENDING
}
