package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issue #465 — références d'un dialogue vérifiées contre ce que le serveur et la source connaissent
 * réellement : quêtes citées par les conditions et les actions, liste de quêtes d'un
 * {@code OFFER_CATALOG_QUEST}, PNJ et objets.
 *
 * <p>{@link DialogueValidator} ne regarde que la forme du dialogue ; un dialogue réécrit par une IA
 * peut être bien formé et citer une quête qui n'existe pas. Ce contrôle est <strong>déterministe</strong>
 * : il ne devine rien et ne prétend rien vérifier sans relevé — une référence non vérifiable est
 * dite telle (INFO), jamais déclarée bonne.</p>
 */
public final class DialogueReferenceCheck {

    private DialogueReferenceCheck() {
    }

    /**
     * @param ref      quêtes / PNJ / objets relevés (augmentés de ceux fournis par le pack)
     * @param catalogs identifiants des listes de quêtes relevées ({@code quest.catalog.list}),
     *                 ou {@code null} si aucun relevé n'a eu lieu
     */
    public static List<Diagnostic> check(DialogueDraft d, RefData ref, Set<String> catalogs) {
        return check(d, ref, ref, catalogs);
    }

    /**
     * @param surveyed ce que le serveur a réellement relevé : décide si une famille de références est
     *                 vérifiable. {@code ref} peut l'augmenter des éléments fournis par un pack, mais
     *                 ne doit pas faire passer « une quête du pack » pour « un relevé du serveur ».
     */
    public static List<Diagnostic> check(DialogueDraft d, RefData ref, RefData surveyed, Set<String> catalogs) {
        RefData r = ref == null ? RefData.empty() : ref;
        RefData known = surveyed == null ? RefData.empty() : surveyed;
        List<Diagnostic> out = new ArrayList<>();
        Set<String> unverified = new LinkedHashSet<>();
        for (DialogueDraft.Node n : d.nodes) {
            for (int i = 0; i < n.choices.size(); i++) {
                DialogueDraft.Choice c = n.choices.get(i);
                String ctx = "nodes[" + n.id + "].choices[" + i + "]";
                for (Map<String, String> e : c.conditions) {
                    checkEntry(out, unverified, ctx, e, r, known, catalogs);
                }
                for (Map<String, String> e : c.actions) {
                    checkEntry(out, unverified, ctx, e, r, known, catalogs);
                }
            }
        }
        for (DialogueDraft.Entry entry : d.entries) {
            for (Map<String, String> e : entry.conditions) {
                checkEntry(out, unverified, "entries[" + entry.node + "]", e, r, known, catalogs);
            }
        }
        for (String what : unverified) {
            out.add(Diagnostic.info("références", "Non vérifié faute de relevé du serveur : " + what
                    + ". « Actualiser » les listes concernées, puis réanalyser."));
        }
        return out;
    }

    private static void checkEntry(List<Diagnostic> out, Set<String> unverified, String ctx,
                                   Map<String, String> entry, RefData ref, RefData known,
                                   Set<String> catalogs) {
        String type = entry.getOrDefault("type", "").trim();
        String quest = entry.getOrDefault("quest", "").trim();
        if (!quest.isEmpty()) {
            if (!known.sourceKnown("quest")) {
                unverified.add("quêtes");
            } else if (!ref.isQuestKnown(quest)) {
                out.add(Diagnostic.error(ctx, type + " cite la quête « " + quest + " », inconnue du "
                        + "serveur et de la source. Une IA ne doit pas inventer de quête : corriger "
                        + "l'identifiant ou créer la quête d'abord."));
            }
        }
        String catalog = entry.getOrDefault("catalog", "").trim();
        if ("OFFER_CATALOG_QUEST".equalsIgnoreCase(type) && !catalog.isEmpty()) {
            if (catalogs == null) {
                unverified.add("listes de quêtes");
            } else if (!catalogs.contains(catalog)) {
                out.add(Diagnostic.error(ctx, "OFFER_CATALOG_QUEST cite la liste « " + catalog
                        + " », inconnue du serveur. Listes relevées : "
                        + (catalogs.isEmpty() ? "aucune" : String.join(", ", catalogs)) + "."));
            }
        }
        String npc = entry.getOrDefault("npc", "").trim();
        if (!npc.isEmpty()) {
            if (!known.sourceKnown("npc")) {
                unverified.add("PNJ");
            } else if (!ref.isNpcKnown(npc)) {
                out.add(Diagnostic.warning(ctx, type + " cite le PNJ « " + npc + " », inconnu du serveur."));
            }
        }
        String material = entry.getOrDefault("material", "").trim();
        if (!material.isEmpty() && known.sourceKnown("material") && !ref.isValueKnown("material", material)) {
            out.add(Diagnostic.warning(ctx, type + " cite l'objet « " + material + " », absent du "
                    + "catalogue du serveur."));
        }
    }
}
