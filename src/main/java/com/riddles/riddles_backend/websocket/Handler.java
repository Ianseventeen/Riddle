package com.riddles.riddles_backend.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.riddles.riddles_backend.Player;
import com.riddles.riddles_backend.Question;
import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Handler extends TextWebSocketHandler {

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();
    private final List<Question> todasPerguntas;
    private final Gson gson = new Gson();

    public Handler() {
        try (InputStream is = getClass().getResourceAsStream("/questions.json")) {
            if (is != null) {
                InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                Question[] array = gson.fromJson(reader, Question[].class);
                todasPerguntas = Arrays.asList(array);
            } else {
                todasPerguntas = new ArrayList<>();
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao carregar perguntas: " + e.getMessage(), e);
        }
        System.out.println("Perguntas carregadas: " + todasPerguntas.size());
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        Map<String, String> params = parseQueryParams(session.getUri() != null ? session.getUri().getQuery() : null);
        String role = params.getOrDefault("role", "player");
        String mode = params.getOrDefault("mode", "single");
        String roomCode = params.get("room");

        if ("tv".equalsIgnoreCase(role)) {
            roomCode = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            GameRoom newRoom = new GameRoom(roomCode, mode, session, todasPerguntas);
            rooms.put(roomCode, newRoom);
            sessionToRoom.put(session.getId(), roomCode);

            Map<String, Object> resp = new HashMap<>();
            resp.put("type", "ROOM_CREATED");
            resp.put("roomCode", roomCode);
            resp.put("mode", mode);
            session.sendMessage(new TextMessage(gson.toJson(resp)));
            System.out.println("TV conectada! Nova sala criada: " + roomCode + " (" + mode + ")");

        } else {
            if (roomCode == null || !rooms.containsKey(roomCode)) {
                sendErrorAndClose(session, "Sala não encontrada: " + roomCode);
                return;
            }

            GameRoom room = rooms.get(roomCode);
            Player player = room.addPlayer(session);

            if (player == null) {
                sendErrorAndClose(session, "Sala " + roomCode + " já está lotada.");
                return;
            }

            sessionToRoom.put(session.getId(), roomCode);

            // Confirm join to Player
            Map<String, Object> playerResp = new HashMap<>();
            playerResp.put("type", "JOINED_SUCCESS");
            playerResp.put("playerNumber", player.playerNumber);
            playerResp.put("mode", room.mode);
            playerResp.put("roomCode", roomCode);
            session.sendMessage(new TextMessage(gson.toJson(playerResp)));

            // Notify TV about new player
            Map<String, Object> tvNotify = new HashMap<>();
            tvNotify.put("type", "PLAYER_JOINED");
            tvNotify.put("playerNumber", player.playerNumber);
            tvNotify.put("totalPlayers", room.players.size());
            tvNotify.put("isReady", room.isReadyToStart());

            if (room.tvSession != null && room.tvSession.isOpen()) {
                room.tvSession.sendMessage(new TextMessage(gson.toJson(tvNotify)));
            }

            System.out.println("Jogador " + player.playerNumber + " entrou na sala " + roomCode);
        }
    }

    @Override
    public void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        String roomCode = sessionToRoom.get(session.getId());
        if (roomCode == null || !rooms.containsKey(roomCode)) {
            return;
        }

        GameRoom room = rooms.get(roomCode);
        JsonObject json;
        try {
            json = JsonParser.parseString(message.getPayload()).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("Mensagem inválida recebida: " + message.getPayload());
            return;
        }

        String type = json.has("type") ? json.get("type").getAsString() : "";

        if ("START_GAME".equalsIgnoreCase(type)) {
            if (session.getId().equals(room.tvSession.getId())) {
                room.state = "PLAYING";
                room.currentQuestionIndex = 0;
                Question firstQuestion = room.getCurrentQuestion();

                // Notify TV
                Map<String, Object> tvMsg = new HashMap<>();
                tvMsg.put("type", "GAME_STARTED");
                tvMsg.put("question", firstQuestion);
                tvMsg.put("mode", room.mode);
                sendMessageSafely(room.tvSession, gson.toJson(tvMsg));

                // Notify Players
                Map<String, Object> playerMsg = new HashMap<>();
                playerMsg.put("type", "GAME_STARTED");
                playerMsg.put("questionId", firstQuestion != null ? firstQuestion.id : null);
                playerMsg.put("mode", room.mode);

                for (Player p : room.players) {
                    playerMsg.put("playerNumber", p.playerNumber);
                    sendMessageSafely(p.session, gson.toJson(playerMsg));
                }
            }
        } else if ("SUBMIT_ANSWER".equalsIgnoreCase(type)) {
            if (!"PLAYING".equalsIgnoreCase(room.state)) {
                return;
            }

            String questionId = json.has("questionId") ? json.get("questionId").getAsString() : "";
            int valorSubmitted = json.has("valor") ? json.get("valor").getAsInt() : Integer.MIN_VALUE;

            Question currentQ = room.getCurrentQuestion();
            if (currentQ == null || !currentQ.id.equals(questionId)) {
                return; // answer for old or wrong question
            }

            Player senderPlayer = findPlayerBySession(room, session);
            if (senderPlayer == null) return;

            boolean correct = (valorSubmitted == currentQ.answer);

            if ("single".equalsIgnoreCase(room.mode)) {
                if (correct) {
                    senderPlayer.score++;
                    Question nextQ = room.nextQuestion();

                    // Response to Player
                    Map<String, Object> pResp = new HashMap<>();
                    pResp.put("type", "ANSWER_RESULT");
                    pResp.put("correct", true);
                    pResp.put("score", senderPlayer.score);
                    pResp.put("nextQuestionId", nextQ != null ? nextQ.id : null);
                    sendMessageSafely(senderPlayer.session, gson.toJson(pResp));

                    // Broadcast update to TV
                    Map<String, Object> tvResp = new HashMap<>();
                    tvResp.put("type", "SCORE_UPDATE");
                    tvResp.put("score", senderPlayer.score);
                    tvResp.put("correct", true);
                    tvResp.put("nextQuestion", nextQ);
                    sendMessageSafely(room.tvSession, gson.toJson(tvResp));
                } else {
                    // Send wrong feedback to player
                    Map<String, Object> pResp = new HashMap<>();
                    pResp.put("type", "ANSWER_RESULT");
                    pResp.put("correct", false);
                    pResp.put("message", "Incorreto! Tente novamente.");
                    sendMessageSafely(senderPlayer.session, gson.toJson(pResp));
                }
            } else if ("multi".equalsIgnoreCase(room.mode)) {
                if (correct) {
                    senderPlayer.score++;
                    Question nextQ = room.nextQuestion();

                    Player p1 = room.players.size() > 0 ? room.players.get(0) : null;
                    Player p2 = room.players.size() > 1 ? room.players.get(1) : null;

                    // Notify TV
                    Map<String, Object> tvResp = new HashMap<>();
                    tvResp.put("type", "SCORE_UPDATE");
                    tvResp.put("p1Score", p1 != null ? p1.score : 0);
                    tvResp.put("p2Score", p2 != null ? p2.score : 0);
                    tvResp.put("roundWinner", senderPlayer.playerNumber);
                    tvResp.put("nextQuestion", nextQ);
                    sendMessageSafely(room.tvSession, gson.toJson(tvResp));

                    // Notify Players
                    for (Player p : room.players) {
                        Map<String, Object> pResp = new HashMap<>();
                        if (p.playerNumber == senderPlayer.playerNumber) {
                            pResp.put("type", "ANSWER_RESULT");
                            pResp.put("correct", true);
                            pResp.put("score", p.score);
                        } else {
                            pResp.put("type", "ROUND_WON_BY_OTHER");
                            pResp.put("winnerPlayer", senderPlayer.playerNumber);
                        }
                        pResp.put("nextQuestionId", nextQ != null ? nextQ.id : null);
                        sendMessageSafely(p.session, gson.toJson(pResp));
                    }
                } else {
                    // Incorrect answer in Multiplayer: don't penalize, just inform sender to try again!
                    Map<String, Object> pResp = new HashMap<>();
                    pResp.put("type", "ANSWER_RESULT");
                    pResp.put("correct", false);
                    pResp.put("message", "Incorreto! Tente novamente.");
                    sendMessageSafely(senderPlayer.session, gson.toJson(pResp));
                }
            }
        } else if ("TIME_UP".equalsIgnoreCase(type)) {
            if (session.getId().equals(room.tvSession.getId())) {
                room.state = "FINISHED";

                // Notify TV
                Map<String, Object> tvMsg = new HashMap<>();
                tvMsg.put("type", "GAME_OVER");
                tvMsg.put("mode", room.mode);
                sendMessageSafely(room.tvSession, gson.toJson(tvMsg));

                // Notify Players
                for (Player p : room.players) {
                    Map<String, Object> pMsg = new HashMap<>();
                    pMsg.put("type", "GAME_OVER");
                    pMsg.put("score", p.score);
                    pMsg.put("playerNumber", p.playerNumber);
                    pMsg.put("mode", room.mode);
                    sendMessageSafely(p.session, gson.toJson(pMsg));
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        String roomCode = sessionToRoom.get(session.getId());
        if (roomCode != null && rooms.containsKey(roomCode)) {
            GameRoom room = rooms.get(roomCode);

            if (room.tvSession != null && room.tvSession.getId().equals(session.getId())) {
                // TV disconnected
                rooms.remove(roomCode);
                System.out.println("TV se desconectou. Sala " + roomCode + " encerrada.");
            } else {
                // Player disconnected
                room.players.removeIf(p -> p.session.getId().equals(session.getId()));
                Map<String, Object> tvNotify = new HashMap<>();
                tvNotify.put("type", "PLAYER_LEFT");
                tvNotify.put("totalPlayers", room.players.size());
                tvNotify.put("isReady", room.isReadyToStart());
                sendMessageSafely(room.tvSession, gson.toJson(tvNotify));
            }
        }
        sessionToRoom.remove(session.getId());
    }

    private Player findPlayerBySession(GameRoom room, WebSocketSession session) {
        for (Player p : room.players) {
            if (p.session.getId().equals(session.getId())) {
                return p;
            }
        }
        return null;
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null && !query.trim().isEmpty()) {
            for (String pair : query.split("&")) {
                String[] keyValue = pair.split("=");
                if (keyValue.length >= 2) {
                    params.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    params.put(keyValue[0], "");
                }
            }
        }
        return params;
    }

    private void sendErrorAndClose(WebSocketSession session, String errorMsg) {
        try {
            Map<String, Object> err = new HashMap<>();
            err.put("type", "ERROR");
            err.put("message", errorMsg);
            session.sendMessage(new TextMessage(gson.toJson(err)));
            session.close(CloseStatus.BAD_DATA);
        } catch (Exception ignored) {}
    }

    private void sendMessageSafely(WebSocketSession session, String messageText) {
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(messageText));
            } catch (Exception e) {
                System.out.println("Erro ao enviar mensagem WS: " + e.getMessage());
            }
        }
    }
}