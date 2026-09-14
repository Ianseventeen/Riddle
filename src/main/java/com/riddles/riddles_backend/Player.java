package com.riddles.riddles_backend;

import org.springframework.web.socket.WebSocketSession;

public class Player {
    public WebSocketSession session;
    public String role; // "tv" or "player"
    public String username;
    public String email;
    public int score;
    public String room;
    public int playerNumber; // 1 (Blue) or 2 (Red)
}
