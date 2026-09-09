package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.docs.Markdown;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry d'aide aux diagnostics (#89 / #49). À chaque code de diagnostic du moteur RPGQuest
 * (PNJ, dialogues, quêtes, stories) correspond une entrée <strong>en français clair</strong> :
 * ce qui ne va pas, la conséquence, l'action recommandée, et un lien vers une <strong>ancre
 * précise</strong> du centre de documentation. Les messages ne sont plus dispersés dans les
 * templates : un seul endroit, réutilisé partout.
 *
 * <p>{@link #render} produit un composant Bootstrap cohérent (alert colorée selon la sévérité,
 * titre, texte, conséquence, action, bouton « Comment corriger ? », éventuel bouton d'action
 * immédiate, et le code technique en secondaire).</p>
 */
public final class DiagnosticHelp {

    public enum Level {
        ERROR("alert-danger", "error"),
        WARNING("alert-warning", "warning"),
        INFO("alert-info", "info");

        final String alertClass;
        final String icon;

        Level(String alertClass, String icon) {
            this.alertClass = alertClass;
            this.icon = icon;
        }

        static Level of(String severity) {
            return switch (severity == null ? "" : severity.trim().toLowerCase(Locale.ROOT)) {
                case "error" -> ERROR;
                case "warning" -> WARNING;
                default -> INFO;
            };
        }
    }

    /**
     * @param code        code technique du moteur (ex. {@code BINDING_NO_DEFINITION})
     * @param level       sévérité
     * @param title       titre humain (sert aussi d'ancre : {@code Markdown.slug(title)})
     * @param what        « ce qui ne va pas » — {@code {name}} est remplacé par le nom du sujet
     * @param consequence « pourquoi c'est important »
     * @param action      « quoi faire »
     * @param docSlug     fiche du centre de documentation ({@code /docs/<docSlug>})
     */
    public record Entry(String code, Level level, String title, String what, String consequence,
                        String action, String docSlug) {

        public String anchor() {
            return Markdown.slug(title);
        }

        public String docHref() {
            return "/docs/" + docSlug + "#" + anchor();
        }
    }

    private static final Map<String, Entry> BY_CODE = new LinkedHashMap<>();

    private static void add(String code, Level level, String title, String what, String consequence,
                            String action, String docSlug) {
        BY_CODE.put(code, new Entry(code, level, title, what, consequence, action, docSlug));
    }

    static {
        // ---- PNJ (npc.NpcCatalog) ------------------------------------------------------
        add("BINDING_NO_DEFINITION", Level.ERROR, "Fiche RPGQuest manquante",
                "Le PNJ {name} existe bien dans le jeu, mais il n'a pas encore de fiche RPGQuest.",
                "Ses dialogues, ses quêtes et son rôle ne peuvent pas être gérés depuis PlugAdmin.",
                "Créez sa définition RPGQuest.", "pnj-depannage");
        add("NO_DEFINITION", Level.ERROR, "Fiche RPGQuest manquante",
                "Un contenu RPGQuest (quête, dialogue…) fait référence au PNJ {name}, mais aucune fiche "
                        + "RPGQuest ne le décrit.",
                "Le contenu qui pointe vers ce PNJ ne fonctionnera pas correctement.",
                "Créez sa définition RPGQuest, ou corrigez la référence dans le contenu concerné.",
                "pnj-depannage");
        add("DIALOGUE_MISSING", Level.ERROR, "Dialogue introuvable",
                "La fiche de {name} pointe vers un dialogue qui n'existe pas (ou qui n'est pas chargé).",
                "Le PNJ n'ouvrira aucune conversation en jeu.",
                "Choisissez un dialogue existant dans la fiche du PNJ, ou créez le dialogue manquant.",
                "pnj-depannage");
        add("DUPLICATE_DEFINITION", Level.ERROR, "Fiche RPGQuest en double",
                "Plusieurs fiches RPGQuest décrivent le même identifiant {name}.",
                "PlugAdmin ne sait pas laquelle utiliser : le comportement en jeu devient imprévisible.",
                "Ne gardez qu'une seule fiche pour cet identifiant (supprimez le fichier en trop côté serveur).",
                "pnj-depannage");
        add("DUPLICATE_BINDING", Level.ERROR, "Plusieurs PNJ pour le même identifiant",
                "Plusieurs PNJ du jeu sont associés à l'identifiant {name}.",
                "En jeu, on ne sait pas lequel doit porter les dialogues et les quêtes.",
                "Retirez l'association en trop : un seul PNJ doit porter cet identifiant.",
                "pnj-depannage");
        add("DISABLED", Level.INFO, "Fiche désactivée",
                "La fiche RPGQuest de {name} est marquée comme inactive.",
                "Le PNJ est ignoré par RPGQuest tant que sa fiche reste inactive.",
                "Activez la fiche (« PNJ actif ») si c'est bien voulu.", "pnj-depannage");
        add("NOT_LINKED", Level.INFO, "PNJ pas encore présent en jeu",
                "La fiche RPGQuest de {name} est prête, mais aucun PNJ du jeu ne lui est encore associé.",
                "Les joueurs ne verront pas ce PNJ tant qu'il n'existe pas en jeu.",
                "Créez le PNJ en jeu, ou associez un PNJ existant à cette fiche.", "pnj-depannage");
        add("GIVER_NO_DIALOGUE", Level.INFO, "Donneur de quête sans conversation",
                "{name} est déclaré donneur de quête, mais il n'a pas de dialogue associé.",
                "La quête ne pourra pas être proposée au joueur au cours d'une conversation.",
                "Associez un dialogue à {name}.", "pnj-depannage");

        // ---- Dialogues (dialogue.DialogueCatalog) -----------------------------------
        add("DIALOGUE_NO_NPC", Level.INFO, "Dialogue non associé à un PNJ",
                "Le dialogue {name} existe, mais aucun PNJ RPGQuest ne l'utilise actuellement.",
                "Les joueurs ne pourront pas ouvrir ce dialogue via un PNJ tant qu'aucune liaison n'est définie.",
                "Associez ce dialogue à la fiche RPGQuest du PNJ concerné.", "dialogues-depannage");
        add("MULTIPLE_NPCS", Level.INFO, "Dialogue partagé par plusieurs PNJ",
                "Plusieurs PNJ RPGQuest utilisent le dialogue {name}.",
                "Ce n'est pas forcément un problème, mais une modification affectera tous ces PNJ.",
                "Vérifiez que ce partage est bien voulu.", "dialogues-depannage");
        add("DEFINITION_DIALOGUE_DIVERGES", Level.WARNING, "Le PNJ pointe vers un autre dialogue",
                "La fiche d'un PNJ et ce dialogue ne portent pas le même identifiant.",
                "Le PNJ ouvrira le dialogue déclaré dans sa fiche, pas forcément celui-ci.",
                "Alignez l'identifiant du dialogue dans la fiche du PNJ.", "dialogues-depannage");
        add("NEXT_MISSING", Level.ERROR, "Choix sans destination",
                "Un choix de ce dialogue renvoie vers un nœud qui n'existe pas.",
                "En jeu, ce choix bloquera ou fermera la conversation de façon inattendue.",
                "Corrigez la destination du choix, ou supprimez-le.", "dialogues-depannage");
        add("QUEST_REF_UNKNOWN", Level.WARNING, "Quête inconnue référencée",
                "Ce dialogue fait référence à une quête absente du catalogue.",
                "L'action liée à cette quête ne se déclenchera pas.",
                "Corrigez l'identifiant de la quête, ou créez la quête manquante.", "dialogues-depannage");
        add("NODE_UNREACHABLE", Level.INFO, "Nœud jamais atteint",
                "Un nœud de ce dialogue n'est relié à aucun choix : il est inaccessible.",
                "Ce nœud ne sera jamais affiché en jeu.",
                "Reliez-le via un choix, ou supprimez-le s'il est inutile.", "dialogues-depannage");
        add("DIALOGUE_LOAD_ISSUE", Level.ERROR, "Fichier de dialogue rejeté",
                "Le fichier {name} n'a pas pu être chargé : {detail}",
                "Ce dialogue est totalement absent du jeu.",
                "Corrigez le fichier YAML côté serveur, puis rechargez le catalogue.", "dialogues-depannage");
        add("DIALOGUE_DECLARED_MISSING", Level.ERROR, "Dialogue déclaré mais absent",
                "La fiche du PNJ {name} déclare le dialogue {detail}, qui n'existe pas.",
                "Le PNJ n'ouvrira aucune conversation.",
                "Créez le dialogue, ou retirez la référence dans la fiche du PNJ.", "dialogues-depannage");

        // ---- Quêtes / Stories (vérifications de référence côté PlugAdmin) -----------
        add("QUEST_LOAD_ISSUE", Level.ERROR, "Quête non chargée",
                "Le fichier {name} contient une erreur : {detail}",
                "Cette quête est absente du jeu.",
                "Corrigez le fichier de quête, puis rechargez le catalogue.", "quetes-depannage");
        add("QUEST_PREREQ_UNKNOWN", Level.WARNING, "Prérequis inconnu",
                "Cette quête exige une quête prérequise ({detail}) qui n'existe pas dans le catalogue.",
                "Les joueurs ne pourront jamais remplir ce prérequis : la quête restera bloquée.",
                "Corrigez l'identifiant du prérequis, ou créez la quête manquante.", "quetes-depannage");
        add("QUEST_GIVER_UNKNOWN", Level.WARNING, "Donneur de quête inconnu",
                "Cette quête désigne comme donneur un PNJ ({detail}) qui n'a pas de fiche RPGQuest.",
                "La quête ne pourra pas être proposée au joueur au cours d'une conversation.",
                "Créez la fiche du PNJ donneur, ou corrigez l'identifiant du donneur.", "quetes-depannage");
        add("STORY_LOAD_ISSUE", Level.ERROR, "Story non chargée",
                "Le fichier {name} contient une erreur : {detail}",
                "Cette story est absente du jeu.",
                "Corrigez le fichier de story, puis rechargez le catalogue.", "stories-depannage");
        add("STORY_QUEST_UNKNOWN", Level.WARNING, "Quête inconnue dans la chaîne",
                "Cette story enchaîne une quête ({detail}) qui n'existe pas dans le catalogue.",
                "La progression de la story s'arrêtera à cette étape.",
                "Corrigez l'identifiant de la quête, ou créez la quête manquante.", "stories-depannage");
    }

    private DiagnosticHelp() {
    }

    /** Entrée connue pour ce code, ou {@code null}. */
    public static Entry forCode(String code) {
        return code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /** Toutes les entrées (pour les tests / la génération de doc). */
    public static Map<String, Entry> all() {
        return java.util.Collections.unmodifiableMap(BY_CODE);
    }

    /**
     * Rend le composant Bootstrap d'un diagnostic.
     *
     * @param code        code technique renvoyé par le moteur
     * @param severity    sévérité renvoyée par le moteur (repli si code inconnu)
     * @param rawMessage  message technique du moteur (repli si code inconnu)
     * @param subjectName nom lisible du sujet (PNJ / dialogue / quête) — remplace {@code {name}}
     * @param detail      complément ({@code {detail}}) : message brut, id de prérequis…
     * @param fixHtml     bouton « Corriger maintenant » fourni par la page (peut être {@code ""})
     */
    public static String render(String code, String severity, String rawMessage, String subjectName,
                                String detail, String fixHtml) {
        Entry e = forCode(code);
        Level level = e != null ? e.level() : Level.of(severity);
        String subj = subjectName == null || subjectName.isBlank() ? "concerné" : subjectName;
        String title;
        String what;
        String consequence;
        String action;
        String docHref;
        if (e != null) {
            title = e.title();
            what = interp(e.what(), subj, detail);
            consequence = e.consequence();
            action = interp(e.action(), subj, detail);
            docHref = e.docHref();
        } else {
            // Code non catalogué : on garde le message du moteur mais dans le même habillage.
            title = level == Level.ERROR ? "Anomalie de configuration"
                    : level == Level.WARNING ? "Point de vigilance" : "Information";
            what = rawMessage == null || rawMessage.isBlank() ? "Diagnostic sans détail." : rawMessage;
            consequence = "";
            action = "Consultez la documentation pour ce type d'anomalie.";
            docHref = "/docs";
        }

        StringBuilder sb = new StringBuilder("<div class=\"alert ").append(level.alertClass)
                .append(" pa-diag pa-diag-").append(level.name().toLowerCase(Locale.ROOT)).append("\">");
        sb.append("<div class=\"pa-diag-h\">").append(Icons.icon(level.icon))
                .append("<span>").append(Http.esc(title)).append("</span></div>");
        sb.append("<p class=\"pa-diag-what\">").append(Http.esc(what)).append("</p>");
        if (!consequence.isBlank()) {
            sb.append("<p class=\"pa-diag-why\"><strong>Conséquence :</strong> ")
                    .append(Http.esc(consequence)).append("</p>");
        }
        if (!action.isBlank()) {
            sb.append("<p class=\"pa-diag-do\"><strong>À faire :</strong> ")
                    .append(Http.esc(action)).append("</p>");
        }
        sb.append("<div class=\"pa-diag-btns\">");
        if (fixHtml != null && !fixHtml.isBlank()) {
            sb.append(fixHtml);
        }
        sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(Http.esc(docHref)).append("\">")
                .append(Icons.icon("help")).append("Comment corriger ?</a>");
        sb.append("</div>");
        String shownCode = e != null ? e.code() : (code == null || code.isBlank() ? "" : code);
        if (!shownCode.isBlank()) {
            sb.append("<p class=\"pa-diag-code\">Code technique : <code class=\"tid\">")
                    .append(Http.esc(shownCode)).append("</code></p>");
        }
        return sb.append("</div>").toString();
    }

    private static String interp(String tpl, String name, String detail) {
        return tpl.replace("{name}", "« " + name + " »")
                .replace("{detail}", detail == null || detail.isBlank() ? "—" : "« " + detail + " »");
    }
}
