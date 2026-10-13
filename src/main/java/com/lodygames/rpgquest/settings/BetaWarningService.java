package com.lodygames.rpgquest.settings;

import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.travel.WildEntryPrompt;
import com.lodygames.rpgquest.travel.WildEntryPromptButton;
import com.lodygames.rpgquest.travel.WildEntryPromptPresenter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Avertissement « serveur en version bêta » à chaque connexion (issue #28), activé et rédigé depuis
 * PlugAdmin > Paramètres généraux > Connexion (issue #335).
 *
 * <ul>
 *   <li>« OK » : le joueur joue, le message reviendra à la prochaine connexion.</li>
 *   <li>« OK et ne plus afficher » : préférence propre au joueur (variable {@value #HIDDEN_KEY},
 *       par UUID), qui survit aux déconnexions et aux redémarrages.</li>
 *   <li>La désactivation globale n'efface aucune préférence : la réactiver retrouve les choix de
 *       chacun. Un reset « nouveau joueur » efface les variables, donc l'avertissement revient.</li>
 *   <li>Une seule fenêtre par connexion (jamais rouverte par un respawn ou un second événement),
 *       affichée après un court délai pour ne pas se superposer à l'arrivée au Hub. Une erreur ne
 *       bloque jamais la connexion : au pire, le message n'apparaît pas.</li>
 * </ul>
 */
public final class BetaWarningService implements Listener {

    static final String HIDDEN_KEY = "BETA_WARNING_HIDDEN";
    static final String OK_LABEL = "OK";
    static final String HIDE_LABEL = "OK et ne plus afficher";
    /** Trois secondes : l'arrivée au Hub et ses messages passent d'abord. */
    static final long DELAY_TICKS = 60L;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final PlayerVariableRepository variables;
    private final Supplier<GameSettings> settings;
    private final WildEntryPromptPresenter presenter;
    private final Set<UUID> shownThisSession = ConcurrentHashMap.newKeySet();

    public BetaWarningService(JavaPlugin plugin, PlayerVariableRepository variables, Supplier<GameSettings> settings,
                              WildEntryPromptPresenter presenter) {
        this.plugin = plugin;
        this.variables = variables;
        this.settings = settings;
        this.presenter = presenter;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!settings.get().betaWarningEnabled() || !shownThisSession.add(id)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> check(id), DELAY_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shownThisSession.remove(event.getPlayer().getUniqueId());
    }

    private void check(UUID id) {
        if (online(id).isEmpty() || !settings.get().betaWarningEnabled()) {
            return;
        }
        variables.get(id, HIDDEN_KEY).whenComplete((value, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("Avertissement bêta : préférence illisible pour {} ({}) — affiché.",
                        id, error.toString());
            }
            boolean hidden = error == null && value.map("true"::equalsIgnoreCase).orElse(false);
            if (!hidden) {
                plugin.getServer().getScheduler().runTask(plugin, () -> show(id));
            }
        });
    }

    private void show(UUID id) {
        Optional<Player> player = online(id);
        if (player.isEmpty()) {
            return;
        }
        try {
            presenter.present(player.get(), prompt(id, settings.get().betaWarningText()));
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Avertissement bêta : affichage impossible pour {}", id, e);
        }
    }

    WildEntryPrompt prompt(UUID id, String text) {
        return new WildEntryPrompt(
                MM.deserialize("<gold><bold>Serveur en version bêta</bold></gold>"),
                List.of(Component.text(text)),
                List.of(new WildEntryPromptButton(MM.deserialize("<green>" + OK_LABEL + "</green>"), () -> { }),
                        new WildEntryPromptButton(MM.deserialize("<yellow>" + HIDE_LABEL + "</yellow>"), () -> hide(id))));
    }

    void hide(UUID id) {
        variables.set(id, HIDDEN_KEY, "true").whenComplete((ok, error) -> plugin.getServer().getScheduler()
                .runTask(plugin, () -> online(id).ifPresent(p -> p.sendMessage(error == null
                        ? MM.deserialize("<gray>C'est noté : ce message ne s'affichera plus.</gray>")
                        : MM.deserialize("<red>Ton choix n'a pas pu être enregistré : le message reviendra à la "
                                + "prochaine connexion.</red>")))));
    }

    private Optional<Player> online(UUID id) {
        return Optional.ofNullable(plugin.getServer().getPlayer(id));
    }
}
