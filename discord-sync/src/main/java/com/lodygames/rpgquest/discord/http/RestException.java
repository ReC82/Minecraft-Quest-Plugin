package com.lodygames.rpgquest.discord.http;

/**
 * Échec d'un appel REST.
 *
 * <p>{@link #ambiguous()} est la distinction qui compte pour l'anti-doublon (issue #202) : une
 * coupure réseau, un 429 ou un 5xx laissent <strong>ignorer si l'écriture a abouti côté serveur</strong>.
 * Dans ce cas, l'appelant ne doit <strong>jamais</strong> rejouer une création à l'aveugle : il doit
 * d'abord réconcilier. Un 4xx de validation, lui, est un échec franc : rien n'a été écrit.</p>
 */
public class RestException extends RuntimeException {

    private final int status;
    private final boolean ambiguous;

    public RestException(String message, int status, boolean ambiguous) {
        super(message);
        this.status = status;
        this.ambiguous = ambiguous;
    }

    /** Code HTTP, ou 0 si l'appel n'a pas abouti du tout. */
    public int status() {
        return status;
    }

    /** L'écriture a-t-elle pu aboutir côté serveur malgré l'erreur ? */
    public boolean ambiguous() {
        return ambiguous;
    }
}
