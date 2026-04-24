package org.ha2yo.alkagi.game;

/**
 * 게임 세션이 어떤 단계에 있는지를 나타낸다.
 */
public enum GameState {
    WAITING,
    TEAM_ASSIGNING,
    PLACING,
    PLAYING,
    ENDING
}
