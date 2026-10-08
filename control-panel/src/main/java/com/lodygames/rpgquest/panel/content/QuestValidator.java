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
    /**
     * Sources de liste à vocabulaire FIXE : leurs valeurs viennent d'une énumération du moteur, pas
     * d'un relevé serveur. Une valeur hors liste est donc une vraie erreur, pas une incertitude.
     */
    private static final Map<String, List<String>> FIXED_SOURCES = Map.of(
            "waypointCountMode", List.of("NEW_ONLY", "INCLUDE_EXISTING"));

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
        } else if (refData.itemCatalog().isBlockWithoutItem(q.icon)) {
            // #196 : refus explicite et expliqué, plutôt qu'une icône qui n'apparaîtra jamais.
            out.add(Diagnostic.error("icon", q.icon.trim() + " existe comme bloc mais n'a aucune "
                    + "forme d'objet dans cette version : il ne peut pas servir d'icône. Choisir un "
                    + "objet réellement affichable dans un inventaire."));
        } else if (refData.materialsFromServer() && !refData.isValueKnown("material", q.icon)) {
            out.add(Diagnostic.warning("icon", "Icône « " + q.icon.trim() + " » absente du catalogue "
                    + "de la version installée (Minecraft "
                    + refData.itemCatalog().minecraftVersion() + "). Vérifier l'identifiant : le "
                    + "serveur retombera sur « BOOK » s'il ne le reconnaît pas."));
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
                        + "(absente de la source éditable et du dernier relevé « quest.list »). "
                        + "La référence est conservée telle quelle — à corriger ou à déployer."));
            } else if (!refData.questsKnown()) {
                out.add(Diagnostic.info("prerequisites", "Impossible de vérifier le prérequis « " + p
                        + " » : aucun relevé « quest.list »."));
            }
        }

        // --- Cycles de prérequis (issue #163) ---
        // L'auto-référence est déjà signalée ci-dessus ; ici on attrape les boucles INDIRECTES
        // (A exige B, B exige A) que le formulaire ne peut pas voir champ par champ. Le graphe vient
        // de RefData (source + runtime) : si aucun des deux n'est disponible, on ne prétend rien.
        String cycle = refData.findPrereqCycle(id, q.prerequisites);
        if (cycle != null) {
            out.add(Diagnostic.error("prerequisites", "Cycle de prérequis détecté : " + cycle
                    + ". Une quête ne peut jamais dépendre d'elle-même, même indirectement — "
                    + "elle deviendrait impossible à démarrer."));
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
            if ("MONEY".equalsIgnoreCase(r.getOrDefault("kind", ""))) {
                moneyRewardWarnings(out, ctx, r.get("amount"));
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

    /**
     * Seuil au-delà duquel un montant de récompense monétaire est <strong>signalé</strong>
     * (issue #16). Ce n'est pas un plafond : l'équilibrage économique est une décision de jeu, pas
     * une règle technique, et refuser un gros montant reviendrait à la prendre à la place de
     * l'auteur. L'avertissement n'existe que pour attraper la faute de frappe à six zéros, qui est
     * la seule erreur réellement coûteuse ici.
     */
    static final long MONEY_REWARD_WARNING_THRESHOLD = 1_000_000L;

    private static void moneyRewardWarnings(List<Diagnostic> out, String ctx, String rawAmount) {
        out.add(Diagnostic.info(ctx, "Récompense en pièces : crédite le portefeuille persistant du joueur "
                + "(aucun objet donné, aucun item compté comme monnaie). Le crédit est tracé au journal "
                + "des transactions et ne peut pas avoir lieu deux fois pour la même complétion."));
        Long amount = parseLong(rawAmount);
        if (amount != null && amount > MONEY_REWARD_WARNING_THRESHOLD) {
            out.add(Diagnostic.warning(ctx, "Montant de " + amount + " pièces : inhabituellement élevé. "
                    + "Rien ne l'interdit — vérifie seulement qu'il ne s'agit pas d'une faute de frappe."));
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
                    // Issue #185 : vocabulaire FIXE venu d'une énumération du moteur, pas d'un relevé
                    // serveur. Le vérifier ici évite à la fois un « valeur inconnue » trompeur et un
                    // « impossible de vérifier » alors que les deux seules valeurs valides sont connues.
                    if (FIXED_SOURCES.containsKey(f.selectSource())) {
                        List<String> allowed = FIXED_SOURCES.get(f.selectSource());
                        if (!allowed.contains(v.trim().toUpperCase(Locale.ROOT))) {
                            out.add(Diagnostic.error(ctx, "« " + f.label() + " » : valeur « " + v.trim()
                                    + " » invalide. Valeurs acceptées : " + String.join(", ", allowed) + "."));
                        }
                        continue;
                    }
                    // #196 : un bloc sans forme d'objet n'est pas une valeur « inconnue » — il
                    // existe bel et bien, mais ne peut jamais devenir un objet. Le dire
                    // précisément évite de chercher une faute de frappe qui n'existe pas.
                    if (isMaterialSource(f.selectSource()) && ref.itemCatalog().isBlockWithoutItem(v)) {
                        out.add(Diagnostic.error(ctx, "« " + f.label() + " » : " + v.trim()
                                + " existe comme bloc mais n'a aucune forme d'objet dans cette "
                                + "version — il ne peut donc être ni une icône ni une récompense. "
                                + "Choisir un objet (par exemple son seau, sa version en bloc "
                                + "posable, ou un objet équivalent)."));
                    } else if (ref.sourceKnown(f.selectSource()) && !ref.isValueKnown(f.selectSource(), v)) {
                        out.add(Diagnostic.warning(ctx, "« " + f.label() + " » : valeur « " + v.trim()
                                + " » hors des valeurs connues. Le serveur refusera un " + f.selectSource()
                                + " réellement invalide au chargement."));
                    } else if (!ref.sourceKnown(f.selectSource())) {
                        out.add(Diagnostic.info(ctx, "Impossible de vérifier « " + f.label() + " » : aucun relevé de référence."));
                    }
                }
                // Issue #185 : chaque entrée de la liste est vérifiée séparément, et un doublon est
                // signalé — « world_hub, world_hub » ne doit pas laisser croire à deux mondes.
                case LIST -> {
                    List<String> parts = QuestYaml.splitList(v);
                    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                    for (String part : parts) {
                        if (!seen.add(part.toLowerCase(Locale.ROOT))) {
                            out.add(Diagnostic.warning(ctx, "« " + f.label() + " » : « " + part
                                    + " » est répété ; il ne compte qu'une fois."));
                        } else if (ref.sourceKnown(f.selectSource()) && !ref.isValueKnown(f.selectSource(), part)) {
                            out.add(Diagnostic.warning(ctx, "« " + f.label() + " » : valeur « " + part
                                    + " » hors des valeurs connues. Un " + f.selectSource()
                                    + " inexistant ne fera jamais progresser l'objectif."));
                        }
                    }
                }
                default -> {
                    // TEXT / TEXTAREA : rien de structurel à vérifier ici
                }
            }
        }
    }

    /** Les champs dont la valeur est un matériau Minecraft (icône ou objet). */
    private static boolean isMaterialSource(String source) {
        return "material".equals(source) || "icon".equals(source);
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
