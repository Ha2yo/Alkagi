package org.ha2yo.alkagi.game;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 게임에서 사용하는 팀 종류와 팀 표시 정보를 담는다.
 */
public enum TeamType {
    BLACK("흑", NamedTextColor.DARK_GRAY),
    WHITE("백", NamedTextColor.WHITE);

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

    public TeamType opposite() {
        return this == BLACK ? WHITE : BLACK;
    }
}