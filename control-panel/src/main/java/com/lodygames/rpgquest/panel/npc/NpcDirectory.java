package com.lodygames.rpgquest.panel.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Le catalogue PNJ du serveur, tel que le panel peut le raisonner (issues #225 et #226).
 *
 * <p>Construit depuis le dernier relevé {@code npc.list} réussi. <strong>Aucune requête n'est
 * déclenchée</strong> : un relevé absent donne un annuaire {@link #available() indisponible}, et
 * c'est l'appelant qui doit alors refuser de conclure. La nuance est vitale pour une suppression :
 * « ce PNJ n'a aucune dépendance » et « on n'a jamais demandé » ne sont pas la même phrase.</p>
 */
public record NpcDirectory(List<NpcView> npcs, boolean available) {

    public NpcDirectory {
        npcs = List.copyOf(npcs == null ? List.of() : npcs);
    }

    public static NpcDirectory unavailable() {
        return new NpcDirectory(List.of(), false);
    }

    /**
     * Projette les détails d'un {@code npc.list}. {@code null} ⇒ annuaire indisponible.
     *
     * @param details la table {@code details} du relevé, telle que l'agent l'a renvoyée
     */
    public static NpcDirectory from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        List<NpcView> out = new ArrayList<>();
        for (Object raw : asList(details.get("npcs"))) {
            Map<String, Object> m = asMap(raw);
            String id = str(m.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            List<NpcView.Warning> warnings = new ArrayList<>();
            for (Object w : asList(m.get("warnings"))) {
                Map<String, Object> wm = asMap(w);
                warnings.add(new NpcView.Warning(str(wm.get("code")), str(wm.get("severity")),
                        str(wm.get("message"))));
            }
            out.add(new NpcView(id, str(m.get("displayName")),
                    bool(m.get("logicalDefinitionPresent")),
                    bool(m.get("citizensBindingPresent")), integer(m.get("citizensNumericId")),
                    intOr(m.get("bindingCount"), 0),
                    !Boolean.FALSE.equals(m.get("enabled")),
                    str(m.get("definedDialogueId")), str(m.get("dialogueId")),
                    intOr(m.get("dialogueNodes"), 0), intOr(m.get("dialogueChoices"), 0),
                    strings(m.get("dialogueStartsQuests")),
                    strings(m.get("questsGiven")), strings(m.get("questsReferenced")),
                    strings(m.get("sources")), str(m.get("state")), warnings));
        }
        return new NpcDirectory(out, true);
    }

    /** La fiche d'un PNJ, par identifiant logique (insensible à la casse). */
    public Optional<NpcView> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return npcs.stream().filter(n -> n.id().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /** Les PNJ réellement définis — ceux qui ont un fichier, par opposition aux entrées déduites. */
    public List<NpcView> defined() {
        return npcs.stream().filter(NpcView::definitionPresent).toList();
    }

    /** Les entrées sans définition logique : à expliquer et à nettoyer (#126, #225). */
    public List<NpcView> orphans() {
        return npcs.stream().filter(NpcView::orphan).toList();
    }

    /**
     * Tous les PNJ <strong>définis</strong> qui déclarent ce dialogue.
     *
     * <p>C'est la question « ce dialogue est-il partagé ? », et elle décide d'un refus : un
     * dialogue cité par deux définitions ne doit jamais être supprimé parce qu'on supprime l'une
     * d'elles (#226).</p>
     */
    public List<NpcView> npcsLinkedToDialogue(String dialogueId) {
        String key = NpcView.plainKey(dialogueId);
        if (key.isEmpty()) {
            return List.of();
        }
        return defined().stream()
                .filter(n -> n.linkedDialogueKey().equals(key))
                .toList();
    }

    /**
     * Les entrées du catalogue qui <strong>semblent</strong> parler du même personnage que
     * {@code npc}, sans être lui : même dialogue lié, ou identifiant dont l'autre est un préfixe.
     *
     * <p>C'est ce qui relie les deux Mira. L'heuristique est volontairement étroite — un rapprochement
     * faux serait pire qu'absent, puisqu'il suggérerait de nettoyer une entrée légitime : on exige
     * donc une relation <em>démontrable</em> (le même dialogue) ou un préfixe commun suffisamment
     * long, jamais une simple ressemblance de nom.</p>
     */
    public List<NpcView> relatedEntries(NpcView npc) {
        if (npc == null) {
            return List.of();
        }
        String id = npc.id().toLowerCase(Locale.ROOT);
        String dialogueKey = npc.linkedDialogueKey();
        Set<NpcView> out = new LinkedHashSet<>();
        for (NpcView other : npcs) {
            String otherId = other.id().toLowerCase(Locale.ROOT);
            if (otherId.equals(id)) {
                continue;
            }
            // 1. L'entrée EST le dialogue lié, déduit comme PNJ par le catalogue du moteur.
            if (!dialogueKey.isEmpty() && otherId.equals(dialogueKey)) {
                out.add(other);
                continue;
            }
            // 2. Les deux entrées pointent le même dialogue : il n'y a qu'un personnage pour deux
            //    identités, et c'est exactement ce qu'il faut montrer avant de supprimer.
            if (!dialogueKey.isEmpty() && other.linkedDialogueKey().equals(dialogueKey)) {
                out.add(other);
                continue;
            }
            // 3. Préfixe commun long : « mira_cartographer » / « mira_first_map » partagent
            //    « mira_ ». On exige au moins 4 caractères avant le séparateur pour ne pas
            //    rapprocher deux PNJ qui commencent simplement par la même lettre.
            String prefix = commonPrefix(id, otherId);
            int cut = prefix.lastIndexOf('_');
            if (cut >= 4 && other.orphan() != npc.orphan()) {
                out.add(other);
            }
        }
        return List.copyOf(out);
    }

    /** Index id → nom affiché, pour les écrans qui n'ont besoin que de ça. */
    public Map<String, String> displayNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (NpcView n : npcs) {
            if (!n.displayName().isEmpty()) {
                out.putIfAbsent(n.id(), n.displayName());
            }
        }
        return Map.copyOf(out);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static String commonPrefix(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object raw) {
        return asList(raw).stream().map(NpcDirectory::str).filter(s -> !s.isEmpty()).toList();
    }

    private static String str(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        return "null".equals(s) ? "" : s;
    }

    private static boolean bool(Object raw) {
        return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(str(raw));
    }

    private static Integer integer(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String s = str(raw);
        try {
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(Object raw, int fallback) {
        Integer v = integer(raw);
        return v == null ? fallback : v;
    }
}
