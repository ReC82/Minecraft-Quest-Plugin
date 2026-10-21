package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.dialogue.PlayerConditionEvaluator;
import com.lodygames.rpgquest.progression.RequiredLevelRule;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Vérification <strong>côté serveur</strong> des conditions d'accès d'un portail entre mondes
 * (issue #567, #228-P4) : niveau RPG ({@link RequiredLevelRule}, #532), conditions joueur ({@link
 * PlayerConditionEvaluator}, le même évaluateur que les dialogues) et délai entre deux passages
 * (table {@code portal_cooldowns}, clé {@code world:<id>} — un portail de zone ne peut pas porter
 * ce nom, son id n'admet pas « : »).
 *
 * <p>Une seule évaluation sert au franchissement et à la simulation PlugAdmin : la simulation
 * n'est qu'un appel sans téléportation ni écriture. Toutes les conditions sont évaluées (pas
 * d'arrêt au premier refus) pour que la simulation montre la liste complète ; le joueur refusé ne
 * reçoit que le premier message.</p>
 *
 * <p>{@link #evaluate} se lance sur le thread principal (inventaire, permissions, niveau en cache) ;
 * le future peut se terminer hors du thread principal (état de quête, variables, délai lus en
 * base). Une erreur de lecture fait échouer le future : l'appelant refuse le passage.</p>
 */
public final class PortalAccessChecker {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * Un contrôle : {@code code} = {@code LEVEL}, {@code CONDITION} ou {@code COOLDOWN}.
     *
     * @param refusal     message de refus MiniMessage tel que configuré
     * @param values      valeurs de ses jetons ({@code %level%}, {@code %seconds%}) — des jetons texte plutôt
     *                    que des balises MiniMessage, pour rester éditables dans le champ stylé du panel
     * @param refusalText le même message en texte simple, valeurs remplacées (simulation PlugAdmin)
     */
    public record Check(String code, String label, boolean passed, String refusal, Map<String, String> values,
                        String refusalText) {

        public Check {
            values = Map.copyOf(values);
        }
    }

    /** Verdict complet : autorisé si tous les contrôles passent. */
    public record Verdict(boolean allowed, List<Check> checks) {

        public Verdict {
            checks = List.copyOf(checks);
        }

        public Optional<Check> firstRefusal() {
            return checks.stream().filter(c -> !c.passed()).findFirst();
        }
    }

    /** Délais en cours, par joueur et par clé de portail. */
    public interface CooldownStore {

        CompletableFuture<Optional<Instant>> expiry(UUID playerId, String key);

        void record(UUID playerId, String key, Instant expiry);
    }

    private final PlayerConditionEvaluator evaluator;
    private final CooldownStore cooldowns;
    private final Clock clock;
    private volatile RequiredLevelRule levelRule = RequiredLevelRule.VANILLA;

    public PortalAccessChecker(PlayerConditionEvaluator evaluator, CooldownStore cooldowns, Clock clock) {
        this.evaluator = evaluator;
        this.cooldowns = cooldowns;
        this.clock = clock;
    }

    /** Règle partagée du niveau (#532), câblée par le bootstrap comme pour {@link PortalService}. */
    public void setRequiredLevelRule(RequiredLevelRule rule) {
        this.levelRule = Objects.requireNonNull(rule, "rule");
    }

    public static String cooldownKey(String portalId) {
        return "world:" + portalId;
    }

    /**
     * @param withCooldown {@code false} : le délai n'est ni lu ni montré (test de téléportation)
     */
    public CompletableFuture<Verdict> evaluate(Player player, WorldPortalDefinition portal, boolean withCooldown) {
        PortalAccess access = portal.access();
        List<CompletableFuture<Check>> checks = new ArrayList<>();
        if (access.requiredLevel() != null) {
            int required = access.requiredLevel();
            int level = levelRule.levelOf(player);
            checks.add(CompletableFuture.completedFuture(check("LEVEL", "Niveau RPG " + required + " minimum (actuel : "
                    + level + ")", level >= required, access.levelRefusal(), Map.of("level", String.valueOf(required)))));
        }
        for (PortalAccess.Rule rule : access.rules()) {
            checks.add(evaluator.evaluate(player, null, rule.condition()).thenApply(ok -> check("CONDITION",
                    PortalAccess.describe(rule.condition()), ok, portal.messages().refusalFor(rule), Map.of())));
        }
        if (withCooldown && access.cooldownSeconds() > 0) {
            checks.add(cooldowns.expiry(player.getUniqueId(), cooldownKey(portal.id())).thenApply(expiry -> {
                Instant now = clock.instant();
                long remaining = expiry.filter(now::isBefore).map(e -> Duration.between(now, e).toSeconds() + 1)
                        .orElse(0L);
                return check("COOLDOWN", "Délai de " + access.cooldownSeconds() + " s entre deux passages"
                                + (remaining > 0 ? " (reste " + remaining + " s)" : ""), remaining == 0,
                        access.cooldownRefusal(), Map.of("seconds", String.valueOf(remaining)));
            }));
        }
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenApply(v -> {
            List<Check> done = checks.stream().map(CompletableFuture::join).toList();
            return new Verdict(done.stream().allMatch(Check::passed), done);
        });
    }

    /** Passage réussi : démarre le délai du portail pour ce joueur (sans délai configuré : rien). */
    public void recordPassage(Player player, WorldPortalDefinition portal) {
        int seconds = portal.access().cooldownSeconds();
        if (seconds > 0) {
            cooldowns.record(player.getUniqueId(), cooldownKey(portal.id()), clock.instant().plusSeconds(seconds));
        }
    }

    /** Message de refus prêt à envoyer au joueur. */
    public static Component render(Check check) {
        return MM.deserialize(fill(check.refusal(), check.values()));
    }

    private static Check check(String code, String label, boolean passed, String refusal, Map<String, String> values) {
        String text;
        try {
            text = PlainTextComponentSerializer.plainText().serialize(MM.deserialize(fill(refusal, values)));
        } catch (RuntimeException e) {
            text = refusal;
        }
        return new Check(code, label, passed, refusal, values, text);
    }

    /** Remplace {@code %clé%} par sa valeur (des chiffres seulement : aucune balise ne peut être injectée). */
    private static String fill(String message, Map<String, String> values) {
        String out = message;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    /**
     * Délais conservés en base ({@code portal_cooldowns}) et en mémoire : la mémoire couvre
     * l'écriture asynchrone encore en cours, la base couvre un redémarrage.
     */
    public static CooldownStore persistent(PortalCooldownRepository repository, Logger logger) {
        Map<UUID, Map<String, Instant>> memory = new ConcurrentHashMap<>();
        return new CooldownStore() {
            @Override
            public CompletableFuture<Optional<Instant>> expiry(UUID playerId, String key) {
                Instant cached = memory.getOrDefault(playerId, Map.of()).get(key);
                return repository.allForPlayer(playerId).thenApply(all -> {
                    Instant stored = all.get(key);
                    if (stored == null) {
                        return Optional.ofNullable(cached);
                    }
                    return Optional.of(cached == null || stored.isAfter(cached) ? stored : cached);
                });
            }

            @Override
            public void record(UUID playerId, String key, Instant expiry) {
                memory.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(key, expiry);
                repository.setCooldown(playerId, key, expiry).exceptionally(error -> {
                    logger.error("Impossible d'enregistrer le délai du portail {} pour {}.", key, playerId, error);
                    return null;
                });
            }
        };
    }
}
