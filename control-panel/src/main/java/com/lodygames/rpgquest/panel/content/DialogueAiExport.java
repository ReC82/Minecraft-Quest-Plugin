package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Issue #465 — « Modifier ce dialogue avec une IA » : un content pack <strong>ciblé</strong> (#108/#110)
 * contenant le dialogue EXISTANT de la source, ses dépendances, et un prompt prêt à copier.
 *
 * <p>Ce n'est pas une seconde filière d'import/export : le pack produit est un
 * {@code lodyquests-content-pack} ordinaire, réimporté par {@code /content/import} (#109). Deux
 * métadonnées le rattachent au dialogue d'origine :</p>
 * <ul>
 *   <li>{@value #BASE_ELEMENT} — {@code dialogues/<slug>} : le pack réimporté doit modifier CE
 *       dialogue (même identifiant), pas en créer un parallèle ;</li>
 *   <li>{@value #BASE_SHA} — l'empreinte de la source au moment de l'export : si le fichier a changé
 *       depuis, le réimport est refusé au lieu d'écraser ce qui a été fait entre-temps.</li>
 * </ul>
 *
 * <p>Le dialogue est exporté dans sa forme canonique ({@link DialogueYaml#write}) : c'est la forme
 * que le réimport ré-émettra, si bien qu'un pack rendu tel quel par l'IA ne produit
 * <em>aucun</em> changement de sens. Un dialogue que l'éditeur ne sait pas relire sans perte n'est
 * pas exporté : la réimportation le réduirait.</p>
 */
public final class DialogueAiExport {

    public static final String BASE_ELEMENT = "baseElement";
    public static final String BASE_SHA = "baseSha";
    public static final String PURPOSE = "ai-edit";

    /** Une liste de quêtes (catalogue #369) : nom affiché, état, quêtes proposables. */
    public record CatalogInfo(String id, String name, boolean enabled, List<String> quests) {
    }

    /**
     * @param catalogs         listes portées par les PNJ récurrents qui disent ce dialogue
     * @param otherCatalogs    les autres listes relevées sur le serveur (pour qu'une IA puisse en citer une)
     * @param questTitle       titre d'une quête à partir de son identifiant
     * @param models           noms des PNJ récurrents qui disent ce dialogue
     */
    public record Context(List<CatalogInfo> catalogs, List<CatalogInfo> otherCatalogs,
                          Function<String, String> questTitle, List<String> models) {
    }

    /** @param problem vide si l'export est possible, sinon pourquoi il ne l'est pas */
    public record Result(String problem, String pack, String prompt, List<String> quests) {
        public boolean ok() {
            return problem.isEmpty();
        }
    }

    private DialogueAiExport() {
    }

    public static Result export(ContentWorkspace.ContentFile file, Context ctx) {
        DialogueYaml.ReadResult rr = DialogueYaml.read(file.text());
        if (rr.draft() == null || !rr.ok()) {
            return new Result("L'éditeur ne sait pas relire ce dialogue sans rien perdre ("
                    + String.join(" ; ", rr.problems()) + ") : l'exporter pour une IA puis le réimporter "
                    + "le réduirait. Corriger d'abord son fichier.", "", "", List.of());
        }
        DialogueDraft d = rr.draft();
        if (d.id.isBlank()) {
            d.id = file.slug();
        }
        List<String> quests = questsCited(d, ctx);
        String pack = pack(file, d, quests);
        return new Result("", pack, prompt(file.slug(), d, quests, ctx, pack), quests);
    }

    /** Le pack : enveloppe #110, métadonnées de rattachement, dépendances, le dialogue canonique. */
    static String pack(ContentWorkspace.ContentFile file, DialogueDraft d, List<String> quests) {
        StringBuilder sb = new StringBuilder();
        sb.append("format: ").append(ContentPackSchema.FORMAT).append('\n');
        sb.append("schemaVersion: ").append(ContentPackSchema.SCHEMA_VERSION).append('\n');
        sb.append("metadata:\n");
        sb.append("  title: ").append(DialogueYaml.quote("Modification IA du dialogue " + file.slug())).append('\n');
        sb.append("  purpose: ").append(PURPOSE).append('\n');
        sb.append("  ").append(BASE_ELEMENT).append(": dialogues/").append(file.slug()).append('\n');
        sb.append("  ").append(BASE_SHA).append(": ").append(DialogueYaml.quote(file.sha256())).append('\n');
        if (!quests.isEmpty()) {
            sb.append("dependencies:\n  quests:\n");
            quests.forEach(q -> sb.append("    - ").append(q).append('\n'));
        }
        sb.append("content:\n  dialogues:\n");
        boolean first = true;
        for (String line : DialogueYaml.write(d).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            sb.append(first ? "    - " : "      ").append(line).append('\n');
            first = false;
        }
        return sb.toString();
    }

    /** Quêtes citées par le dialogue, puis celles des listes de ses PNJ récurrents, sans doublon. */
    private static List<String> questsCited(DialogueDraft d, Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (DialogueDraft.Node n : d.nodes) {
            for (DialogueDraft.Choice c : n.choices) {
                c.conditions.forEach(e -> addQuest(out, e));
                c.actions.forEach(e -> addQuest(out, e));
            }
        }
        d.entries.forEach(entry -> entry.conditions.forEach(e -> addQuest(out, e)));
        for (CatalogInfo c : ctx.catalogs()) {
            c.quests().forEach(q -> out.add(QuestYaml.nsId(q)));
        }
        return new ArrayList<>(out);
    }

    private static void addQuest(Set<String> out, Map<String, String> entry) {
        String q = entry.getOrDefault("quest", "").trim();
        if (!q.isEmpty()) {
            out.add(QuestYaml.nsId(q));
        }
    }

    // ---- Prompt -------------------------------------------------------------------------------

    static String prompt(String slug, DialogueDraft d, List<String> quests, Context ctx, String pack) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu modifies un dialogue de PNJ EXISTANT du serveur Minecraft LodyQuests (plugin RPGQuest).\n\n");
        sb.append("## Ce que je veux\n");
        sb.append("<<< ÉCRIS ICI LA MODIFICATION VOULUE. Exemple : « Adapter ce dialogue à la liste de quêtes ");
        sb.append(ctx.catalogs().isEmpty() ? "…" : "\"" + ctx.catalogs().get(0).name() + "\"");
        sb.append(" avec OFFER_CATALOG_QUEST : mettre à jour toutes les branches, conditions et textes qui ");
        sb.append("parlent d'une quête précise, garder refus / plus tard / suivi. » >>>\n\n");

        sb.append("## Règles impératives\n");
        sb.append("1. Réponds UNIQUEMENT par le pack YAML complet ci-dessous, modifié, dans un seul bloc ```yaml. ");
        sb.append("Pas d'explication hors du bloc.\n");
        sb.append("2. Garde EXACTEMENT : `format`, `schemaVersion`, tout le bloc `metadata` (notamment `")
                .append(BASE_ELEMENT).append("` et `").append(BASE_SHA).append("`) et l'`id` du dialogue (`")
                .append(DialogueYaml.nsId(d.id)).append("`). Un autre id créerait un dialogue parallèle : il ")
                .append("serait refusé.\n");
        sb.append("3. N'ajoute ni quête, ni PNJ, ni autre dialogue au pack. Ne cite que les quêtes et listes ")
                .append("du contexte ci-dessous : une référence inconnue est refusée au réimport.\n");
        sb.append("4. N'utilise que les conditions et effets listés plus bas, avec leurs paramètres exacts. ")
                .append("N'invente aucune capacité.\n");
        sb.append("5. Chaque `next:` doit viser une réplique existante ; `start:` aussi. Identifiants de ")
                .append("réplique : minuscules, chiffres, `_`, `-`. Chaque réplique a `speaker` et `text` ")
                .append("(").append(DialogueValidator.MAX_TEXT).append(" caractères max).\n");
        sb.append("6. Aucune quête ne démarre sans acceptation : un START_QUEST n'est porté que par une réponse ")
                .append("dont le texte commence par « Commencer », et jamais deux fois pour la même quête. ")
                .append("Garder les réponses « Plus tard » / « Refuser » et le suivi d'une tâche en cours.\n");
        sb.append("7. OFFER_CATALOG_QUEST affiche LUI-MÊME la quête tirée (titre, but, délai) et les boutons ")
                .append("« Commencer (j'accepte) », « Plus tard », « Refuser » ; il rappelle une tâche en cours et ")
                .append("dit « Rien pour le moment » quand aucune quête n'est possible (0, 1 ou plusieurs quêtes ")
                .append("dans la liste). Ne pas recopier ces écrans dans le dialogue, et ne pas laisser de ")
                .append("texte qui nomme une quête précise si la quête est tirée au hasard.\n");
        sb.append("8. Textes : MiniMessage (<white>, <gray>, <gold>, <green>, <newline>…), en français, ton du ")
                .append("PNJ conservé. Les commentaires « # » ne sont pas conservés : n'en ajoute pas.\n\n");

        sb.append("## Contexte réel du serveur (lecture seule)\n");
        if (!ctx.models().isEmpty()) {
            sb.append("PNJ récurrent(s) qui disent ce dialogue : ").append(String.join(", ", ctx.models())).append('\n');
        }
        appendCatalogs(sb, "Listes de quêtes de ces PNJ (paramètre `catalog` vide = ces listes)", ctx.catalogs(), ctx);
        appendCatalogs(sb, "Autres listes de quêtes du serveur", ctx.otherCatalogs(), ctx);
        if (!quests.isEmpty()) {
            sb.append("Quêtes citées par ce dialogue ou ses listes :\n");
            for (String q : quests) {
                sb.append("- ").append(q).append(" — ").append(ctx.questTitle().apply(q)).append('\n');
            }
        }
        sb.append('\n');

        sb.append("## Conditions possibles sur une réponse (ajouter `negate: true` pour inverser)\n");
        appendDescriptors(sb, Descriptors.DIALOGUE_CONDITIONS);
        sb.append("\n## Effets possibles d'une réponse (`actions:`)\n");
        appendDescriptors(sb, Descriptors.DIALOGUE_ACTIONS);
        sb.append("\nÉtats de quête : NOT_STARTED, ACTIVE, READY_TO_TURN_IN, COMPLETED, FAILED, ABANDONED.\n");
        sb.append("Entrées (`entries:`, facultatif) : réplique d'ouverture choisie selon des conditions, ")
                .append("évaluées dans l'ordre avant `start`.\n\n");

        sb.append("## Le pack à modifier (dialogue « ").append(slug).append(" » tel qu'enregistré)\n");
        sb.append("```yaml\n").append(pack).append("```\n");
        return sb.toString();
    }

    private static void appendCatalogs(StringBuilder sb, String title, List<CatalogInfo> catalogs, Context ctx) {
        if (catalogs.isEmpty()) {
            return;
        }
        sb.append(title).append(" :\n");
        for (CatalogInfo c : catalogs) {
            sb.append("- `").append(c.id()).append("` « ").append(c.name()).append(" »")
                    .append(c.enabled() ? "" : " (désactivée)").append(" : ");
            if (c.quests().isEmpty()) {
                sb.append("aucune quête proposable");
            } else {
                List<String> named = new ArrayList<>();
                c.quests().forEach(q -> named.add(QuestYaml.nsId(q) + " (" + ctx.questTitle().apply(q) + ")"));
                sb.append(String.join(", ", named));
            }
            sb.append('\n');
        }
    }

    private static void appendDescriptors(StringBuilder sb, List<Descriptors.Descriptor> list) {
        for (Descriptors.Descriptor d : list) {
            sb.append("- ").append(d.kind());
            if (!d.fields().isEmpty()) {
                List<String> fields = new ArrayList<>();
                for (Descriptors.Field f : d.fields()) {
                    fields.add(f.name() + (f.required() ? "" : " (facultatif)"));
                }
                sb.append(" [").append(String.join(", ", fields)).append(']');
            }
            sb.append(" : ").append(d.hint()).append('\n');
        }
    }
}
