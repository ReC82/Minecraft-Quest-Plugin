package com.lodygames.rpgquest.npc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texture de skin <strong>signée par Mojang</strong>, telle que MineSkin la publie : la valeur
 * (JSON encodé en Base64 qui pointe vers {@code textures.minecraft.net}) et sa signature.
 *
 * <p>C'est ce couple, et non un lien {@code minesk.in}, que Citizens sait poser sur un PNJ joueur.
 * Un lien {@code minesk.in} renvoie vers une page web : le passer à {@code /npc skin --url} (qui
 * attend une image) échoue en tâche de fond, et le PNJ garde l'apparence du compte Minecraft qui
 * porte son nom. C'est exactement le défaut constaté sur Lucien (issue #325).</p>
 *
 * <p>{@link #skinHash()} est l'empreinte de l'image ({@code textures.minecraft.net/texture/<hash>}) :
 * elle identifie le skin quel que soit le compte qui l'a signé, ce qui permet de comparer ce que
 * porte réellement le PNJ avec ce qui a été demandé.</p>
 */
public record MineSkinTexture(String value, String signature) {

    /**
     * Texture de l'apparence par défaut ({@link NpcIdentityService#DEFAULT_SKIN_URL}), relue sur
     * {@code api.mineskin.org/v2/skins/f534ead9d5a3413e9bb51bce9b211995} le 2026-10-13. Embarquée
     * pour que l'apparence par défaut ne dépende d'aucun appel réseau : une signature Mojang ne
     * périme pas. Le test {@code MineSkinTextureTest} vérifie qu'elle désigne bien l'image attendue.
     */
    public static final MineSkinTexture DEFAULT = new MineSkinTexture(
            "ewogICJ0aW1lc3RhbXAiIDogMTc2ODg3NTAxNDI2OSwKICAicHJvZmlsZUlkIiA6ICI1NzI1YWJkMzY4MGI0MWVl" +
            "OWJjMzhhNmUyM2M5OGNiMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJBYmVuZHJlZ2VuXyIsCiAgInNpZ25hdHVyZVJl" +
            "cXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0" +
            "cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8xYTYwMDcwNjk3NjdhZGM3YTQyNDM4MjhkZjFmNjkw" +
            "YzQ3NGU2YmRmN2ZlOGNjMWY4ZjQxNmEzMzhjYmUzMDlmIgogICAgfQogIH0KfQ==",
            "EUoF1AChqk1EQvKKe0ioErvaPb+4qSd5KbdB+bwG5lxBAwJSBm0M79SBb55H0U+qIkoJFOcnJELQBZL1YM/p0yZA" +
            "mAI85aAyINF+fgWHTYV2M0gENg2O1RReqJKj+1EBQ7HF3hZ7dPiUSLRi2MlS9jF7m1sOFhqnE/jdmaxBL84Qyy39" +
            "HwxsXeuFAuQzZ8PnMSvBwBINY5nRx7LOnGepr8hnYFwVgWLVQu5NZIlfk37jpWzf+ZH042QEHjSqYhLYbehcNnSD" +
            "DviJnUfq/soszL/wjs4tVMO1cK4yg5tpBwWyuQTlIpbpgsviQEItaX2xhixM5pbObjOSz0t0kXIeJolHgjMRm8eA" +
            "gCrlR7T9EcWkjEAhJe/760qjSWhCWEb52KVb2dQCRkwL2cJYWjHptFGO6ZEFp2MbQw4pQkYCoFvHtTvbTyLUFNOA" +
            "8gUmwhQANzoU6laXWN5htEJCRg9/Um/7tKlxvkdgc3m8+v73dm+uWxAPuOxk6ikEfXleJ5QnB7UBEA7FTdNLt3TN" +
            "bVgsB8exQ5vnIdfbJCViizTY6VeigU5Jr8haTi6u2ijVJjxEsQIMQ3PelmAoEN9kInDxZXSVWIFQvZpsYbMg9og7" +
            "PTj2kc57UyLJQI+pFoS/WJuvN9CPx9DMvAXtvKj0a3uHPZCl7AwyaM/E6GMK1sUdkMs=");

    /** Empreinte de l'image de l'apparence par défaut. */
    public static final String DEFAULT_SKIN_HASH =
            "1a6007069767adc7a4243828df1f690c474e6bdf7fe8cc1f8f416a338cbe309f";

    private static final Pattern SKIN_URL = Pattern.compile(
            "\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"https?://textures\\.minecraft\\.net/texture/([0-9a-fA-F]+)\"");

    public MineSkinTexture {
        if (value == null || value.isBlank() || signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("texture et signature obligatoires");
        }
    }

    /** Empreinte de l'image désignée par la valeur, ou vide si la valeur est illisible. */
    public Optional<String> skinHash() {
        return hashOf(value);
    }

    /** Empreinte de l'image désignée par une valeur de texture Base64, ou vide. */
    public static Optional<String> hashOf(String textureValue) {
        if (textureValue == null || textureValue.isBlank()) {
            return Optional.empty();
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(textureValue.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Matcher m = SKIN_URL.matcher(json);
        return m.find() ? Optional.of(m.group(1).toLowerCase(java.util.Locale.ROOT)) : Optional.empty();
    }
}
