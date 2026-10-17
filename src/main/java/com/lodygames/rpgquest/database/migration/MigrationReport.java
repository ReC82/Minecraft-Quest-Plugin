package com.lodygames.rpgquest.database.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport d'une exécution de l'outil de migration (issue #42), rendu en texte (console, fichier
 * {@code .txt}) et en JSON (fichier {@code .json}, exploitable par un outil ou le Control Panel).
 *
 * <p><strong>Aucun secret</strong> : ni mot de passe, ni nom de variable d'environnement contenant
 * un secret, ni hôte de la base cible — seul le moteur et le nom de base sont cités, pour qu'un
 * rapport puisse être joint à un ticket sans exposer l'infrastructure.</p>
 */
public final class MigrationReport {

    public enum Status { SUCCESS, FAILED }

    /** Résultat d'une table : comptages et empreintes logiques côté source et côté cible. */
    public record TableResult(String table, long sourceRows, String sourceChecksum,
                              long targetRows, String targetChecksum) {
        public boolean matches() {
            return sourceRows == targetRows && sourceChecksum.equals(targetChecksum);
        }
    }

    /** Alignement de séquence {@code AUTOINCREMENT} : valeur source, valeur cible après migration. */
    public record SequenceResult(String table, long sourceNext, long targetNext, boolean applied) {
        public boolean ok() {
            return targetNext >= sourceNext;
        }
    }

    private static final Map<String, String> DOMAINS = Map.ofEntries(
            Map.entry("player_profiles", "Joueurs"),
            Map.entry("player_variables", "Variables joueur"),
            Map.entry("quest_progress", "Quêtes"),
            Map.entry("quest_objective_progress", "Quêtes"),
            Map.entry("quest_reward_grants", "Quêtes"),
            Map.entry("periodic_quest_assignments", "Quêtes"),
            Map.entry("story_progress", "Stories"),
            Map.entry("dialogue_node_reads", "Dialogues"),
            Map.entry("claims", "Claims"),
            Map.entry("claim_members", "Claims"),
            Map.entry("wallets", "Économie"),
            Map.entry("transactions", "Économie"),
            Map.entry("market_listings", "Économie"),
            Map.entry("store_deliveries_processed", "Économie"),
            Map.entry("player_entitlements", "Économie"),
            Map.entry("reward_entitlements", "Récompenses"),
            Map.entry("player_skills", "Compétences"),
            Map.entry("xp_grants", "Compétences"),
            Map.entry("player_placed_blocks", "Compétences"),
            Map.entry("resource_nodes", "Compétences"),
            Map.entry("backpacks", "Sacs à dos"),
            Map.entry("backpack_overflow", "Sacs à dos"),
            Map.entry("backpack_audit", "Sacs à dos"),
            Map.entry("personal_vaults", "Coffres personnels"),
            Map.entry("vault_access_points", "Coffres personnels"),
            Map.entry("npc_ids", "PNJ"),
            Map.entry("npc_citizens_bindings", "PNJ"),
            Map.entry("npc_citizens_skins", "PNJ"),
            Map.entry("encounter_sites", "PNJ"));

    private final MigrationMode mode;
    private final Instant startedAt;
    private Instant finishedAt;
    private String sourceFile = "";
    private String sourceShaBefore = "";
    private String sourceShaAfter = "";
    private int sourceSchemaVersion = -1;
    private int toolSchemaVersion = -1;
    private String targetLabel = "";
    private int targetSchemaVersion = -1;
    private boolean dataCommitted;
    private final List<TableResult> tables = new ArrayList<>();
    private final List<SequenceResult> sequences = new ArrayList<>();
    private final List<MigrationFinding> findings = new ArrayList<>();

    public MigrationReport(MigrationMode mode, Instant startedAt) {
        this.mode = mode;
        this.startedAt = startedAt;
    }

    // ---- alimentation -----------------------------------------------------------------------

    public void add(MigrationFinding finding) {
        findings.add(finding);
    }

    public void addTable(TableResult result) {
        tables.add(result);
        if (!result.matches()) {
            add(MigrationFinding.critical(MigrationFinding.Check.DATA, result.table(), "contenu différent après migration : source "
                    + result.sourceRows() + " ligne(s), cible " + result.targetRows() + " ligne(s)"
                    + (result.sourceRows() == result.targetRows() ? ", empreintes logiques différentes" : "")));
        }
    }

    public void addSequence(SequenceResult result) {
        sequences.add(result);
        if (!result.ok()) {
            add(MigrationFinding.critical(MigrationFinding.Check.SEQUENCE, result.table(), "séquence AUTOINCREMENT en recul : la cible "
                    + "attribuerait " + result.targetNext() + " alors que la source est à "
                    + result.sourceNext() + " (identifiants réutilisés)"));
        }
    }

    public void source(String file, int schemaVersion, int toolVersion) {
        this.sourceFile = file;
        this.sourceSchemaVersion = schemaVersion;
        this.toolSchemaVersion = toolVersion;
    }

    public void sourceChecksums(String before, String after) {
        this.sourceShaBefore = before;
        this.sourceShaAfter = after;
        if (!before.equals(after)) {
            add(MigrationFinding.critical(MigrationFinding.Check.SOURCE, "source", "le fichier SQLite source a changé pendant l'exécution "
                    + "(SHA-256 avant ≠ après) : une autre application l'a écrit, ou l'outil a un défaut"));
        }
    }

    public void target(String label, int schemaVersion) {
        this.targetLabel = label;
        this.targetSchemaVersion = schemaVersion;
    }

    public void dataCommitted(boolean committed) {
        this.dataCommitted = committed;
    }

    public void finish(Instant at) {
        this.finishedAt = at;
    }

    // ---- lecture ----------------------------------------------------------------------------

    public Status status() {
        boolean critical = findings.stream().anyMatch(f -> f.severity() == MigrationFinding.Severity.CRITICAL);
        return critical ? Status.FAILED : Status.SUCCESS;
    }

    public MigrationMode mode() {
        return mode;
    }

    public boolean dataCommitted() {
        return dataCommitted;
    }

    public List<TableResult> tables() {
        return List.copyOf(tables);
    }

    public List<SequenceResult> sequences() {
        return List.copyOf(sequences);
    }

    public List<MigrationFinding> findings() {
        return List.copyOf(findings);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt);
    }

    static String domainOf(String table) {
        String domain = DOMAINS.get(table);
        if (domain != null) {
            return domain;
        }
        if (table.startsWith("building_")) {
            return "Bâtiments";
        }
        if (table.startsWith("way") || table.contains("travel") || table.contains("portal")
                || table.startsWith("village") || table.startsWith("city")) {
            return "Voyage / monde";
        }
        return "Autres";
    }

    // ---- rendu ------------------------------------------------------------------------------

    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append("RPGQuest — migration SQLite → ").append(targetLabel.isEmpty() ? "(aucune cible)" : targetLabel)
                .append('\n');
        out.append("Mode              : ").append(mode.label()).append('\n');
        out.append("Début             : ").append(startedAt).append('\n');
        out.append("Durée             : ").append(formatDuration(duration())).append('\n');
        out.append("Source            : ").append(sourceFile).append(" (schéma V").append(sourceSchemaVersion)
                .append(", outil V").append(toolSchemaVersion).append(")\n");
        out.append("SHA-256 source    : ").append(sourceShaBefore).append('\n');
        if (!targetLabel.isEmpty()) {
            out.append("Cible             : ").append(targetLabel).append(" (schéma V")
                    .append(targetSchemaVersion).append(")\n");
        }
        out.append('\n');

        if (!tables.isEmpty()) {
            out.append(String.format(Locale.ROOT, "%-18s %-28s %10s %10s  %s%n",
                    "Domaine", "Table", "SQLite", "Cible", "Résultat"));
            for (TableResult table : tables) {
                out.append(String.format(Locale.ROOT, "%-18s %-28s %10d %10s  %s%n",
                        domainOf(table.table()), table.table(), table.sourceRows(),
                        table.targetChecksum().isEmpty() ? "-" : Long.toString(table.targetRows()),
                        table.targetChecksum().isEmpty() ? "non migrée" : table.matches() ? "OK" : "ÉCART"));
            }
            out.append('\n');
        }
        if (!sequences.isEmpty()) {
            out.append("Séquences AUTOINCREMENT (prochain identifiant) :\n");
            for (SequenceResult sequence : sequences) {
                out.append(String.format(Locale.ROOT, "  %-28s source %-8d cible %-8d %s%n", sequence.table(),
                        sequence.sourceNext(), sequence.targetNext(),
                        !sequence.ok() ? "RECUL" : sequence.applied() ? "OK (alignée)" : "OK"));
            }
            out.append('\n');
        }

        out.append("Intégrité SQLite        : ").append(verdict(MigrationFinding.Check.INTEGRITY)).append('\n');
        out.append("Clés étrangères         : ").append(verdict(MigrationFinding.Check.FOREIGN_KEY)).append('\n');
        out.append("UUID joueurs            : ").append(verdict(MigrationFinding.Check.UUID)).append('\n');
        out.append("Compatibilité des types : ").append(verdict(MigrationFinding.Check.TYPE)).append('\n');
        out.append("Comptages / empreintes  : ").append(tables.isEmpty() ? "non exécuté"
                : tables.stream().allMatch(TableResult::matches) ? "OK" : "ÉCHEC").append('\n');
        out.append("Source SQLite inchangée : ").append(sourceShaAfter.isEmpty() ? "non vérifié"
                : sourceShaBefore.equals(sourceShaAfter) ? "OK" : "ÉCHEC").append('\n');
        out.append("Données conservées      : ").append(dataCommitted ? "OUI (validées dans la cible)"
                : "NON (" + (mode == MigrationMode.COMMIT ? "transaction annulée" : "aucune écriture conservée")
                + ")").append('\n');
        out.append('\n');

        if (!findings.isEmpty()) {
            out.append("Constats :\n");
            for (MigrationFinding finding : findings) {
                out.append("  [").append(finding.severity()).append("] ").append(finding.scope()).append(" — ")
                        .append(finding.message()).append('\n');
            }
            out.append('\n');
        }
        out.append("Statut de la migration : ").append(status()).append('\n');
        return out.toString();
    }

    /** Verdict d'une famille de contrôles, d'après ses constats. */
    private String verdict(MigrationFinding.Check family) {
        boolean critical = false;
        boolean warning = false;
        boolean checked = false;
        for (MigrationFinding finding : findings) {
            if (finding.check() != family) {
                continue;
            }
            checked = true;
            critical |= finding.severity() == MigrationFinding.Severity.CRITICAL;
            warning |= finding.severity() == MigrationFinding.Severity.WARNING;
        }
        if (!checked) {
            return "OK";
        }
        return critical ? "ÉCHEC" : warning ? "OK (avec avertissements)" : "OK";
    }

    public String toJson() {
        StringBuilder out = new StringBuilder("{\n");
        field(out, "status", status().name());
        field(out, "mode", mode.name());
        field(out, "startedAt", startedAt.toString());
        field(out, "durationMs", duration().toMillis());
        field(out, "sourceFile", sourceFile);
        field(out, "sourceSchemaVersion", sourceSchemaVersion);
        field(out, "toolSchemaVersion", toolSchemaVersion);
        field(out, "sourceSha256Before", sourceShaBefore);
        field(out, "sourceSha256After", sourceShaAfter);
        field(out, "target", targetLabel);
        field(out, "targetSchemaVersion", targetSchemaVersion);
        field(out, "dataCommitted", dataCommitted);
        out.append("  \"tables\": [");
        for (int i = 0; i < tables.size(); i++) {
            TableResult t = tables.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"table\": ").append(quote(t.table()))
                    .append(", \"domain\": ").append(quote(domainOf(t.table())))
                    .append(", \"sourceRows\": ").append(t.sourceRows())
                    .append(", \"targetRows\": ").append(t.targetRows())
                    .append(", \"sourceChecksum\": ").append(quote(t.sourceChecksum()))
                    .append(", \"targetChecksum\": ").append(quote(t.targetChecksum()))
                    .append(", \"ok\": ").append(t.matches()).append('}');
        }
        out.append(tables.isEmpty() ? "],\n" : "\n  ],\n");
        out.append("  \"sequences\": [");
        for (int i = 0; i < sequences.size(); i++) {
            SequenceResult s = sequences.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"table\": ").append(quote(s.table()))
                    .append(", \"sourceNext\": ").append(s.sourceNext())
                    .append(", \"targetNext\": ").append(s.targetNext())
                    .append(", \"applied\": ").append(s.applied())
                    .append(", \"ok\": ").append(s.ok()).append('}');
        }
        out.append(sequences.isEmpty() ? "],\n" : "\n  ],\n");
        out.append("  \"findings\": [");
        for (int i = 0; i < findings.size(); i++) {
            MigrationFinding f = findings.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"severity\": ").append(quote(f.severity().name()))
                    .append(", \"check\": ").append(quote(f.check().name()))
                    .append(", \"scope\": ").append(quote(f.scope()))
                    .append(", \"message\": ").append(quote(f.message())).append('}');
        }
        out.append(findings.isEmpty() ? "]\n" : "\n  ]\n");
        return out.append("}\n").toString();
    }

    private static void field(StringBuilder out, String name, Object value) {
        out.append("  ").append(quote(name)).append(": ")
                .append(value instanceof String s ? quote(s) : String.valueOf(value)).append(",\n");
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String formatDuration(Duration duration) {
        return String.format(Locale.ROOT, "%d.%03d s", duration.toSeconds(), duration.toMillisPart());
    }
}
