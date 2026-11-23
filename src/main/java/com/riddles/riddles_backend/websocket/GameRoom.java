package com.riddles.riddles_backend.websocket;

import com.riddles.riddles_backend.Level;
import com.riddles.riddles_backend.Player;
import com.riddles.riddles_backend.Question;
import org.springframework.web.socket.WebSocketSession;

import java.security.SecureRandom;
import java.util.*;

/**
 * Regra de uma sala: lobby, partida com timer global, primeiro acerto leva o ponto,
 * quem erra fica bloqueado na pergunta, revelação e resultado.
 *
 * Não conhece WebSocket: envia mensagens por {@link Listener} e agenda tarefas por
 * {@link Scheduler}, o que permite testar tudo com relógio falso. Todos os métodos
 * públicos são sincronizados na própria sala.
 */
public class GameRoom {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 10;
    public static final int MAX_NICKNAME_LENGTH = 16;
    public static final long REVEAL_MS = 2500;
    public static final long RECONNECT_GRACE_MS = 30_000;

    public enum State { LOBBY, PLAYING, REVEAL, FINISHED }

    /** Saída de mensagens da sala. */
    public interface Listener {
        void send(Player to, Map<String, Object> message);
        /** Chamado quando o último jogador sai e a sala pode ser descartada. */
        void roomEmpty(GameRoom room);
    }

    /** Relógio e agendador; em testes, um relógio manual. */
    public interface Scheduler {
        long now();
        /** Agenda a tarefa e devolve uma ação que a cancela. */
        Runnable schedule(Runnable task, long delayMs);
    }

    /** Erro de regra, enviado ao cliente com um código estável. */
    public static class GameException extends Exception {
        public final String code;
        public GameException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public final String code;
    public final Level level;

    private final Listener listener;
    private final Scheduler scheduler;
    private final Random random;
    private final SecureRandom tokens = new SecureRandom();

    private final List<Player> players = new ArrayList<>();
    private final Map<String, Runnable> pendingRemovals = new HashMap<>();
    private String hostId;
    private int nextPlayerSeq = 1;

    private State state = State.LOBBY;
    private final List<Question> pool;
    private int poolIndex;
    private Question current;
    private Map<String, Object> currentView;
    private int questionNumber;
    private final Set<String> locked = new HashSet<>();
    private long endsAt;
    /** Incrementa a cada partida, para ignorar tarefas agendadas de partidas antigas. */
    private int generation;
    private Runnable cancelGameEnd;
    private Runnable cancelNextQuestion;

    public GameRoom(String code, Level level, List<Question> questions,
                    Listener listener, Scheduler scheduler, Random random) {
        if (questions.isEmpty()) throw new IllegalArgumentException("Sem questões para o nível " + level.id);
        this.code = code;
        this.level = level;
        this.pool = new ArrayList<>(questions);
        this.listener = listener;
        this.scheduler = scheduler;
        this.random = random;
    }

    // ------------------------------------------------------------------ entrada e saída

    /** Coloca um novo jogador na sala; {@code session} pode ser null em testes. */
    public synchronized Player join(String rawNickname, WebSocketSession session) throws GameException {
        if (state != State.LOBBY) throw new GameException("GAME_IN_PROGRESS", "A partida já começou. Aguarde a próxima.");
        if (players.size() >= MAX_PLAYERS) throw new GameException("ROOM_FULL", "A sala está cheia (máximo de " + MAX_PLAYERS + ").");
        String nickname = normalizeNickname(rawNickname);
        for (Player p : players) {
            if (p.nickname.equalsIgnoreCase(nickname)) throw new GameException("NICK_TAKEN", "Já existe alguém com esse apelido na sala.");
        }

        Player player = new Player("p" + nextPlayerSeq++, newToken(), nickname, freeColor());
        player.session = session;
        players.add(player);
        if (hostId == null) hostId = player.id;

        Map<String, Object> joined = message("JOINED");
        joined.put("roomCode", code);
        joined.put("playerId", player.id);
        joined.put("token", player.token);
        listener.send(player, joined);
        broadcastRoom();
        return player;
    }

    /** Reconecta um jogador pelo token. Devolve null se o token não pertence à sala. */
    public synchronized Player resume(String token, WebSocketSession session) {
        Player player = findByToken(token);
        if (player == null) return null;
        player.session = session;
        Runnable cancel = pendingRemovals.remove(player.id);
        if (cancel != null) cancel.run();
        player.connected = true;

        Map<String, Object> joined = message("JOINED");
        joined.put("roomCode", code);
        joined.put("playerId", player.id);
        joined.put("token", player.token);
        joined.put("resumed", true);
        listener.send(player, joined);
        broadcastRoom();
        sendCurrentPhase(player);
        return player;
    }

    /** A conexão caiu: o jogador tem um tempo para voltar antes de sair da sala. */
    public synchronized void disconnected(Player player) {
        if (!players.contains(player) || !player.connected) return;
        player.connected = false;
        pendingRemovals.put(player.id, scheduler.schedule(() -> expire(player), RECONNECT_GRACE_MS));
        broadcastRoom();
        if (state == State.PLAYING) checkEveryoneLocked();
    }

    public synchronized void leave(Player player) {
        remove(player);
    }

    private synchronized void expire(Player player) {
        if (pendingRemovals.remove(player.id) != null) remove(player);
    }

    private void remove(Player player) {
        if (!players.remove(player)) return;
        Runnable cancel = pendingRemovals.remove(player.id);
        if (cancel != null) cancel.run();
        locked.remove(player.id);

        if (players.isEmpty()) {
            cancelTimers();
            listener.roomEmpty(this);
            return;
        }
        if (player.id.equals(hostId)) hostId = players.get(0).id;

        if ((state == State.PLAYING || state == State.REVEAL) && players.size() < MIN_PLAYERS) {
            finish();
            return;
        }
        broadcastRoom();
        if (state == State.PLAYING) checkEveryoneLocked();
    }

    // ------------------------------------------------------------------ partida

    public synchronized void start(Player requester) throws GameException {
        requireHost(requester);
        if (state != State.LOBBY) throw new GameException("INVALID_STATE", "A partida já começou.");
        if (players.size() < MIN_PLAYERS) throw new GameException("NOT_ENOUGH_PLAYERS", "São necessários pelo menos " + MIN_PLAYERS + " jogadores.");

        generation++;
        for (Player p : players) p.score = 0;
        questionNumber = 0;
        Collections.shuffle(pool, random);
        poolIndex = 0;

        long duration = level.durationSeconds * 1000L;
        endsAt = scheduler.now() + duration;
        int gen = generation;
        cancelGameEnd = scheduler.schedule(() -> onTimeUp(gen), duration);
        nextQuestion();
    }

    public synchronized void answer(Player player, String questionId, int value) throws GameException {
        if (state != State.PLAYING || current == null || !current.id.equals(questionId)) return; // resposta atrasada
        if (!players.contains(player)) return;
        if (locked.contains(player.id)) throw new GameException("LOCKED", "Você já errou esta pergunta. Aguarde a próxima.");
        if (!current.hasOption(value)) throw new GameException("INVALID_OPTION", "Alternativa inválida.");

        if (value == current.answer) {
            player.score++;
            reveal(player);
        } else {
            locked.add(player.id);
            Map<String, Object> wrong = message("WRONG");
            wrong.put("questionId", current.id);
            listener.send(player, wrong);
            broadcastLocked();
            checkEveryoneLocked();
        }
    }

    /** O host volta todos ao lobby para jogar de novo. */
    public synchronized void rematch(Player requester) throws GameException {
        requireHost(requester);
        if (state != State.FINISHED) throw new GameException("INVALID_STATE", "A partida ainda não terminou.");
        state = State.LOBBY;
        for (Player p : players) p.score = 0;
        current = null;
        currentView = null;
        locked.clear();
        broadcastRoom();
    }

    private void nextQuestion() {
        if (poolIndex >= pool.size()) {
            // Banco esgotado: embaralha de novo, evitando repetir a última de imediato.
            Question last = current;
            Collections.shuffle(pool, random);
            if (pool.size() > 1 && pool.get(0) == last) Collections.swap(pool, 0, 1);
            poolIndex = 0;
        }
        current = pool.get(poolIndex++);
        currentView = current.toClientView(random);
        questionNumber++;
        locked.clear();
        state = State.PLAYING;
        for (Player p : players) {
            if (p.connected) sendQuestion(p);
        }
    }

    private void reveal(Player winner) {
        state = State.REVEAL;
        Map<String, Object> msg = message("REVEAL");
        msg.put("questionId", current.id);
        msg.put("answer", current.answer);
        msg.put("rule", current.rule);
        msg.put("winnerId", winner != null ? winner.id : null);
        msg.put("players", publicPlayers());
        msg.put("endsAt", endsAt);
        msg.put("serverNow", scheduler.now());
        broadcast(msg);

        int gen = generation;
        cancelNextQuestion = scheduler.schedule(() -> onRevealDone(gen), REVEAL_MS);
    }

    private synchronized void onRevealDone(int gen) {
        if (gen != generation || state != State.REVEAL) return;
        if (scheduler.now() >= endsAt) {
            finish();
        } else {
            nextQuestion();
        }
    }

    private synchronized void onTimeUp(int gen) {
        if (gen != generation) return;
        if (state == State.PLAYING || state == State.REVEAL) finish();
    }

    private void finish() {
        cancelTimers();
        state = State.FINISHED;
        current = null;
        currentView = null;
        locked.clear();
        broadcast(gameOverMessage());
        broadcastRoom();
    }

    private void checkEveryoneLocked() {
        if (state != State.PLAYING) return;
        boolean anyoneCanAnswer = players.stream().anyMatch(p -> p.connected && !locked.contains(p.id));
        if (!anyoneCanAnswer) reveal(null);
    }

    private void cancelTimers() {
        if (cancelGameEnd != null) cancelGameEnd.run();
        if (cancelNextQuestion != null) cancelNextQuestion.run();
        cancelGameEnd = null;
        cancelNextQuestion = null;
    }

    // ------------------------------------------------------------------ mensagens

    private void sendQuestion(Player p) {
        Map<String, Object> msg = message("QUESTION");
        msg.put("number", questionNumber);
        msg.put("question", currentView);
        msg.put("endsAt", endsAt);
        msg.put("serverNow", scheduler.now());
        msg.put("locked", locked.contains(p.id));
        msg.put("lockedIds", new ArrayList<>(locked));
        listener.send(p, msg);
    }

    /** Coloca o jogador que reconectou na fase atual da partida. */
    private void sendCurrentPhase(Player p) {
        switch (state) {
            case PLAYING -> sendQuestion(p);
            case FINISHED -> listener.send(p, gameOverMessage());
            default -> { /* LOBBY e REVEAL: o snapshot da sala basta */ }
        }
    }

    private void broadcastLocked() {
        Map<String, Object> msg = message("LOCKED");
        msg.put("questionId", current.id);
        msg.put("lockedIds", new ArrayList<>(locked));
        broadcast(msg);
    }

    private void broadcastRoom() {
        Map<String, Object> msg = message("ROOM");
        msg.put("roomCode", code);
        msg.put("level", level.id);
        msg.put("durationSeconds", level.durationSeconds);
        msg.put("state", state.name());
        msg.put("hostId", hostId);
        msg.put("minPlayers", MIN_PLAYERS);
        msg.put("maxPlayers", MAX_PLAYERS);
        msg.put("players", publicPlayers());
        broadcast(msg);
    }

    private Map<String, Object> gameOverMessage() {
        Map<String, Object> msg = message("GAME_OVER");
        List<Map<String, Object>> ranking = new ArrayList<>();
        List<Player> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparingInt((Player p) -> -p.score));
        int position = 0;
        int lastScore = Integer.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            Player p = sorted.get(i);
            if (p.score != lastScore) {
                position = i + 1; // empate divide a posição
                lastScore = p.score;
            }
            Map<String, Object> view = p.toPublicView(p.id.equals(hostId));
            view.put("position", position);
            ranking.add(view);
        }
        msg.put("ranking", ranking);
        msg.put("questionsPlayed", questionNumber);
        return msg;
    }

    private List<Map<String, Object>> publicPlayers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : players) list.add(p.toPublicView(p.id.equals(hostId)));
        return list;
    }

    private void broadcast(Map<String, Object> msg) {
        for (Player p : players) {
            if (p.connected) listener.send(p, msg);
        }
    }

    private static Map<String, Object> message(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    // ------------------------------------------------------------------ utilitários

    private void requireHost(Player p) throws GameException {
        if (p == null || !p.id.equals(hostId)) throw new GameException("NOT_HOST", "Só o host pode fazer isso.");
    }

    private Player findByToken(String token) {
        if (token == null) return null;
        for (Player p : players) {
            if (p.token.equals(token)) return p;
        }
        return null;
    }

    private int freeColor() {
        Set<Integer> used = new HashSet<>();
        for (Player p : players) used.add(p.color);
        for (int c = 0; c < MAX_PLAYERS; c++) {
            if (!used.contains(c)) return c;
        }
        return 0;
    }

    private String newToken() {
        byte[] bytes = new byte[18];
        tokens.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String normalizeNickname(String raw) throws GameException {
        if (raw == null) raw = "";
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) throw new GameException("INVALID_NICKNAME", "Escolha um apelido.");
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_NICKNAME_LENGTH) {
            throw new GameException("INVALID_NICKNAME", "O apelido pode ter até " + MAX_NICKNAME_LENGTH + " caracteres.");
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ leitura (testes e Handler)

    public synchronized State state() { return state; }
    public synchronized String hostId() { return hostId; }
    public synchronized List<Player> players() { return List.copyOf(players); }
    public synchronized Question currentQuestion() { return current; }
    public synchronized boolean isEmpty() { return players.isEmpty(); }
}
