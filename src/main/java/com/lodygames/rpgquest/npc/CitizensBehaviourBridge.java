package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.citizensnpcs.Settings;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.waypoint.WanderWaypointProvider;
import net.citizensnpcs.trait.waypoint.Waypoint;
import net.citizensnpcs.trait.waypoint.WaypointProvider;
import net.citizensnpcs.trait.waypoint.Waypoints;
import org.bukkit.Location;

/**
 * Adaptateur des <strong>comportements</strong> Citizens pilotables depuis le panel : « regarder
 * les joueurs » ({@code lookclose}) et « promenade » (fournisseur {@code wander} du trait
 * {@code waypoints}). Issue #165.
 *
 * <p><strong>Pourquoi une classe séparée de {@link CitizensNpcBridge}.</strong> Les types utilisés
 * ici ({@code net.citizensnpcs.trait.LookClose}, {@code net.citizensnpcs.trait.waypoint.*}) vivent
 * dans l'artefact {@code citizens-main}, pas dans {@code citizensapi} auquel se limite le reste du
 * projet. Ce sont des classes du plugin Citizens lui-même : leur présence et leurs signatures
 * dépendent de la build réellement installée. Les isoler dans une classe à part garantit qu'une
 * build incompatible ne fasse échouer <em>que</em> ces deux options — le reste de l'intégration PNJ
 * (liaison, apparition, renommage, skin, déplacement) continue de fonctionner.</p>
 *
 * <p><strong>Compatibilité.</strong> Tous les appels passent par {@link #guarded} qui intercepte
 * {@link LinkageError} ({@code NoClassDefFoundError}, {@code NoSuchMethodError}) : si la build
 * installée ne fournit pas ce que l'on attend, l'opération renvoie un refus explicite nommant la
 * cause au lieu d'un succès trompeur ou d'une pile d'exception dans la console. C'est la même
 * discipline que {@code ops.ConsoleTap} pour Log4j. Aucune réflexion, aucune clé de persistance
 * interne : uniquement des accesseurs publics typés, documentés dans les sources Citizens.</p>
 *
 * <p>Référence vérifiée sur les sources {@code citizens-main} 2.0.43 (snapshot Maven du
 * 2026-09-11) : {@code LookClose#isEnabled/lookClose(boolean)/getRange/setRange}, et
 * {@code Waypoints#getCurrentProviderName/getCurrentProvider/setWaypointProvider} avec
 * {@code WanderWaypointProvider#setXYRange/addRegionCentre/removeRegionCentres/setDelay/setPathfind}.</p>
 *
 * <p>À appeler sur le <strong>thread principal</strong> : c'est l'API Citizens.</p>
 */
final class CitizensBehaviourBridge {

    /** Message unique en cas de build Citizens incompatible — jamais un succès silencieux. */
    private static final String INCOMPATIBLE = "Build Citizens incompatible : cette version n'expose pas "
            + "l'API attendue pour ce comportement. Les autres opérations PNJ restent disponibles.";

    // ------------------------------------------------------------------ Look Close

    /**
     * État réellement enregistré du trait {@code lookclose}.
     *
     * <p>Un PNJ qui n'a jamais reçu le trait n'en a pas : on <strong>ne l'ajoute pas pour le
     * lire</strong> (lire ne doit rien modifier). On rapporte alors les défauts effectifs de la
     * configuration Citizens installée, lus sur {@code Settings} — pas des constantes recopiées.</p>
     */
    Optional<LookCloseState> readLookClose(UUID uuid) {
        return guardedRead(() -> {
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
            if (npc == null) {
                return Optional.empty();
            }
            LookClose trait = npc.getTraitNullable(LookClose.class);
            if (trait == null) {
                return Optional.of(effectiveLookCloseDefaults());
            }
            return Optional.of(new LookCloseState(trait.isEnabled(), trait.getRange(),
                    trait.useRealisticLooking(), trait.disableWhileNavigating(), trait.targetNPCs()));
        });
    }

    /**
     * Pose un état <strong>explicite</strong>. Jamais {@code LookClose#toggle()} : rejouer une
     * requête (double clic, retry réseau, action rejouée par le cache d'idempotence) ne doit pas
     * inverser l'état obtenu. {@code range} nul laisse la portée inchangée.
     */
    NpcBehaviourOutcome applyLookClose(UUID uuid, boolean enabled, Double range) {
        return guarded(() -> {
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
            if (npc == null) {
                return NpcBehaviourOutcome.fail("CITIZENS_NOT_FOUND", "PNJ Citizens introuvable.");
            }
            LookClose trait = npc.getOrAddTrait(LookClose.class);
            trait.lookClose(enabled);
            if (range != null) {
                trait.setRange(range);
            }
            CitizensAPI.getNPCRegistry().saveToStore();
            // Relecture : on ne rapporte que ce que le trait porte vraiment après écriture.
            double applied = trait.getRange();
            boolean readBack = trait.isEnabled();
            if (readBack != enabled) {
                return NpcBehaviourOutcome.fail("LOOKCLOSE_NOT_APPLIED",
                        "Citizens n'a pas retenu l'état demandé pour « regarder les joueurs ».");
            }
            return NpcBehaviourOutcome.ok("LOOKCLOSE_SET", (enabled ? "Regard activé" : "Regard désactivé")
                    + " — portée " + trimmed(applied) + " bloc(s).");
        });
    }

    // ------------------------------------------------------------------ Wander

    /** État de promenade réellement enregistré, sans rien modifier. */
    Optional<WanderState> readWander(UUID uuid) {
        return guardedRead(() -> {
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
            if (npc == null) {
                return Optional.empty();
            }
            Waypoints trait = npc.getTraitNullable(Waypoints.class);
            if (trait == null) {
                // État initial de Citizens pour tout PNJ jamais configuré.
                return Optional.of(new WanderState(false, WanderChangePlanner.LINEAR, 0,
                        null, 0, 0, 0, 25, 3, -1, true));
            }
            String providerName = trait.getCurrentProviderName();
            WaypointProvider provider = trait.getCurrentProvider();
            if (provider instanceof WanderWaypointProvider wander) {
                List<Location> centres = wander.getRegionCentres();
                Location anchor = centres.isEmpty() ? null : centres.get(0);
                return Optional.of(new WanderState(true, providerName, 0,
                        anchor == null || anchor.getWorld() == null ? null : anchor.getWorld().getName(),
                        anchor == null ? 0 : anchor.getX(),
                        anchor == null ? 0 : anchor.getY(),
                        anchor == null ? 0 : anchor.getZ(),
                        wander.getXRange(), wander.getYRange(), wander.getDelay(), wander.isPathfind()));
            }
            return Optional.of(new WanderState(false, providerName, countWaypoints(provider),
                    null, 0, 0, 0, 25, 3, -1, true));
        });
    }

    /**
     * Active la promenade sur une zone <strong>bornée</strong> autour de {@code anchor}.
     *
     * <p>Sans ancre, {@code WanderWaypointProvider} ne transmet aucun arbre de régions à son objectif
     * et le PNJ erre sans limite : on exige donc toujours une ancre, et on la pose explicitement.</p>
     *
     * <p><strong>Ordre volontaire.</strong> {@code setXYRange} ne recalcule pas l'arbre de régions
     * dans Citizens 2.0.43 — seul {@code addRegionCentre}/{@code removeRegionCentres} le fait. On
     * règle donc la zone <em>avant</em> de poser l'ancre, sinon la zone effective resterait celle
     * d'avant (25×3 par défaut) alors que la fiche afficherait la nouvelle.</p>
     */
    NpcBehaviourOutcome applyWanderEnable(UUID uuid, Location anchor, int xRange, int yRange, boolean confirmReplace) {
        return guarded(() -> {
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
            if (npc == null) {
                return NpcBehaviourOutcome.fail("CITIZENS_NOT_FOUND", "PNJ Citizens introuvable.");
            }
            Waypoints trait = npc.getOrAddTrait(Waypoints.class);
            String providerName = trait.getCurrentProviderName();
            int waypoints = countWaypoints(trait.getCurrentProvider());
            WanderChangePlanner.Decision decision =
                    WanderChangePlanner.planEnable(providerName, waypoints, confirmReplace);
            if (decision == WanderChangePlanner.Decision.REQUIRES_CONFIRMATION) {
                return NpcBehaviourOutcome.fail("WANDER_CONFLICT", "Ce PNJ a déjà "
                        + WanderChangePlanner.conflictDescription(providerName, waypoints)
                        + ". Activer la promenade le remplacerait définitivement : confirmez pour continuer.");
            }
            if (decision != WanderChangePlanner.Decision.RECONFIGURE
                    && !trait.setWaypointProvider(WanderChangePlanner.WANDER)) {
                return NpcBehaviourOutcome.fail("WANDER_UNAVAILABLE",
                        "Citizens a refusé le mode promenade sur cette build.");
            }
            if (!(trait.getCurrentProvider() instanceof WanderWaypointProvider wander)) {
                return NpcBehaviourOutcome.fail("WANDER_UNAVAILABLE",
                        "Citizens n'a pas installé le mode promenade sur ce PNJ.");
            }
            wander.setXYRange(xRange, yRange);
            // Une seule ancre : on retire les précédentes par l'API qui recalcule la zone.
            List<Location> previous = new ArrayList<>(wander.getRegionCentres());
            if (!previous.isEmpty()) {
                wander.removeRegionCentres(previous);
            }
            wander.addRegionCentre(anchor);
            CitizensAPI.getNPCRegistry().saveToStore();

            // Relecture fidèle : on ne rapporte que ce que Citizens porte après écriture.
            if (!(trait.getCurrentProvider() instanceof WanderWaypointProvider after)
                    || after.getRegionCentres().isEmpty()) {
                return NpcBehaviourOutcome.fail("WANDER_NOT_APPLIED",
                        "Citizens n'a pas retenu la promenade demandée.");
            }
            String replaced = decision == WanderChangePlanner.Decision.REPLACE
                    ? " (a remplacé " + WanderChangePlanner.conflictDescription(providerName, waypoints) + ")"
                    : "";
            return NpcBehaviourOutcome.ok("WANDER_ENABLED", "Promenade active autour de "
                    + describe(anchor) + ", zone ±" + after.getXRange() + " blocs horizontalement et ±"
                    + after.getYRange() + " verticalement" + replaced + ".");
        });
    }

    /**
     * Désactive la promenade et revient au fournisseur neutre de Citizens ({@code linear} vide).
     *
     * <p>Ne touche <strong>jamais</strong> un fournisseur qui n'est pas la promenade : désactiver ne
     * doit pas devenir une façon d'effacer la patrouille configurée par quelqu'un d'autre.</p>
     *
     * <p>{@code Waypoints#setWaypointProvider} appelle {@code onRemove()} sur la promenade, qui met
     * son objectif en pause ; cette pause annule elle-même la navigation en cours
     * ({@code WanderGoal#pause} → {@code Navigator#cancelNavigation}). On annule en plus
     * explicitement si le PNJ navigue encore, pour que « désactivé » veuille dire « arrêté ».</p>
     */
    NpcBehaviourOutcome applyWanderDisable(UUID uuid) {
        return guarded(() -> {
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
            if (npc == null) {
                return NpcBehaviourOutcome.fail("CITIZENS_NOT_FOUND", "PNJ Citizens introuvable.");
            }
            Waypoints trait = npc.getOrAddTrait(Waypoints.class);
            String providerName = trait.getCurrentProviderName();
            if (WanderChangePlanner.planDisable(providerName) == WanderChangePlanner.Decision.NOT_WANDER) {
                return NpcBehaviourOutcome.fail("WANDER_NOT_ACTIVE", "La promenade n'est pas active sur ce PNJ ("
                        + WanderChangePlanner.conflictDescription(providerName,
                                countWaypoints(trait.getCurrentProvider()))
                        + ") : rien n'a été modifié.");
            }
            trait.setWaypointProvider(WanderChangePlanner.LINEAR);
            if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
            CitizensAPI.getNPCRegistry().saveToStore();
            if (trait.getCurrentProvider() instanceof WanderWaypointProvider) {
                return NpcBehaviourOutcome.fail("WANDER_NOT_APPLIED", "Citizens n'a pas retiré la promenade.");
            }
            Location now = npc.isSpawned() && npc.getEntity() != null
                    ? npc.getEntity().getLocation()
                    : npc.getStoredLocation();
            return NpcBehaviourOutcome.ok("WANDER_DISABLED", "Promenade désactivée, déplacement interrompu — "
                    + (now == null ? "position finale inconnue" : "position finale " + describe(now)) + ".");
        });
    }

    // ------------------------------------------------------------------ Outils

    /** Défauts effectifs de la configuration Citizens installée, jamais des constantes recopiées. */
    private static LookCloseState effectiveLookCloseDefaults() {
        return new LookCloseState(
                Settings.Setting.DEFAULT_LOOK_CLOSE.asBoolean(),
                Settings.Setting.DEFAULT_LOOK_CLOSE_RANGE.asDouble(),
                Settings.Setting.DEFAULT_REALISTIC_LOOKING.asBoolean(),
                Settings.Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.asBoolean(),
                false);
    }

    private static int countWaypoints(WaypointProvider provider) {
        if (!(provider instanceof WaypointProvider.EnumerableWaypointProvider enumerable)) {
            return 0;
        }
        int n = 0;
        for (Waypoint ignored : enumerable.waypoints()) {
            n++;
        }
        return n;
    }

    private static String describe(Location loc) {
        String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        return world + " " + trimmed(loc.getX()) + " / " + trimmed(loc.getY()) + " / " + trimmed(loc.getZ());
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** Toute écriture passe ici : une build incompatible produit un refus nommé, jamais un succès. */
    private static NpcBehaviourOutcome guarded(java.util.function.Supplier<NpcBehaviourOutcome> body) {
        try {
            return body.get();
        } catch (LinkageError e) {
            return NpcBehaviourOutcome.fail("CITIZENS_INCOMPATIBLE", INCOMPATIBLE + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Même garde pour les lectures : une build incompatible rend « inconnu », jamais une valeur inventée. */
    private static <T> Optional<T> guardedRead(java.util.function.Supplier<Optional<T>> body) {
        try {
            return body.get();
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }
}
