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
    /** Délai {@code cooldown:} accepté par le moteur (secondes, ou nombre + unité s/m/h/d). */
    private static final Pattern COOLDOWN = Pattern.compile("[0-9]{1,9}[smhd]?");
    private static final Pattern VAR_KEY = Pattern.compile("[A-Za-z0-9_]+");
    /**
     * Sources de liste à vocabulaire FIXE : leurs valeurs viennent d'une énumération du moteur, pas
     * d'un relevé serveur. Une valeur hors liste est donc une vraie erreur, pas une incertitude.
     */
    private static final Map<String, List<String>> FIXED_SOURCES = Map.of(
            "waypointCountMode", List.of("NEW_ONLY", "INCLUDE_EXISTING"),
            "countMode", List.of("NEW_ONLY", "INCLUDE_EXISTING"));

    private QuestValidator() {
    }

    /**
     * Ids d'étape de la version enregistrée qui ont disparu du brouillon (#143) — renommés ou
     * supprimés. Le moteur retrouve l'étape courante d'un joueur par son id
     * ({@code quest_progress}, {@code quest_objective_progress}) : un id disparu fait reprendre la
     * quête à l'étape 1, compteurs perdus, pour tout joueur qui était à cette étape. Simple
     * avertissement : c'est parfois voulu (quête jamais publiée, refonte assumée).
     */
    public static List<Diagnostic> stepIdChanges(QuestDraft q, List<String> savedStepIds) {
        List<Diagnostic> out = new ArrayList<>();
        Set<String> now = new LinkedHashSet<>(q.stepIds());
        for (String old : savedStepIds) {
            if (!now.contains(old)) {
                out.add(Diagnostic.warning("steps", "L'étape « " + old + " » de la version enregistrée n'existe "
                        + "plus (renommée ou supprimée). Un joueur qui a cette quête en cours à cette étape "
                        + "la reprendra à l'étape 1, sans sa progression. Sans joueur concerné, ignorer."));
            }
        }
        return out;
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

        // --- Délai de ré-acceptation (issue #13) : mêmes règles que QuestDefinitionParser ---
        if (!blank(q.cooldown)) {
            if (!COOLDOWN.matcher(q.cooldown.trim().toLowerCase(java.util.Locale.ROOT)).matches()) {
                out.add(Diagnostic.error("cooldown", "Délai « " + q.cooldown.trim() + " » invalide : un nombre "
                        + "de secondes, ou un nombre suivi de s, m, h ou d (30m, 20h, 1d)."));
            } else if (!q.repeatable) {
                out.add(Diagnostic.error("cooldown", "Un délai de ré-acceptation n'a de sens que pour une "
                        + "quête répétable : cocher « Quête répétable » ou vider le délai."));
            }
        }

        // --- Quête quotidienne / hebdomadaire (issue #15) : mêmes règles que QuestDefinitionParser ---
        if (!blank(q.period)) {
            String period = q.period.trim().toLowerCase(java.util.Locale.ROOT);
            if (!period.equals("daily") && !period.equals("weekly")) {
                out.add(Diagnostic.error("period", "Renouvellement « " + q.period.trim()
                        + " » invalide : « daily » (chaque jour) ou « weekly » (chaque semaine)."));
            }
            if (!blank(q.cooldown)) {
                out.add(Diagnostic.error("cooldown", "Une quête quotidienne ou hebdomadaire se renouvelle "
                        + "avec sa période : vider le délai."));
            }
            if (!q.prerequisites.isEmpty()) {
                out.add(Diagnostic.error("prerequisites", "Une quête quotidienne ou hebdomadaire ne peut pas "
                        + "avoir de prérequis."));
            }
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
            if ("HEART".equalsIgnoreCase(r.getOrDefault("kind", ""))) {
                heartRewardChecks(out, ctx, r.get("amount"), r.get("key"));
            }
            if ("TABLE".equalsIgnoreCase(r.getOrDefault("kind", ""))) {
                String table = r.getOrDefault("table", "").trim();
                if (!table.isEmpty() && !TABLE_ID.matcher(table).matches()) {
                    out.add(Diagnostic.error(ctx, "Identifiant de table « " + table + " » invalide : minuscules, "
                            + "chiffres, « _ - » uniquement (32 caractères au plus)."));
                }
                if (!table.isEmpty() && TABLE_ID.matcher(table).matches()) {
                    tableReferenceCheck(out, ctx, table, refData);
                }
                out.add(Diagnostic.info(ctx, "Tirage au sort : une fois par complétion, résultat figé avec la fin "
                        + "de quête. Si la table n'existe pas ou est désactivée, le joueur est prévenu que le "
                        + "tirage est indisponible (aucun gain inventé)."));
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

    /** Plafond technique d'une récompense HEART : 512 cœurs = 1024 PV (attribute.maxHealth.max). */
    static final int HEART_REWARD_MAX = 512;
    /** Seuil au-delà duquel un nombre de cœurs est signalé, jamais refusé (issue #166). */
    static final int HEART_REWARD_WARNING_THRESHOLD = 5;
    /** Identifiant d'une table de récompenses aléatoires (issue #177). */
    private static final Pattern TABLE_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Issue #177 : table connue du dernier relevé, et active — sinon un avertissement qui dit quoi faire. */
    private static void tableReferenceCheck(List<Diagnostic> out, String ctx, String table, RefData ref) {
        if (!ref.sourceKnown("reward-table")) {
            out.add(Diagnostic.info(ctx, "Impossible de vérifier la table « " + table + " » : ouvrir la page "
                    + "Récompenses et cliquer sur « Rafraîchir »."));
        } else if (!ref.isValueKnown("reward-table", table)) {
            out.add(Diagnostic.warning(ctx, "Table « " + table + " » introuvable sur le serveur : la créer dans "
                    + "Récompenses, ou choisir une table de la liste. Sinon le joueur verra « tirage "
                    + "indisponible »."));
        } else if (ref.rewardTableDisabled(table)) {
            out.add(Diagnostic.warning(ctx, "La table « " + table + " » est désactivée : aucun tirage tant qu'elle "
                    + "ne l'est pas (le joueur verra « tirage indisponible »)."));
        }
    }
    private static final Pattern HEART_GRANT_KEY = Pattern.compile("[a-z0-9_.:\\-]{1,128}");

    private static void heartRewardChecks(List<Diagnostic> out, String ctx, String rawAmount, String rawKey) {
        Long hearts = rawAmount == null || rawAmount.isBlank() ? Long.valueOf(1) : parseLong(rawAmount);
        if (hearts != null && hearts > HEART_REWARD_MAX) {
            out.add(Diagnostic.error(ctx, "« Nombre de cœurs » ne peut pas dépasser " + HEART_REWARD_MAX
                    + " (plafond technique de la vie maximale côté serveur)."));
        } else if (hearts != null && hearts > HEART_REWARD_WARNING_THRESHOLD) {
            out.add(Diagnostic.warning(ctx, hearts + " cœurs d'un coup : inhabituel. Rien ne l'interdit — "
                    + "vérifie seulement qu'il ne s'agit pas d'une faute de frappe."));
        }
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.isEmpty() && !HEART_GRANT_KEY.matcher(key).matches()) {
            out.add(Diagnostic.error(ctx, "Clé de droit « " + key + " » invalide : minuscules, chiffres et "
                    + "« _ . : - » uniquement (128 caractères au plus)."));
        }
        out.add(Diagnostic.info(ctx, key.isEmpty()
                ? "Cœur supplémentaire propre à cette quête : accordé une seule fois par joueur, même si la "
                        + "quête est répétable."
                : "Cœur supplémentaire partagé sous la clé « " + key + " » : une seule fois par joueur pour "
                        + "toutes les quêtes portant cette clé."));
    }

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
        if ("DISCOVER_CITY".equalsIgnoreCase(kind)) {
            validateDiscoverCity(out, ctx, row, ref);
        }
        // Issue #171 : même forme d'identifiant que le plugin (forme courte = rpgquest:<id>).
        String mob = row.getOrDefault("mob", "").trim();
        if ("KILL_QUEST_BOSS".equalsIgnoreCase(kind) && !mob.isEmpty()
                && !mob.toLowerCase(Locale.ROOT).matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
            out.add(Diagnostic.error(ctx, "Profil de boss invalide « " + mob + " » : minuscules, chiffres, _ . - "
                    + "(ex. ogre ou rpgquest:ogre)."));
        }
        for (Descriptors.Field f : found.get().fields()) {
            if ("DISCOVER_CITY".equalsIgnoreCase(kind) && "city".equals(f.name())) {
                continue; // vérifiée par validateDiscoverCity, avec un message propre aux villes
            }
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
                    if ("reward-table".equals(f.selectSource())) {
                        continue; // #177 : message dédié dans le bloc « TABLE » des récompenses
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
                                + " » hors des valeurs connues. Le serveur refusera un " + sourceNoun(f.selectSource())
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

    /**
     * Issue #178 — les deux formes de {@code DISCOVER_CITY}, et la référence de ville. Une ville
     * inconnue est une ERREUR dès que le relevé des villes existe : l'objectif ne progresserait
     * jamais. Sans relevé, on le dit plutôt que de bloquer.
     */
    private static void validateDiscoverCity(List<Diagnostic> out, String ctx, Map<String, String> row,
                                             RefData ref) {
        String city = row.getOrDefault("city", "").trim();
        String amount = row.getOrDefault("amount", "").trim();
        if (city.isEmpty() && amount.isEmpty()) {
            out.add(Diagnostic.error(ctx, "Découvrir des villes : choisir une ville précise, ou indiquer "
                    + "un nombre de villes distinctes."));
            return;
        }
        if (!city.isEmpty() && !amount.isEmpty() && !"1".equals(amount)) {
            out.add(Diagnostic.error(ctx, "Découvrir des villes : avec une ville précise, laisser la quantité "
                    + "vide (une ville se découvre une fois)."));
        }
        if (city.isEmpty()) {
            return;
        }
        if (!ref.sourceKnown("city")) {
            out.add(Diagnostic.info(ctx, "Impossible de vérifier la ville « " + city + " » : rafraîchir la "
                    + "page Villes pour charger leur liste."));
        } else if (!ref.isValueKnown("city", city)) {
            out.add(Diagnostic.error(ctx, "Ville inconnue « " + city + " » : choisir une ville existante dans "
                    + "la liste (page Villes)."));
        }
    }

    /** Les champs dont la valeur est un matériau Minecraft (icône ou objet). */
    /** Nom français de la source dans un message (« un bloc », pas « un block »). */
    private static String sourceNoun(String source) {
        return switch (source == null ? "" : source) {
            case "material" -> "matériau";
            case "block" -> "bloc";
            case "entity" -> "type d'entité";
            case "world" -> "monde";
            default -> source;
        };
    }

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
