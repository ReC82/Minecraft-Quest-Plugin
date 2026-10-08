package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.content.ContentPackSchema;

/**
 * Extrait le document YAML de la réponse brute d'un modèle (issue #146).
 *
 * <p><strong>Pourquoi cette classe existe.</strong> Le prompt demande explicitement « du YAML seul,
 * sans rien autour ». Les modèles désobéissent régulièrement : ils enrobent dans un bloc de code,
 * ajoutent « Voici la quête demandée : », ou terminent par un commentaire. Refuser ces réponses
 * serait techniquement défendable et pratiquement absurde — le document est là, il suffit de le
 * trouver. En revanche, rien n'est <em>réparé</em> : on délimite, on ne réécrit pas. Ce qui est
 * extrait est ensuite validé par les validateurs réels, exactement comme un fichier collé à la main.
 *
 * <p>Trois stratégies, dans l'ordre : un bloc de code délimité, puis la ligne {@code format:} du
 * format attendu, puis la réponse telle quelle. L'échec est explicite : il vaut mieux un message
 * clair qu'un YAML tronqué qui produirait un diagnostic incompréhensible.</p>
 */
public final class AiYamlExtractor {

    /** Préfixe attendu du document, qui sert d'ancre de découpage. */
    private static final String ANCHOR = "format: " + ContentPackSchema.FORMAT;

    private AiYamlExtractor() {
    }

    /**
     * @param yaml  le document extrait, ou {@code null} si rien d'exploitable
     * @param note  ce qui a été fait, à afficher pour que l'administrateur sache que la réponse
     *              n'était pas conforme à la consigne ({@code null} si elle l'était)
     */
    public record Extraction(String yaml, String note) {

        public boolean ok() {
            return yaml != null && !yaml.isBlank();
        }
    }

    public static Extraction extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Extraction(null, "Le modèle n'a renvoyé aucun texte.");
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');

        // 1. Bloc de code délimité : la forme la plus fréquente malgré la consigne.
        String fenced = fromCodeFence(text);
        if (fenced != null) {
            return new Extraction(fenced, text.trim().startsWith("```") && countFences(text) == 2
                    ? null
                    : "La réponse était enrobée dans un bloc de code ou accompagnée de texte : le "
                      + "document a été extrait tel quel, sans modification.");
        }

        // 2. Ancre « format: … » : on coupe tout ce qui précède.
        int anchor = text.indexOf(ANCHOR);
        if (anchor >= 0) {
            String cut = text.substring(anchor).trim();
            return new Extraction(cut, anchor == 0 ? null
                    : "La réponse comportait du texte avant le document : il a été retiré, le "
                      + "document lui-même est intact.");
        }

        // 3. Rien d'identifiable. On ne devine pas : le document est rendu tel quel pour que
        //    l'analyse produise un diagnostic lisible, et le motif est dit.
        return new Extraction(text.trim(),
                "La réponse ne contient pas la ligne « " + ANCHOR + " » : ce n'est probablement pas "
                + "un content pack. Elle est analysée telle quelle pour que l'erreur soit visible.");
    }

    /**
     * Contenu du premier bloc délimité par trois accents graves. Un éventuel langage ({@code yaml},
     * {@code yml}) est retiré. {@code null} si aucun bloc complet n'est présent — un bloc ouvert et
     * jamais fermé signale une réponse tronquée, qu'il vaut mieux laisser échouer à l'analyse que
     * compléter nous-mêmes.
     */
    private static String fromCodeFence(String text) {
        int open = text.indexOf("```");
        if (open < 0) {
            return null;
        }
        int lineEnd = text.indexOf('\n', open);
        if (lineEnd < 0) {
            return null;
        }
        int close = text.indexOf("```", lineEnd);
        if (close < 0) {
            return null;
        }
        String inner = text.substring(lineEnd + 1, close).trim();
        return inner.isEmpty() ? null : inner;
    }

    private static int countFences(String text) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf("```", from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 3;
        }
    }
}
