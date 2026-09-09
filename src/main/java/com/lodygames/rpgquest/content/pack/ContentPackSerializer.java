package com.lodygames.rpgquest.content.pack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sérialise un {@link ContentPack} en YAML <strong>canonique et déterministe</strong> (issue #108) :
 * ordre de clés fixe, familles triées par id, textes toujours entre guillemets, champs nuls/vides
 * omis, indentation de 2 espaces, retours ligne {@code \n}, encodage UTF-8. Deux appels sur un même
 * pack (même {@code exportedAt}) produisent un octet-pour-octet identique — quel que soit le type de
 * {@code Map}/{@code Set} interne.
 *
 * <p>Écriture à la main (même approche que {@code DialogueDefinitionWriter}) plutôt qu'un
 * {@code Yaml.dump} : le rendu ne dépend d'aucune configuration de bibliothèque et reste stable
 * dans le temps.</p>
 */
public final class ContentPackSerializer {

    private ContentPackSerializer() {
    }

    public static String toYaml(ContentPack pack) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# ").append(pack.format()).append(" — export de contenu déclaratif LodyQuests (issue #108).\n");
        sb.append("# Format public versionné. Aucune donnée joueur / runtime / secret. Voir docs/CONTENT_PACK.md.\n");
        sb.append("format: ").append(pack.format()).append('\n');
        sb.append("schemaVersion: ").append(pack.schemaVersion()).append('\n');

        ContentPack.Manifest m = pack.metadata();
        sb.append("metadata:\n");
        kv(sb, 1, "exportedAt", quote(m.exportedAt()));
        kv(sb, 1, "pluginVersion", quote(m.pluginVersion()));
        kv(sb, 1, "generator", quote(m.generator()));
        sb.append("  families: ").append(inlineList(m.families())).append('\n');
        sb.append("  counts:\n");
        for (String key : new TreeSet<>(m.counts().keySet())) {
            kv(sb, 2, key, String.valueOf(m.counts().get(key)));
        }

        sb.append("content:\n");
        writeQuests(sb, sorted(pack.quests(), QuestPackEntry::id));
        writeStories(sb, sorted(pack.stories(), StoryPackEntry::id));
        writeDialogues(sb, sorted(pack.dialogues(), DialoguePackEntry::id));
        writeNpcs(sb, sorted(pack.npcs(), NpcPackEntry::id));
        return sb.toString();
    }

    // ---- Quests --------------------------------------------------------------------------------

    private static void writeQuests(StringBuilder sb, List<QuestPackEntry> quests) {
        if (quests.isEmpty()) {
            sb.append("  quests: []\n");
            return;
        }
        sb.append("  quests:\n");
        for (QuestPackEntry q : quests) {
            sb.append("    - id: ").append(id(q.id())).append('\n');
            text(sb, 3, "title", q.title());
            text(sb, 3, "description", q.description());
            kv(sb, 3, "category", plain(q.category()));
            kv(sb, 3, "icon", plain(q.icon()));
            kv(sb, 3, "repeatable", String.valueOf(q.repeatable()));
            kv(sb, 3, "secret", String.valueOf(q.secret()));
            if (q.giver() != null) {
                kv(sb, 3, "giver", id(q.giver()));
            }
            if (!q.prerequisites().isEmpty()) {
                sb.append("      prerequisites: ").append(inlineIds(q.prerequisites())).append('\n');
            }
            sb.append("      steps:\n");
            for (QuestPackEntry.Step step : q.steps()) {
                sb.append("        - id: ").append(id(step.id())).append('\n');
                sb.append("          objectives:\n");
                for (QuestPackEntry.Objective o : step.objectives()) {
                    writeObjective(sb, o);
                }
            }
            if (!q.rewards().isEmpty()) {
                sb.append("      rewards:\n");
                for (QuestPackEntry.Reward r : q.rewards()) {
                    writeReward(sb, r);
                }
            }
            if (!q.variables().isEmpty()) {
                sb.append("      variables:\n");
                for (String key : new TreeSet<>(q.variables().keySet())) {
                    kv(sb, 4, key, quote(q.variables().get(key)));
                }
            }
        }
    }

    private static void writeObjective(StringBuilder sb, QuestPackEntry.Objective o) {
        sb.append("            - type: ").append(plain(o.type())).append('\n');
        opt(sb, 7, "material", o.material() == null ? null : plain(o.material()));
        opt(sb, 7, "entity", o.entity() == null ? null : plain(o.entity()));
        opt(sb, 7, "npc", o.npc() == null ? null : id(o.npc()));
        opt(sb, 7, "world", o.world() == null ? null : plain(o.world()));
        opt(sb, 7, "amount", o.amount() == null ? null : String.valueOf(o.amount()));
        opt(sb, 7, "x", num(o.x()));
        opt(sb, 7, "y", num(o.y()));
        opt(sb, 7, "z", num(o.z()));
        opt(sb, 7, "radius", num(o.radius()));
    }

    private static void writeReward(StringBuilder sb, QuestPackEntry.Reward r) {
        sb.append("        - type: ").append(plain(r.type())).append('\n');
        opt(sb, 5, "amount", r.amount() == null ? null : String.valueOf(r.amount()));
        opt(sb, 5, "material", r.material() == null ? null : plain(r.material()));
        opt(sb, 5, "key", r.key() == null ? null : quote(r.key()));
        opt(sb, 5, "value", r.value() == null ? null : quote(r.value()));
        opt(sb, 5, "command", r.command() == null ? null : quote(r.command()));
    }

    // ---- Stories ------------------------------------------------------------------------------

    private static void writeStories(StringBuilder sb, List<StoryPackEntry> stories) {
        if (stories.isEmpty()) {
            sb.append("  stories: []\n");
            return;
        }
        sb.append("  stories:\n");
        for (StoryPackEntry s : stories) {
            sb.append("    - id: ").append(id(s.id())).append('\n');
            text(sb, 3, "name", s.name());
            kv(sb, 3, "secret", String.valueOf(s.secret()));
            sb.append("      questIds: ").append(inlineIds(s.questIds())).append('\n');
        }
    }

    // ---- Dialogues ---------------------------------------------------------------------------

    private static void writeDialogues(StringBuilder sb, List<DialoguePackEntry> dialogues) {
        if (dialogues.isEmpty()) {
            sb.append("  dialogues: []\n");
            return;
        }
        sb.append("  dialogues:\n");
        for (DialoguePackEntry d : dialogues) {
            sb.append("    - id: ").append(id(d.id())).append('\n');
            kv(sb, 3, "start", id(d.start()));
            sb.append("      nodes:\n");
            for (DialoguePackEntry.Node node : orderedNodes(d)) {
                sb.append("        ").append(id(node.id())).append(":\n");
                kv(sb, 5, "speaker", quote(node.speaker()));
                text(sb, 5, "text", node.text());
                sb.append("          choices:\n");
                for (DialoguePackEntry.Choice choice : node.choices()) {
                    writeChoice(sb, choice);
                }
            }
        }
    }

    /** Nœud de départ d'abord, puis les autres triés par id (déterministe, comme DialogueDefinitionWriter). */
    private static List<DialoguePackEntry.Node> orderedNodes(DialoguePackEntry d) {
        List<DialoguePackEntry.Node> out = new ArrayList<>();
        d.nodes().stream().filter(n -> n.id().equals(d.start())).forEach(out::add);
        d.nodes().stream().filter(n -> !n.id().equals(d.start()))
                .sorted(Comparator.comparing(DialoguePackEntry.Node::id)).forEach(out::add);
        return out;
    }

    private static void writeChoice(StringBuilder sb, DialoguePackEntry.Choice c) {
        if (c.text().isPlain()) {
            sb.append("            - text: ").append(quote(c.text().base())).append('\n');
        } else {
            sb.append("            - text:\n");
            writeLocaleTable(sb, 7, c.text());
        }
        if (!c.conditions().isEmpty()) {
            sb.append("              conditions:\n");
            for (DialoguePackEntry.Condition cond : c.conditions()) {
                writeCondition(sb, cond);
            }
        }
        if (!c.actions().isEmpty()) {
            sb.append("              actions:\n");
            for (DialoguePackEntry.Action a : c.actions()) {
                writeAction(sb, a);
            }
        }
        if (c.next() != null && !c.next().isBlank()) {
            kv(sb, 7, "next", id(c.next()));
        }
    }

    private static void writeAction(StringBuilder sb, DialoguePackEntry.Action a) {
        sb.append("                - type: ").append(plain(a.type())).append('\n');
        opt(sb, 9, "quest", a.quest() == null ? null : id(a.quest()));
        opt(sb, 9, "dialogue", a.dialogue() == null ? null : id(a.dialogue()));
        opt(sb, 9, "merchant", a.merchant() == null ? null : id(a.merchant()));
        opt(sb, 9, "material", a.material() == null ? null : plain(a.material()));
        opt(sb, 9, "amount", a.amount() == null ? null : String.valueOf(a.amount()));
        opt(sb, 9, "key", a.key() == null ? null : quote(a.key()));
        opt(sb, 9, "value", a.value() == null ? null : quote(a.value()));
        opt(sb, 9, "command", a.command() == null ? null : quote(a.command()));
    }

    private static void writeCondition(StringBuilder sb, DialoguePackEntry.Condition c) {
        sb.append("                - type: ").append(plain(c.type())).append('\n');
        opt(sb, 9, "quest", c.quest() == null ? null : id(c.quest()));
        opt(sb, 9, "state", c.state() == null ? null : plain(c.state()));
        opt(sb, 9, "material", c.material() == null ? null : plain(c.material()));
        opt(sb, 9, "amount", c.amount() == null ? null : String.valueOf(c.amount()));
        opt(sb, 9, "permission", c.permission() == null ? null : quote(c.permission()));
        opt(sb, 9, "key", c.key() == null ? null : quote(c.key()));
        opt(sb, 9, "value", c.value() == null ? null : quote(c.value()));
        opt(sb, 9, "item", c.item() == null ? null : id(c.item()));
        if (c.negate()) {
            kv(sb, 9, "negate", "true");
        }
    }

    // ---- NPCs --------------------------------------------------------------------------------

    private static void writeNpcs(StringBuilder sb, List<NpcPackEntry> npcs) {
        if (npcs.isEmpty()) {
            sb.append("  npcs: []\n");
            return;
        }
        sb.append("  npcs:\n");
        for (NpcPackEntry n : npcs) {
            sb.append("    - id: ").append(id(n.id())).append('\n');
            kv(sb, 3, "displayName", quote(n.displayName()));
            opt(sb, 3, "description", n.description() == null ? null : quote(n.description()));
            opt(sb, 3, "dialogue", n.dialogue() == null ? null : id(n.dialogue()));
            opt(sb, 3, "role", n.role() == null ? null : plain(n.role()));
            kv(sb, 3, "enabled", String.valueOf(n.enabled()));
        }
    }

    // ---- Primitives ------------------------------------------------------------------------

    private static <T> List<T> sorted(List<T> list, java.util.function.Function<T, String> key) {
        List<T> out = new ArrayList<>(list);
        out.sort(Comparator.comparing(key));
        return out;
    }

    private static void kv(StringBuilder sb, int indent, String key, String value) {
        sb.append("  ".repeat(indent)).append(key).append(": ").append(value).append('\n');
    }

    private static void opt(StringBuilder sb, int indent, String key, String value) {
        if (value != null) {
            kv(sb, indent, key, value);
        }
    }

    private static void text(StringBuilder sb, int indent, String key, PackText t) {
        if (t.isPlain()) {
            kv(sb, indent, key, quote(t.base()));
            return;
        }
        sb.append("  ".repeat(indent)).append(key).append(":\n");
        writeLocaleTable(sb, indent + 1, t);
    }

    private static void writeLocaleTable(StringBuilder sb, int indent, PackText t) {
        Map<String, String> m = t.byLocale();
        kv(sb, indent, PackText.DEFAULT_KEY, quote(m.get(PackText.DEFAULT_KEY)));
        new TreeSet<>(m.keySet()).stream().filter(k -> !k.equals(PackText.DEFAULT_KEY))
                .forEach(k -> kv(sb, indent, k, quote(m.get(k))));
    }

    private static String num(Double d) {
        if (d == null) {
            return null;
        }
        return d == Math.rint(d) && Double.isFinite(d) ? Long.toString((long) (double) d) : Double.toString(d);
    }

    /** Id métier : caractères sûrs uniquement — sinon on retombe sur le guillemet double. */
    private static String id(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_][A-Za-z0-9_.:/\\-]*") ? raw : quote(raw);
    }

    /** Jeton attendu « propre » (enum, material, entity, category, role, state). */
    private static String plain(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_][A-Za-z0-9_.\\-]*") ? raw : quote(raw);
    }

    private static String inlineList(List<String> values) {
        return "[" + String.join(", ", values) + "]";
    }

    private static String inlineIds(List<String> ids) {
        List<String> quoted = new ArrayList<>(ids.size());
        for (String i : ids) {
            quoted.add(id(i));
        }
        return "[" + String.join(", ", quoted) + "]";
    }

    /** Toujours entre guillemets doubles : peut contenir espaces, « : », MiniMessage, Unicode… */
    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
