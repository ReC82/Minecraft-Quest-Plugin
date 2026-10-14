package com.lodygames.rpgquest.panel.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue #125 — les noms affichés que plusieurs PNJ partagent.
 *
 * <p>Deux PNJ peuvent légitimement s'appeler « Robert » : leurs identifiants techniques
 * ({@code robert_writer}, {@code iron_specialist}) les distinguent pour le moteur. Mais pas pour
 * l'administrateur, qui choisit une fiche par son nom. Ce n'est donc <strong>jamais un refus</strong>,
 * seulement un avertissement. La vraie collision — deux PNJ pour le même identifiant — reste
 * l'affaire du moteur ({@code DUPLICATE_DEFINITION}, {@code DUPLICATE_BINDING}) et de
 * {@link NpcCreationPlanner#freeId}, et elle reste bloquante.</p>
 *
 * <p>Deux noms sont « les mêmes » quand ils se lisent pareil à l'écran : balises de mise en forme
 * retirées, espaces resserrés, casse ignorée. {@code <yellow>Robert</yellow>} et {@code robert}
 * se confondent ; {@code Robert} et {@code Roberte} non. Les accents comptent : élargir la règle
 * signalerait des noms que l'œil distingue.</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 */
public final class NpcDisplayNames {

    /** Un nom porté par plusieurs PNJ : le nom tel qu'affiché la première fois, et leurs identifiants. */
    public record Group(String name, List<String> ids) {
        public Group {
            ids = List.copyOf(ids);
        }
    }

    private final Map<String, Group> byKey;

    private NpcDisplayNames(Map<String, Group> byKey) {
        this.byKey = byKey;
    }

    /**
     * @param namesById identifiant → nom affiché (balises admises). Les noms vides sont ignorés :
     *                  un PNJ sans nom n'en partage aucun.
     */
    public static NpcDisplayNames of(Map<String, String> namesById) {
        Map<String, String> firstName = new LinkedHashMap<>();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        if (namesById != null) {
            for (Map.Entry<String, String> e : namesById.entrySet()) {
                String id = e.getKey() == null ? "" : e.getKey().trim();
                String key = key(e.getValue());
                if (id.isEmpty() || key.isEmpty()) {
                    continue;
                }
                firstName.putIfAbsent(key, visible(e.getValue()));
                List<String> list = ids.computeIfAbsent(key, k -> new ArrayList<>());
                if (list.stream().noneMatch(id::equalsIgnoreCase)) {
                    list.add(id);
                }
            }
        }
        Map<String, Group> out = new LinkedHashMap<>();
        ids.forEach((key, list) -> out.put(key, new Group(firstName.get(key), list)));
        return new NpcDisplayNames(out);
    }

    /**
     * Les noms des PNJ <strong>de la liste</strong> du panel, d'après les relevés {@code npc.list}
     * et {@code npc.citizens.list} : les entrées qui ont une fiche RPGQuest ou un personnage
     * rattaché, sous le nom que l'administrateur lit — celui de la fiche, à défaut celui du jeu.
     * Une entrée seulement citée par une quête n'est pas un personnage : elle n'a pas de nom à partager.
     */
    public static NpcDisplayNames fromSurvey(List<?> npcs, List<?> citizensRoster) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (Object o : npcs == null ? List.of() : npcs) {
            if (!(o instanceof Map<?, ?> n)) {
                continue;
            }
            if (!Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))
                    && !Boolean.TRUE.equals(n.get("citizensBindingPresent"))) {
                continue;
            }
            String id = str(n.get("id"));
            String name = str(n.get("displayName"));
            if (name.isEmpty()) {
                name = inGameName(citizensRoster, id, str(n.get("citizensNumericId")));
            }
            byId.putIfAbsent(id, name);
        }
        return of(byId);
    }

    /** La forme comparée d'un nom : sans balises, espaces resserrés, en minuscules. Vide si aucun nom. */
    public static String key(String rawName) {
        return visible(rawName).toLowerCase(Locale.ROOT);
    }

    /** Les noms portés par au moins deux PNJ, dans l'ordre du relevé. */
    public List<Group> duplicates() {
        return byKey.values().stream().filter(g -> g.ids().size() > 1).toList();
    }

    /** Les <em>autres</em> PNJ qui portent le même nom que {@code id} ; vide si son nom est unique. */
    public List<String> othersSharing(String id) {
        if (id == null) {
            return List.of();
        }
        for (Group g : byKey.values()) {
            if (g.ids().stream().anyMatch(id.trim()::equalsIgnoreCase)) {
                return g.ids().stream().filter(other -> !other.equalsIgnoreCase(id.trim())).toList();
            }
        }
        return List.of();
    }

    /**
     * Les PNJ qui portent déjà ce nom, en excluant {@code exceptId} (celui qu'on renomme).
     * C'est la question que pose le formulaire avant d'enregistrer.
     */
    public List<String> holdersOf(String rawName, String exceptId) {
        Group g = byKey.get(key(rawName));
        if (g == null) {
            return List.of();
        }
        return g.ids().stream().filter(id -> exceptId == null || !id.equalsIgnoreCase(exceptId.trim())).toList();
    }

    /** Index nom comparé → identifiants, pour l'avertissement en direct du formulaire. */
    public Map<String, List<String>> index() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        byKey.forEach((key, g) -> out.put(key, g.ids()));
        return out;
    }

    /** Le nom en jeu du personnage rattaché : par liaison d'abord, à défaut par numéro Citizens. */
    private static String inGameName(List<?> roster, String id, String numericId) {
        List<?> rows = roster == null ? List.of() : roster;
        for (Object o : rows) {
            if (o instanceof Map<?, ?> c && str(c.get("linkedNpcId")).equalsIgnoreCase(id)) {
                return str(c.get("name"));
            }
        }
        for (Object o : rows) {
            if (o instanceof Map<?, ?> c && !numericId.isEmpty() && numericId.equals(str(c.get("numericId")))) {
                return str(c.get("name"));
            }
        }
        return "";
    }

    /** Le relevé transporte parfois la chaîne {@code "null"} : elle ne veut dire que « absent ». */
    private static String str(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        return "null".equals(s) ? "" : s;
    }

    private static String visible(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }
}
