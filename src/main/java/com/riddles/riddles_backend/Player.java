package com.riddles.riddles_backend;

import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;

/** Um jogador dentro de uma sala. */
public class Player {
    public final String id;
    /** Segredo usado para reconectar depois que o celular perde a conexão. */
    public final String token;
    public final String nickname;
    /** Índice da cor do jogador (0 a 9), único dentro da sala. */
    public final int color;
    public int score;
    public boolean connected = true;

    /** Conexão atual; gerenciada pelo Handler. A lógica do jogo não a usa. */
    public transient WebSocketSession session;

    public Player(String id, String token, String nickname, int color) {
        this.id = id;
        this.token = token;
        this.nickname = nickname;
        this.color = color;
    }

    public Map<String, Object> toPublicView(boolean isHost) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("nickname", nickname);
        view.put("color", color);
        view.put("score", score);
        view.put("connected", connected);
        view.put("isHost", isHost);
        return view;
    }
}
