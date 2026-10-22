package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Le dialogue tel que l'éditeur simple le présente, et le chemin retour formulaire → dialogue
 * (issue #332).
 *
 * <p><strong>Ce que l'utilisateur voit</strong> : des répliques (« Le personnage dit… »), sous
 * chacune les réponses du joueur, et pour chaque réponse « Ensuite… » (une autre réplique ou la
 * fin de la conversation) plus, éventuellement, une quête à proposer, faire avancer ou rendre.
 * Le reste (conditions, autres effets) vit dans une partie « avancée » de la réponse.</p>
 *
 * <p><strong>Fidélité.</strong> Tout ce que porte le fichier passe par le formulaire et en revient
 * à l'identique : l'ordre des effets, la place de la fermeture, les champs que l'éditeur ne sait
 * pas nommer. {@link #fields} produit exactement les champs que la page émet, et {@link #parse}
 * reconstruit le dialogue depuis ces champs ; {@code DialogueFormTest} vérifie que
 * {@code parse(fields(d))} réécrit le même fichier, y compris pour les dialogues complexes du
 * dépôt. Rien ne peut disparaître silencieusement en passant par l'éditeur simple.</p>
 *
 * <p>Classe pure : aucun accès disque, aucune dépendance web.</p>
 */
public final class DialogueForm {

    /** Valeur de « Ensuite… » : la conversation se termine. */
    public static final String END = "__end";
    /** Texte de la réponse posée d'office sur une réplique neuve (issue #730). */
    public static final String GOODBYE = "Au revoir.";
    /** Valeur de « Ensuite… » : une nouvelle réplique, créée à l'enregistrement. */
    public static final String NEW = "__new";

    /** Les trois effets de quête proposés directement sous une réponse, dans l'ordre du menu. */
    public static final List<String> QUEST_ACTIONS = List.of("START_QUEST", "ADVANCE_QUEST", "TURN_IN_QUEST");

    /**
     * Issue #430 — « Proposer une quête au hasard dans une liste » : l'action {@code OFFER_CATALOG_QUEST}
     * de #369, rangée dans le même menu que les trois effets de quête. Son paramètre est un catalogue
     * ({@code catalog}, facultatif), jamais une quête.
     */
    public static final String CATALOG_ACTION = "OFFER_CATALOG_QUEST";

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final int MAX_NODES = 200;
    private static final int MAX_CHOICES = 30;
    private static final int MAX_ENTRIES = 30;

    private DialogueForm() {
    }

    // ---- lecture : ce que l'éditeur montre d'une réponse ------------------------------------

    /**
     * Une réponse découpée pour l'éditeur.
     *
     * @param next         id de la réplique suivante, ou {@link #END}
     * @param questAction  {@code START_QUEST} / {@code ADVANCE_QUEST} / {@code TURN_IN_QUEST} /
     *                     {@link #CATALOG_ACTION}, ou vide
     * @param questId      quête de cet effet, ou vide
     * @param catalogId    catalogue de {@link #CATALOG_ACTION} ; vide = ceux des modèles de PNJ
     * @param questPos     position de cet effet dans la liste d'origine, {@code -1} s'il n'y en a pas
     * @param closePos     position de la fermeture dans la liste d'origine, {@code -1} sans fermeture
     * @param wasEnd       la réponse terminait déjà la conversation
     * @param originalNext {@code next} tel qu'écrit dans le fichier (même avec une fermeture)
     * @param conditions   conditions, dans l'ordre du fichier
     * @param otherEffects autres effets (ni la quête ci-dessus, ni la fermeture), dans l'ordre
     */
    public record Reply(String text, String next, String questAction, String questId, String catalogId,
                        int questPos,
                        int closePos, boolean wasEnd, String originalNext,
                        List<Map<String, String>> conditions, List<Map<String, String>> otherEffects) {
    }

    public static Reply reply(DialogueDraft.Choice c) {
        int questPos = -1;
        int closePos = -1;
        List<Map<String, String>> others = new ArrayList<>();
        for (int i = 0; i < c.actions.size(); i++) {
            Map<String, String> a = c.actions.get(i);
            String type = a.getOrDefault("type", "").toUpperCase(Locale.ROOT);
            if (closePos < 0 && "CLOSE".equals(type) && a.size() == 1) {
                closePos = i;
            } else if (questPos < 0 && (QUEST_ACTIONS.contains(type) && isPlainQuestAction(a)
                    || CATALOG_ACTION.equals(type) && isPlainCatalogAction(a))) {
                questPos = i;
            } else {
                others.add(a);
            }
        }
        String next = c.next == null ? "" : c.next.trim();
        boolean ends = next.isEmpty();
        String questAction = questPos < 0 ? "" : c.actions.get(questPos).get("type").toUpperCase(Locale.ROOT);
        String questId = questPos < 0 ? "" : c.actions.get(questPos).getOrDefault("quest", "");
        String catalogId = questPos < 0 ? "" : c.actions.get(questPos).getOrDefault("catalog", "");
        return new Reply(c.text, ends ? END : next, questAction, questId, catalogId, questPos, closePos, ends, next,
                List.copyOf(c.conditions), List.copyOf(others));
    }

    /** Une action de quête que le menu simple sait représenter : {@code type} + {@code quest}, rien d'autre. */
    private static boolean isPlainQuestAction(Map<String, String> a) {
        for (String k : a.keySet()) {
            if (!"type".equals(k) && !"quest".equals(k)) {
                return false;
            }
        }
        return a.containsKey("quest");
    }

    /**
     * {@code type} + {@code catalog} facultatif. Un {@code catalog} écrit vide n'est pas représentable
     * (le menu l'omettrait) : l'entrée reste alors dans « autres effets », telle quelle.
     */
    private static boolean isPlainCatalogAction(Map<String, String> a) {
        for (Map.Entry<String, String> kv : a.entrySet()) {
            if ("catalog".equals(kv.getKey()) ? kv.getValue().isBlank() : !"type".equals(kv.getKey())) {
                return false;
            }
        }
        return true;
    }

    // ---- noms de champs : uniques, partagés par la page et par parse() -------------------

    public static String node(int i, String field) {
        return "n" + i + "_" + field;
    }

    public static String reply(int i, int j, String field) {
        return "n" + i + "_c" + j + "_" + field;
    }

    /** {@code kind} = {@code cd} (condition) ou {@code fx} (autre effet). */
    public static String entry(int i, int j, String kind, int k, String field) {
        return "n" + i + "_c" + j + "_" + kind + k + "_" + field;
    }

    /** Champs émis par la page pour ce dialogue, avec leurs valeurs (cases à cocher incluses). */
    public static Map<String, String> fields(DialogueDraft d) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("d_id", DialogueYaml.plainId(d.id));
        f.put("d_start", d.start == null ? "" : d.start);
        f.put("n_count", String.valueOf(d.nodes.size()));
        for (int i = 0; i < d.nodes.size(); i++) {
            DialogueDraft.Node n = d.nodes.get(i);
            f.put(node(i, "id"), n.id);
            f.put(node(i, "speaker"), n.speaker);
            f.put(node(i, "text"), n.text);
            f.put(node(i, "c_count"), String.valueOf(n.choices.size()));
            for (int j = 0; j < n.choices.size(); j++) {
                Reply r = reply(n.choices.get(j));
                f.put(reply(i, j, "text"), r.text());
                f.put(reply(i, j, "next"), r.next());
                f.put(reply(i, j, "qa"), r.questAction());
                f.put(reply(i, j, "qid"), r.questId());
                f.put(reply(i, j, "qcat"), r.catalogId());
                f.put(reply(i, j, "qpos"), String.valueOf(r.questPos()));
                f.put(reply(i, j, "cpos"), String.valueOf(r.closePos()));
                f.put(reply(i, j, "wend"), r.wasEnd() ? "1" : "0");
                f.put(reply(i, j, "onext"), r.originalNext());
                putEntries(f, i, j, "cd", r.conditions());
                putEntries(f, i, j, "fx", r.otherEffects());
            }
        }
        return f;
    }

    private static void putEntries(Map<String, String> f, int i, int j, String kind, List<Map<String, String>> entries) {
        f.put(reply(i, j, kind + "_count"), String.valueOf(entries.size()));
        for (int k = 0; k < entries.size(); k++) {
            Map<String, String> e = entries.get(k);
            f.put(entry(i, j, kind, k, "keys"), String.join(",", e.keySet()));
            f.put(entry(i, j, kind, k, "otype"), e.getOrDefault("type", ""));
            for (Map.Entry<String, String> kv : e.entrySet()) {
                if ("cd".equals(kind) && Descriptors.NEGATE.name().equals(kv.getKey())) {
                    // Case à cocher : cochée = « true ». La valeur d'origine voyage à part, pour
                    // qu'un « false » écrit à la main revienne tel quel.
                    f.put(entry(i, j, kind, k, "onegate"), kv.getValue());
                    if ("true".equalsIgnoreCase(kv.getValue())) {
                        f.put(entry(i, j, kind, k, "v_negate"), "true");
                    }
                } else {
                    f.put(entry(i, j, kind, k, "v_" + kv.getKey()), kv.getValue());
                }
            }
        }
    }

    // ---- écriture : formulaire → dialogue ------------------------------------------------

    /** Le dialogue reconstruit, et les répliques créées par « Nouvelle réplique ». */
    public record Parsed(DialogueDraft draft, List<String> createdNodes) {
    }

    public static Parsed parse(Map<String, String> form) {
        DialogueDraft d = new DialogueDraft();
        d.id = get(form, "d_id");
        d.start = get(form, "d_start");
        int nodes = count(form, "n_count", MAX_NODES);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < nodes; i++) {
            ids.add(get(form, node(i, "id")));
        }
        List<String> created = new ArrayList<>();
        List<DialogueDraft.Node> extra = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            DialogueDraft.Node n = new DialogueDraft.Node(get(form, node(i, "id")));
            n.speaker = get(form, node(i, "speaker"));
            n.text = get(form, node(i, "text"));
            int choices = count(form, node(i, "c_count"), MAX_CHOICES);
            for (int j = 0; j < choices; j++) {
                if ("1".equals(form.get(reply(i, j, "del")))) {
                    continue;
                }
                DialogueDraft.Choice c = parseChoice(form, i, j);
                if (NEW.equals(c.next)) {
                    String id = freeNodeId(ids);
                    ids.add(id);
                    created.add(id);
                    DialogueDraft.Node fresh = new DialogueDraft.Node(id);
                    fresh.speaker = n.speaker;
                    fresh.choices.add(goodbye());
                    extra.add(fresh);
                    c.next = id;
                }
                n.choices.add(c);
            }
            d.nodes.add(n);
        }
        d.nodes.addAll(extra);
        return new Parsed(d, List.copyOf(created));
    }

    private static DialogueDraft.Choice parseChoice(Map<String, String> form, int i, int j) {
        DialogueDraft.Choice c = new DialogueDraft.Choice();
        c.text = get(form, reply(i, j, "text"));
        String next = get(form, reply(i, j, "next"));
        String originalNext = get(form, reply(i, j, "onext"));
        boolean wasEnd = "1".equals(form.get(reply(i, j, "wend")));
        int closePos = intOr(form.get(reply(i, j, "cpos")), -1);
        int questPos = intOr(form.get(reply(i, j, "qpos")), -1);

        c.conditions.addAll(parseEntries(form, i, j, "cd"));
        List<Map<String, String>> actions = new ArrayList<>(parseEntries(form, i, j, "fx"));

        String qa = get(form, reply(i, j, "qa")).toUpperCase(Locale.ROOT);
        String qid = get(form, reply(i, j, "qid"));
        Map<String, String> quest = null;
        if (QUEST_ACTIONS.contains(qa)) {
            quest = new LinkedHashMap<>();
            quest.put("type", qa);
            quest.put("quest", qid);
        } else if (CATALOG_ACTION.equals(qa)) {
            quest = new LinkedHashMap<>();
            quest.put("type", qa);
            String catalog = get(form, reply(i, j, "qcat"));
            if (!catalog.isEmpty()) {
                quest.put("catalog", catalog);
            }
        }

        boolean ends = END.equals(next) || next.isEmpty();
        boolean keepClose;
        if (ends) {
            c.next = "";
            // Fin de conversation : la fermeture d'origine reste à sa place ; une fin implicite
            // d'origine (ni suite ni fermeture) reste implicite tant que la réponse ne gagne pas de
            // quête ; une fin nouvelle est explicite.
            keepClose = closePos >= 0 || !wasEnd || (quest != null && questPos < 0);
        } else {
            c.next = next;
            // Une suite ET une fermeture dans le fichier : rien ne change tant que la suite non plus.
            keepClose = closePos >= 0 && next.equals(originalNext);
        }
        Map<String, String> close = null;
        if (keepClose) {
            close = new LinkedHashMap<>();
            close.put("type", "CLOSE");
            c.close = true;
        }
        // Les deux effets que le menu simple représente reprennent leur place d'origine. Une quête
        // nouvellement proposée se place avant la fermeture : la conversation se ferme en dernier.
        int far = Integer.MAX_VALUE;
        int questTarget = quest == null ? far
                : questPos >= 0 ? questPos : (close != null && closePos >= 0 ? closePos : far);
        int closeTarget = close == null ? far
                : closePos >= 0 ? (quest != null && questPos < 0 ? closePos + 1 : closePos) : far;
        if (quest != null && (close == null || questTarget <= closeTarget)) {
            actions.add(Math.min(questTarget, actions.size()), quest);
            if (close != null) {
                actions.add(Math.min(closeTarget, actions.size()), close);
            }
        } else {
            if (close != null) {
                actions.add(Math.min(closeTarget, actions.size()), close);
            }
            if (quest != null) {
                actions.add(Math.min(questTarget, actions.size()), quest);
            }
        }
        c.actions.addAll(actions);
        c.simple = c.conditions.isEmpty() && actions.stream()
                .allMatch(a -> "CLOSE".equalsIgnoreCase(a.getOrDefault("type", "")));
        return c;
    }

    private static List<Map<String, String>> parseEntries(Map<String, String> form, int i, int j, String kind) {
        List<Map<String, String>> out = new ArrayList<>();
        int n = count(form, reply(i, j, kind + "_count"), MAX_ENTRIES);
        for (int k = 0; k < n; k++) {
            if ("1".equals(form.get(entry(i, j, kind, k, "del")))) {
                continue;
            }
            String type = get(form, entry(i, j, kind, k, "v_type"));
            List<String> keys = new ArrayList<>();
            for (String key : get(form, entry(i, j, kind, k, "keys")).split(",")) {
                if (KEY.matcher(key).matches() && !keys.contains(key)) {
                    keys.add(key);
                }
            }
            List<String> wanted = fieldsOf(kind, type);
            String originalType = originalType(form, i, j, kind, k);
            if (!type.equalsIgnoreCase(originalType)) {
                // Le type a changé : on ne garde que les champs du nouveau type (jamais une valeur
                // héritée de l'ancien, qui n'aurait plus de sens).
                keys = new ArrayList<>(wanted);
            } else {
                for (String w : wanted) {
                    if (!keys.contains(w) && !get(form, entry(i, j, kind, k, "v_" + w)).isEmpty()) {
                        keys.add(w);
                    }
                }
            }
            if (!keys.contains("type")) {
                keys.add(0, "type");
            }
            String negateKey = Descriptors.NEGATE.name();
            boolean condition = "cd".equals(kind);
            boolean ticked = condition && "true".equalsIgnoreCase(form.get(entry(i, j, kind, k, "v_negate")));
            String originalNegate = get(form, entry(i, j, kind, k, "onegate"));
            Map<String, String> e = new LinkedHashMap<>();
            for (String key : keys) {
                if (condition && negateKey.equals(key)) {
                    // À sa place d'origine : cochée = true ; décochée = la valeur écrite à la main
                    // si ce n'était pas « true », sinon retirée.
                    if (ticked) {
                        e.put(key, "true");
                    } else if (!originalNegate.isEmpty() && !"true".equalsIgnoreCase(originalNegate)) {
                        e.put(key, originalNegate);
                    }
                    continue;
                }
                e.put(key, get(form, entry(i, j, kind, k, "v_" + key)));
            }
            if (ticked && !e.containsKey(negateKey)) {
                e.put(negateKey, "true");
            }
            out.add(e);
        }
        return out;
    }

    /** Type d'origine d'une entrée : premier élément de {@code keys} relu, ou le type courant. */
    private static String originalType(Map<String, String> form, int i, int j, String kind, int k) {
        String ot = form.get(entry(i, j, kind, k, "otype"));
        return ot == null ? get(form, entry(i, j, kind, k, "v_type")) : ot.trim();
    }

    /** Champs attendus par un type, {@code type} en tête ; vide si le type est inconnu. */
    public static List<String> fieldsOf(String kind, String type) {
        List<Descriptors.Descriptor> catalog = "cd".equals(kind)
                ? Descriptors.DIALOGUE_CONDITIONS : Descriptors.DIALOGUE_ACTIONS;
        List<String> out = new ArrayList<>();
        out.add("type");
        catalog.stream().filter(d -> d.kind().equalsIgnoreCase(type)).findFirst()
                .ifPresent(d -> d.fields().forEach(f -> out.add(f.name())));
        return out;
    }

    // ---- gestes de l'éditeur (sans enregistrer) -----------------------------------------

    /** Ajoute une réponse vide à la réplique {@code nodeIndex}. Elle termine la conversation. */
    public static void addReply(DialogueDraft d, int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= d.nodes.size()) {
            return;
        }
        DialogueDraft.Node n = d.nodes.get(nodeIndex);
        if (n.choices.size() < MAX_CHOICES) {
            n.choices.add(new DialogueDraft.Choice("", "", true));
        }
    }

    /** Ajoute une condition ou un effet vide (type donné) à une réponse. */
    public static void addEntry(DialogueDraft d, int nodeIndex, int choiceIndex, String kind, String type) {
        if (nodeIndex < 0 || nodeIndex >= d.nodes.size()) {
            return;
        }
        DialogueDraft.Node n = d.nodes.get(nodeIndex);
        if (choiceIndex < 0 || choiceIndex >= n.choices.size() || !knownType(kind, type)) {
            return;
        }
        DialogueDraft.Choice c = n.choices.get(choiceIndex);
        Map<String, String> e = new LinkedHashMap<>();
        for (String f : fieldsOf(kind, type)) {
            e.put(f, "type".equals(f) ? type.toUpperCase(Locale.ROOT) : "");
        }
        if ("cd".equals(kind)) {
            c.conditions.add(e);
        } else {
            // Avant la fermeture éventuelle, pour garder la fermeture en dernier.
            int close = -1;
            for (int i = 0; i < c.actions.size(); i++) {
                if ("CLOSE".equalsIgnoreCase(c.actions.get(i).getOrDefault("type", ""))) {
                    close = i;
                }
            }
            c.actions.add(close < 0 ? c.actions.size() : close, e);
        }
    }

    private static boolean knownType(String kind, String type) {
        List<Descriptors.Descriptor> catalog = "cd".equals(kind)
                ? Descriptors.DIALOGUE_CONDITIONS : Descriptors.DIALOGUE_ACTIONS;
        return type != null && catalog.stream().anyMatch(x -> x.kind().equalsIgnoreCase(type));
    }

    /**
     * Ajoute une réplique, prononcée par le même personnage que la première.
     *
     * @return l'id de la réplique créée
     */
    public static String addNode(DialogueDraft d) {
        Set<String> ids = new LinkedHashSet<>();
        d.nodes.forEach(n -> ids.add(n.id));
        String id = freeNodeId(ids);
        DialogueDraft.Node n = new DialogueDraft.Node(id);
        n.speaker = d.nodes.isEmpty() ? "" : d.nodes.get(0).speaker;
        n.choices.add(goodbye());
        d.nodes.add(n);
        return id;
    }

    /**
     * Issue #730 — la réponse posée d'office sur une réplique neuve : le jeu exige au moins une
     * réponse par réplique, et celle-ci termine la conversation. Elle se modifie ou se remplace.
     */
    static DialogueDraft.Choice goodbye() {
        return new DialogueDraft.Choice(GOODBYE, "", true);
    }

    /**
     * Retire une réplique. Les réponses qui y menaient terminent désormais la conversation, et on
     * le dit : le nombre renvoyé sert au message affiché. La réplique de départ ne se retire pas.
     *
     * @return le nombre de réponses redirigées, ou {@code -1} si rien n'a été retiré
     */
    public static int removeNode(DialogueDraft d, int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= d.nodes.size()) {
            return -1;
        }
        DialogueDraft.Node gone = d.nodes.get(nodeIndex);
        if (gone.id.equals(d.start) || d.nodes.size() == 1) {
            return -1;
        }
        d.nodes.remove(nodeIndex);
        int redirected = 0;
        for (DialogueDraft.Node n : d.nodes) {
            for (DialogueDraft.Choice c : n.choices) {
                if (gone.id.equals(c.next == null ? "" : c.next.trim())) {
                    c.next = "";
                    if (!c.close) {
                        Map<String, String> close = new LinkedHashMap<>();
                        close.put("type", "CLOSE");
                        c.actions.add(close);
                        c.close = true;
                    }
                    redirected++;
                }
            }
        }
        return redirected;
    }

    // ---- libellés lisibles ---------------------------------------------------------------

    /** « Réplique 2 », dans l'ordre d'affichage (la réplique de départ est toujours la 1). */
    public static List<DialogueDraft.Node> displayOrder(DialogueDraft d) {
        List<DialogueDraft.Node> out = new ArrayList<>();
        DialogueDraft.Node start = d.startNode();
        if (start != null) {
            out.add(start);
        }
        for (DialogueDraft.Node n : d.nodes) {
            if (n != start) {
                out.add(n);
            }
        }
        return out;
    }

    /** Numéro affiché d'une réplique (1 = départ), ou 0 si elle n'existe pas. */
    public static int number(DialogueDraft d, String nodeId) {
        List<DialogueDraft.Node> order = displayOrder(d);
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id.equals(nodeId)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Début lisible d'une réplique, sans balises : « Bonjour voyageur, tu… ». */
    public static String excerpt(String text, int max) {
        String plain = text == null ? "" : text.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        return plain.length() <= max ? plain : plain.substring(0, Math.max(1, max - 1)).trim() + "…";
    }

    /** Ids de répliques auxquelles aucune réponse ne mène (hors départ). */
    public static Set<String> unreachable(DialogueDraft d) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>();
        if (d.startNode() != null) {
            todo.add(d.start);
        }
        while (!todo.isEmpty()) {
            String id = todo.remove(todo.size() - 1);
            if (!seen.add(id)) {
                continue;
            }
            for (DialogueDraft.Node n : d.nodes) {
                if (n.id.equals(id)) {
                    for (DialogueDraft.Choice c : n.choices) {
                        if (c.next != null && !c.next.isBlank()) {
                            todo.add(c.next.trim());
                        }
                    }
                }
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (DialogueDraft.Node n : d.nodes) {
            if (!seen.contains(n.id)) {
                out.add(n.id);
            }
        }
        return out;
    }

    // ---- utilitaires ----------------------------------------------------------------------

    private static String freeNodeId(Set<String> taken) {
        for (int i = 2; i < 10_000; i++) {
            String id = "r" + i;
            if (!taken.contains(id)) {
                return id;
            }
        }
        return "r" + System.nanoTime();
    }

    /** Identifiant de réplique acceptable par le moteur. */
    public static boolean validNodeId(String id) {
        return id != null && NODE_ID.matcher(id).matches();
    }

    private static String get(Map<String, String> form, String key) {
        String v = form.get(key);
        return v == null ? "" : v.trim();
    }

    private static int count(Map<String, String> form, String key, int max) {
        return Math.max(0, Math.min(max, intOr(form.get(key), 0)));
    }

    private static int intOr(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
