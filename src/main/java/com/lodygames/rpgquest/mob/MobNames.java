package com.lodygames.rpgquest.mob;

import java.util.function.Function;
import org.bukkit.NamespacedKey;

/**
 * Nom affiché (texte brut) d'un profil de mob à partir de son identifiant (issue #171), pour les
 * libellés d'objectif produits par du code statique — même relais que {@code city.CityNames}. Sans
 * registre branché (tests, démarrage), la partie courte de l'identifiant est rendue.
 */
public final class MobNames {

    private static volatile Function<NamespacedKey, String> resolver = MobNames::fallback;

    private MobNames() {
    }

    public static void use(SpecialMobRegistry registry) {
        resolver = id -> registry.find(id).map(def -> plain(def.displayName())).orElseGet(() -> fallback(id));
    }

    public static String of(NamespacedKey id) {
        if (id == null) {
            return "";
        }
        String name = resolver.apply(id);
        return name == null || name.isBlank() ? fallback(id) : name;
    }

    private static String fallback(NamespacedKey id) {
        return id.getKey().replace('_', ' ');
    }

    private static String plain(String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
