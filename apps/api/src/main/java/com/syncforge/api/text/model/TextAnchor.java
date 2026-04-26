package com.syncforge.api.text.model;

public record TextAnchor(String atomId) {
    public static final String START = "START";

    public TextAnchor {
        atomId = atomId == null || atomId.isBlank() ? START : atomId;
    }

    public static TextAnchor start() {
        return new TextAnchor(START);
    }

    public static TextAnchor after(String atomId) {
        return new TextAnchor(atomId);
    }

    public boolean isStart() {
        return START.equals(atomId);
    }
}
