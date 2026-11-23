package com.riddles.riddles_backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Garante a integridade estrutural do banco de questões (questions.json). */
class QuestionBankTest {

    private static final Set<String> CATEGORIES = Set.of("matriz", "padrao");
    private static final Set<String> LEVELS = Set.of("normal", "universitario");
    private static final int MIN_PER_LEVEL_AND_CATEGORY = 20;

    private static List<JsonObject> questions;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = QuestionBankTest.class.getResourceAsStream("/questions.json")) {
            assertNotNull(is, "questions.json não encontrado no classpath");
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonArray();
            questions = new ArrayList<>();
            arr.forEach(e -> questions.add(e.getAsJsonObject()));
        }
    }

    @Test
    void idsSaoUnicos() {
        Set<String> ids = new HashSet<>();
        for (JsonObject q : questions) {
            assertTrue(ids.add(q.get("id").getAsString()), "ID duplicado: " + q.get("id"));
        }
    }

    @Test
    void camposObrigatoriosEValoresValidos() {
        for (JsonObject q : questions) {
            String id = q.get("id").getAsString();
            assertTrue(CATEGORIES.contains(q.get("category").getAsString()), id + ": categoria inválida");
            assertTrue(LEVELS.contains(q.get("level").getAsString()), id + ": nível inválido");
            assertTrue(q.has("answer"), id + ": sem answer");
            assertTrue(q.has("rule") && !q.get("rule").getAsString().isBlank(), id + ": sem rule");
        }
    }

    @Test
    void quatroOpcoesDistintasComARespostaEntreElas() {
        for (JsonObject q : questions) {
            String id = q.get("id").getAsString();
            JsonArray options = q.getAsJsonArray("options");
            assertEquals(4, options.size(), id + ": precisa de 4 opções");
            Set<Long> distinct = new HashSet<>();
            options.forEach(o -> distinct.add(o.getAsLong()));
            assertEquals(4, distinct.size(), id + ": opções repetidas");
            assertTrue(distinct.contains(q.get("answer").getAsLong()), id + ": resposta não está nas opções");
        }
    }

    @Test
    void exatamenteUmaLacunaNoEnunciado() {
        for (JsonObject q : questions) {
            String id = q.get("id").getAsString();
            List<JsonElement> cells = new ArrayList<>();
            if ("matriz".equals(q.get("category").getAsString())) {
                JsonArray grid = q.getAsJsonArray("grid");
                assertEquals(3, grid.size(), id + ": matriz precisa de 3 linhas");
                grid.forEach(row -> {
                    assertEquals(3, row.getAsJsonArray().size(), id + ": linha precisa de 3 colunas");
                    row.getAsJsonArray().forEach(cells::add);
                });
            } else {
                JsonArray seq = q.getAsJsonArray("sequence");
                assertTrue(seq.size() >= 4, id + ": sequência curta demais");
                assertTrue(seq.get(seq.size() - 1).isJsonNull(), id + ": lacuna deve ser o último termo");
                seq.forEach(cells::add);
            }
            long gaps = cells.stream().filter(JsonElement::isJsonNull).count();
            assertEquals(1, gaps, id + ": precisa de exatamente uma lacuna");
        }
    }

    @Test
    void quantidadeMinimaPorNivelECategoria() {
        Map<String, Integer> count = new HashMap<>();
        for (JsonObject q : questions) {
            count.merge(q.get("level").getAsString() + "/" + q.get("category").getAsString(), 1, Integer::sum);
        }
        for (String level : LEVELS) {
            for (String cat : CATEGORIES) {
                int n = count.getOrDefault(level + "/" + cat, 0);
                assertTrue(n >= MIN_PER_LEVEL_AND_CATEGORY, level + "/" + cat + " tem só " + n + " questões");
            }
        }
    }
}
