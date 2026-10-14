package com.lodygames.rpgquest.panel.dbtarget;

/**
 * Test de connexion à la base cible (issue #42) : <strong>lecture seule</strong>, borné dans le
 * temps, sans aucune écriture ni création — « connexion testée » ne veut jamais dire « serveur
 * migré ».
 */
@FunctionalInterface
public interface DbConnectionTester {

    /**
     * @param settings paramètres validés
     * @param password mot de passe en clair, jamais journalisé ni renvoyé
     */
    Result test(DbTargetSettings settings, String password);

    /**
     * Résultat affichable. {@code summary} ne contient jamais le mot de passe ; les messages d'erreur
     * du pilote sont expurgés de l'hôte, du compte et du mot de passe.
     */
    record Result(boolean ok, String summary) {
        public static Result success(String summary) {
            return new Result(true, summary);
        }

        public static Result failure(String summary) {
            return new Result(false, summary);
        }
    }

    /** Retire les valeurs sensibles d'un message d'erreur et le borne en longueur. */
    static String redact(String message, DbTargetSettings settings, String password) {
        String out = message == null ? "erreur inconnue" : message;
        if (password != null && !password.isEmpty()) {
            out = out.replace(password, "<mot de passe>");
        }
        if (!settings.host().isEmpty()) {
            out = out.replace(settings.host(), "<hôte>");
        }
        if (!settings.username().isEmpty()) {
            out = out.replace(settings.username(), "<compte>");
        }
        out = out.replaceAll("\\s+", " ").trim();
        return out.length() > 300 ? out.substring(0, 300) + "…" : out;
    }
}
