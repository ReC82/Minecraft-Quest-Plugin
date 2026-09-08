package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff ligne à ligne minimal (préfixe/suffixe communs + bloc central) pour l'aperçu « avant /
 * après » de l'éditeur #46. Pas un algorithme de Myers : suffisant pour un YAML court et
 * déterministe, et le diff n'est qu'un <strong>diagnostic</strong>, jamais la source de vérité.
 */
public final class TextDiff {

    public record Line(char kind, String text) {
        // kind : ' ' contexte, '-' retiré, '+' ajouté
    }

    private TextDiff() {
    }

    public static List<Line> diff(String before, String after) {
        List<String> a = split(before);
        List<String> b = split(after);
        int n = a.size();
        int m = b.size();
        int start = 0;
        while (start < n && start < m && a.get(start).equals(b.get(start))) {
            start++;
        }
        int endA = n;
        int endB = m;
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        List<Line> out = new ArrayList<>();
        int ctxBefore = Math.max(0, start - 3);
        for (int i = ctxBefore; i < start; i++) {
            out.add(new Line(' ', a.get(i)));
        }
        for (int i = start; i < endA; i++) {
            out.add(new Line('-', a.get(i)));
        }
        for (int i = start; i < endB; i++) {
            out.add(new Line('+', b.get(i)));
        }
        int ctxAfter = Math.min(n, endA + 3);
        for (int i = endA; i < ctxAfter; i++) {
            out.add(new Line(' ', a.get(i)));
        }
        return out;
    }

    public static boolean identical(String before, String after) {
        return before.equals(after);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String line : (s == null ? "" : s).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            out.add(line);
        }
        return out;
    }
}
