package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.ai.AiJournalStore.Event;
import com.lodygames.rpgquest.panel.json.Json;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Le fichier texte « journal IA » d'un projet de l'Atelier (issue #739) : autonome, lisible, et
 * assez structuré pour être confié tel quel à une autre IA chargée de trouver où l'information se
 * perd.
 *
 * <p>Trois parties : une <strong>notice</strong> (but, format, identifiants, fuseaux, comment suivre
 * une tentative, ce qui manque), un <strong>index chronologique</strong> compact où chaque
 * diagnostic distinct reçoit une lettre — une boucle A → B → A s'y lit d'un coup d'œil — puis le
 * <strong>détail</strong> de chaque événement, textes reproduits en entier.</p>
 *
 * <p>Rien n'est reconstruit : seuls les événements journalisés apparaissent, et les versions du
 * projet antérieures au journal sont listées comme telles. Les verdicts que la session n'a pas
 * relevés elle-même (gestes manuels) sont lus à l'export dans les actions du serveur et marqués
 * « lu à l'export ».</p>
 */
public final class BuildingAiJournalExport {

    public static final String FORMAT = "lodyquests-ai-journal/1";
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx")
            .withZone(BRUSSELS);
    private static final String RULE = "=".repeat(100);
    private static final String THIN = "-".repeat(100);

    /** Le verdict du serveur sur une version, lu à l'export (gestes manuels). */
    public record ExportVerdict(boolean accepted, List<String> codes, String diagnosis, Instant at,
                                String actionId) {
    }

    /** Une version du projet, pour situer ce qui précède le journal. */
    public record DraftInfo(String id, int version, String origin, Instant createdAt) {
    }

    /** Ce que l'export doit savoir du projet. */
    public record Subject(String projectId, String projectName, String buildingId, String exportedBy,
                          Instant exportedAt, Instant installedAt, int maxEvents, List<DraftInfo> drafts) {
    }

    private BuildingAiJournalExport() {
    }

    /** Nom de fichier : bâtiment + date d'export, sans caractère hasardeux. */
    public static String filename(String projectName, String buildingId, Instant at) {
        String base = (buildingId == null || buildingId.isBlank() ? projectName : buildingId)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return "journal-ia-" + (base.isEmpty() ? "projet" : base) + "-"
                + DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(BRUSSELS).format(at) + ".txt";
    }

    public static String render(Subject s, List<Event> events, Function<String, Optional<ExportVerdict>> verdicts) {
        Labels labels = new Labels();
        Map<String, Event> validationByDraft = new HashMap<>();
        for (Event e : events) {
            if ("VALIDATION".equals(e.kind())) {
                validationByDraft.put(e.draftId(), e);
                labels.of(str(e.body(), "fingerprint"), e.seq());
            }
        }
        // Les versions produites sans vérification journalisée : verdict lu à l'export.
        Map<String, ExportVerdict> exported = new LinkedHashMap<>();
        for (Event e : events) {
            if ("RESPONSE".equals(e.kind()) && !e.draftId().isEmpty() && !validationByDraft.containsKey(e.draftId())) {
                verdicts.apply(e.draftId()).ifPresent(v -> {
                    exported.put(e.draftId(), v);
                    labels.of(v.accepted() ? "" : BuildingAiJournal.fingerprint(v.codes(), v.diagnosis()), e.seq());
                });
            }
        }

        StringBuilder out = new StringBuilder();
        notice(out, s, events);
        stats(out, events, labels);
        index(out, events, validationByDraft, exported, labels);
        details(out, events, labels, exported);
        out.append(RULE).append("\nFIN DU JOURNAL — ").append(events.size()).append(" événement(s).\n");
        return out.toString();
    }

    // ---- Notice ------------------------------------------------------------------------------------

    private static void notice(StringBuilder out, Subject s, List<Event> events) {
        out.append(RULE).append('\n');
        out.append("JOURNAL IA — ").append(s.projectName()).append('\n');
        out.append("Format : ").append(FORMAT).append(" — texte UTF-8\n");
        out.append(RULE).append("\n\n");
        out.append("NOTICE\n\n");
        line(out, "But", "retracer, dans l'ordre, chaque échange réel entre le panel PlugAdmin et le fournisseur "
                + "d'IA pour ce projet de l'Atelier de bâtiments, avec les vérifications du serveur Minecraft qui "
                + "les relient. Le fichier est autonome : il peut être confié tel quel à une autre IA pour trouver "
                + "où l'information se perd entre deux tentatives.");
        line(out, "Projet", s.projectName() + " — identifiant " + s.projectId()
                + (s.buildingId().isBlank() ? "" : " — bâtiment « " + s.buildingId() + " »"));
        line(out, "Exporté", time(s.exportedAt()) + " par " + s.exportedBy());
        line(out, "Périmètre", "tous les événements journalisés de ce projet (" + events.size() + "), toutes "
                + "versions et toutes sessions confondues : générations, améliorations, corrections automatiques, "
                + "conceptions détaillées, erreurs d'appel et vérifications.");
        line(out, "Installation du journal", time(s.installedAt()) + ". Rien de ce qui précède n'a été journalisé "
                + "et RIEN N'EST RECONSTRUIT : les échanges antérieurs sont absents de ce fichier.");
        List<DraftInfo> before = s.drafts().stream().filter(d -> d.createdAt() != null
                && d.createdAt().isBefore(s.installedAt())).toList();
        if (!before.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (DraftInfo d : before) {
                list.append("\n      - version ").append(shortId(d.id())).append(" n° ").append(d.version())
                        .append(" (").append(d.origin()).append(", créée le ").append(time(d.createdAt())).append(')');
            }
            line(out, "Versions antérieures au journal", before.size() + " version(s) de ce projet ont été créées "
                    + "avant l'installation : leurs échanges IA ne sont pas disponibles." + list);
        }
        line(out, "Horodatage", "chaque heure est donnée en UTC, ISO 8601 à la milliseconde (suffixe Z), puis entre "
                + "crochets en heure de Bruxelles avec son décalage. Une requête est datée de son DÉPART réel, sa "
                + "réponse de son ARRIVÉE réelle ; la durée de l'appel est indiquée sur la réponse.");
        line(out, "Numéro (n°)", "numéro de séquence propre au projet, attribué à l'écriture : il fixe l'ordre total "
                + "des événements, même pour des appels simultanés ou une réponse tardive.");
        line(out, "Identifiants", "appel C-xxxxxxxx = un appel au fournisseur (sa requête et sa réponse portent le "
                + "même) ; session S-xxxxxxxx = une conception automatique ; version V-xxxxxxxx = une version du "
                + "plan (identifiant court) ; « manuel » = geste d'un administrateur hors session.");
        line(out, "Suivre une tentative", "REQUÊTE (appel C-x, « entrée » = version corrigée et diagnostic "
                + "transmis) → RÉPONSE (même appel C-x, « version produite » V-y) → VÉRIFICATION de V-y par le "
                + "serveur (diagnostic et lettre) → REQUÊTE suivante, dont l'entrée cite V-y et la lettre "
                + "transmise. « parent » donne ces liens explicitement (draft:…, call:…).");
        line(out, "Lettres de diagnostic", "chaque diagnostic distinct du serveur (codes + texte exact, empreinte "
                + "SHA-256 courte) reçoit une lettre A, B, C… dans l'ordre d'apparition. « ↺ » signale un "
                + "diagnostic déjà rencontré : A → B → A est une boucle. Pour chaque requête, la ligne « Diagnostics "
                + "antérieurs présents » dit si le texte exact de chaque diagnostic déjà reçu figure mot pour mot "
                + "dans ce qui a été envoyé.");
        line(out, "Textes", "les longs textes envoyés (Q1, Q2…) et reçus (R1, R2…) sont sortis du corps JSON et "
                + "reproduits EN ENTIER entre « ----- début » et « ----- fin », avec leur rôle (system, user, "
                + "assistant…), leur chemin JSON et leur empreinte ; un texte identique octet pour octet à un "
                + "texte déjà reproduit (les consignes système d'un appel à l'autre) renvoie à sa première "
                + "occurrence au lieu d'être recopié ; le corps restant montre les paramètres réellement transmis "
                + "(modèle, plafond de sortie…). Aucune troncature : une réponse coupée par le fournisseur se voit "
                + "à sa « fin de génération » (length, max_tokens, MAX_TOKENS).");
        line(out, "Inventaire du contexte", "pour chaque requête : brief, conception détaillée, plan précédent, "
                + "motif, mesures, images — taille, empreinte, et présence mot pour mot dans le texte envoyé "
                + "(« non » = pièce perdue ou réduite).");
        line(out, "Masqué ou absent", "les clés API, en-têtes d'authentification, cookies et secrets sont "
                + "remplacés par " + AiTrace.MASK + " ; chaque masquage est listé sur sa requête. Les pixels des "
                + "images ne sont pas recopiés (type, taille, empreinte). Le raisonnement interne non exposé du "
                + "modèle n'est pas accessible par l'API et n'apparaît donc pas.");
        line(out, "Rétention", "au plus " + s.maxEvents() + " événements par projet ; au-delà les plus anciens "
                + "sont retirés et un événement RETENTION dit lesquels. Aucune perte silencieuse.");
        out.append('\n');
    }

    // ---- Statistiques ------------------------------------------------------------------------------

    private static void stats(StringBuilder out, List<Event> events, Labels labels) {
        int requests = 0;
        int errors = 0;
        int notSent = 0;
        int validations = 0;
        int refused = 0;
        for (Event e : events) {
            switch (e.kind()) {
                case "REQUEST" -> requests++;
                case "CALL_ERROR" -> errors++;
                case "CALL_NOT_SENT" -> notSent++;
                case "VALIDATION" -> {
                    validations++;
                    if (!Boolean.TRUE.equals(e.body().get("accepted"))) {
                        refused++;
                    }
                }
                default -> {
                }
            }
        }
        out.append(RULE).append("\nSYNTHÈSE\n\n");
        out.append("  Appels envoyés : ").append(requests).append(" (dont ").append(errors).append(" en erreur) ; ")
                .append("appels non émis : ").append(notSent).append('\n');
        out.append("  Vérifications journalisées : ").append(validations).append(" (dont ").append(refused)
                .append(" refus) ; diagnostics distincts : ").append(labels.size()).append('\n');
        if (!labels.loops.isEmpty()) {
            out.append("  ⚠ Diagnostics revenus après un autre (boucle A → B → A) : ")
                    .append(String.join(" ; ", labels.loops)).append('\n');
        }
        out.append('\n');
    }

    // ---- Index -------------------------------------------------------------------------------------

    private static void index(StringBuilder out, List<Event> events, Map<String, Event> validations,
                              Map<String, ExportVerdict> exported, Labels labels) {
        out.append(RULE).append("\nINDEX CHRONOLOGIQUE\n\n");
        out.append("  n°    heure UTC                 type            session     opération (tentative)            "
                + "version     diagnostic\n");
        out.append("  ").append(THIN).append('\n');
        for (Event e : events) {
            String diag = "";
            String version = e.draftId().isEmpty() ? "" : "V-" + shortId(e.draftId());
            switch (e.kind()) {
                case "VALIDATION" -> diag = verdictCell(Boolean.TRUE.equals(e.body().get("accepted")),
                        labels.letter(str(e.body(), "fingerprint")), labels.firstSeen(str(e.body(), "fingerprint"),
                                e.seq()), e.body().get("codes"));
                case "REQUEST", "CALL_NOT_SENT" -> {
                    String in = str(e.body(), "inputDiagnosis");
                    diag = in.isEmpty() ? "" : "transmis : " + labels.letterOrHash(in);
                }
                case "RESPONSE" -> {
                    ExportVerdict v = exported.get(e.draftId());
                    if (v != null) {
                        String fp = v.accepted() ? "" : BuildingAiJournal.fingerprint(v.codes(), v.diagnosis());
                        diag = verdictCell(v.accepted(), labels.letter(fp), labels.firstSeen(fp, e.seq()), v.codes())
                                + " (lu à l'export)";
                    }
                }
                default -> {
                }
            }
            out.append(String.format(Locale.ROOT, "  %-5d %-25s %-15s %-11s %-32s %-11s %s%n", e.seq(),
                    UTC.format(e.at()), kindLabel(e.kind()), e.runId().isEmpty() ? (e.callId().isEmpty() ? "" : "manuel")
                            : "S-" + shortId(e.runId()), cut(e.operation() + (e.attempt() > 0 ? " (" + e.attempt()
                            + ")" : ""), 32), version, diag));
        }
        out.append('\n');
        if (!labels.sequence.isEmpty()) {
            out.append("  Suite des diagnostics de refus, dans l'ordre : ").append(String.join(" → ", labels.sequence))
                    .append('\n');
        }
        out.append('\n');
    }

    private static String verdictCell(boolean accepted, String letter, Long firstSeen, Object codes) {
        if (accepted) {
            return "CONFORME";
        }
        String c = codes instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : "";
        return "REFUS " + letter + (firstSeen != null ? " ↺ déjà vu au n°" + firstSeen : "") + " [" + c + "]";
    }

    // ---- Détail ------------------------------------------------------------------------------------

    private static void details(StringBuilder out, List<Event> events, Labels labels,
                                Map<String, ExportVerdict> exported) {
        out.append(RULE).append("\nDÉTAIL DES ÉVÉNEMENTS\n\n");
        Map<String, String> diagnosisByLetterSeq = new LinkedHashMap<>();
        Map<String, String> printed = new HashMap<>();
        for (Event e : events) {
            out.append(THIN).append('\n');
            out.append("n°").append(e.seq()).append(" — ").append(kindLabel(e.kind())).append(" — ")
                    .append(time(e.at())).append('\n');
            List<String> ids = new ArrayList<>();
            if (!e.callId().isEmpty()) {
                ids.add("appel C-" + e.callId());
            }
            ids.add(e.runId().isEmpty() ? (e.callId().isEmpty() ? "hors session" : "manuel")
                    : "session S-" + shortId(e.runId()));
            if (!e.operation().isEmpty()) {
                ids.add(e.operation() + (e.attempt() > 0 ? ", tentative " + e.attempt() : ""));
            }
            if (!e.actor().isEmpty()) {
                ids.add("par " + e.actor());
            }
            out.append("  ").append(String.join(" — ", ids)).append('\n');
            if (!e.parentRef().isEmpty()) {
                out.append("  parent : ").append(e.parentRef()).append('\n');
            }
            if (e.durationMs() >= 0) {
                out.append("  durée : ").append(e.durationMs()).append(" ms\n");
            }
            out.append("  résumé : ").append(e.summary()).append('\n');
            switch (e.kind()) {
                case "REQUEST", "CALL_NOT_SENT" -> request(out, e, labels, diagnosisByLetterSeq, printed);
                case "RESPONSE", "CALL_ERROR" -> {
                    ExportVerdict v = exported.get(e.draftId());
                    response(out, e, v, labels, printed);
                    if (v != null && !v.accepted()) {
                        diagnosisByLetterSeq.putIfAbsent(BuildingAiJournal.fingerprint(v.codes(), v.diagnosis()),
                                v.diagnosis());
                    }
                }
                case "VALIDATION" -> {
                    validation(out, e, labels);
                    String fp = str(e.body(), "fingerprint");
                    if (!fp.isEmpty()) {
                        diagnosisByLetterSeq.putIfAbsent(fp, str(e.body(), "diagnosis"));
                    }
                }
                default -> {
                    if (!e.body().isEmpty()) {
                        out.append("  détail :\n").append(indent(Json.writePretty(e.body()), "    ")).append('\n');
                    }
                }
            }
            out.append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private static void request(StringBuilder out, Event e, Labels labels, Map<String, String> earlierDiagnoses,
                                Map<String, String> printed) {
        Map<String, Object> b = e.body();
        out.append("  entrée : ").append(e.draftId().isEmpty() ? "aucune version (demande neuve)"
                : "version V-" + shortId(e.draftId())).append(" ; diagnostic transmis : ")
                .append(str(b, "inputDiagnosis").isEmpty() ? "aucun" : labels.letterOrHash(str(b, "inputDiagnosis")))
                .append('\n');
        if (b.get("context") instanceof List<?> ctx && !ctx.isEmpty()) {
            out.append("  contexte déclaré par l'appelant :\n");
            ctx.forEach(c -> out.append("    - ").append(c).append('\n'));
        }
        if (b.get("manifest") instanceof Map<?, ?> m && !m.isEmpty()) {
            out.append("  inventaire de la demande (vérifié sur le texte envoyé) :\n");
            ((Map<String, Object>) m).forEach((k, v) -> out.append("    ").append(k).append(" : ")
                    .append(v instanceof Map<?, ?> || v instanceof List<?> ? Json.write(v) : String.valueOf(v))
                    .append('\n'));
        }
        List<Map<String, Object>> texts = textList(b.get("texts"));
        if (!earlierDiagnoses.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var d : earlierDiagnoses.entrySet()) {
                boolean present = !d.getValue().isBlank() && texts.stream()
                        .anyMatch(t -> String.valueOf(t.get("content")).contains(d.getValue().trim()));
                sb.append(sb.isEmpty() ? "" : ", ").append(labels.letter(d.getKey())).append(present ? " oui" : " non");
            }
            out.append("  diagnostics antérieurs présents mot pour mot dans cette requête : ").append(sb).append('\n');
        }
        if (b.containsKey("endpoint")) {
            out.append("  adresse : ").append(str(b, "endpoint")).append('\n');
            out.append("  en-têtes : ").append(Json.write(b.getOrDefault("headers", Map.of()))).append('\n');
        }
        if (b.get("redactions") instanceof List<?> r && !r.isEmpty()) {
            out.append("  masquages :\n");
            r.forEach(x -> out.append("    - ").append(x).append('\n'));
        }
        if (!str(b, "skeleton").isEmpty()) {
            out.append("  corps JSON envoyé (textes longs renvoyés plus bas) :\n")
                    .append(indent(str(b, "skeleton"), "    ")).append('\n');
        }
        failure(out, b);
        texts(out, texts, e.seq(), printed);
    }

    private static void response(StringBuilder out, Event e, ExportVerdict verdict, Labels labels,
                                 Map<String, String> printed) {
        Map<String, Object> b = e.body();
        int status = b.get("httpStatus") instanceof Number n ? n.intValue() : -1;
        out.append("  statut HTTP : ").append(status < 0 ? "aucune réponse HTTP" : String.valueOf(status));
        if (!str(b, "transportError").isEmpty()) {
            out.append(" — erreur réseau : ").append(str(b, "transportError"));
        }
        out.append('\n');
        out.append("  modèle rapporté : ").append(blank(str(b, "model"))).append(" ; usage : ")
                .append(blank(str(b, "usage"))).append('\n');
        out.append("  identifiant fournisseur : ").append(blank(str(b, "providerRequestId")))
                .append(" ; fin de génération : ").append(blank(str(b, "finishReason")))
                .append(truncatedHint(str(b, "finishReason"))).append('\n');
        if (!str(b, "error").isEmpty()) {
            out.append("  erreur : ").append(str(b, "error")).append('\n');
        }
        if (!str(b, "extractionNote").isEmpty()) {
            out.append("  extraction du plan : ").append(str(b, "extractionNote")).append('\n');
        }
        out.append("  plan extrait : ").append(Boolean.TRUE.equals(b.get("planExtracted"))
                ? "oui, empreinte " + str(b, "planSha256") : "non").append('\n');
        failure(out, b);
        if (!e.draftId().isEmpty()) {
            out.append("  version produite : V-").append(shortId(e.draftId())).append(" (").append(e.draftId())
                    .append(", n° ").append(b.getOrDefault("producedVersion", "?")).append(")\n");
        }
        if (verdict != null) {
            String fp = verdict.accepted() ? "" : BuildingAiJournal.fingerprint(verdict.codes(), verdict.diagnosis());
            out.append("  verdict du serveur (lu à l'export, action ").append(verdict.actionId()).append(", ")
                    .append(verdict.at() == null ? "heure inconnue" : time(verdict.at())).append(") : ")
                    .append(verdict.accepted() ? "CONFORME" : "REFUS " + labels.letter(fp) + " ["
                            + String.join(",", verdict.codes()) + "], empreinte " + fp).append('\n');
            if (!verdict.accepted()) {
                block(out, "diagnostic", verdict.diagnosis());
            }
        }
        if (!str(b, "skeleton").isEmpty()) {
            out.append("  corps JSON reçu (textes longs renvoyés plus bas) :\n")
                    .append(indent(str(b, "skeleton"), "    ")).append('\n');
        }
        texts(out, textList(b.get("texts")), e.seq(), printed);
    }

    /** #747 : l'appel échoué — sa nature, la version restée active, le contexte de reprise. */
    @SuppressWarnings("unchecked")
    private static void failure(StringBuilder out, Map<String, Object> b) {
        if (!(b.get("failure") instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> f = (Map<String, Object>) raw;
        out.append("  APPEL ÉCHOUÉ : ").append(str(f, "label")).append(str(f, "detail").isEmpty() ? ""
                : " — " + str(f, "detail")).append('\n');
        String kept = str(f, "activeVersionKept");
        out.append("  version créée : non ; version active conservée : ")
                .append(kept.isEmpty() ? "aucune (demande neuve)" : "V-" + shortId(kept) + " (" + kept + ")")
                .append('\n');
        if (f.get("retry") instanceof Map<?, ?> r) {
            Map<String, Object> retry = (Map<String, Object>) r;
            out.append("  contexte de reprise conservé : geste ").append(blank(str(retry, "gesture")))
                    .append(", mode ").append(blank(str(retry, "mode"))).append(", consignes ")
                    .append(str(retry, "notes").isEmpty() ? "aucune" : str(retry, "notes").length() + " caractère(s)")
                    .append('\n');
        }
        if (!str(f, "retryOf").isEmpty()) {
            out.append("  reprise de l'appel échoué : ").append(str(f, "retryOf")).append('\n');
        }
    }

    private static void validation(StringBuilder out, Event e, Labels labels) {
        Map<String, Object> b = e.body();
        boolean accepted = Boolean.TRUE.equals(b.get("accepted"));
        String fp = str(b, "fingerprint");
        out.append("  version jugée : V-").append(shortId(e.draftId())).append(" (n° ")
                .append(b.getOrDefault("version", "?")).append(") ; action du serveur : ")
                .append(blank(str(b, "auditActionId"))).append(" ; source : ").append(blank(str(b, "source")))
                .append('\n');
        out.append("  verdict : ").append(accepted ? "CONFORME" : "REFUS " + labels.letter(fp) + ", empreinte " + fp)
                .append(" ; codes : ").append(b.getOrDefault("codes", List.of())).append('\n');
        if (!str(b, "headline").isEmpty()) {
            out.append("  titre : ").append(str(b, "headline")).append('\n');
        }
        block(out, "diagnostic exact", str(b, "diagnosis"));
        block(out, "mesures", str(b, "metrics"));
        if (b.get("warnings") instanceof List<?> w && !w.isEmpty()) {
            out.append("  remarques :\n");
            w.forEach(x -> out.append("    - ").append(x).append('\n'));
        }
    }

    /**
     * Les textes en entier. Un texte IDENTIQUE à un texte déjà reproduit (les consignes système, d'un
     * appel à l'autre) n'est pas recopié : la ligne dit lequel, et son empreinte permet de le vérifier.
     */
    private static void texts(StringBuilder out, List<Map<String, Object>> texts, long seq,
                              Map<String, String> printed) {
        for (Map<String, Object> t : texts) {
            String ref = String.valueOf(t.get("ref"));
            String content = String.valueOf(t.get("content"));
            String role = String.valueOf(t.getOrDefault("role", ""));
            String sha = AiTrace.sha256(content).substring(0, 16);
            out.append("  ").append(ref).append(" [rôle : ").append(role.isEmpty() ? "non précisé" : role)
                    .append("] ").append(t.get("path")).append(" — ").append(content.length())
                    .append(" caractères, empreinte ").append(sha).append('\n');
            String first = printed.putIfAbsent(sha, ref + " du n°" + seq);
            if (first != null) {
                out.append("  (contenu IDENTIQUE, octet pour octet, à ").append(first)
                        .append(" : non recopié ici)\n");
                continue;
            }
            out.append("----- début ").append(ref).append(" -----\n").append(content)
                    .append(content.endsWith("\n") ? "" : "\n").append("----- fin ").append(ref).append(" -----\n");
        }
    }

    private static void block(StringBuilder out, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        out.append("  ").append(title).append(" :\n").append(indent(text, "    ")).append('\n');
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> textList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    // ---- Lettres de diagnostic --------------------------------------------------------------------

    /** A, B, C… par empreinte, dans l'ordre d'apparition ; repère les retours (A → B → A). */
    private static final class Labels {
        private final Map<String, String> letters = new LinkedHashMap<>();
        private final Map<String, Long> first = new HashMap<>();
        private final List<String> sequence = new ArrayList<>();
        private final List<String> loops = new ArrayList<>();
        private String previous = "";

        void of(String fingerprint, long seq) {
            if (fingerprint == null || fingerprint.isEmpty()) {
                return;
            }
            boolean known = letters.containsKey(fingerprint);
            String letter = letters.computeIfAbsent(fingerprint, k -> name(letters.size()));
            first.putIfAbsent(fingerprint, seq);
            if (known && !letter.equals(previous)) {
                loops.add(letter + " revient au n°" + seq + " (vu d'abord au n°" + first.get(fingerprint) + ")");
            }
            sequence.add(letter + (known ? "↺" : "") + "(n°" + seq + ")");
            previous = letter;
        }

        String letter(String fingerprint) {
            return fingerprint == null || fingerprint.isEmpty() ? "" : letters.getOrDefault(fingerprint, "?");
        }

        String letterOrHash(String fingerprint) {
            String l = letters.get(fingerprint);
            return l == null ? "empreinte " + fingerprint + " (non vue dans ce journal)" : l + " (" + fingerprint + ")";
        }

        /** Le n° de première apparition, si ce n'est pas celle-ci. */
        Long firstSeen(String fingerprint, long seq) {
            Long f = first.get(fingerprint);
            return f == null || f == seq ? null : f;
        }

        int size() {
            return letters.size();
        }

        private static String name(int i) {
            return i < 26 ? String.valueOf((char) ('A' + i)) : "D" + (i + 1);
        }
    }

    // ---- Utilitaires --------------------------------------------------------------------------------

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "REQUEST" -> "REQUÊTE";
            case "RESPONSE" -> "RÉPONSE";
            case "CALL_ERROR" -> "ERREUR D'APPEL";
            case "CALL_NOT_SENT" -> "APPEL NON ÉMIS";
            case "VALIDATION" -> "VÉRIFICATION";
            case "RUN_START" -> "DÉBUT SESSION";
            case "RUN_END" -> "FIN SESSION";
            case "PROJECT_CHANGED" -> "FICHE MODIFIÉE";
            default -> kind;
        };
    }

    private static String truncatedHint(String finish) {
        String f = finish.toLowerCase(Locale.ROOT);
        return f.equals("length") || f.contains("max_tokens") ? " ⚠ RÉPONSE COUPÉE par le plafond de sortie" : "";
    }

    static String time(Instant at) {
        return at == null ? "inconnue" : UTC.format(at) + " [Bruxelles " + LOCAL.format(at) + "]";
    }

    private static void line(StringBuilder out, String title, String text) {
        out.append("  • ").append(title).append(" : ").append(text).append('\n');
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String blank(String s) {
        return s.isEmpty() ? "non rapporté" : s;
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String indent(String text, String prefix) {
        return prefix + text.replace("\n", "\n" + prefix);
    }
}
