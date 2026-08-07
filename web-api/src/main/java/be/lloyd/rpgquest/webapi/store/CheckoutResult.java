package be.lloyd.rpgquest.webapi.store;

public record CheckoutResult(Outcome outcome, String redirectUrl) {

    public enum Outcome {
        CREATED, UNKNOWN_PRODUCT, INVALID_PLAYER_UUID
    }

    public static CheckoutResult created(String redirectUrl) {
        return new CheckoutResult(Outcome.CREATED, redirectUrl);
    }

    public static CheckoutResult unknownProduct() {
        return new CheckoutResult(Outcome.UNKNOWN_PRODUCT, null);
    }

    public static CheckoutResult invalidPlayerUuid() {
        return new CheckoutResult(Outcome.INVALID_PLAYER_UUID, null);
    }
}
