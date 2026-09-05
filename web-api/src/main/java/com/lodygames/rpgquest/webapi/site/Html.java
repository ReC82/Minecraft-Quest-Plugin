package com.lodygames.rpgquest.webapi.site;

/** Échappement HTML minimal — toutes les données du snapshot (pseudos, annonces...) passent par ici avant affichage. */
public final class Html {

    private Html() {
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&#39;");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }
}
