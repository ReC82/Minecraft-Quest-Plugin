package com.lodygames.rpgquest.quest.progress;

import java.util.List;
import java.util.function.Function;
import org.bukkit.NamespacedKey;

/**
 * Rend l'état de remise d'un PNJ (issue #123) sous forme de texte MiniMessage, pour le marqueur
 * {@code %delivery_status%} d'un texte de nœud de dialogue — « ce qui a déjà été remis » et « ce
 * qui manque », en une ligne par matériau :
 *
 * <pre>
 * ✔ Bâton 1/1
 *   Pierre 1/2
 *   Cuir 2/4
 * </pre>
 *
 * <p>Les noms d'objets passent par la balise MiniMessage {@code <lang:…>} construite depuis la clé
 * de traduction vanilla du matériau : le client affiche donc « Cuir » ou « Leather » dans sa propre
 * langue, sans aucune table de correspondance à maintenir côté plugin. C'est la même raison que
 * {@code ui.ObjectiveLabels}, adaptée au fait qu'un marqueur de texte produit une <em>chaîne</em>
 * MiniMessage et non un {@code Component}.</p>
 *
 * <p>La phrase qui entoure cette liste n'est pas ici : elle vit dans le texte du nœud
 * ({@code dialogues/*.yml}), donc entièrement éditable depuis le Control Panel sans toucher au
 * code.</p>
 */
public final class DeliveryStatusText {

    /** Réponse quand ce PNJ n'attend rien de ce joueur — jamais une liste vide muette. */
    public static final String NOTHING_EXPECTED = "<gray>Je n'attends aucun matériau de ta part.</gray>";

    /** Conclusion du bilan quand la remise qui vient d'avoir lieu a tout complété (issue #467). */
    public static final String ALL_DELIVERED = "<green>Merci, c'est tout ce qu'il me fallait !</green>";

    private DeliveryStatusText() {
    }

    /** Issue #475 : en tête du bilan d'une remise réussie, suivi de « 2 × Bâton, 3 × Pierre ». */
    public static final String JUST_DELIVERED = "<white>Tu viens de me remettre :</white> ";

    /** Issue #475 : la remise n'a rien retiré — dit DANS le dialogue, plus seulement dans le chat. */
    public static final String NOTHING_USEFUL = "<gray>Tu n'as rien d'utile à me remettre pour l'instant.</gray>";

    /** Issue #475 : il reste à remettre, mais le joueur ne porte aucun des matériaux manquants. */
    public static final String NOTHING_CARRIED = "<gray>Tu n'as sur toi aucun des matériaux qui manquent.</gray>";

    /** Issue #475 : il reste à remettre et le joueur porte de quoi avancer. */
    public static final String CARRIES_USEFUL = "<yellow>Tu as sur toi des matériaux que j'attends.</yellow>";

    /** Issue #475 : en tête de la liste {@code %delivery_hint%}. */
    public static final String EXPECTED_HEADER = "<white>Matériaux à me remettre :</white>";

    /**
     * Comme {@link #render(List)}, juste après une remise (issue #467). Une remise qui termine la
     * quête la retire des quêtes actives : {@code pending} est alors vide, et « Je n'attends aucun
     * matériau » contredisait le « Quête terminée » affiché au même instant. Dans ce seul cas, le
     * bilan de la remise ({@code justDelivered}) remplace l'état relu ; dès qu'il reste quelque chose
     * à remettre (remise partielle, étape suivante qui demande d'autres matériaux), l'état réel prime.
     */
    public static String render(List<DeliveryLine> pending, DeliveryOutcome justDelivered) {
        if (!pending.isEmpty() || justDelivered == null || justDelivered.lines().isEmpty()
                || !justDelivered.allComplete()) {
            return render(pending);
        }
        return render(justDelivered.lines()) + "<newline>" + ALL_DELIVERED;
    }

    /**
     * Rendu complet de {@code %delivery_status%} (issue #475) : le bilan de la remise qui vient
     * d'avoir lieu (« Tu viens de me remettre : 2 × Bâton », ou « rien d'utile »), l'état réel relu
     * (déjà remis / demandé), ce que le joueur porte encore d'utile, puis la fin des quêtes que la
     * remise a terminées ({@code completionText}, par exemple « Amélioration du kit obtenue »). Le
     * joueur lit tout dans la fenêtre du dialogue, jamais derrière, dans le chat.
     *
     * @param carriesUseful  {@code true} si une nouvelle remise retirerait encore quelque chose
     * @param justDelivered  résultat du choix qui vient d'être cliqué, {@code null} sinon
     * @param completionText phrase de fin d'une quête terminée ; {@code null} = rien à ajouter
     */
    public static String render(List<DeliveryLine> pending, boolean carriesUseful, DeliveryOutcome justDelivered,
                                Function<NamespacedKey, String> completionText) {
        List<String> parts = new java.util.ArrayList<>();
        DeliveryOutcome.Status status = justDelivered == null ? null : justDelivered.status();
        if (status == DeliveryOutcome.Status.DELIVERED && !justDelivered.justDelivered().isEmpty()) {
            parts.add(JUST_DELIVERED + amounts(justDelivered.justDelivered()));
        } else if (status == DeliveryOutcome.Status.NOTHING_USEFUL) {
            parts.add(NOTHING_USEFUL);
        }
        parts.add(render(pending, justDelivered));
        boolean missing = pending.stream().anyMatch(line -> !line.complete());
        if (missing && status != DeliveryOutcome.Status.NOTHING_USEFUL) {
            parts.add(carriesUseful ? CARRIES_USEFUL : NOTHING_CARRIED);
        }
        if (justDelivered != null && completionText != null) {
            for (NamespacedKey questId : justDelivered.completedQuests()) {
                String done = completionText.apply(questId);
                if (done != null && !done.isBlank()) {
                    parts.add(done);
                }
            }
        }
        return String.join("<newline>", parts);
    }

    /**
     * {@code %delivery_hint%} (issue #475) : rien du tout quand ce PNJ n'attend rien de ce joueur —
     * le marqueur peut donc vivre dans un texte d'accueil sans le changer pour les autres —, sinon
     * un retour à la ligne, la liste de ce qu'il reste à remettre et ce que le joueur porte d'utile.
     */
    public static String hint(List<DeliveryLine> pending, boolean carriesUseful) {
        if (pending.stream().allMatch(DeliveryLine::complete)) {
            return "";
        }
        return "<newline>" + EXPECTED_HEADER + "<newline>" + render(pending) + "<newline>"
                + (carriesUseful ? CARRIES_USEFUL : NOTHING_CARRIED);
    }

    /** « 2 × Bâton, 3 × Pierre » : ce qui vient d'être remis, noms traduits côté client. */
    private static String amounts(List<DeliveryLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (DeliveryLine line : lines) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(line.justNow()).append(" × <lang:").append(line.material().translationKey()).append(">");
        }
        return "<white>" + sb + "</white>";
    }

    public static String render(List<DeliveryLine> lines) {
        if (lines.isEmpty()) {
            return NOTHING_EXPECTED;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("<newline>");
            }
            sb.append(line(lines.get(i)));
        }
        return sb.toString();
    }

    private static String line(DeliveryLine line) {
        String name = "<lang:" + line.material().translationKey() + ">";
        if (line.complete()) {
            return "<green>✔ " + name + " " + line.delivered() + "/" + line.required() + "</green>";
        }
        return "<white>• " + name + " " + line.delivered() + "/" + line.required() + "</white>";
    }
}
