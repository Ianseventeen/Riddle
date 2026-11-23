package com.riddles.riddles_backend.websocket;

import com.riddles.riddles_backend.Level;
import com.riddles.riddles_backend.Player;
import com.riddles.riddles_backend.Question;
import com.riddles.riddles_backend.QuestionBank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameRoomTest {

    /** Relógio manual: o tempo só anda quando o teste chama {@link #advance}. */
    static class FakeScheduler implements GameRoom.Scheduler {
        record Task(long at, Runnable run, boolean[] cancelled) {}
        long now = 1_000_000;
        final List<Task> tasks = new ArrayList<>();

        @Override public long now() { return now; }

        @Override public Runnable schedule(Runnable task, long delayMs) {
            boolean[] cancelled = {false};
            tasks.add(new Task(now + delayMs, task, cancelled));
            return () -> cancelled[0] = true;
        }

        void advance(long ms) {
            long target = now + ms;
            while (true) {
                Task next = tasks.stream()
                        .filter(t -> !t.cancelled()[0] && t.at() <= target)
                        .min(Comparator.comparingLong(Task::at)).orElse(null);
                if (next == null) break;
                tasks.remove(next);
                now = next.at();
                next.run().run();
            }
            now = target;
        }
    }

    /** Guarda tudo o que a sala enviou para cada jogador. */
    static class Recorder implements GameRoom.Listener {
        final Map<Player, List<Map<String, Object>>> inbox = new HashMap<>();
        boolean empty;

        @Override public void send(Player to, Map<String, Object> message) {
            inbox.computeIfAbsent(to, k -> new ArrayList<>()).add(new LinkedHashMap<>(message));
        }

        @Override public void roomEmpty(GameRoom room) { empty = true; }

        List<Map<String, Object>> of(Player p, String type) {
            return inbox.getOrDefault(p, List.of()).stream().filter(m -> type.equals(m.get("type"))).toList();
        }

        Map<String, Object> last(Player p, String type) {
            List<Map<String, Object>> list = of(p, type);
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }
    }

    FakeScheduler clock;
    Recorder out;
    GameRoom room;

    static Question question(String id, int answer, int... options) {
        Question q = new Question();
        q.id = id;
        q.category = "padrao";
        q.level = "normal";
        q.sequence = new Integer[]{1, 2, 3, null};
        q.options = options;
        q.answer = answer;
        q.rule = "regra " + id;
        return q;
    }

    @BeforeEach
    void setUp() {
        clock = new FakeScheduler();
        out = new Recorder();
        List<Question> qs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) qs.add(question("Q" + i, 4, 4, 5, 6, 7));
        room = new GameRoom("ABCDE", Level.NORMAL, qs, out, clock, new Random(42));
    }

    Player join(String nick) throws Exception {
        return room.join(nick, null);
    }

    String currentId() {
        return room.currentQuestion().id;
    }

    // ------------------------------------------------------------------ lobby

    @Test
    void primeiroJogadorViraHostERecebeToken() throws Exception {
        Player a = join("Ana");
        assertEquals(a.id, room.hostId());
        Map<String, Object> joined = out.last(a, "JOINED");
        assertEquals("ABCDE", joined.get("roomCode"));
        assertEquals(a.token, joined.get("token"));
        assertNotNull(out.last(a, "ROOM"));
    }

    @Test
    void aceitaDezJogadoresERecusaODecimoPrimeiro() throws Exception {
        for (int i = 1; i <= 10; i++) join("J" + i);
        GameRoom.GameException e = assertThrows(GameRoom.GameException.class, () -> join("J11"));
        assertEquals("ROOM_FULL", e.code);
        Set<Integer> colors = new HashSet<>();
        room.players().forEach(p -> colors.add(p.color));
        assertEquals(10, colors.size(), "cada jogador tem uma cor diferente");
    }

    @Test
    void recusaApelidoRepetidoVazioOuLongo() throws Exception {
        join("Ana");
        assertEquals("NICK_TAKEN", assertThrows(GameRoom.GameException.class, () -> join("  ana ")).code);
        assertEquals("INVALID_NICKNAME", assertThrows(GameRoom.GameException.class, () -> join("   ")).code);
        assertEquals("INVALID_NICKNAME", assertThrows(GameRoom.GameException.class, () -> join("x".repeat(17))).code);
    }

    @Test
    void soOHostComecaEPrecisaDeDoisJogadores() throws Exception {
        Player a = join("Ana");
        assertEquals("NOT_ENOUGH_PLAYERS", assertThrows(GameRoom.GameException.class, () -> room.start(a)).code);
        Player b = join("Bia");
        assertEquals("NOT_HOST", assertThrows(GameRoom.GameException.class, () -> room.start(b)).code);
        room.start(a);
        assertEquals(GameRoom.State.PLAYING, room.state());
    }

    @Test
    void ninguemEntraDepoisQueAPartidaComeca() throws Exception {
        Player a = join("Ana");
        join("Bia");
        room.start(a);
        assertEquals("GAME_IN_PROGRESS", assertThrows(GameRoom.GameException.class, () -> join("Caio")).code);
    }

    // ------------------------------------------------------------------ pergunta

    @Test
    void perguntaNaoRevelaRespostaNemRegra() throws Exception {
        Player a = join("Ana");
        join("Bia");
        room.start(a);
        @SuppressWarnings("unchecked")
        Map<String, Object> q = (Map<String, Object>) out.last(a, "QUESTION").get("question");
        assertFalse(q.containsKey("answer"));
        assertFalse(q.containsKey("rule"));
        assertEquals(4, ((List<?>) q.get("options")).size());
        assertEquals(clock.now() + 60_000, out.last(a, "QUESTION").get("endsAt"));
    }

    @Test
    void primeiroAcertoLevaOPontoERevela() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.answer(b, currentId(), 4);

        assertEquals(1, b.score);
        assertEquals(GameRoom.State.REVEAL, room.state());
        Map<String, Object> reveal = out.last(a, "REVEAL");
        assertEquals(b.id, reveal.get("winnerId"));
        assertEquals(4, reveal.get("answer"));
        assertNotNull(reveal.get("rule"));

        // Uma resposta que chega depois do acerto é ignorada.
        room.answer(a, (String) reveal.get("questionId"), 4);
        assertEquals(0, a.score);

        clock.advance(GameRoom.REVEAL_MS);
        assertEquals(GameRoom.State.PLAYING, room.state());
        assertEquals(2, out.last(a, "QUESTION").get("number"));
    }

    @Test
    void quemErraFicaBloqueadoNaPergunta() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        join("Caio");
        room.start(a);
        String q = currentId();
        room.answer(a, q, 5);

        assertNotNull(out.last(a, "WRONG"));
        assertEquals(List.of(a.id), out.last(b, "LOCKED").get("lockedIds"));
        assertEquals("LOCKED", assertThrows(GameRoom.GameException.class, () -> room.answer(a, q, 4)).code);
        assertEquals(GameRoom.State.PLAYING, room.state(), "os outros ainda podem responder");
    }

    @Test
    void seTodosErraremRevelaSemPonto() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.answer(a, currentId(), 5);
        room.answer(b, currentId(), 6);

        assertEquals(GameRoom.State.REVEAL, room.state());
        assertNull(out.last(a, "REVEAL").get("winnerId"));
        assertEquals(0, a.score + b.score);
    }

    @Test
    void alternativaForaDasOpcoesERecusada() throws Exception {
        Player a = join("Ana");
        join("Bia");
        room.start(a);
        assertEquals("INVALID_OPTION", assertThrows(GameRoom.GameException.class, () -> room.answer(a, currentId(), 999)).code);
    }

    @Test
    void perguntasNaoSeRepetemAteOBancoAcabar() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            assertTrue(seen.add(currentId()), "repetiu " + currentId());
            room.answer(b, currentId(), 4);
            clock.advance(GameRoom.REVEAL_MS);
        }
        assertEquals(6, out.last(a, "QUESTION").get("number"), "depois de esgotar, o banco é reembaralhado");
    }

    // ------------------------------------------------------------------ tempo e fim

    @Test
    void tempoGlobalEncerraAPartidaComRanking() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.answer(b, currentId(), 4);
        clock.advance(60_000);

        assertEquals(GameRoom.State.FINISHED, room.state());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.last(a, "GAME_OVER").get("ranking");
        assertEquals(b.id, ranking.get(0).get("id"));
        assertEquals(1, ranking.get(0).get("position"));
        assertEquals(2, ranking.get(1).get("position"));
    }

    @Test
    void universitarioDura90Segundos() throws Exception {
        GameRoom uni = new GameRoom("UNIV1", Level.UNIVERSITARIO, List.of(question("U1", 4, 4, 5, 6, 7)), out, clock, new Random(1));
        Player a = uni.join("Ana", null);
        uni.join("Bia", null);
        uni.start(a);
        clock.advance(60_000);
        assertEquals(GameRoom.State.PLAYING, uni.state());
        clock.advance(30_000);
        assertEquals(GameRoom.State.FINISHED, uni.state());
    }

    @Test
    void empateDividePosicao() throws Exception {
        Player a = join("Ana");
        join("Bia");
        room.start(a);
        clock.advance(60_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.last(a, "GAME_OVER").get("ranking");
        assertEquals(1, ranking.get(0).get("position"));
        assertEquals(1, ranking.get(1).get("position"));
    }

    @Test
    void revancheVoltaAoLobbyComPlacarZerado() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.answer(b, currentId(), 4);
        clock.advance(60_000);
        assertEquals("NOT_HOST", assertThrows(GameRoom.GameException.class, () -> room.rematch(b)).code);
        room.rematch(a);
        assertEquals(GameRoom.State.LOBBY, room.state());
        assertEquals(0, b.score);
        room.start(a);
        assertEquals(GameRoom.State.PLAYING, room.state());
    }

    @Test
    void timerDaPartidaAnteriorNaoEncerraARevanche() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.leave(b); // a partida termina por falta de jogadores, mas o timer de 60 s seguia agendado
        clock.advance(30_000);
        room.rematch(a);
        Player c = join("Caio");
        room.start(a);
        clock.advance(59_000);
        assertEquals(GameRoom.State.PLAYING, room.state());
        assertNotNull(c);
    }

    // ------------------------------------------------------------------ saídas e reconexão

    @Test
    void hostQueSaiPassaOHostParaOProximo() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.leave(a);
        assertEquals(b.id, room.hostId());
        assertEquals(b.id, out.last(b, "ROOM").get("hostId"));
    }

    @Test
    void partidaTerminaSeSobrarUmJogador() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.leave(b);
        assertEquals(GameRoom.State.FINISHED, room.state());
        assertNotNull(out.last(a, "GAME_OVER"));
    }

    @Test
    void partidaSegueSeSobraremDoisOuMais() throws Exception {
        Player a = join("Ana");
        join("Bia");
        Player c = join("Caio");
        room.start(a);
        room.leave(c);
        assertEquals(GameRoom.State.PLAYING, room.state());
    }

    @Test
    void salaVaziaAvisaParaSerDescartada() throws Exception {
        Player a = join("Ana");
        room.leave(a);
        assertTrue(out.empty);
        assertTrue(room.isEmpty());
    }

    @Test
    void desconectadoVoltaComTokenEReceberAPerguntaAtual() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        room.start(a);
        room.disconnected(b);
        assertFalse(b.connected);

        clock.advance(10_000);
        Player back = room.resume(b.token, null);
        assertSame(b, back);
        assertTrue(b.connected);
        assertEquals(Boolean.TRUE, out.last(b, "JOINED").get("resumed"));
        assertEquals(currentId(), ((Map<?, ?>) out.last(b, "QUESTION").get("question")).get("id"));

        clock.advance(GameRoom.RECONNECT_GRACE_MS);
        assertTrue(room.players().contains(b), "a remoção agendada foi cancelada");
    }

    @Test
    void desconectadoQueNaoVoltaSaiDaSala() throws Exception {
        join("Ana");
        Player b = join("Bia");
        room.disconnected(b);
        clock.advance(GameRoom.RECONNECT_GRACE_MS);
        assertFalse(room.players().contains(b));
    }

    @Test
    void tokenInvalidoNaoReconecta() throws Exception {
        join("Ana");
        assertNull(room.resume("token-falso", null));
    }

    @Test
    void desconectadoNaoTravaARevelacaoQuandoOsOutrosErram() throws Exception {
        Player a = join("Ana");
        Player b = join("Bia");
        join("Caio");
        room.start(a);
        room.disconnected(room.players().get(2));
        room.answer(a, currentId(), 5);
        room.answer(b, currentId(), 5);
        assertEquals(GameRoom.State.REVEAL, room.state());
    }

    // ------------------------------------------------------------------ banco real

    @Test
    void bancoRealTemQuarentaQuestoesPorNivel() {
        QuestionBank bank = QuestionBank.loadDefault();
        assertEquals(40, bank.forLevel(Level.NORMAL).size());
        assertEquals(40, bank.forLevel(Level.UNIVERSITARIO).size());
    }
}
