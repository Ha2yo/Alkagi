package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 알까기에서 사용하는 팀 종류와 표시 색상을 정의한다.
 */
public enum TeamType {
    BLUE("청", NamedTextColor.BLUE),
    RED("홍", NamedTextColor.RED);

    private final String displayName;
    private final NamedTextColor color;

    TeamType(String displayName, NamedTextColor color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public NamedTextColor getColor() {
        return color;
    }

    /**
     * 상대 팀을 반환한다.
     */
    public TeamType opposite() {
        return this == BLUE ? RED : BLUE;
    }
}
