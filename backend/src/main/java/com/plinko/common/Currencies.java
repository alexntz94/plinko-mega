package com.plinko.common;

import java.util.Currency;
import java.util.Locale;

public final class Currencies {

    /**
     * Currencies the platform can be set to. The rule (review 7.9): only currencies with exactly 2 decimal places, as
     * every amount in the game is rounded, signed and sent to the PAM with 2 decimals. Offering JPY or KRW (0) or KWD,
     * BHD (3) would need the decimals to come from the currency everywhere: rounding, round signatures, the PAM contract
     * and the game's display.
     */
    public static final java.util.List<String> OFFERED = java.util.List.of("USD", "EUR", "GBP", "CAD", "AUD", "NZD", "CHF", "SEK", "NOK",
            "DKK", "PLN", "RON", "CZK", "HUF", "BRL", "MXN", "ZAR", "TRY", "INR");

    static {
        OFFERED.forEach(code -> {
            if (Currency.getInstance(code).getDefaultFractionDigits() != 2) {
                throw new ExceptionInInitializerError(code + " does not have 2 decimal places");
            }
        });
    }

    private Currencies() {
    }

    /** "USD – US Dollar ($)" for menus. */
    public static String label(String code) {
        try {
            Currency currency = Currency.getInstance(code);
            String symbol = symbol(code).trim();
            return code + " – " + currency.getDisplayName(Locale.US) + (symbol.equals(code) ? "" : " (" + symbol + ")");
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** "€", "$", "£"; currencies without a symbol get their code and a space, e.g. "RON ". */
    public static String symbol(String code) {
        try {
            String symbol = Currency.getInstance(code).getSymbol(Locale.US);
            return symbol.length() == 1 ? symbol : code + " ";
        } catch (IllegalArgumentException e) {
            return code + " ";
        }
    }

    /** A currency the game can work in: a real ISO code with 2 decimal places (never JPY, KRW, KWD and the like). */
    public static boolean isValid(String code) {
        try {
            return Currency.getInstance(code).getDefaultFractionDigits() == 2;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
