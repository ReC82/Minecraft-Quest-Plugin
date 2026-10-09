package com.lodygames.rpgquest.panel.publish;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Les ressources qui demandent une décision : tout ce qui n'est pas « Synchronisé » (issue #47).
 *
 * <h2>Pourquoi une liste et pas un compteur</h2>
 *
 * <p>« 7 ressources non synchronisées » n'aide personne : il faut ouvrir sept fiches pour savoir
 * lesquelles. Cette vue nomme chacune, avec son état, ses deux empreintes et l'action qui convient
 * — c'est la seule forme qui permette de décider sans naviguer.</p>
 *
 * <h2>Filtrage et tri : purs, donc testés</h2>
 *
 * <p>Tout ce fichier est sans HTML et sans HTTP. Les combinaisons de filtres sont nombreuses
 * (famille × état × recherche) et ce sont exactement le genre de conditions qu'on casse sans le
 * voir ; les vérifier ici coûte trois lignes par cas.</p>
 */
public record PendingChanges(List<Row> rows) {

    public PendingChanges {
        rows = List.copyOf(rows == null ? List.of() : rows);
    }

    /**
     * Une ressource à décider.
     *
     * @param kind       {@code quests}, {@code stories} ou {@code dialogues}
     * @param slug       l'identifiant « nu », qui est aussi le nom de fichier
     * @param declaredId l'identifiant que le moteur doit porter (souvent préfixé)
     * @param name       le libellé humain, ou le slug à défaut
     * @param verifiedAt horodatage de la dernière opération de publication connue, ou {@code ""}
     */
    public record Row(String kind, String slug, String declaredId, String name,
                      PublishState state, String sourceSha, String devSha,
                      boolean runtimeLoaded, String verifiedAt) {

        public Row {
            name = name == null || name.isBlank() ? slug : name;
            sourceSha = sourceSha == null ? "" : sourceSha;
            devSha = devSha == null ? "" : devSha;
            verifiedAt = verifiedAt == null ? "" : verifiedAt;
        }

        /** Clé stable d'une ressource, utilisée comme nom de champ de formulaire. */
        public String key() {
            return kind + "/" + slug;
        }

        /** Libellé de famille, au singulier. */
        public String kindLabel() {
            return switch (kind) {
                case "quests" -> "Quête";
                case "stories" -> "Story";
                case "dialogues" -> "Dialogue";
                default -> kind;
            };
        }

        public String shortSource() {
            return shorten(sourceSha);
        }

        public String shortDev() {
            return shorten(devSha);
        }

        /** Vrai si cette ligne peut être incluse dans une publication groupée. */
        public boolean selectable() {
            // Un conflit n'est JAMAIS publiable en lot : il exige d'avoir regardé la différence,
            // ce qui se fait sur la fiche. L'inclure dans une sélection groupée reviendrait à
            // offrir l'écrasement aveugle que le ticket interdit.
            return state.publishable() && !sourceSha.isEmpty();
        }

        private static String shorten(String sha) {
            return sha == null || sha.isEmpty() ? "—"
                    : sha.length() <= 12 ? sha : sha.substring(0, 12);
        }
    }

    /** Les familles filtrables, dans l'ordre d'affichage. */
    public static final List<String> KINDS = List.of("quests", "dialogues", "stories");

    /**
     * Ne garde que ce qui demande une décision, et trie.
     *
     * <p>Tri : les états qui exigent une attention d'abord (conflit, publié-non-chargé), puis le
     * reste, puis par famille et identifiant. Un administrateur qui ouvre cette page veut voir le
     * problème en haut, pas à chercher.</p>
     */
    public static PendingChanges from(List<Row> all) {
        List<Row> kept = new ArrayList<>();
        for (Row row : all == null ? List.<Row>of() : all) {
            if (row.state() != PublishState.SYNCED) {
                kept.add(row);
            }
        }
        kept.sort(Comparator.comparingInt((Row r) -> priority(r.state()))
                .thenComparing(r -> KINDS.indexOf(r.kind()) < 0 ? 99 : KINDS.indexOf(r.kind()))
                .thenComparing(Row::slug));
        return new PendingChanges(kept);
    }

    private static int priority(PublishState state) {
        return switch (state) {
            case CONFLICT -> 0;
            case NOT_LOADED -> 1;
            case DIFFERENT -> 2;
            case SOURCE_ONLY -> 3;
            case UNKNOWN -> 4;
            case RUNTIME_ONLY -> 5;
            default -> 6;
        };
    }

    /**
     * Applique les filtres de l'écran.
     *
     * @param kind   une famille, ou {@code ""} / {@code "all"} pour toutes
     * @param state  un code d'état, ou {@code ""} / {@code "all"} pour tous
     * @param search fragment cherché dans l'identifiant ou le nom, insensible à la casse
     */
    public PendingChanges filter(String kind, String state, String search) {
        String wantedKind = normalize(kind);
        String wantedState = normalize(state);
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            if (!wantedKind.isEmpty() && !wantedKind.equals(row.kind())) {
                continue;
            }
            if (!wantedState.isEmpty() && !wantedState.equals(row.state().code()
                    .toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!needle.isEmpty()
                    && !row.slug().toLowerCase(Locale.ROOT).contains(needle)
                    && !row.name().toLowerCase(Locale.ROOT).contains(needle)
                    && !row.declaredId().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            kept.add(row);
        }
        return new PendingChanges(kept);
    }

    public int total() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Combien de lignes peuvent entrer dans une publication groupée. */
    public long selectableCount() {
        return rows.stream().filter(Row::selectable).count();
    }

    /** Combien exigent une attention particulière (conflit, ou publié mais non chargé). */
    public long attentionCount() {
        return rows.stream().filter(r -> r.state().needsAttention()).count();
    }

    public long countOf(String kind) {
        return rows.stream().filter(r -> r.kind().equals(kind)).count();
    }

    public long countOf(PublishState state) {
        return rows.stream().filter(r -> r.state() == state).count();
    }

    private static String normalize(String raw) {
        String clean = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return clean.isEmpty() || clean.equals("all") || clean.equals("tous") ? "" : clean;
    }
}
