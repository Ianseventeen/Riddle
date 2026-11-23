package com.riddles.riddles_backend;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Carrega o questions.json e separa as questões por nível. */
public class QuestionBank {

    private final Map<Level, List<Question>> byLevel;

    public QuestionBank(List<Question> questions) {
        this.byLevel = questions.stream()
                .collect(Collectors.groupingBy(q -> Level.fromId(q.level)));
    }

    public static QuestionBank loadDefault() {
        try (InputStream is = QuestionBank.class.getResourceAsStream("/questions.json")) {
            if (is == null) throw new IllegalStateException("questions.json não encontrado");
            Question[] array = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Question[].class);
            return new QuestionBank(Arrays.asList(array));
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao carregar questions.json: " + e.getMessage(), e);
        }
    }

    public List<Question> forLevel(Level level) {
        return new ArrayList<>(byLevel.getOrDefault(level, List.of()));
    }

    public int size() {
        return byLevel.values().stream().mapToInt(List::size).sum();
    }
}
