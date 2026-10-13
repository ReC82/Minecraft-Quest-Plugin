package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.CityConfig;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.CityDiscoveryRepository;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Découverte des villes en jeu (issue #178) : titre « Bienvenue à … » à la première entrée, et
 * enregistrement persistant (joueur, ville).
 *
 * <h2>Ce qui déclenche une vérification</h2>
 *
 * <p>Un changement de bloc horizontal (X ou Z) ou de monde, une téléportation, une connexion, une
 * réapparition, et tout changement de ville fait depuis le panel ou l'outil (création, déplacement,
 * agrandissement, réactivation). Changer d'altitude ou de regard ne déclenche rien. Chaque
 * vérification ne lit qu'une cellule de l'index (voir {@link CityIndex}).</p>
 *
 * <h2>Une seule annonce par ville et par joueur</h2>
 *
 * <p>L'annonce suit l'écriture en base, jamais l'inverse : le titre n'apparaît que si la ligne
 * (joueur, ville) vient réellement d'être insérée. Sortir puis revenir, mourir, se reconnecter ou
 * redémarrer le serveur ne la répète donc pas, et ne crée pas de seconde découverte.</p>
 *
 * <p>Rien ici n'est déclenché par le panel au nom d'un joueur : consulter la page Villes ne découvre
 * rien.</p>
 */
public final class CityDiscoveryService implements PluginService {

    private final RPGQuestPlugin plugin;
    private final CityRegistry cities;
    private final CityDiscoveryRepository repository;
    private final QuestMessagesService messages;
    private final Supplier<CityConfig> config;
    private final Clock clock;
    private final Logger logger;
    private final CityDiscoveryTracker tracker = new CityDiscoveryTracker();
    private final List<BiConsumer<Player, VillageCenter>> discoveryListeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<Player>> loadListeners = new CopyOnWriteArrayList<>();

    public CityDiscoveryService(RPGQuestPlugin plugin, CityRegistry cities, CityDiscoveryRepository repository,
                                QuestMessagesService messages, Supplier<CityConfig> config) {
        this.plugin = plugin;
        this.cities = cities;
        this.repository = repository;
        this.messages = messages;
        this.config = config;
        this.clock = Clock.systemUTC();
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        // Une ville créée, déplacée, agrandie ou réactivée sous les pieds d'un joueur doit pouvoir
        // être découverte sans qu'il ait à bouger d'un bloc.
        cities.onChange(this::recheckOnline);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            load(player);
        }
    }

    @Override
    public void stop() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            tracker.forget(player.getUniqueId());
        }
    }

    /** Appelé quand une PREMIÈRE découverte est enregistrée (objectifs de quête). */
    public void onFirstDiscovery(BiConsumer<Player, VillageCenter> listener) {
        discoveryListeners.add(listener);
    }

    /** Appelé quand le relevé d'un joueur est chargé (recalcul des objectifs cumulatifs). */
    public void onLoaded(java.util.function.Consumer<Player> listener) {
        loadListeners.add(listener);
    }

    public CityListener listener() {
        return new CityListener(this);
    }

    /** Relit les découvertes d'un joueur, puis vérifie sa position actuelle (cas de la connexion). */
    void load(Player player) {
        UUID playerId = player.getUniqueId();
        repository.discoveredBy(playerId).thenAccept(ids -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            tracker.loaded(playerId, ids);
            for (var listener : loadListeners) {
                listener.accept(player);
            }
            check(player, player.getLocation());
        })).exceptionally(error -> {
            logger.error("Impossible de lire les villes découvertes de {}", player.getName(), error);
            return null;
        });
    }

    void forget(Player player) {
        tracker.forget(player.getUniqueId());
    }

    /** Vérifie la position {@code at} de {@code player} ; découvre la ville retenue si nécessaire. */
    void check(Player player, Location at) {
        if (at == null || at.getWorld() == null || !config.get().discoveryEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<VillageCenter> found = tracker.observe(playerId, cities.index(),
                at.getWorld().getName(), at.getBlockX(), at.getBlockZ());
        if (found.isEmpty()) {
            return;
        }
        VillageCenter city = found.get();
        repository.insert(playerId, city.id(), clock.instant())
                .thenAccept(inserted -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!inserted) {
                        return; // déjà en base (relevé en retard) : rien à annoncer.
                    }
                    logger.info("city_discovered player={} city={} name=\"{}\"", player.getName(),
                            city.id(), city.name());
                    if (player.isOnline()) {
                        announce(player, cities.find(city.id()).orElse(city));
                        for (var listener : discoveryListeners) {
                            listener.accept(player, city);
                        }
                    }
                }))
                .exceptionally(error -> {
                    tracker.release(playerId, city.id());
                    logger.error("Impossible d'enregistrer la découverte de {} par {}", city.id(),
                            player.getName(), error);
                    return null;
                });
    }

    private void announce(Player player, VillageCenter city) {
        CityConfig c = config.get();
        var cityName = Placeholder.unparsed("city", city.name());
        player.showTitle(Title.title(
                messages.current().format("city.welcome-title", cityName),
                messages.current().format("city.welcome-subtitle", cityName),
                Title.Times.times(ticks(c.fadeInTicks()), ticks(c.stayTicks()), ticks(c.fadeOutTicks()))));
    }

    private void recheckOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            check(player, player.getLocation());
        }
    }

    /**
     * Nombre de villes <strong>existantes</strong> découvertes par ce joueur ; {@code cityId} non
     * nul restreint à cette ville (0 ou 1). Une ville désactivée reste comptée : la découverte est
     * acquise. Relevé non chargé : 0, et le recalcul est refait au chargement.
     */
    public int discoveredCount(UUID playerId, String cityId) {
        int count = tracker.count(playerId, id -> cities.find(id).isPresent()
                && (cityId == null || cityId.isBlank() || cityId.equals(id)));
        return Math.max(0, count);
    }

    public CompletableFuture<Map<String, Integer>> discoveriesByCity() {
        return repository.countByCity();
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }
}
