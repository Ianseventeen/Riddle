package com.riddles.riddles_backend.websocket;

import com.riddles.riddles_backend.Player;
import com.riddles.riddles_backend.Question;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameRoom {
    public String roomCode;
    public String mode; // "single" or "multi"
    public WebSocketSession tvSession;
    public List<Player> players = new ArrayList<>();
    public int currentQuestionIndex = 0;
    public String state = "LOBBY"; // LOBBY, PLAYING, FINISHED
    public List<Question> questions = new ArrayList<>();

    public GameRoom(String roomCode, String mode, WebSocketSession tvSession, List<Question> questionsPool) {
        this.roomCode = roomCode;
        this.mode = mode != null ? mode.toLowerCase() : "single";
        this.tvSession = tvSession;
        this.questions = new ArrayList<>(questionsPool);
        Collections.shuffle(this.questions);
    }

    public synchronized Player addPlayer(WebSocketSession session) {
        if ("single".equalsIgnoreCase(mode) && !players.isEmpty()) {
            return null;
        }
        if ("multi".equalsIgnoreCase(mode) && players.size() >= 2) {
            return null;
        }
        Player p = new Player();
        p.session = session;
        p.room = roomCode;
        p.score = 0;
        p.playerNumber = players.size() + 1;
        p.role = "player";
        players.add(p);
        return p;
    }

    public synchronized Question getCurrentQuestion() {
        if (questions == null || questions.isEmpty()) return null;
        return questions.get(currentQuestionIndex % questions.size());
    }

    public synchronized Question nextQuestion() {
        currentQuestionIndex++;
        return getCurrentQuestion();
    }

    public synchronized boolean isReadyToStart() {
        if ("single".equalsIgnoreCase(mode)) {
            return players.size() >= 1;
        } else {
            return players.size() >= 2;
        }
    }
}
