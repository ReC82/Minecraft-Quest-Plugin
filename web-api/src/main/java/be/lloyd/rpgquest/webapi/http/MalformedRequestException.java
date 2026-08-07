package be.lloyd.rpgquest.webapi.http;

/** Levée par un handler ou {@link QueryParams} sur une requête malformée ; traduite en HTTP 400. */
public final class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }
}
