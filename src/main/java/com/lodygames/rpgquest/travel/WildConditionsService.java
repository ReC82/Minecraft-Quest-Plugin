package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.WildConditions;
import com.lodygames.rpgquest.travel.model.WildWeather;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.World;

/**
 * Lecture — et <strong>rien que</strong> la lecture — des conditions réelles du monde
 * d'exploration configuré ({@code travel.wild-world}), pour que le Garde du Hub puisse renseigner
 * un joueur avant son départ (issue #24). Aucune écriture : ni {@code World#setTime}, ni {@code
 * World#setStorm}, ni {@code World#setThundering} — le cycle jour/nuit et la météo du Wild ne sont
 * jamais touchés par ce service (contrairement au Hub, qui est volontairement figé par
 * {@code hub.HubWorldRulesService}).
 *
 * <p>Le monde est résolu à <em>chaque</em> appel via {@code worldLookup} (jamais mémorisé) : un
 * monde déchargé, renommé en configuration ou chargé plus tard donne simplement un relevé
 * indisponible, jamais une valeur périmée.</p>
 *
 * <p><strong>Jour/nuit</strong> : déduit de l'horloge du monde ({@code World#getTime()}, exprimée
 * en ticks dans le cycle de 24 000), avec les bornes historiques de {@code World#isDayTime()} —
 * {@value #NIGHT_START} à {@value #NIGHT_END}. Volontairement <em>indépendant</em> de la météo :
 * {@code World#isDayTime()} tient compte de l'orage (un orage de midi y est « la nuit »), ce qui
 * rendrait la phrase du Garde contradictoire avec l'heure réelle. Ici l'heure et la météo sont
 * rapportées comme deux faits distincts.</p>
 */
public final class WildConditionsService {

    /** Début de la nuit, en ticks du cycle de 24 000 — même borne que {@code World#isDayTime()}. */
    static final long NIGHT_START = 12_300L;
    /** Fin de la nuit (lever du jour), en ticks — même borne que {@code World#isDayTime()}. */
    static final long NIGHT_END = 23_850L;

    /** Réponse du Garde quand le Wild n'est pas (ou plus) chargé : jamais une valeur inventée. */
    public static final String UNKNOWN_DESCRIPTION = "Je n'ai pas de nouvelles du Wild pour le moment.";

    private final Function<String, Optional<World>> worldLookup;
    private final Supplier<String> wildWorld;

    public WildConditionsService(Function<String, Optional<World>> worldLookup, Supplier<String> wildWorld) {
        this.worldLookup = worldLookup;
        this.wildWorld = wildWorld;
    }

    /** {@code Optional.empty()} si le monde d'exploration n'est pas chargé — jamais de valeur par défaut inventée. */
    public Optional<WildConditions> current() {
        String worldName = wildWorld.get();
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }
        return worldLookup.apply(worldName).map(WildConditionsService::read);
    }

    /** Phrase prête à être lue par un PNJ, ou {@link #UNKNOWN_DESCRIPTION} si le Wild n'est pas chargé. */
    public String describe() {
        return current().map(WildConditions::description).orElse(UNKNOWN_DESCRIPTION);
    }

    private static WildConditions read(World world) {
        return new WildConditions(isDaytime(world.getTime()), weatherOf(world));
    }

    private static boolean isDaytime(long time) {
        long tick = Math.floorMod(time, 24_000L);
        return tick < NIGHT_START || tick > NIGHT_END;
    }

    private static WildWeather weatherOf(World world) {
        if (world.isThundering()) {
            return WildWeather.THUNDER; // l'orage englobe la pluie : jamais annoncé comme une simple averse.
        }
        return world.hasStorm() ? WildWeather.RAIN : WildWeather.CLEAR;
    }
}
