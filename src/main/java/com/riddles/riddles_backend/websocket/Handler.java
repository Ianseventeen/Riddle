package com.riddles.riddles_backend.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.riddles.riddles_backend.Level;
import com.riddles.riddles_backend.Player;
import com.riddles.riddles_backend.QuestionBank;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ponte entre o WebSocket e as salas. Protocolo (JSON, campo {@code type}):
 *
 * <pre>
 * celular → servidor
 *   CREATE  {nickname, level}       cria a sala e entra como host
 *   JOIN    {room, nickname}        entra numa sala existente
 *   RESUME  {room, token}           volta depois de perder a conexão
 *   START   {}                      host começa a partida
 *   ANSWER  {questionId, value}     responde a pergunta atual
 *   REMATCH {}                      host volta todos ao lobby
 *   LEAVE   {}                      sai da sala
 *   PING    {}                      mantém a conexão viva
 *
 * servidor → celular
 *   JOINED, ROOM, QUESTION, WRONG, LOCKED, REVEAL, GAME_OVER, ERROR, PONG
 * </pre>
 */
public class Handler extends TextWebSocketHandler implements GameRoom.Listener {

    private static final Logger log = LoggerFactory.getLogger(Handler.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 5;
    private static final int MAX_ROOMS = 500;
    private static final int MAX_PAYLOAD = 4096;

    private record Binding(GameRoom room, Player player) {}

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final QuestionBank bank;
    private final GameRoom.Scheduler scheduler;
    private final Random random = new SecureRandom();
    private final Gson gson = new Gson();

    public Handler(QuestionBank bank, GameRoom.Scheduler scheduler) {
        this.bank = bank;
        this.scheduler = scheduler;
        log.info("Perguntas carregadas: {}", bank.size());
    }

    public Handler() {
        this(QuestionBank.loadDefault(), executorScheduler());
    }

    private static GameRoom.Scheduler executorScheduler() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "riddles-timers");
            t.setDaemon(true);
            return t;
        });
        return new GameRoom.Scheduler() {
            @Override public long now() { return System.currentTimeMillis(); }
            @Override public Runnable schedule(Runnable task, long delayMs) {
                ScheduledFuture<?> f = exec.schedule(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("Erro numa tarefa agendada", e);
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
                return () -> f.cancel(false);
            }
        };
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        // Garante que envios de threads diferentes (timer e requisições) não se atropelem.
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024));
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        WebSocketSession s = sessions.remove(session.getId());
        Binding b = bindings.remove(session.getId());
        if (b != null && b.player().session == s) {
            b.room().disconnected(b.player());
        }
    }

    @Override
    public void handleTextMessage(@NonNull WebSocketSession raw, @NonNull TextMessage message) {
        WebSocketSession session = sessions.get(raw.getId());
        if (session == null) return;
        if (message.getPayloadLength() > MAX_PAYLOAD) return;

        JsonObject json;
        try {
            json = JsonParser.parseString(message.getPayload()).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String type = str(json, "type").toUpperCase();

        try {
            switch (type) {
                case "CREATE" -> create(session, json);
                case "JOIN" -> join(session, json);
                case "RESUME" -> resume(session, json);
                case "START" -> {
                    Binding b = requireBinding(session);
                    b.room().start(b.player());
                }
                case "ANSWER" -> {
                    Binding b = requireBinding(session);
                    JsonElement v = json.get("value");
                    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                        throw new GameRoom.GameException("INVALID_OPTION", "Alternativa inválida.");
                    }
                    b.room().answer(b.player(), str(json, "questionId"), v.getAsInt());
                }
                case "REMATCH" -> {
                    Binding b = requireBinding(session);
                    b.room().rematch(b.player());
                }
                case "LEAVE" -> leaveCurrent(session);
                case "PING" -> sendRaw(session, Map.of("type", "PONG"));
                default -> { /* ignora tipos desconhecidos */ }
            }
        } catch (GameRoom.GameException e) {
            sendError(session, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Erro ao tratar mensagem {}", type, e);
            sendError(session, "INTERNAL", "Algo deu errado. Tente de novo.");
        }
    }

    // ------------------------------------------------------------------ ações

    private void create(WebSocketSession session, JsonObject json) throws GameRoom.GameException {
        Level level;
        try {
            level = Level.fromId(str(json, "level"));
        } catch (IllegalArgumentException e) {
            throw new GameRoom.GameException("INVALID_LEVEL", "Escolha Normal ou Universitário.");
        }
        if (rooms.size() >= MAX_ROOMS) throw new GameRoom.GameException("SERVER_FULL", "Servidor cheio. Tente daqui a pouco.");

        leaveCurrent(session);
        GameRoom room = newRoom(level);
        try {
            Player player = room.join(str(json, "nickname"), session);
            bindings.put(session.getId(), new Binding(room, player));
            log.info("Sala {} criada ({})", room.code, level.id);
        } catch (GameRoom.GameException e) {
            rooms.remove(room.code, room);
            throw e;
        }
    }

    private void join(WebSocketSession session, JsonObject json) throws GameRoom.GameException {
        GameRoom room = findRoom(str(json, "room"));
        leaveCurrent(session);
        Player player = room.join(str(json, "nickname"), session);
        bindings.put(session.getId(), new Binding(room, player));
    }

    private void resume(WebSocketSession session, JsonObject json) throws GameRoom.GameException {
        GameRoom room = findRoom(str(json, "room"));
        String token = str(json, "token");
        Binding current = bindings.get(session.getId());
        if (current != null && current.room() != room) leaveCurrent(session);

        Player player;
        WebSocketSession previous;
        synchronized (room) {
            Player existing = room.players().stream()
                    .filter(p -> p.token.equals(token)).findFirst().orElse(null);
            previous = existing != null ? existing.session : null;
            player = room.resume(token, session);
        }
        if (player == null) throw new GameRoom.GameException("RESUME_FAILED", "Não foi possível voltar para a sala.");
        bindings.put(session.getId(), new Binding(room, player));

        // Se o mesmo jogador estava aberto em outra conexão (outra aba), ela é encerrada.
        if (previous != null && previous != session) {
            bindings.remove(previous.getId());
            try {
                previous.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // a conexão antiga já pode estar morta
            }
        }
    }

    private void leaveCurrent(WebSocketSession session) {
        Binding b = bindings.remove(session.getId());
        if (b != null) b.room().leave(b.player());
    }

    // ------------------------------------------------------------------ GameRoom.Listener

    @Override
    public void send(Player to, Map<String, Object> message) {
        if (to.session != null) sendRaw(to.session, message);
    }

    @Override
    public void roomEmpty(GameRoom room) {
        rooms.remove(room.code, room);
        log.info("Sala {} encerrada", room.code);
    }

    // ------------------------------------------------------------------ utilitários

    private GameRoom newRoom(Level level) {
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            GameRoom room = new GameRoom(sb.toString(), level, bank.forLevel(level), this, scheduler, random);
            if (rooms.putIfAbsent(room.code, room) == null) return room;
        }
    }

    private GameRoom findRoom(String code) throws GameRoom.GameException {
        GameRoom room = rooms.get(code.trim().toUpperCase());
        if (room == null) throw new GameRoom.GameException("ROOM_NOT_FOUND", "Sala não encontrada. Confira o código.");
        return room;
    }

    private Binding requireBinding(WebSocketSession session) throws GameRoom.GameException {
        Binding b = bindings.get(session.getId());
        if (b == null) throw new GameRoom.GameException("NOT_IN_ROOM", "Você não está em uma sala.");
        return b;
    }

    private static String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", "ERROR");
        err.put("code", code);
        err.put("message", message);
        sendRaw(session, err);
    }

    private void sendRaw(WebSocketSession session, Map<String, Object> message) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(gson.toJson(message)));
        } catch (Exception e) {
            log.debug("Falha ao enviar para {}: {}", session.getId(), e.getMessage());
        }
    }
}
