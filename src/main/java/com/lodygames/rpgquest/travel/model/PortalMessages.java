package com.lodygames.rpgquest.travel.model;

/**
 * Messages et avertissement propres à un portail entre mondes (issue #569, #228-P6). Chaque texte
 * est du MiniMessage saisi dans PlugAdmin (StyleField) ; {@code ""} = texte par défaut, c'est-à-dire
 * <strong>exactement</strong> le texte affiché avant #569 : un portail sans section {@code messages:}
 * ni {@code warning:} ({@link #NONE}) se comporte comme avant.
 *
 * @param welcome     message envoyé après une arrivée réussie ({@code ""} = aucun)
 * @param preparing   message d'attente avant le départ ({@code ""} = celui de l'avertissement
 *                    après confirmation, aucun sinon)
 * @param success     réussite ({@code ""} = « Téléportation réussie. »)
 * @param unavailable monde de destination indisponible
 * @param unsafe      destination dangereuse : arrivée non sûre, téléportation refusée
 * @param refusal     refus d'une condition d'accès sans message propre ({@code ""} = message par
 *                    défaut de la condition, #567)
 * @param warning     avertissement avant le départ
 */
public record PortalMessages(String welcome, String preparing, String success, String unavailable, String unsafe,
                             String refusal, Warning warning) {

    public static final int MAX_LENGTH = 200;
    /** Le corps de l'avertissement peut être plus long qu'une ligne de chat. */
    public static final int MAX_TEXT_LENGTH = 500;

    public static final PortalMessages NONE = new PortalMessages("", "", "", "", "", "", Warning.DEFAULT);

    /** Avertissement avec confirmation (fenêtre Paper ou chat cliquable). */
    public enum Mode {
        /** Comportement d'avant #569 : avertissement seulement pour un portail vers le Wild. */
        DEFAULT,
        /** Avertissement affiché, quelle que soit la destination. */
        ALWAYS,
        /** Aucun avertissement, même vers le Wild. */
        NEVER
    }

    /**
     * @param mode      quand l'afficher
     * @param title     titre de la fenêtre
     * @param text      texte principal
     * @param hint      ligne d'indice sous le texte
     * @param enter     bouton « Entrer »
     * @param enterHide bouton « Entrer et ne plus afficher »
     * @param cancel    bouton « Annuler »
     * @param cancelText message après « Annuler »
     * @param allowHide proposer « Entrer et ne plus afficher » (préférence par joueur et par portail)
     */
    public record Warning(Mode mode, String title, String text, String hint, String enter, String enterHide,
                          String cancel, String cancelText, boolean allowHide) {

        public static final Warning DEFAULT = new Warning(Mode.DEFAULT, "", "", "", "", "", "", "", true);

        public Warning {
            mode = mode == null ? Mode.DEFAULT : mode;
            title = check("titre", title, MAX_LENGTH);
            text = check("texte", text, MAX_TEXT_LENGTH);
            hint = check("indice", hint, MAX_LENGTH);
            enter = check("bouton Entrer", enter, MAX_LENGTH);
            enterHide = check("bouton Entrer et ne plus afficher", enterHide, MAX_LENGTH);
            cancel = check("bouton Annuler", cancel, MAX_LENGTH);
            cancelText = check("message après Annuler", cancelText, MAX_LENGTH);
        }

        public boolean isDefault() {
            return equals(DEFAULT);
        }
    }

    public PortalMessages {
        welcome = check("bienvenue", welcome, MAX_LENGTH);
        preparing = check("attente", preparing, MAX_LENGTH);
        success = check("réussite", success, MAX_LENGTH);
        unavailable = check("monde indisponible", unavailable, MAX_LENGTH);
        unsafe = check("destination dangereuse", unsafe, MAX_LENGTH);
        refusal = check("refus", refusal, MAX_LENGTH);
        warning = warning == null ? Warning.DEFAULT : warning;
    }

    /** Rien de personnalisé : fichier, empreinte et comportement identiques à avant #569. */
    public boolean isEmpty() {
        return equals(NONE);
    }

    /** Tous les messages hors avertissement sont-ils par défaut ? */
    public boolean messagesEmpty() {
        return welcome.isEmpty() && preparing.isEmpty() && success.isEmpty() && unavailable.isEmpty()
                && unsafe.isEmpty() && refusal.isEmpty();
    }

    public PortalMessages withWarning(Warning newWarning) {
        return new PortalMessages(welcome, preparing, success, unavailable, unsafe, refusal, newWarning);
    }

    /** Message de refus d'une condition : le sien, sinon le refus du portail, sinon celui par défaut (#567). */
    public String refusalFor(PortalAccess.Rule rule) {
        if (!rule.message().isEmpty() || refusal.isEmpty()) {
            return rule.refusal();
        }
        return refusal;
    }

    /** Un avertissement s'affiche-t-il pour ce portail ? {@code towardsWild} = destination {@code travel.wild-world}. */
    public boolean warns(boolean towardsWild) {
        return switch (warning.mode()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case DEFAULT -> towardsWild;
        };
    }

    private static String check(String label, String value, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException("« " + label + " » trop long : " + max + " caractères au plus.");
        }
        return v;
    }
}
