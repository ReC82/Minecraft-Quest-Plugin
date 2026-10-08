package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSite;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * L'orchestration de la saisie du nom dans l'enclume (issue #227).
 *
 * <h2>Le contrat, en une phrase</h2>
 *
 * <p><strong>Seul un clic sur le résultat crée quelque chose.</strong> Fermer la fenêtre, se
 * déconnecter, laisser expirer, ou ne rien taper : aucune écriture, aucun identifiant consommé.</p>
 *
 * <h2>Le piège de la fermeture, et comment il est évité</h2>
 *
 * <p>Valider implique de fermer la fenêtre — donc {@code InventoryCloseEvent} se déclenche
 * <em>aussi</em> après un succès. Si la fermeture annulait aveuglément, chaque création s'annoncerait
 * « annulée » juste après avoir réussi. La demande est donc <strong>retirée du registre</strong>
 * ({@code take}) au moment où l'on confirme : la fermeture qui suit ne trouve plus rien et se tait.
 * C'est la même mécanique qui rend un double clic inoffensif — le second ne trouve plus de demande à
 * confirmer.</p>
 *
 * <h2>Pourquoi ce listener ne décide rien</h2>
 *
 * <p>L'expiration, la validité du nom et le doublon d'ancre sont tranchés par
 * {@link BuildingSiteService#confirm} et {@link PendingBuildingSiteRegistry}, sans Bukkit. Ici il ne
 * reste que la plomberie d'événements : ce qui est vérifiable l'est ailleurs, et ce qui ne l'est pas
 * (le rendu chez le client) tient en quelques lignes.</p>
 */
public final class BuildingSiteNameListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BuildingSiteService service;
    private final PendingBuildingSiteRegistry pendings;

    public BuildingSiteNameListener(BuildingSiteService service,
                                    PendingBuildingSiteRegistry pendings) {
        this.service = service;
        this.pendings = pendings;
    }

    /**
     * Force un objet de résultat cliquable et un coût nul.
     *
     * <p>Sans cela, l'enclume vanilla ne propose un résultat que si le nom diffère de l'original —
     * et elle réclame des niveaux. Le joueur se retrouverait avec un bouton inerte sans comprendre
     * pourquoi.</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent event) {
        Inventory top = event.getInventory();
        if (!BuildingSiteNamePrompt.isPromptItem(top.getItem(0))) {
            return;
        }
        event.setResult(BuildingSiteNamePrompt.resultItem(BuildingSiteNamePrompt.typedName(top)));
        event.getView().setRepairCost(0);
        event.getView().setMaximumRepairCost(0);
    }

    /** Le clic sur le résultat : le seul geste qui écrit. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!BuildingSiteNamePrompt.isPromptItem(top.getItem(0))) {
            return;
        }
        // Notre fenêtre : aucun objet n'y entre ni n'en sort, quel que soit le clic.
        event.setCancelled(true);
        if (event.getRawSlot() != BuildingSiteNamePrompt.RESULT_SLOT) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String typed = BuildingSiteNamePrompt.typedName(top);
        String playerKey = player.getUniqueId().toString();

        // take() retire la demande : la fermeture qui suit se taira, et un second clic ne trouvera
        // plus rien à confirmer.
        var pending = pendings.take(playerKey).orElse(null);
        if (pending == null) {
            player.closeInventory();
            player.sendMessage(MM.deserialize(
                    "<red>Demande expirée ou déjà traitée :</red> <gray>rien n'a été enregistré. "
                            + "Recliquez avec l'outil.</gray>"));
            return;
        }
        // Le nom est validé AVANT de fermer : un refus doit laisser le joueur corriger sa saisie,
        // pas le renvoyer cliquer à nouveau dans le monde.
        BuildingSiteName.Checked checked = BuildingSiteName.check(typed);
        if (!checked.ok()) {
            // La demande a été retirée par take() : on la remet, puisqu'elle reste valable.
            pendings.open(playerKey, pending.world(), pending.anchor(), pending.facing(),
                    pending.clickedX(), pending.clickedY(), pending.clickedZ());
            player.sendMessage(MM.deserialize("<red><error></red>",
                    Placeholder.unparsed("error", checked.error())));
            return;
        }

        player.closeInventory();
        service.confirm(pending, checked.name(), player.getName())
                .thenAccept(result -> announce(player, result))
                .exceptionally(error -> {
                    player.sendMessage(MM.deserialize(
                            "<red>Création impossible :</red> <white><cause></white>"
                                    + "<gray> — rien n'a été enregistré.</gray>",
                            Placeholder.unparsed("cause", rootName(error))));
                    return null;
                });
    }

    /**
     * Fermer la fenêtre annule la demande. C'est le comportement attendu d'un missclick : on referme,
     * et il ne reste rien.
     *
     * <p>Après un succès, la demande a déjà été retirée : il n'y a rien à annoncer, et ce silence est
     * voulu.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (!BuildingSiteNamePrompt.isPromptItem(event.getView().getTopInventory().getItem(0))) {
            return;
        }
        HumanEntity who = event.getPlayer();
        if (!(who instanceof Player player)) {
            return;
        }
        String playerKey = player.getUniqueId().toString();
        if (!pendings.hadPending(playerKey)) {
            return;
        }
        pendings.cancel(playerKey);
        player.sendMessage(MM.deserialize(
                "<gray>Création annulée : <white>aucun emplacement n'a été créé</white>.</gray>"));
    }

    /** Déconnexion : la demande est abandonnée, et elle n'avait rien écrit. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendings.cancel(event.getPlayer().getUniqueId().toString());
    }

    /**
     * Le message de succès. Il énonce le nom, l'identifiant, le monde, la position et l'orientation —
     * dans cet ordre, parce que c'est l'ordre dans lequel l'administrateur va les vérifier.
     */
    private void announce(Player player, BuildingSiteService.CreateResult result) {
        switch (result.outcome()) {
            case CREATED -> {
                BuildingSite site = result.site();
                player.sendMessage(MM.deserialize(
                        "<green>Emplacement créé :</green>"
                                + "<newline><white><name></white>"
                                + "<newline><gray><id></gray>"
                                + "<newline><gray><world></gray>"
                                + "<newline><gray><pos></gray>"
                                + "<newline><gray>Orientation : <facing></gray>",
                        Placeholder.unparsed("name", site.name()),
                        Placeholder.unparsed("id", site.id()),
                        Placeholder.unparsed("world", site.world()),
                        Placeholder.unparsed("pos", site.positionLabel()),
                        Placeholder.unparsed("facing", site.facing().name())));
            }
            case ALREADY_THERE -> player.sendMessage(MM.deserialize(
                    "<yellow>Un emplacement existe déjà ici :</yellow> <white><id></white> "
                            + "<gray>(« <name> »). Rien n'a été créé.</gray>",
                    Placeholder.unparsed("id", result.site().id()),
                    Placeholder.unparsed("name", result.site().name())));
            case EXPIRED, INVALID_NAME -> player.sendMessage(MM.deserialize(
                    "<red><error></red> <gray>Rien n'a été enregistré.</gray>",
                    Placeholder.unparsed("error",
                            result.error() == null ? "Création refusée." : result.error())));
            case DEBOUNCED -> {
                // Impossible par ce chemin : l'anti-rebond appartient au clic dans le monde.
            }
        }
    }

    private static String rootName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
