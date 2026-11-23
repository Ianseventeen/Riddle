package com.riddles.riddles_backend;

/** Nível da sala, que define o banco de questões e a duração da partida. */
public enum Level {
    NORMAL("normal", 60),
    UNIVERSITARIO("universitario", 90);

    public final String id;
    public final int durationSeconds;

    Level(String id, int durationSeconds) {
        this.id = id;
        this.durationSeconds = durationSeconds;
    }

    public static Level fromId(String id) {
        for (Level l : values()) {
            if (l.id.equalsIgnoreCase(id)) return l;
        }
        throw new IllegalArgumentException("Nível inválido: " + id);
    }
}
