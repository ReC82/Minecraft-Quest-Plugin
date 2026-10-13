package com.lodygames.rpgquest.panel.npc;

import com.lodygames.rpgquest.panel.content.DialogueDraft;
import com.lodygames.rpgquest.panel.content.DialogueYaml;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Issue #325 — ce que « Créer un PNJ » décide avant d'envoyer quoi que ce soit : l'identifiant et
 * le dialogue initial.
 *
 * <h2>L'identifiant</h2>
 *
 * <p>Déduit du nom avec la <strong>même règle que le plugin</strong> ({@code BukkitAgentActions
 * .slugify}) : minuscules ASCII, « _ » comme séparateur, 48 caractères au plus. Quand il est déjà
 * pris — par une définition, un PNJ en jeu, une quête qui le cite ou un dialogue qui porte ce nom —
 * on essaie {@code _2}, {@code _3}… Un deuxième « Lucien » reçoit donc {@code lucien_2} au lieu
 * d'un refus. Le plugin refuse de toute façon un identifiant déjà défini : c'est le dernier rempart,
 * pas celui-ci.</p>
 *
 * <h2>Le dialogue initial</h2>
 *
 * <p>Un dialogue <strong>propre à ce PNJ</strong>, nommé comme lui ({@code dialogues/<id>.yml}),
 * pour que le jeu l'ouvre par la convention {@code rpgquest:<id>} et que modifier celui de Lucien
 * ne touche jamais celui d'un autre. Une réplique, « Salut ! », et un choix « Au revoir ». Aucune
 * quête n'y est proposée.</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 */
public final class NpcCreationPlanner {

    /** Longueur maximale d'un nom de PNJ : celle que le renommage accepte. */
    public static final int MAX_NAME = 48;

    /** Réplique du dialogue initial. */
    public static final String GREETING = "Salut !";

    /** Choix unique du dialogue initial : il ferme la conversation. */
    public static final String FAREWELL = "Au revoir";

    private static final int MAX_ID = 48;
    private static final int MAX_SUFFIX = 99;

    private NpcCreationPlanner() {
    }

    /** Identifiant déduit d'un nom — la règle du plugin, à l'identique. */
    public static String slugify(String displayName) {
        String plain = displayName == null ? "" : displayName.replaceAll("<[^>]*>", "");
        String ascii = Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.length() > MAX_ID ? slug.substring(0, MAX_ID).replaceAll("_+$", "") : slug;
    }

    /**
     * Premier identifiant libre pour ce nom, ou vide si le nom ne contient ni lettre ni chiffre (ou
     * si les 99 variantes sont prises, ce qui ne se produit qu'avec un nom réutilisé à l'excès).
     *
     * @param taken identifiants déjà utilisés, avec ou sans préfixe {@code rpgquest:}, toute casse
     */
    public static Optional<String> freeId(String displayName, Collection<String> taken) {
        String base = slugify(displayName);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        Set<String> used = normalize(taken);
        if (!used.contains(base)) {
            return Optional.of(base);
        }
        for (int i = 2; i <= MAX_SUFFIX; i++) {
            String suffix = "_" + i;
            String head = base.length() + suffix.length() > MAX_ID
                    ? base.substring(0, MAX_ID - suffix.length()).replaceAll("_+$", "")
                    : base;
            String candidate = head + suffix;
            if (!used.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Le dialogue initial d'un nouveau PNJ, prêt à écrire dans la source. */
    public static String initialDialogueYaml(String npcId, String speaker) {
        DialogueDraft draft = new DialogueDraft();
        draft.id = npcId;
        draft.start = "start";
        DialogueDraft.Node start = new DialogueDraft.Node("start");
        start.speaker = speaker == null ? "" : speaker.trim();
        start.text = GREETING;
        start.choices.add(new DialogueDraft.Choice(FAREWELL, "", true));
        draft.nodes.add(start);
        return DialogueYaml.write(draft);
    }

    /** Référence de dialogue que le jeu ouvre pour ce PNJ (convention {@code rpgquest:<id>}). */
    public static String dialogueRef(String npcId) {
        return "rpgquest:" + npcId;
    }

    /**
     * Nom visible, sans balises de mise en forme : c'est lui que compte le compteur « 0/48 ».
     */
    public static String visibleName(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]*>", "").trim();
    }

    private static Set<String> normalize(Collection<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids == null) {
            return out;
        }
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            String low = raw.trim().toLowerCase(Locale.ROOT);
            int colon = low.indexOf(':');
            out.add(colon >= 0 ? low.substring(colon + 1) : low);
        }
        return out;
    }
}
