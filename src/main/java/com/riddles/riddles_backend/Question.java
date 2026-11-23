package com.riddles.riddles_backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Uma questão do banco (questions.json). Matrizes usam {@code grid} (3x3, com uma
 * célula null); padrões usam {@code sequence} (último termo null).
 */
public class Question {
    public String id;
    public String category; // "matriz" | "padrao"
    public String level;    // "normal" | "universitario"
    public Integer[][] grid;
    public Integer[] sequence;
    public int[] options;
    public int answer;
    public String rule;

    public boolean hasOption(int value) {
        for (int o : options) {
            if (o == value) return true;
        }
        return false;
    }

    /**
     * Visão enviada ao celular durante a pergunta: sem a resposta e sem a regra,
     * com as alternativas embaralhadas para ninguém decorar a posição.
     */
    public Map<String, Object> toClientView(Random random) {
        List<Integer> shuffled = new ArrayList<>();
        for (int o : options) shuffled.add(o);
        Collections.shuffle(shuffled, random);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("category", category);
        if (grid != null) view.put("grid", grid);
        if (sequence != null) view.put("sequence", sequence);
        view.put("options", shuffled);
        return view;
    }
}
