package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation d'un {@link QuestDraft} <strong>avant enregistrement</strong> (#46, B7). Reproduit les
 * contrôles structurels de {@code quest.QuestDefinitionParser} du moteur (champs obligatoires,
 * entiers positifs, types d'objectifs/récompenses connus) et ajoute des contrôles de cohérence de
 * référence à partir de {@link RefData} (quête prérequise inconnue, PNJ inconnu, entité/matériau
 * hors liste curée…). Ne dépend pas de Bukkit : la validation « matériau/entité réellement
 * valides » reste garantie au chargement serveur par le plugin.
 */
public final class QuestValidator {

    /** Règle d'id de {@code NamespacedKey} (clé, après un éventuel {@code namespace:}). */
    private static final Pattern KEY = Pattern.compile("[a-z0-9._/-]+");
    private static final Pattern STEP_ID = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern VAR_KEY = Pattern.compile("[A-Za-z0-9_]+");

    private QuestValidator() {
    }

    public static List<Diagnostic> validate(QuestDraft q, RefData ref) {
        List<Diagnostic> out = new ArrayList<>();
        RefData refData = ref == null ? RefData.empty() : ref;

        // --- Général ---
        String id = QuestYaml.plainId(q.id);
        if (id.isBlank()) {
            out.add(Diagnostic.error("id", "L'identifiant canonique est obligatoire."));
        } else if (!KEY.matcher(id).matches()) {
            out.add(Diagnostic.error("id", "Identifiant invalide « " + id + " » : minuscules, chiffres et "
                    + "« . _ - / » uniquement (namespace « rpgquest: » ajouté automatiquement)."));
        }
        if (blank(q.title)) {
            out.add(Diagnostic.error("title", "Le titre est obligatoire."));
        }
        if (blank(q.description)) {
            out.add(Diagnostic.error("description", "La description est obligatoire."));
        }
        if (blank(q.category)) {
            out.add(Diagnostic.error("category", "La catégorie est obligatoire."));
        }
        if (blank(q.icon)) {
            out.add(Diagnostic.info("icon", "Aucune icône : « BOOK » sera utilisée par défaut."));
        }

        // --- Donneur ---
        if (!blank(q.giver)) {
            if (refData.npcsKnown() && !refData.isNpcKnown(q.giver)) {
                out.add(Diagnostic.warning("giver", "PNJ donneur « " + q.giver.trim() + " » absent du dernier "
                        + "relevé des PNJ. Vérifier l'identifiant logique (tag RPGQuest)."));
            } else if (!refData.npcsKnown()) {
                out.add(Diagnostic.info("giver", "Impossible de vérifier le PNJ donneur : aucun relevé « npc.list »."));
            }
        }

        // --- Prérequis ---
        Set<String> seenPrereq = new LinkedHashSet<>();
        for (String raw : q.prerequisites) {
            String p = QuestYaml.plainId(raw);
            if (p.isBlank()) {
                continue;
            }
            if (!KEY.matcher(p).matches()) {
                out.add(Diagnostic.error("prerequisites", "Prérequis invalide « " + raw + " »."));
                continue;
            }
            if (p.equals(id)) {
                out.add(Diagnostic.error("prerequisites", "Une quête ne peut pas être son propre prérequis."));
            }
            if (!seenPrereq.add(p)) {
                out.add(Diagnostic.warning("prerequisites", "Prérequis en double « " + p + " »."));
            }
            if (refData.questsKnown() && !refData.isQuestKnown(p)) {
                out.add(Diagnostic.warning("prerequisites", "Quête prérequise inconnue « " + p + " » "
                        + "(absente du dernier relevé « quest.list »)."));
            } else if (!refData.questsKnown()) {
                out.add(Diagnostic.info("prerequisites", "Impossible de vérifier le prérequis « " + p
                        + " » : aucun relevé « quest.list »."));
            }
        }

        // --- Étapes / objectifs ---
        if (q.steps.isEmpty()) {
            out.add(Diagnostic.error("steps", "Une quête doit contenir au moins une étape."));
        }
        Set<String> stepIds = new LinkedHashSet<>();
        for (int si = 0; si < q.steps.size(); si++) {
            QuestDraft.Step step = q.steps.get(si);
            String ctx = "steps[" + si + "]";
            String sid = step.id == null ? "" : step.id.trim();
            if (sid.isBlank()) {
                out.add(Diagnostic.error(ctx, "L'identifiant de l'étape " + (si + 1) + " est obligatoire."));
            } else if (!STEP_ID.matcher(sid).matches()) {
                out.add(Diagnostic.error(ctx, "Identifiant d'étape invalide « " + sid + " » (minuscules, chiffres, « _ - »)."));
            } else if (!stepIds.add(sid)) {
                out.add(Diagnostic.error(ctx, "Identifiant d'étape en double « " + sid + " »."));
            }
            if (step.objectives.isEmpty()) {
                out.add(Diagnostic.error(ctx, "L'étape « " + (sid.isBlank() ? (si + 1) : sid) + " » doit avoir au moins un objectif."));
            }
            for (int oi = 0; oi < step.objectives.size(); oi++) {
                validateRow(out, ctx + ".objectives[" + oi + "]", step.objectives.get(oi), Descriptors::objective,
                        "objectif", refData);
            }
        }

        // --- Récompenses ---
        for (int ri = 0; ri < q.rewards.size(); ri++) {
            Map<String, String> r = q.rewards.get(ri);
            String ctx = "rewards[" + ri + "]";
            validateRow(out, ctx, r, Descriptors::reward, "récompense", refData);
            if ("COMMAND".equalsIgnoreCase(r.getOrDefault("kind", ""))) {
                out.add(Diagnostic.warning(ctx, "Récompense « commande console » : sensible. Elle sera validée "
                        + "strictement côté serveur au chargement ; pas de console générique."));
            }
        }

        // --- Variables ---
        for (String key : q.variables.keySet()) {
            if (!VAR_KEY.matcher(key).matches()) {
                out.add(Diagnostic.warning("variables", "Clé de variable inhabituelle « " + key + " » "
                        + "(lettres, chiffres et « _ » recommandés)."));
            }
        }
        return out;
    }

    private interface DescriptorLookup {
        java.util.Optional<Descriptors.Descriptor> find(String kind);
    }

    private static void validateRow(List<Diagnostic> out, String ctx, Map<String, String> row,
                                    DescriptorLookup lookup, String noun, RefData ref) {
        String kind = row.getOrDefault("kind", "").trim();
        if (kind.isBlank()) {
            out.add(Diagnostic.error(ctx, "Type de " + noun + " manquant."));
            return;
        }
        var found = lookup.find(kind);
        if (found.isEmpty()) {
            out.add(Diagnostic.error(ctx, "Type de " + noun + " inconnu « " + kind + " »."));
            return;
        }
        for (Descriptors.Field f : found.get().fields()) {
            String v = row.get(f.name());
            boolean present = v != null && !v.isBlank();
            if (!present) {
                if (f.required()) {
                    out.add(Diagnostic.error(ctx, "Champ « " + f.label() + " » obligatoire pour « "
                            + found.get().label() + " »."));
                }
                continue;
            }
            switch (f.type()) {
                case INT -> {
                    Integer n = parseInt(v);
                    if (n == null) {
                        out.add(Diagnostic.error(ctx, "« " + f.label() + " » doit être un entier."));
                    } else if (n <= 0) {
                        out.add(Diagnostic.error(ctx, "« " + f.label() + " » doit être strictement positif."));
                    }
                }
                case DOUBLE -> {
                    Double d = parseDouble(v);
                    if (d == null) {
                        out.add(Diagnostic.error(ctx, "« " + f.label() + " » doit être un nombre."));
                    } else if ("radius".equals(f.name()) && d <= 0) {
                        out.add(Diagnostic.error(ctx, "« " + f.label() + " » doit être strictement positif."));
                    }
                }
                case SELECT -> {
                    if (ref.sourceKnown(f.selectSource()) && !ref.isValueKnown(f.selectSource(), v)) {
                        out.add(Diagnostic.warning(ctx, "« " + f.label() + " » : valeur « " + v.trim()
                                + " » hors des valeurs connues. Le serveur refusera un " + f.selectSource()
                                + " réellement invalide au chargement."));
                    } else if (!ref.sourceKnown(f.selectSource())) {
                        out.add(Diagnostic.info(ctx, "Impossible de vérifier « " + f.label() + " » : aucun relevé de référence."));
                    }
                }
                default -> {
                    // TEXT / TEXTAREA : rien de structurel à vérifier ici
                }
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.valueOf(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String namespace(String locale) {
        return locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }
}
