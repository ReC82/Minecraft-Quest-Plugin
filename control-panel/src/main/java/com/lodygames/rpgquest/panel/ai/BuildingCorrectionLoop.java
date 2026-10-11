package com.lodygames.rpgquest.panel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * La boucle « propose — audite — corrige », avec un budget d'appels payants qui est une
 * <strong>contrainte de construction</strong> (issue #94, lot F).
 *
 * <h2>Pourquoi cette classe ne connaît ni HTTP, ni le serveur Minecraft</h2>
 *
 * <p>Elle reçoit deux fonctions : {@link Asker}, qui interroge le modèle, et {@link Auditor}, qui
 * fait juger un plan par le plugin. Elle ne sait pas comment elles s'exécutent. C'est ce qui permet
 * de l'éprouver entièrement hors ligne — sans clé d'API, sans serveur, et donc
 * <strong>sans dépenser un seul jeton</strong> — alors que c'est précisément la pièce dont une
 * erreur coûte de l'argent réel.</p>
 *
 * <h2>Les garde-fous, et pourquoi ils sont là</h2>
 *
 * <ul>
 *   <li><strong>Le budget est borné à la construction.</strong> {@code maxAttempts} est ramené dans
 *       {@code [1, 5]} par le constructeur du budget : aucun formulaire, aucun appelant ne peut
 *       demander vingt tentatives. Une boucle de correction qui part en vrille sur un plan
 *       insoluble est le mode de défaillance le plus coûteux de cette fonctionnalité.</li>
 *   <li><strong>Un appel au modèle est toujours compté, même s'il échoue.</strong> Un appel HTTP
 *       refusé après émission est déjà facturé chez certains fournisseurs ; compter autrement
 *       ferait du budget une illusion.</li>
 *   <li><strong>Le mode automatique est explicite.</strong> Sans lui, la boucle s'arrête après le
 *       premier refus et rend la main : l'administrateur voit le motif et décide de payer la suite.
 *       C'est le comportement par défaut, parce que personne ne doit découvrir une dépense.</li>
 *   <li><strong>Elle s'arrête sur un plan qui ne bouge plus.</strong> Si le modèle renvoie deux fois
 *       le même document, continuer serait payer pour la même réponse.</li>
 * </ul>
 *
 * <h2>Ce que cette classe ne fait pas</h2>
 *
 * <p>Elle n'écrit rien et ne génère rien. Un plan qui passe l'audit ressort d'ici comme
 * <em>accepté</em>, pas comme <em>construit</em> : la génération reste un geste humain distinct,
 * conformément à la discipline de {@link AiBuildingStudio}. L'audit lui-même n'écrit rien non plus,
 * c'est tout l'intérêt de l'action {@code building.plan.audit} sur laquelle elle s'appuie.</p>
 */
public final class BuildingCorrectionLoop {

    /** Borne dure du budget : au-delà, c'est de l'argent dépensé sans supervision. */
    public static final int MAX_ATTEMPTS_CEILING = 5;

    /**
     * Le budget consenti pour une session de correction.
     *
     * @param maxAttempts nombre total d'appels au modèle, ramené dans {@code [1, 5]}
     * @param automatic   vrai si l'administrateur a explicitement autorisé l'enchaînement des
     *                    corrections sans repasser par lui
     */
    public record Budget(int maxAttempts, boolean automatic) {

        public Budget {
            maxAttempts = Math.min(MAX_ATTEMPTS_CEILING, Math.max(1, maxAttempts));
        }

        /** Le budget par défaut : une seule proposition, et la main rendue à l'administrateur. */
        public static Budget manual() {
            return new Budget(1, false);
        }

        public String label() {
            return automatic
                    ? maxAttempts + " tentative" + (maxAttempts > 1 ? "s" : "") + " enchaînées"
                    : "une proposition, puis retour à l'administrateur";
        }
    }

    /** Interroge le modèle. La première tentative passe {@code null} en motif de refus. */
    public interface Asker {
        AiBuildingStudio.Proposal ask(String previousPlan, String refusal, String metrics,
                                      int attempt, int maxAttempts);
    }

    /** Fait juger un plan par le plugin, qui est le validateur autoritaire. */
    public interface Auditor {
        Audit audit(String planYaml);
    }

    /**
     * Le verdict du plugin, réduit à ce dont la boucle a besoin.
     *
     * @param reachable faux si le serveur n'a pas pu être interrogé — cas à distinguer d'un refus :
     *                  relancer le modèle parce que l'agent était hors ligne serait payer pour rien
     * @param message   le motif, destiné à être renvoyé au modèle
     * @param metrics   les mesures prises sur la grille, déjà mises en forme
     */
    public record Audit(boolean reachable, boolean accepted, String code, String message,
                        List<String> codes, String metrics) {

        public Audit {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            codes = List.copyOf(codes == null ? List.of() : codes);
            metrics = metrics == null ? "" : metrics;
        }

        public static Audit unreachable(String why) {
            return new Audit(false, false, "AGENT_UNREACHABLE", why, List.of(), "");
        }
    }

    /** Une passe de la boucle, telle que l'écran la restitue. */
    public record Attempt(int rank, AiBuildingStudio.Proposal proposal, Audit audit) {

        /** Cette passe a-t-elle produit un plan accepté par le serveur ? */
        public boolean accepted() {
            return audit != null && audit.accepted();
        }
    }

    /**
     * L'issue de la boucle.
     *
     * @param outcome pourquoi la boucle s'est arrêtée — c'est l'information que l'écran doit
     *                afficher, parce que « aucun plan » n'a pas le même sens selon la raison
     * @param spent   nombre d'appels au modèle réellement effectués, donc facturés
     */
    public record Run(List<Attempt> attempts, Outcome outcome, int spent, Budget budget) {

        public Run {
            attempts = List.copyOf(attempts == null ? List.of() : attempts);
        }

        /** Le plan accepté, ou {@code null} : il n'y a pas de demi-succès. */
        public String acceptedPlan() {
            Attempt last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            return last != null && last.accepted() ? last.proposal().plan() : null;
        }

        public Attempt last() {
            return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        }

        public String summary() {
            return outcome.label() + " — " + spent + " appel" + (spent > 1 ? "s" : "")
                    + " au modèle sur un budget de " + budget.maxAttempts() + ".";
        }
    }

    /** Pourquoi la boucle s'est arrêtée. */
    public enum Outcome {
        /** Le serveur a accepté le plan. Rien n'a été écrit pour autant. */
        ACCEPTED("Plan accepté par le serveur"),
        /** Refusé, et le budget est épuisé. */
        BUDGET_EXHAUSTED("Budget de tentatives épuisé"),
        /** Refusé, et la main est rendue : le mode automatique n'était pas autorisé. */
        AWAITING_DECISION("Refusé — en attente d'une décision de l'administrateur"),
        /** Le modèle n'a rien renvoyé d'exploitable. */
        NO_PLAN("Le modèle n'a produit aucun plan exploitable"),
        /** L'appel au modèle a échoué. */
        CALL_FAILED("L'appel au fournisseur d'IA a échoué"),
        /** Le serveur n'a pas pu juger le plan. */
        AGENT_UNREACHABLE("Le serveur n'a pas pu auditer le plan"),
        /** Le modèle a renvoyé deux fois le même plan. */
        STALLED("Le modèle renvoie le même plan : correction interrompue");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Asker asker;
    private final Auditor auditor;

    public BuildingCorrectionLoop(Asker asker, Auditor auditor) {
        this.asker = asker;
        this.auditor = auditor;
    }

    /**
     * Déroule la boucle dans la limite du budget.
     *
     * @param previousPlan plan de départ à corriger, ou {@code null} pour une première demande
     * @param refusal      motif qui justifie la reprise, ou {@code null}
     */
    public Run run(Budget budget, String previousPlan, String refusal, String metrics) {
        Budget effective = budget == null ? Budget.manual() : budget;
        List<Attempt> attempts = new ArrayList<>();
        String plan = previousPlan;
        String why = refusal;
        String measured = metrics;
        int spent = 0;

        while (spent < effective.maxAttempts()) {
            int rank = spent + 1;
            AiBuildingStudio.Proposal proposal =
                    asker.ask(plan, why, measured, rank, effective.maxAttempts());
            // Compté avant tout examen : un appel émis est un appel payé.
            spent++;

            if (!proposal.callOk()) {
                attempts.add(new Attempt(rank, proposal, null));
                return new Run(attempts, Outcome.CALL_FAILED, spent, effective);
            }
            if (!proposal.hasPlan()) {
                attempts.add(new Attempt(rank, proposal, null));
                return new Run(attempts, Outcome.NO_PLAN, spent, effective);
            }
            // Le modèle ressert le même document : la correction ne progresse plus, et chaque passe
            // supplémentaire coûterait le prix de la réponse déjà obtenue.
            if (plan != null && plan.trim().equals(proposal.plan().trim())) {
                attempts.add(new Attempt(rank, proposal, null));
                return new Run(attempts, Outcome.STALLED, spent, effective);
            }

            Audit audit = auditor.audit(proposal.plan());
            attempts.add(new Attempt(rank, proposal, audit));

            if (!audit.reachable()) {
                return new Run(attempts, Outcome.AGENT_UNREACHABLE, spent, effective);
            }
            if (audit.accepted()) {
                return new Run(attempts, Outcome.ACCEPTED, spent, effective);
            }
            if (!effective.automatic()) {
                // Le refus est entre les mains de l'administrateur, avec le motif sous les yeux.
                return new Run(attempts, Outcome.AWAITING_DECISION, spent, effective);
            }
            plan = proposal.plan();
            why = audit.message();
            measured = audit.metrics();
        }
        return new Run(attempts, Outcome.BUDGET_EXHAUSTED, spent, effective);
    }

    /**
     * Met les mesures du serveur en phrases, pour le modèle.
     *
     * <p>Le détail de l'action arrive en {@code Map} non typée. On ne recopie que ce qui est
     * présent : une clé absente est passée sous silence plutôt que rendue en « null », qui
     * apprendrait au modèle une fausse mesure.</p>
     */
    public static String formatMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        line(lines, metrics, "solidCells", "blocs de matière posés");
        line(lines, metrics, "glazedCells", "cellules vitrées");
        line(lines, metrics, "glazedFacades", "façades portant un vitrage");
        line(lines, metrics, "lightSources", "sources de lumière");
        line(lines, metrics, "openEnvelopeCellsHigh", "cellules d'enveloppe ouvertes");
        line(lines, metrics, "floors", "niveaux praticables détectés");
        line(lines, metrics, "reachableFloors", "niveaux réellement atteignables depuis l'entrée");
        line(lines, metrics, "furnishingCells", "cellules de mobilier");
        line(lines, metrics, "envelopeMaterials", "matériaux distincts dans l'enveloppe");
        Object top = metrics.get("topMaterial");
        Object share = metrics.get("topMaterialShare");
        if (top != null && !String.valueOf(top).isBlank() && share instanceof Number number) {
            lines.add("- matériau dominant : " + top + " à "
                    + Math.round(number.doubleValue() * 100) + " % de l'enveloppe");
        }
        return String.join("\n", lines);
    }

    private static void line(List<String> out, Map<String, Object> metrics, String key,
                             String label) {
        Object value = metrics.get(key);
        if (value instanceof Number number) {
            out.add("- " + label + " : " + number.longValue());
        }
    }
}
