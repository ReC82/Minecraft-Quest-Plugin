package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.AiContentStudio;
import com.lodygames.rpgquest.panel.ai.ContentPromptBuilder;
import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.Diagnostic;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.npc.DialogueTargetDecision;
import com.lodygames.rpgquest.panel.npc.NpcView;
import java.util.List;

/**
 * Atelier « Créer avec une IA » (issue #146) : une page séparée, dédiée à la génération d'<strong>un
 * seul élément</strong> à la fois — une quête, un dialogue ou une story.
 *
 * <p>L'administrateur ne remplit qu'un formulaire en français. Le contrat de contenu, le schéma, les
 * types réellement supportés et les références réellement disponibles sont joints automatiquement —
 * c'est toute la différence avec un copier-coller manuel dans ChatGPT.</p>
 *
 * <p><strong>Cette page n'écrit jamais.</strong> Elle s'arrête à l'aperçu validé ; l'enregistrement
 * passe par la page d'import (#109), avec son arbitrage des collisions et sa confirmation explicite.
 * Il n'existe donc qu'un seul chemin d'écriture dans le panel, et l'IA n'en obtient aucun
 * raccourci.</p>
 *
 * <p><strong>Texte stylé</strong> : tout champ destiné au joueur — titre de quête, titre de story,
 * nom affiché du locuteur — utilise le composant guidé partagé (palette, styles, aperçu), comme
 * partout ailleurs. Aucune balise MiniMessage n'est demandée dans le parcours normal.</p>
 *
 * <p>Le choix de la famille se fait par un lien, donc par un GET : la politique de sécurité du
 * panel interdit le JavaScript en ligne, et un sélecteur qui échangerait le formulaire côté client
 * exigerait un script. Un lien est aussi partageable et revient par l'historique.</p>
 *
 * <p><strong>Ce que le formulaire renvoie à lui-même (issue #222).</strong> La demande d'origine
 * voyage en entier dans le bouton « Demander une correction » : la sortie refusée, les diagnostics
 * exacts, le fournisseur, et chaque champ saisi. Sans cela, une correction repartait sans les
 * consignes — elle réparait l'erreur signalée en perdant l'identifiant imposé — et le formulaire se
 * rouvrait vide, obligeant à tout retaper. Les diagnostics voyagent dans <em>un seul</em> champ,
 * lignes séparées : le lecteur de formulaire du panel garde une seule valeur par nom, donc une
 * liste de champs homonymes n'en transportait qu'un.</p>
 */
public final class AiStudioPages {

    private AiStudioPages() {
    }

    /**
     * @param decision arbitrage PNJ ↔ dialogue en attente ou déjà rendu (#225), {@code null} pour
     *                 les familles qui n'en ont pas
     * @param fallback contexte de correction à réafficher quand l'appel lui-même a échoué (#222)
     */
    public static String render(List<AiProvider> usable, ContentPromptBuilder.Kind kind,
                                ContentPromptBuilder.QuestRequest questForm,
                                ContentPromptBuilder.DialogueRequest dialogueForm,
                                ContentPromptBuilder.StoryRequest storyForm,
                                String selectedProvider, AiContentStudio.Generation generation,
                                RefData refs, boolean canImport, String error,
                                DialogueTargetDecision.Outcome decision,
                                AiContentStudio.Correction fallback, String rawNpc) {
        ContentPromptBuilder.Kind k = kind == null ? ContentPromptBuilder.Kind.QUEST : kind;
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("gift", "Créer avec une IA",
                "Décrire ce que vous voulez, en français. L'IA propose, les validateurs réels "
                        + "tranchent, et rien n'est enregistré sans votre confirmation.", ""));

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        if (usable.isEmpty()) {
            sb.append(Ui.banner("warning",
                    "Aucun fournisseur d'IA n'est utilisable. Il faut en activer un et y enregistrer "
                    + "une clé API dans <a href=\"/ai/providers\">Fournisseurs d'IA</a>."));
            sb.append(aboutCard());
            return sb.toString();
        }

        sb.append(kindCard(k));

        // L'arbitrage PNJ ↔ dialogue passe AVANT le formulaire : c'est une question posée, pas une
        // remarque à côté. Tant qu'elle est ouverte, aucun appel d'IA n'a eu lieu.
        if (decision != null && decision.needsDecision()) {
            sb.append(decisionCard(decision, usable, dialogueForm, selectedProvider, rawNpc));
        }

        sb.append(switch (k) {
            case QUEST -> questCard(usable, questForm, selectedProvider, refs);
            case DIALOGUE -> dialogueCard(usable, dialogueForm, selectedProvider, refs, decision,
                    rawNpc);
            case STORY -> storyCard(usable, storyForm, selectedProvider, refs);
        });
        if (generation != null) {
            sb.append(resultCard(generation, canImport, questForm, dialogueForm, storyForm,
                    fallback, decision, rawNpc));
        }
        sb.append(aboutCard());
        return sb.toString();
    }

    // ---- Choix de la famille -------------------------------------------------------------------

    /**
     * Une famille à la fois, volontairement. Demander « une quête, son dialogue et une story » dans
     * un seul appel produit un pack dont une partie est bonne et une autre refusée, et il n'existe
     * aucun moyen simple de ne corriger que la mauvaise. Un élément par appel garde chaque échec
     * petit, et chaque correction ciblée.
     */
    private static String kindCard(ContentPromptBuilder.Kind current) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("docs", "1. Que voulez-vous créer ?"));
        sb.append("<div class=\"btnrow\">");
        sb.append(kindLink(ContentPromptBuilder.Kind.QUEST, current, "quests", "Une quête"));
        sb.append(kindLink(ContentPromptBuilder.Kind.DIALOGUE, current, "dialogues", "Un dialogue"));
        sb.append(kindLink(ContentPromptBuilder.Kind.STORY, current, "stories", "Une story"));
        sb.append("</div>");
        sb.append("<p class=\"field-help\">Un seul élément par demande : un échec reste petit, et "
                + "une correction reste ciblée. Pour une quête <em>et</em> son dialogue, faites deux "
                + "demandes — la seconde pourra citer la première, qui existera déjà.</p>");
        return sb.append("</section>").toString();
    }

    private static String kindLink(ContentPromptBuilder.Kind kind, ContentPromptBuilder.Kind current,
                                   String icon, String label) {
        boolean active = kind == current;
        return "<a class=\"btn" + (active ? "" : " secondary") + "\" href=\"/ai/studio?kind="
                + kind.name().toLowerCase(java.util.Locale.ROOT) + "\""
                + (active ? " aria-current=\"page\"" : "") + ">"
                + Icons.icon(icon) + Http.esc(label) + "</a>";
    }

    // ---- Formulaire ----------------------------------------------------------------------------

    private static String questCard(List<AiProvider> usable, ContentPromptBuilder.QuestRequest f,
                                    String selectedProvider, RefData refs) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("quests", "2. Décrire la quête"));
        sb.append(openForm(ContentPromptBuilder.Kind.QUEST));

        sb.append("<div class=\"form-grid\">");
        sb.append("<div class=\"field full\"><label for=\"f-intent\">Ce que la quête doit raconter "
                + "<span aria-hidden=\"true\">*</span></label>");
        sb.append("<textarea id=\"f-intent\" name=\"intent\" rows=\"4\" placeholder=\"Ex. : une quête "
                + "donnée par le Guide, où le joueur descend dans une mine abandonnée, casse de la "
                + "pierre, puis lui rapporte du fer.\">")
                .append(Http.esc(f == null ? "" : f.intent())).append("</textarea>");
        sb.append("<p class=\"field-help\">Le seul champ vraiment nécessaire. Écrivez en français, "
                + "comme à un collègue : tout le contexte technique est ajouté automatiquement.</p>"
                + "</div>");

        // Le titre est du texte vu par le joueur : éditeur guidé, jamais de balise à taper.
        sb.append("<div class=\"full\">").append(StyleField.render("title", "ai-title",
                "Titre exact souhaité (facultatif)", f == null ? "" : f.title(), false,
                "Laisser vide pour que l'IA propose un titre. Si vous en imposez un, choisissez la "
                + "couleur et les styles ici : aucun code à écrire, et l'IA le reprendra tel quel.",
                false, false)).append("</div>");

        // #223 — le placeholder ne montre plus de namespace, et l'aide dit ce qui sera produit.
        sb.append(text("f-questId", "questId", "Identifiant souhaité",
                f == null ? "" : f.questId(), "mines_oubliees",
                "Vide = l'IA en propose un. Minuscules, chiffres, « _ » et « - ». Le namespace "
                + "« rpgquest: » est ajouté automatiquement : saisir « mines_oubliees » donne "
                + "« rpgquest:mines_oubliees ». L'écriture complète est aussi acceptée, et n'est "
                + "jamais doublée. Un identifiant imposé est ensuite vérifié sur la proposition."));
        sb.append(select("f-category", "category", "Catégorie", f == null ? "" : f.category(),
                "dl-category", "Regroupement éditorial (aventure, combat, artisanat…)."));
        sb.append(select("f-giver", "giver", "PNJ donneur", f == null ? "" : f.giver(), "dl-npc",
                refs != null && refs.npcsKnown() && !refs.npcs().isEmpty()
                        ? "Choisir parmi les PNJ réellement existants."
                        : "Aucun relevé de PNJ disponible : laisser vide évite une référence inventée."));
        sb.append(text("f-difficulty", "difficulty", "Difficulté visée",
                f == null ? "" : f.difficulty(), "facile, moyenne, difficile",
                "Intention éditoriale : sert à calibrer les quantités, ce n'est pas une mécanique."));
        sb.append(text("f-duration", "duration", "Durée visée", f == null ? "" : f.duration(),
                "courte, une soirée…", "Aide l'IA à doser le nombre d'objectifs."));
        // Volontairement une indication, et dit comme telle (#224) : contrairement aux nœuds d'un
        // dialogue, un objectif de plus ou de moins est souvent ce qui rend la quête jouable.
        sb.append(number("f-stepCount", "stepCount", "Nombre d'étapes",
                f == null ? 0 : f.stepCount(),
                "0 = l'IA décide. Maximum 10. C'est une indication, pas une contrainte vérifiée : "
                + "l'IA peut s'en écarter si la quête l'exige."));
        sb.append(text("f-rewardIntent", "rewardIntent", "Récompense souhaitée",
                f == null ? "" : f.rewardIntent(), "un peu d'XP et quelques pièces",
                "En français. Les types réellement disponibles sont joints à la demande."));

        sb.append("<div class=\"field full\"><label for=\"f-constraints\">Contraintes "
                + "supplémentaires</label>");
        sb.append("<textarea id=\"f-constraints\" name=\"constraints\" rows=\"2\" placeholder=\"Ex. : "
                + "pas de combat, rester jouable sans claim.\">")
                .append(Http.esc(f == null ? "" : f.constraints())).append("</textarea></div>");

        sb.append("<div class=\"full\"><label class=\"inline\"><input type=\"checkbox\" "
                + "name=\"repeatable\" value=\"on\"")
                .append(f != null && f.repeatable() ? " checked" : "")
                .append("> Quête répétable</label></div>");

        sb.append(providerField(usable, selectedProvider));
        sb.append("</div>");
        sb.append(submitRow());
        sb.append("</form>");
        sb.append(ContentEditorPages.sharedDatalists(refs, false));
        return sb.append("</section>").toString();
    }

    // ---- Formulaire : dialogue -----------------------------------------------------------------

    /**
     * Le dialogue a sa propre liste de consignes, parce qu'un dialogue ne se décrit pas comme une
     * quête : ce qui compte est le PNJ porteur, le ton, et la quête que la conversation articule.
     */
    private static String dialogueCard(List<AiProvider> usable,
                                       ContentPromptBuilder.DialogueRequest f,
                                       String selectedProvider, RefData refs,
                                       DialogueTargetDecision.Outcome decision, String rawNpc) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("dialogues", "2. Décrire la conversation"));
        sb.append(openForm(ContentPromptBuilder.Kind.DIALOGUE));

        sb.append("<div class=\"form-grid\">");
        sb.append("<div class=\"field full\"><label for=\"f-intent\">Ce que la conversation doit "
                + "raconter ou permettre <span aria-hidden=\"true\">*</span></label>");
        sb.append("<textarea id=\"f-intent\" name=\"intent\" rows=\"4\" placeholder=\"Ex. : le "
                + "Guide accueille le joueur, lui propose la quête des mines, et s'il l'a déjà "
                + "terminée le félicite au lieu de la reproposer.\">")
                .append(Http.esc(f == null ? "" : f.intent())).append("</textarea>");
        sb.append("<p class=\"field-help\">Décrivez aussi les cas de figure : « s'il a déjà… », "
                + "« s'il n'a pas assez de… ». Ce sont eux qui deviennent des conditions, et c'est "
                + "ce qu'on oublie le plus souvent de demander.</p></div>");

        // #225 — on demande le PNJ, pas « l'identifiant du dialogue ». Les deux ne coïncident que
        // par défaut, et prétendre le contraire est ce qui créait un second dialogue concurrent.
        sb.append(select("f-npc", "npc", "PNJ porteur du dialogue", rawNpc, "dl-npc",
                "Le dialogue est rattaché à ce PNJ. Par défaut il porte son identifiant — mais si "
                + "le PNJ a déjà un dialogue sous un autre nom, le panel vous le dira et vous "
                + "demandera quoi faire avant d'appeler l'IA. Laisser vide oblige l'IA à deviner."));
        // Le nom du locuteur est vu par le joueur : éditeur guidé, jamais de balise à taper.
        sb.append("<div class=\"full\">").append(StyleField.render("speaker", "ai-speaker",
                "Nom affiché du locuteur (facultatif)", f == null ? "" : f.speaker(), false,
                "Ce que le joueur lit avant la réplique. Choisissez la couleur et les styles ici ; "
                + "l'IA le reprendra tel quel.", false, false)).append("</div>");
        sb.append(text("f-tone", "tone", "Ton de la conversation", f == null ? "" : f.tone(),
                "bourru, solennel, inquiet…",
                "Intention éditoriale : sert à écrire les répliques, ce n'est pas une mécanique."));
        // #224 — une valeur non nulle est impérative, et c'est le panel qui le vérifie.
        sb.append(number("f-nodeCount", "nodeCount", "Nombre de nœuds",
                f == null ? 0 : f.nodeCount(),
                "0 = l'IA décide librement. Une valeur supérieure à 0 est impérative : après "
                + "génération, le panel recompte les nœuds réellement produits et refuse la "
                + "proposition s'ils ne sont pas exactement ce nombre. Maximum "
                + ContentPromptBuilder.DialogueRequest.MAX_NODES + ".",
                ContentPromptBuilder.DialogueRequest.MAX_NODES));
        sb.append(select("f-quest", "quest", "Quête concernée", f == null ? "" : f.quest(),
                "dl-quest",
                "Quête que la conversation propose ou valide. Elle doit déjà exister : un dialogue "
                        + "ne crée pas de quête."));

        sb.append("<div class=\"field full\"><label for=\"f-constraints\">Contraintes "
                + "supplémentaires</label>");
        sb.append("<textarea id=\"f-constraints\" name=\"constraints\" rows=\"2\" placeholder=\"Ex. : "
                + "ne jamais donner d'objet directement, toujours laisser une porte de sortie.\">")
                .append(Http.esc(f == null ? "" : f.constraints())).append("</textarea></div>");

        sb.append(providerField(usable, selectedProvider));
        sb.append("</div>");
        // Une décision déjà rendue reste attachée au formulaire : relancer ne la redemande pas, et
        // ne retombe pas en silence sur la convention.
        if (decision != null && !decision.needsDecision() && decision.npc() != null
                && decision.npc().dialogueNamedDifferently()) {
            sb.append(keptDecision(decision));
        }
        sb.append(submitRow());
        sb.append("</form>");
        sb.append(ContentEditorPages.sharedDatalists(refs, false));
        return sb.append("</section>").toString();
    }

    // ---- #225 : l'arbitrage PNJ ↔ dialogue -----------------------------------------------------

    /**
     * La question posée quand le PNJ porte déjà un dialogue sous un autre nom. Elle arrive
     * <strong>avant</strong> tout appel à l'IA : refuser ici ne coûte rien, alors que découvrir le
     * doublon après coup coûte un aller-retour payant et laisse un orphelin.
     */
    private static String decisionCard(DialogueTargetDecision.Outcome decision,
                                       List<AiProvider> usable,
                                       ContentPromptBuilder.DialogueRequest f,
                                       String selectedProvider, String rawNpc) {
        NpcView npc = decision.npc();
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("npc", "Ce PNJ a déjà un dialogue — que faut-il faire ?"));
        sb.append(Ui.banner("warning",
                "« " + Http.esc(npc.id()) + " » est déjà lié au dialogue <code>"
                + Http.esc(npc.linkedDialogueId()) + "</code>, qui ne porte pas son identifiant. "
                + "Générer un dialogue nommé comme le PNJ créerait un <strong>second</strong> "
                + "dialogue, sans supprimer ni délier le premier. <strong>Aucun appel à l'IA n'a "
                + "été fait</strong> : choisissez d'abord."));

        sb.append("<dl class=\"npc-dl\">");
        dl(sb, "PNJ", (npc.displayName().isEmpty() ? "" : MiniText.html(npc.displayName()) + " ")
                + "<code class=\"tid\">" + Http.esc(npc.id()) + "</code>");
        dl(sb, "Dialogue actuellement lié", "<code class=\"tid\">"
                + Http.esc(npc.linkedDialogueId()) + "</code> <span class=\"faint\">("
                + npc.dialogueNodes() + " nœud(s), " + npc.dialogueChoices() + " choix)</span>");
        dl(sb, "Citizens", npc.citizensBound()
                ? "#" + (npc.citizensNumericId() == null ? "?" : npc.citizensNumericId())
                : "<span class=\"muted\">aucun</span>");
        dl(sb, "Définition logique", npc.definitionPresent()
                ? "<code class=\"tid\">npcs/" + Http.esc(npc.id()) + ".yml</code>"
                : "<span class=\"badge text-bg-danger\">absente</span>");
        if (!decision.related().isEmpty()) {
            StringBuilder rel = new StringBuilder();
            for (NpcView r : decision.related()) {
                rel.append("<div><code class=\"tid\">").append(Http.esc(r.id())).append("</code> ")
                        .append(r.definitionPresent()
                                ? "<span class=\"badge text-bg-secondary\">définition</span>"
                                : "<span class=\"badge text-bg-danger\">sans définition</span>")
                        .append(" <span class=\"faint\">").append(Http.esc(r.provenance()))
                        .append("</span></div>");
            }
            dl(sb, "Entrées apparentées", rel.toString());
        }
        sb.append("</dl>");

        sb.append(openForm(ContentPromptBuilder.Kind.DIALOGUE));
        sb.append(hiddenDialogueFields(f, rawNpc, selectedProvider, usable));
        sb.append("<div class=\"field full\"><fieldset><legend>Que doit faire l'IA ?</legend>");
        sb.append(radio(DialogueTargetDecision.Choice.EDIT_EXISTING,
                "Modifier le dialogue existant « " + npc.linkedDialogueId() + " »",
                "Recommandé. La proposition portera cet identifiant. Le lien du PNJ ne change pas, "
                + "aucun second dialogue n'est créé, et la page d'import affichera la collision "
                + "avec le fichier actuel avant d'écrire quoi que ce soit."));
        sb.append(radio(DialogueTargetDecision.Choice.NEW_REPLACING_LINK,
                "Créer un nouveau dialogue « rpgquest:" + npc.id() + " » et remplacer le lien",
                "L'ancien dialogue n'est ni supprimé, ni délié par cette page. Il faudra repointer "
                + "la définition du PNJ depuis sa fiche, sinon les joueurs continueront d'entendre "
                + "l'ancien."));
        sb.append("</fieldset><p class=\"field-help\">Il n'existe pas de troisième possibilité : le "
                + "moteur ne rattache un dialogue à un PNJ que par le champ <code>dialogueId</code> "
                + "de sa définition ou par la convention de nom. Un dialogue « supplémentaire mais "
                + "non lié » ne serait joignable par personne, et réapparaîtrait lui-même dans "
                + "<a href=\"/npcs\">PNJ</a> comme une entrée sans définition.</p></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" name=\"_action\" "
                + "value=\"generate\">").append(Icons.icon("gift"))
                .append("Continuer avec ce choix</button>")
                .append("<a class=\"btn secondary\" href=\"/npcs?q=").append(Http.esc(npc.id()))
                .append("\">").append(Icons.icon("open")).append("Ouvrir la fiche du PNJ</a></div>");
        sb.append("</form>");
        return sb.append("</section>").toString();
    }

    private static String radio(DialogueTargetDecision.Choice choice, String label, String help) {
        return "<label class=\"inline\"><input type=\"radio\" name=\"dialogueTarget\" value=\""
                + choice.name() + "\" required> " + Http.esc(label) + "</label>"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p>";
    }

    /** Rappel, dans le formulaire, de la décision déjà prise — avec de quoi en changer. */
    private static String keptDecision(DialogueTargetDecision.Outcome decision) {
        String value = decision.dialogueKey()
                .equals(decision.npc().id().toLowerCase(java.util.Locale.ROOT))
                ? DialogueTargetDecision.Choice.NEW_REPLACING_LINK.name()
                : DialogueTargetDecision.Choice.EDIT_EXISTING.name();
        return "<input type=\"hidden\" name=\"dialogueTarget\" value=\"" + value + "\">"
                + "<p class=\"field-help\">Choix retenu pour « " + Http.esc(decision.npc().id())
                + " » : le dialogue produit s'appellera <code>rpgquest:"
                + Http.esc(decision.dialogueKey()) + "</code>. Pour en changer, videz puis "
                + "resaisissez le PNJ : la question sera reposée.</p>";
    }

    // ---- Formulaire : story --------------------------------------------------------------------

    /**
     * Une story n'invente rien : elle ordonne des quêtes qui existent. Le formulaire le dit, et la
     * liste des quêtes réellement disponibles est juste à côté du champ.
     */
    private static String storyCard(List<AiProvider> usable, ContentPromptBuilder.StoryRequest f,
                                    String selectedProvider, RefData refs) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("stories", "2. Décrire l'enchaînement"));
        sb.append(openForm(ContentPromptBuilder.Kind.STORY));

        boolean noQuests = refs == null || !refs.questsKnown() || refs.quests().isEmpty();
        if (noQuests) {
            sb.append(Ui.banner("warning",
                    "Aucun relevé de quêtes n'est disponible. Une story ne peut citer que des "
                    + "quêtes existantes : sans ce relevé, la proposition sera presque sûrement "
                    + "refusée à l'import. Rafraîchissez les données de l'agent d'abord."));
        }

        sb.append("<div class=\"form-grid\">");
        sb.append("<div class=\"field full\"><label for=\"f-intent\">Le fil de l'enchaînement "
                + "<span aria-hidden=\"true\">*</span></label>");
        sb.append("<textarea id=\"f-intent\" name=\"intent\" rows=\"4\" placeholder=\"Ex. : "
                + "l'arrivée d'un nouveau joueur : il rencontre le village, apprend à miner, puis "
                + "part explorer.\">")
                .append(Http.esc(f == null ? "" : f.intent())).append("</textarea>");
        sb.append("<p class=\"field-help\">Une story est un <strong>ordre de quêtes existantes</strong>. "
                + "Elle ne crée ni objectif ni récompense : si une quête manque, elle sera signalée "
                + "comme dépendance manquante plutôt qu'inventée.</p></div>");

        sb.append("<div class=\"field full\"><label for=\"f-quests\">Quêtes à enchaîner</label>");
        sb.append("<textarea id=\"f-quests\" name=\"quests\" rows=\"3\" placeholder=\"")
                .append("rpgquest:premiers_pas, rpgquest:mines_oubliees\">")
                .append(Http.esc(f == null ? "" : f.quests())).append("</textarea>");
        sb.append("<p class=\"field-help\">Une par ligne ou séparées par des virgules, dans "
                + "l'ordre de progression. Vide = l'IA choisit parmi les quêtes existantes. ");
        if (!noQuests) {
            List<String> all = refs.quests();
            List<String> shown = all.size() > 20 ? all.subList(0, 20) : all;
            sb.append("Disponibles : ").append(Http.esc(String.join(", ", shown)));
            if (all.size() > shown.size()) {
                sb.append(" … (+").append(all.size() - shown.size()).append(')');
            }
        }
        sb.append("</p></div>");

        // #223 — une story n'a pas de namespace : son identifiant EST son nom de fichier.
        sb.append(text("f-storyId", "storyId", "Identifiant souhaité", f == null ? "" : f.storyId(),
                "premiers_pas",
                "Vide = l'IA en propose un. Minuscules, chiffres, « _ » et « - ». Une story n'a pas "
                + "de namespace : l'identifiant saisi est aussi le nom du fichier. Écrire "
                + "« rpgquest:premiers_pas » est accepté, et le préfixe est retiré."));
        // Le titre est vu par le joueur : même composant guidé que partout ailleurs.
        sb.append("<div class=\"full\">").append(StyleField.render("title", "ai-story-title",
                "Titre exact souhaité (facultatif)", f == null ? "" : f.title(), false,
                "Laisser vide pour que l'IA propose un titre. Couleur et styles se choisissent ici, "
                + "sans écrire de code.", false, false)).append("</div>");

        sb.append("<div class=\"field full\"><label for=\"f-constraints\">Contraintes "
                + "supplémentaires</label>");
        sb.append("<textarea id=\"f-constraints\" name=\"constraints\" rows=\"2\" placeholder=\"Ex. : "
                + "rester jouable en solo, pas plus de quatre quêtes.\">")
                .append(Http.esc(f == null ? "" : f.constraints())).append("</textarea></div>");

        sb.append(providerField(usable, selectedProvider));
        sb.append("</div>");
        sb.append(submitRow());
        sb.append("</form>");
        sb.append(ContentEditorPages.sharedDatalists(refs, false));
        return sb.append("</section>").toString();
    }

    // ---- Éléments partagés des trois formulaires -----------------------------------------------

    /** La famille voyage en champ caché : le POST ne dépend donc jamais de l'URL d'où il vient. */
    private static String openForm(ContentPromptBuilder.Kind kind) {
        return "<form method=\"post\" action=\"/ai/studio\" class=\"editor\" novalidate>%CSRF%"
                + "<input type=\"hidden\" name=\"kind\" value=\"" + kind.name() + "\">";
    }

    private static String providerField(List<AiProvider> usable, String selectedProvider) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"field\"><label for=\"f-provider\">Fournisseur</label>");
        sb.append("<select id=\"f-provider\" name=\"provider\">");
        for (AiProvider p : usable) {
            sb.append("<option value=\"").append(Http.esc(p.id())).append("\"")
                    .append(p.id().equals(selectedProvider) ? " selected" : "").append('>')
                    .append(Http.esc(p.label())).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Seuls les fournisseurs activés et pourvus d'une "
                + "clé apparaissent ici.</p></div>");
        return sb.toString();
    }

    private static String submitRow() {
        return "<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" name=\"_action\" "
                + "value=\"generate\">" + Icons.icon("gift")
                + "Demander une proposition</button></div>"
                + "<p class=\"field-help\">L'appel part du serveur du panel, jamais de votre "
                + "navigateur. Il peut prendre une minute.</p>";
    }

    // ---- Résultat ------------------------------------------------------------------------------

    private static String resultCard(AiContentStudio.Generation g, boolean canImport,
                                     ContentPromptBuilder.QuestRequest questForm,
                                     ContentPromptBuilder.DialogueRequest dialogueForm,
                                     ContentPromptBuilder.StoryRequest storyForm,
                                     AiContentStudio.Correction fallback,
                                     DialogueTargetDecision.Outcome decision, String rawNpc) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("check", "3. Proposition de l'IA"));

        if (!g.callOk()) {
            sb.append(Ui.banner("error", "L'appel a échoué : " + Http.esc(g.error())
                    + " <strong>Rien n'a été enregistré.</strong>"));
            // #222 — un échec d'appel pendant une correction ne doit pas faire disparaître la
            // proposition précédente avec le bouton qui permettait de la réparer.
            if (fallback != null && fallback.usable()) {
                sb.append(Ui.banner("info",
                        "La proposition précédente et ses diagnostics sont conservés : vous pouvez "
                        + "relancer la correction sans rien resaisir."));
                sb.append(correctionForm(g.kind(), fallback.providerId(), fallback.previousYaml(),
                        fallback.problems(), questForm, dialogueForm, storyForm, decision, rawNpc,
                        "Relancer la correction"));
            }
            return sb.append("</section>").toString();
        }

        sb.append("<p class=\"muted\">").append(Http.esc(g.kind().label())).append(" &middot; ")
                .append(Http.esc(g.providerLabel()));
        if (g.model() != null) {
            sb.append(" &middot; modèle <code>").append(Http.esc(g.model())).append("</code>");
        }
        if (g.usage() != null) {
            sb.append(" &middot; ").append(Http.esc(g.usage())).append(" jeton(s)");
        }
        sb.append("</p>");

        if (decision != null && decision.hasNote()) {
            sb.append(Ui.banner(decision.note().startsWith("ATTENTION") ? "warning" : "info",
                    Http.esc(decision.note())));
        }
        if (g.extractionNote() != null) {
            sb.append(Ui.banner("warning", Http.esc(g.extractionNote())));
        }

        // #223 / #224 — ce que le formulaire imposait et que la proposition ne tient pas. Affiché
        // avant les diagnostics de validation : c'est le refus le plus facile à comprendre.
        for (String unmet : g.unmet()) {
            sb.append(Ui.banner("error", "Demande non respectée — " + Http.esc(unmet)));
        }

        ContentPackImport.Analysis a = g.analysis();
        if (a == null) {
            sb.append(Ui.banner("error", "Aucun document exploitable dans la réponse."));
            return sb.append(rawBlock(g)).append("</section>").toString();
        }

        for (Diagnostic d : a.envelope()) {
            sb.append(Ui.banner(d.level() == Diagnostic.Level.ERROR ? "error"
                    : d.level() == Diagnostic.Level.WARNING ? "warning" : "info",
                    Http.esc(d.message())));
        }

        for (ContentPackImport.Element e : a.elements()) {
            sb.append(element(e));
        }

        if (g.acceptable()) {
            sb.append(Ui.banner("success",
                    "La proposition passe les validateurs réels et respecte ce que vous aviez "
                    + "imposé. <strong>Rien n'est encore enregistré</strong> : l'étape suivante "
                    + "ouvre la page d'import, où vous confirmerez — et trancherez d'éventuelles "
                    + "collisions."));
            sb.append("<form method=\"post\" action=\"/content/import\" class=\"actform\">%CSRF%");
            sb.append("<input type=\"hidden\" name=\"pack\" value=\"").append(Http.esc(g.yaml()))
                    .append("\">");
            sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\"")
                    .append(canImport ? "" : " disabled").append('>').append(Icons.icon("save"))
                    .append("Relire et enregistrer (page d'import)</button></div>");
            if (!canImport) {
                sb.append("<p class=\"field-help\">Votre rôle ne permet pas d'importer du contenu : "
                        + "la proposition est consultable, mais pas enregistrable.</p>");
            }
            sb.append("</form>");
        } else {
            sb.append(Ui.banner("warning",
                    g.unmet().isEmpty()
                            ? "La proposition ne passe pas les validateurs : elle ne peut pas être "
                              + "enregistrée en l'état. Vous pouvez demander une correction à l'IA, "
                              + "qui recevra sa propre sortie, les erreurs exactes et vos consignes "
                              + "d'origine."
                            : "La proposition ne correspond pas à votre demande : elle ne peut pas "
                              + "être enregistrée en l'état. Vous pouvez demander une correction à "
                              + "l'IA, qui recevra sa propre sortie, l'écart exact et vos consignes "
                              + "d'origine — celles-ci restent imposées."));
            if (g.correctable()) {
                sb.append(correctionForm(g.kind(), g.providerId(), g.yaml(), g.problems(),
                        questForm, dialogueForm, storyForm, decision, rawNpc,
                        "Demander une correction à l'IA"));
            }
        }

        sb.append(rawBlock(g));
        return sb.append("</section>").toString();
    }

    /**
     * Le formulaire de correction (#222). Il transporte <strong>tout</strong> ce qu'il faut pour
     * rejouer la demande : la sortie refusée, les diagnostics, le fournisseur, la famille, et chaque
     * champ saisi — donc la correction ne perd ni l'identifiant imposé, ni le nombre de nœuds, et
     * le formulaire se rouvre rempli.
     *
     * <p>Les diagnostics tiennent dans <strong>un seul</strong> champ, une ligne par problème : le
     * lecteur de formulaire du panel ne garde qu'une valeur par nom, et une série de champs
     * homonymes n'en transportait donc qu'un seul — c'était la cause directe du ticket.</p>
     */
    private static String correctionForm(ContentPromptBuilder.Kind kind, String providerId,
                                         String previousYaml, List<String> problems,
                                         ContentPromptBuilder.QuestRequest questForm,
                                         ContentPromptBuilder.DialogueRequest dialogueForm,
                                         ContentPromptBuilder.StoryRequest storyForm,
                                         DialogueTargetDecision.Outcome decision, String rawNpc,
                                         String label) {
        StringBuilder sb = new StringBuilder(
                "<form method=\"post\" action=\"/ai/studio\" class=\"actform\">%CSRF%");
        sb.append(hidden("kind", kind.name()));
        sb.append(hidden("provider", providerId));
        sb.append(hidden("previousYaml", previousYaml));
        sb.append(hidden("problems", String.join("\n", problems)));
        sb.append(switch (kind) {
            case QUEST -> hiddenQuestFields(questForm);
            case DIALOGUE -> hiddenDialogueFields(dialogueForm, rawNpc, providerId, List.of())
                    + (decision == null || decision.needsDecision() ? ""
                            : hidden("dialogueTarget", decisionValue(decision)));
            case STORY -> hiddenStoryFields(storyForm);
        });
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" name=\"_action\" "
                + "value=\"correct\">").append(Icons.icon("refresh")).append(Http.esc(label))
                .append("</button></div>");
        sb.append("<p class=\"field-help\">La correction repart du même fournisseur, du même "
                + "modèle, et de vos consignes d'origine — elles restent imposées. Rien n'est "
                + "enregistré à cette étape.</p>");
        return sb.append("</form>").toString();
    }

    private static String decisionValue(DialogueTargetDecision.Outcome decision) {
        if (decision.npc() == null) {
            return "";
        }
        return decision.dialogueKey().equals(decision.npc().id().toLowerCase(java.util.Locale.ROOT))
                ? DialogueTargetDecision.Choice.NEW_REPLACING_LINK.name()
                : DialogueTargetDecision.Choice.EDIT_EXISTING.name();
    }

    private static String hiddenQuestFields(ContentPromptBuilder.QuestRequest f) {
        if (f == null) {
            return "";
        }
        return hidden("intent", f.intent()) + hidden("title", f.title())
                + hidden("questId", f.questId()) + hidden("category", f.category())
                + hidden("giver", f.giver()) + hidden("difficulty", f.difficulty())
                + hidden("duration", f.duration())
                + hidden("stepCount", String.valueOf(f.stepCount()))
                + (f.repeatable() ? hidden("repeatable", "on") : "")
                + hidden("rewardIntent", f.rewardIntent()) + hidden("constraints", f.constraints());
    }

    /**
     * Les champs d'une demande de dialogue en caché. {@code rawNpc} est repris tel que
     * l'administrateur l'a saisi : c'est lui qui permet de rejouer l'arbitrage #225 à l'identique,
     * là où l'identifiant de dialogue résolu ne dirait plus de quel PNJ il s'agissait.
     */
    private static String hiddenDialogueFields(ContentPromptBuilder.DialogueRequest f, String rawNpc,
                                               String providerId, List<AiProvider> usable) {
        StringBuilder sb = new StringBuilder();
        sb.append(hidden("npc", rawNpc == null ? "" : rawNpc));
        if (f != null) {
            sb.append(hidden("intent", f.intent())).append(hidden("speaker", f.speaker()))
                    .append(hidden("tone", f.tone()))
                    .append(hidden("nodeCount", String.valueOf(f.nodeCount())))
                    .append(hidden("quest", f.quest()))
                    .append(hidden("constraints", f.constraints()));
        }
        // Dans la carte d'arbitrage, le fournisseur n'a pas encore de sélecteur visible : on le
        // reporte en caché pour que « Continuer avec ce choix » n'en change pas.
        if (!usable.isEmpty()) {
            sb.append(hidden("provider", providerId == null ? "" : providerId));
        }
        return sb.toString();
    }

    private static String hiddenStoryFields(ContentPromptBuilder.StoryRequest f) {
        if (f == null) {
            return "";
        }
        return hidden("intent", f.intent()) + hidden("storyId", f.storyId())
                + hidden("title", f.title()) + hidden("quests", f.quests())
                + hidden("constraints", f.constraints());
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\""
                + Http.esc(value == null ? "" : value) + "\">";
    }

    private static void dl(StringBuilder sb, String key, String valueHtml) {
        sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(key)).append("</dt><dd>")
                .append(valueHtml).append("</dd></div>");
    }

    private static String element(ContentPackImport.Element e) {
        StringBuilder sb = new StringBuilder("<div class=\"npc-item\">");
        sb.append("<p class=\"meta-line\"><span class=\"badge ").append(switch (e.status()) {
            case NEW -> "text-bg-success";
            case MODIFIED -> "text-bg-info";
            case UNCHANGED -> "text-bg-secondary";
            case CONFLICT -> "text-bg-warning";
            case SKIPPED -> "text-bg-light text-dark";
            case INVALID -> "text-bg-danger";
        }).append("\">").append(switch (e.status()) {
            case NEW -> "nouveau";
            case MODIFIED -> "modifié";
            case UNCHANGED -> "déjà identique";
            case CONFLICT -> "existe déjà";
            case SKIPPED -> "ignoré";
            case INVALID -> "inexploitable";
        }).append("</span> <code class=\"tid\">").append(Http.esc(e.key())).append("</code> ")
                .append(Ui.id(e.id())).append("</p>");
        if (e.reason() != null && !e.reason().isBlank()) {
            sb.append("<p class=\"muted\">").append(Http.esc(e.reason())).append("</p>");
        }
        for (Diagnostic d : e.diagnostics()) {
            sb.append("<p class=\"muted\"><span class=\"badge ").append(switch (d.level()) {
                case ERROR -> "text-bg-danger";
                case WARNING -> "text-bg-warning";
                case INFO -> "text-bg-secondary";
            }).append("\">").append(d.level().name()).append("</span> ")
                    .append(Http.esc(d.field())).append(" — ").append(Http.esc(d.message()))
                    .append("</p>");
        }
        if (e.yaml() != null) {
            sb.append("<details><summary class=\"muted\">Voir le contenu proposé</summary>")
                    .append("<div class=\"codeblock\"><pre>").append(Http.esc(e.yaml()))
                    .append("</pre></div></details>");
        }
        return sb.append("</div>").toString();
    }

    /** La réponse brute, repliée : utile quand l'IA a mal répondu, inutile le reste du temps. */
    private static String rawBlock(AiContentStudio.Generation g) {
        if (g.rawResponse() == null || g.rawResponse().isBlank()) {
            return "";
        }
        return "<details><summary class=\"muted\">Réponse brute du modèle (mode avancé)</summary>"
                + "<div class=\"codeblock\"><pre>" + Http.esc(g.rawResponse()) + "</pre></div></details>";
    }

    // ---- Champs --------------------------------------------------------------------------------

    private static String text(String id, String name, String label, String value,
                               String placeholder, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\""
                + Http.esc(value) + "\" placeholder=\"" + Http.esc(placeholder) + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String select(String id, String name, String label, String value,
                                 String datalist, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\""
                + Http.esc(value == null ? "" : value) + "\" list=\"" + datalist + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String number(String id, String name, String label, int value, String help) {
        return number(id, name, label, value, help, 10);
    }

    private static String number(String id, String name, String label, int value, String help,
                                 int max) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"number\" min=\"0\" max=\"" + max
                + "\" name=\"" + name + "\" value=\"" + value + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String aboutCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Ce que fait l'atelier, et ce qu'il ne fait pas")
                + "<ul class=\"muted\">"
                + "<li><strong>Vous ne décrivez que votre intention.</strong> Le contrat de contenu, "
                + "le schéma officiel, les types réellement supportés — objectifs, récompenses, "
                + "actions et conditions de dialogue — et les références réellement existantes sont "
                + "joints automatiquement.</li>"
                + "<li><strong>Un seul élément par demande.</strong> Une quête, un dialogue ou une "
                + "story : un échec reste petit, et une correction reste ciblée.</li>"
                + "<li><strong>L'IA ne remplace pas les validateurs.</strong> Sa proposition passe "
                + "par les mêmes validateurs que l'éditeur guidé ; une erreur de sa part est "
                + "attrapée, affichée, et bloque l'enregistrement.</li>"
                + "<li><strong>Ce que vous imposez est vérifié.</strong> Un identifiant imposé et un "
                + "nombre de nœuds non nul ne sont pas des suggestions : le panel les recontrôle sur "
                + "la proposition, et la refuse si elle ne les respecte pas — même si elle est "
                + "valide par ailleurs.</li>"
                + "<li><strong>Un PNJ qui a déjà un dialogue n'en reçoit jamais un second en "
                + "silence.</strong> Le panel affiche le dialogue réellement lié et demande quoi "
                + "faire avant d'appeler l'IA.</li>"
                + "<li><strong>Cette page n'écrit rien.</strong> Elle s'arrête à l'aperçu. "
                + "L'enregistrement passe par la page d'import, avec sa confirmation explicite et "
                + "son arbitrage des collisions.</li>"
                + "<li><strong>Rien n'est publié sur le serveur Minecraft.</strong> Même après "
                + "enregistrement, le contenu est dans la source ; le déploiement reste distinct.</li>"
                + "<li><strong>L'appel part du serveur</strong>, jamais de votre navigateur, et la "
                + "clé API n'y transite jamais.</li>"
                + "<li>Un échec, un délai dépassé ou une réponse illisible <strong>ne modifient "
                + "rien</strong>.</li>"
                + "</ul></section>";
    }
}
