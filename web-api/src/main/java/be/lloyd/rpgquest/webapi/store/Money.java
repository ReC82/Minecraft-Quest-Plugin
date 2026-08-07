package be.lloyd.rpgquest.webapi.store;

import java.util.Locale;

public final class Money {

    private Money() {
    }

    public static String format(long amountCents, String currency) {
        return String.format(Locale.ROOT, "%.2f %s", amountCents / 100.0, currency);
    }
}
