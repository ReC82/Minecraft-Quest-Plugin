package be.lloyd.rpgquest.store;

/** Réponse inattendue/illisible de web-api (mission point 10, "échec temporaire" : jamais fatal, voir {@code StoreDeliveryService}). */
public final class StoreClientException extends RuntimeException {

    public StoreClientException(String message) {
        super(message);
    }

    public StoreClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
