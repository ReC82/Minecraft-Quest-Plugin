package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Issue #430 — ce qui, dans un dialogue, reste lié à une quête <strong>précise</strong> alors que
 * le PNJ propose (ou devrait proposer) une quête tirée d'une liste ({@code OFFER_CATALOG_QUEST},
 * #369).
 *
 * <p><strong>Pourquoi un avertissement et pas une correction.</strong> Le moteur n'a pas de
 * condition « la quête proposée par la liste » : l'action catalogue présente elle-même l'offre, la
 * tâche en cours ou « rien pour le moment ». Une condition {@code QUEST_AVAILABLE} sur une quête
 * de la liste, un {@code START_QUEST} figé ou une réplique qui raconte « 6 araignées » deviennent
 * donc contradictoires dès qu'une autre quête de la liste est tirée. Lesquels garder dépend de
 * l'intention de l'auteur : on les <em>signale</em>, réponse par réponse, sans jamais les
 * réécrire.</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 */
public final class DialogueCatalogCoherence {

    /**
     * Un point à revoir.
     *
     * @param nodeId réplique concernée
     * @param choice index de la réponse dans la réplique, {@link #TEXT} pour le texte de la réplique
     *               (variantes comprises), {@link #ENTRY} pour une entrée d'accueil #158
     * @param quest  quête précise citée
     * @param what   ce qui la cite, en français simple
     */
    public record Finding(String nodeId, int choice, String quest, String what) {
    }

    /**
     * @param offersCatalog le dialogue contient au moins une action {@code OFFER_CATALOG_QUEST}
     * @param catalogQuests quêtes des listes concernées (celles nommées par l'action, sinon celles
     *                      des modèles de PNJ qui portent ce dialogue)
     * @param findings      points à revoir, dans l'ordre du fichier
     */
    public record Report(boolean offersCatalog, Set<String> catalogQuests, List<Finding> findings) {
        public boolean empty() {
            return findings.isEmpty();
        }
    }

    /** {@link Finding#choice()} : le texte de la réplique ou l'une de ses variantes. */
    public static final int TEXT = -1;
    /** {@link Finding#choice()} : une entrée d'accueil conditionnelle ({@code entries:}, #158). */
    public static final int ENTRY = -2;

    private DialogueCatalogCoherence() {
    }

    /**
     * @param d              le dialogue
     * @param modelCatalogs  catalogues des modèles de PNJ récurrents qui portent ce dialogue
     * @param catalogQuests  quêtes de chaque catalogue (id de catalogue → ids de quête)
     * @param title          titre lisible d'une quête (texte brut), pour le repérer dans les répliques
     */
    public static Report analyse(DialogueDraft d, List<String> modelCatalogs,
                                 Map<String, List<String>> catalogQuests, Function<String, String> title) {
        boolean offers = false;
        Set<String> catalogs = new LinkedHashSet<>();
        for (DialogueDraft.Node n : d.nodes) {
            for (DialogueDraft.Choice c : n.choices) {
                for (Map<String, String> a : c.actions) {
                    if (DialogueForm.CATALOG_ACTION.equalsIgnoreCase(a.getOrDefault("type", ""))) {
                        offers = true;
                        String named = a.getOrDefault("catalog", "").trim();
                        if (named.isEmpty()) {
                            catalogs.addAll(modelCatalogs);
                        } else {
                            catalogs.add(named);
                        }
                    }
                }
            }
        }
        if (!offers) {
            // Pas encore d'offre : ce sont les listes du PNJ qui disent ce qu'il faudra revoir.
            catalogs.addAll(modelCatalogs);
        }
        Set<String> quests = new LinkedHashSet<>();
        for (String cat : catalogs) {
            for (String q : catalogQuests.getOrDefault(cat, List.of())) {
                quests.add(DialogueYaml.plainId(q));
            }
        }
        List<Finding> out = new ArrayList<>();
        if (quests.isEmpty()) {
            return new Report(offers, Set.of(), List.of());
        }
        for (DialogueDraft.Entry e : d.entries) {
            for (Map<String, String> cond : e.conditions) {
                String q = DialogueYaml.plainId(cond.getOrDefault("quest", ""));
                if (quests.contains(q)) {
                    out.add(new Finding(e.node, ENTRY, q, "une entrée d'accueil choisie selon « "
                            + conditionLabel(cond) + " » sur cette quête"));
                }
            }
        }
        for (DialogueDraft.Node n : d.nodes) {
            List<String> texts = new ArrayList<>();
            texts.add(n.text);
            texts.addAll(n.variants);
            for (String q : quests) {
                String t = title.apply(q);
                String plainTitle = t == null ? "" : DialogueForm.excerpt(t, Integer.MAX_VALUE).toLowerCase(Locale.ROOT);
                if (plainTitle.length() >= 4 && texts.stream().anyMatch(x -> DialogueForm.excerpt(x, Integer.MAX_VALUE)
                        .toLowerCase(Locale.ROOT).contains(plainTitle))) {
                    out.add(new Finding(n.id, TEXT, q, "le texte de la réplique nomme cette quête"));
                }
            }
            for (int j = 0; j < n.choices.size(); j++) {
                DialogueDraft.Choice c = n.choices.get(j);
                for (Map<String, String> cond : c.conditions) {
                    String q = DialogueYaml.plainId(cond.getOrDefault("quest", ""));
                    if (quests.contains(q)) {
                        out.add(new Finding(n.id, j, q, "une condition « " + conditionLabel(cond) + " » sur cette quête"));
                    }
                }
                for (Map<String, String> a : c.actions) {
                    String q = DialogueYaml.plainId(a.getOrDefault("quest", ""));
                    if (quests.contains(q)) {
                        out.add(new Finding(n.id, j, q, "l'effet « " + actionLabel(a) + " » sur cette quête précise"));
                    }
                }
            }
        }
        return new Report(offers, Set.copyOf(quests), List.copyOf(out));
    }

    private static String conditionLabel(Map<String, String> cond) {
        String type = cond.getOrDefault("type", "").toUpperCase(Locale.ROOT);
        String label = Descriptors.dialogueCondition(type).map(Descriptors.Descriptor::label).orElse(type);
        return "true".equalsIgnoreCase(cond.getOrDefault("negate", "")) ? label + " (inversée)" : label;
    }

    private static String actionLabel(Map<String, String> a) {
        String type = a.getOrDefault("type", "").toUpperCase(Locale.ROOT);
        return Descriptors.dialogueAction(type).map(Descriptors.Descriptor::label).orElse(type);
    }
}
