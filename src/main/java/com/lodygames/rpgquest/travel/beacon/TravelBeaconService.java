package com.lodygames.rpgquest.travel.beacon;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.database.TravelBeaconRepository;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.travel.beacon.model.TravelBeacon;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Réseau de voyage (issues #132/#150) : borne physique (même support qu'un waypoint, bloc de
 * diamant + bouton en bois) ouvrant un menu graphique paginé vers les waypoints déjà découverts
 * par le joueur. Système <strong>strictement distinct</strong> de {@code waypoint}/{@code
 * waystone} : une borne n'est jamais une destination, et cette classe ne fait jamais la moindre
 * fusion d'identité/table avec ces systèmes — elle se contente de <em>lire</em>
 * {@link WaypointService#discoveredBy}/{@link WaypointService#hasActivelyDiscovered}.
 *
 * <p>Catégories « Mon claim » et « Villages » (issue #151) : « Mon claim » résout le claim courant
 * du joueur via {@link ClaimService#mainClaimOf} (jamais une coordonnée copiée), revalidé à chaque
 * départ ; « Villages » liste des centres administrés ({@link VillageCenter}, table dédiée
 * {@code village_centers}, identité indépendante du monde — plusieurs centres peuvent coexister
 * dans {@code world_hub}). Aucune des deux catégories n'ajoute de condition de découverte : un
 * claim existant ou un centre actif est toujours proposé tel quel.</p>
 *
 * <p>Génération automatique de bornes/waypoints dans le Hub (issue #149) hors périmètre : la seule
 * façon de créer une borne ici est {@link #placeAt(Player, Location)}, déclenchée par
 * {@code /rpgadmin travel beacon set}. Aucun chemin de ce service ne crée jamais de waypoint.</p>
 */
public final class TravelBeaconService implements PluginService {

    static final int VERSION = 1;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;

    private final RPGQuestPlugin plugin;
    private final TravelBeaconRepository repository;
    private final WaypointService waypointService;
    private final ClaimService claimService;
    private final VillageCenterRepository villageCenterRepository;
    private final Logger logger;

    private final ConcurrentHashMap<String, TravelBeacon> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TravelBeacon> byInteractorBlock = new ConcurrentHashMap<>();
    private final java.util.Set<String> protectedBlocks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, BeaconMenuSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VillageCenter> villagesById = new ConcurrentHashMap<>();

    public TravelBeaconService(RPGQuestPlugin plugin, TravelBeaconRepository repository, WaypointService waypointService,
                                ClaimService claimService, VillageCenterRepository villageCenterRepository) {
        this.plugin = plugin;
        this.repository = repository;
        this.waypointService = waypointService;
        this.claimService = claimService;
        this.villageCenterRepository = villageCenterRepository;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        repository.loadAll().thenAccept(list -> runSync(() -> {
            for (TravelBeacon beacon : list) {
                index(beacon);
            }
            logger.info("Bornes de voyage chargées : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les bornes de voyage persistées.", error);
            return null;
        });
        villageCenterRepository.loadAll().thenAccept(list -> runSync(() -> {
            for (VillageCenter center : list) {
                villagesById.put(center.id(), center);
            }
            logger.info("Centres de village chargés : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les centres de village persistés.", error);
            return null;
        });
    }

    @Override
    public void stop() {
        byId.clear();
        byInteractorBlock.clear();
        protectedBlocks.clear();
        sessions.clear();
        villagesById.clear();
    }

    public Listener listener() {
        return new TravelBeaconListener(this);
    }

    public Listener protectionListener() {
        return new TravelBeaconProtectionListener(this::isProtectedBlock);
    }

    public boolean isProtectedBlock(String world, int x, int y, int z) {
        return protectedBlocks.contains(blockKey(world, x, y, z));
    }

    public List<TravelBeacon> all() {
        return List.copyOf(byId.values());
    }

    // ---- Placement administrateur ------------------------------------------------------------

    /**
     * Place une borne à la colonne (x, z) de {@code origin} (sans inventer de coordonnées : celles
     * du joueur qui exécute {@code /rpgadmin travel beacon set}), orientée selon son regard actuel.
     * Idempotent : rejouer exactement à la même ancre ne crée jamais de doublon.
     *
     * @return un message d'erreur si le placement a échoué, sinon {@link Optional#empty()} (succès).
     */
    public Optional<String> placeAt(Player admin, Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return Optional.of("Monde introuvable.");
        }
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        // Vérifié AVANT de recalculer une hauteur de sol : une fois la structure posée, la colonne
        // elle-même est plus haute (le bloc de diamant), donc rejouer la commande à la même colonne
        // recalculerait une ancre différente (un niveau plus haut) si on ne coupait pas court ici.
        for (TravelBeacon existing : byId.values()) {
            if (existing.world().equals(world.getName()) && existing.x() == originX && existing.z() == originZ) {
                return Optional.of("Une borne existe déjà à cet endroit.");
            }
        }
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, originX, originZ);
        if (safe.isEmpty()) {
            return Optional.of("Aucun emplacement sûr trouvé à cette colonne — déplace-toi légèrement et réessaie.");
        }
        int anchorX = safe.get().getBlockX();
        int anchorY = safe.get().getBlockY();
        int anchorZ = safe.get().getBlockZ();
        BlockFace facing = cardinalFacing(admin.getLocation().getYaw());

        String id = "beacon_" + world.getName().toLowerCase(Locale.ROOT) + "_" + anchorX + "_" + anchorY + "_" + anchorZ;
        if (!areaFree(world, anchorX, anchorY, anchorZ, facing)) {
            return Optional.of("Zone non libre (construction existante) — déplace-toi et réessaie.");
        }

        place(world, anchorX, anchorY, anchorZ, facing);
        TravelBeacon beacon = new TravelBeacon(id, world.getName(), anchorX, anchorY, anchorZ,
                facing.name(), VERSION, true, Instant.now());
        repository.insertIfAbsent(beacon).thenAccept(inserted -> runSync(() -> {
            if (inserted) {
                index(beacon);
                logger.info("Borne de voyage « {} » placée en {} ({},{},{}).",
                        beacon.id(), world.getName(), anchorX, anchorY, anchorZ);
            }
        })).exceptionally(error -> {
            logger.error("Impossible de persister la borne de voyage {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    // ---- Interaction bouton -------------------------------------------------------------------

    /** @return {@code true} si {@code block} est l'interacteur d'une borne (l'événement doit alors être annulé). */
    boolean handleButtonInteract(Player player, Block block) {
        TravelBeacon beacon = byInteractorBlock.get(blockKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (beacon == null || !beacon.active()) {
            return false;
        }
        openRoot(player);
        return true;
    }

    // ---- Menu : racine --------------------------------------------------------------------

    void openRoot(Player player) {
        Inventory inventory = createMenu(BeaconMenuHolder.Kind.ROOT, 9, "Réseau de voyage");
        inventory.setItem(2, icon(Material.ENDER_EYE, "<gold>Waypoints découverts</gold>",
                "<gray>Tes repères découverts dans le Wild / le Hub.</gray>"));
        boolean hasClaim = claimService.mainClaimOf(player.getUniqueId()).isPresent();
        inventory.setItem(4, hasClaim
                ? icon(Material.GRASS_BLOCK, "<yellow>Mon claim</yellow>", "<gray>Clique pour y voyager.</gray>")
                : icon(Material.BARRIER, "<yellow>Mon claim</yellow>", "<gray>Tu n'as pas encore de claim.</gray>"));
        inventory.setItem(6, icon(Material.BELL, "<yellow>Villages</yellow>",
                "<gray>Centres de village configurés.</gray>"));
        sessions.put(player.getUniqueId(), new BeaconMenuSession(0, ""));
        player.openInventory(inventory);
    }

    /** Revalidation fraîche à chaque clic (jamais l'état de l'icône au moment de l'ouverture). */
    void handleRootCategoryClick(Player player, int slot) {
        switch (slot) {
            case 2 -> openWaypoints(player, 0, "");
            case 4 -> travelToClaim(player);
            case 6 -> openVillages(player, 0);
            default -> { }
        }
    }

    // ---- Menu/voyage : Mon claim (issue #151) ----------------------------------------------

    void travelToClaim(Player player) {
        Optional<Claim> claimOpt = claimService.mainClaimOf(player.getUniqueId());
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu n'as pas (ou plus) de claim.</red>"));
            return;
        }
        Claim claim = claimOpt.get();
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de ton claim n'est pas chargé.</red>"));
            return;
        }
        int centerX = (claim.minX() + claim.maxX()) / 2;
        int centerZ = (claim.minZ() + claim.maxZ()) / 2;
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, centerX, centerZ);
        if (safe.isEmpty() || !claim.contains(world.getName(), safe.get().getBlockX(),
                safe.get().getBlockY(), safe.get().getBlockZ())) {
            player.sendMessage(MM.deserialize(
                    "<red>Arrivée dangereuse ou indisponible sur ton claim — réessaie plus tard.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(safe.get());
        player.sendMessage(MM.deserialize("<gold>Voyage vers ton claim.</gold>"));
    }

    // ---- Menu : Villages (issue #151) -----------------------------------------------------

    void openVillages(Player player, int page) {
        List<VillageCenter> sorted = villagesById.values().stream()
                .filter(VillageCenter::active)
                .sorted(Comparator.comparing(VillageCenter::name))
                .toList();

        int pageCount = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, pageCount - 1));
        int from = Math.min(sorted.size(), clampedPage * PAGE_SIZE);
        int to = Math.min(sorted.size(), from + PAGE_SIZE);
        List<VillageCenter> pageItems = sorted.subList(from, to);

        Inventory inventory = createMenu(BeaconMenuHolder.Kind.VILLAGES, 54, "Villages");
        if (pageItems.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, "<red>Aucun village configuré</red>",
                    "<gray>Demande à un administrateur d'en poser un.</gray>"));
        } else {
            for (int i = 0; i < pageItems.size(); i++) {
                VillageCenter center = pageItems.get(i);
                inventory.setItem(i, icon(Material.BELL, "<gold>" + center.name() + "</gold>",
                        "<gray>Monde :</gray> <white>" + center.world() + "</white>",
                        "<gray>Clique pour voyager.</gray>"));
            }
        }
        inventory.setItem(45, icon(Material.ARROW, "<yellow>Retour</yellow>", ""));
        if (clampedPage > 0) {
            inventory.setItem(48, icon(Material.SPECTRAL_ARROW, "<yellow>Page précédente</yellow>", ""));
        }
        inventory.setItem(49, icon(Material.PAPER, "<white>Page " + (clampedPage + 1) + " / " + pageCount + "</white>", ""));
        if (clampedPage < pageCount - 1) {
            inventory.setItem(50, icon(Material.SPECTRAL_ARROW, "<yellow>Page suivante</yellow>", ""));
        }
        inventory.setItem(53, icon(Material.BARRIER, "<red>Fermer</red>", ""));

        sessions.put(player.getUniqueId(), new BeaconMenuSession(clampedPage, ""));
        player.openInventory(inventory);
    }

    void handleVillagesClick(Player player, int slot, BeaconMenuSession session) {
        switch (slot) {
            case 45 -> openRoot(player);
            case 48 -> openVillages(player, session.page() - 1);
            case 50 -> openVillages(player, session.page() + 1);
            case 53 -> player.closeInventory();
            default -> {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    selectVillageAtSlot(player, session, slot);
                }
            }
        }
    }

    private void selectVillageAtSlot(Player player, BeaconMenuSession session, int slot) {
        List<VillageCenter> sorted = villagesById.values().stream()
                .filter(VillageCenter::active)
                .sorted(Comparator.comparing(VillageCenter::name))
                .toList();
        int index = session.page() * PAGE_SIZE + slot;
        if (index < 0 || index >= sorted.size()) {
            return; // clic périmé (page changée entre-temps) : ignoré.
        }
        travelToVillage(player, sorted.get(index).id());
    }

    /** Revalidation stricte (existence + actif) avant tout déplacement. */
    void travelToVillage(Player player, String villageId) {
        VillageCenter center = villagesById.get(villageId);
        if (center == null || !center.active()) {
            player.sendMessage(MM.deserialize("<red>Ce village n'est plus disponible.</red>"));
            return;
        }
        World world = plugin.getServer().getWorld(center.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de ce village n'est pas chargé.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(new Location(world, center.x(), center.y(), center.z(), center.yaw(), center.pitch()));
        player.sendMessage(MM.deserialize(
                "<gold>Voyage vers</gold> <white><name></white><gray>.</gray>",
                Placeholder.unparsed("name", center.name())));
    }

    // ---- Administration des centres de village (issue #151, /rpgadmin travel village) -----

    /** Crée ou déplace/renomme un centre (même {@code id} = jamais une nouvelle identité). */
    public void setVillage(String id, String name, Location location) {
        World world = location.getWorld();
        VillageCenter center = new VillageCenter(id, name, world.getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch(), true, Instant.now());
        villageCenterRepository.upsert(center).thenRun(() -> runSync(() -> villagesById.put(id, center)))
                .exceptionally(error -> {
                    logger.error("Impossible de persister le centre de village {}", id, error);
                    return null;
                });
    }

    public Optional<String> setVillageActive(String id, boolean active) {
        VillageCenter existing = villagesById.get(id);
        if (existing == null) {
            return Optional.of("Centre de village introuvable : " + id);
        }
        villageCenterRepository.setActive(id, active).thenRun(() -> runSync(() -> {
            VillageCenter current = villagesById.get(id);
            if (current != null) {
                villagesById.put(id, new VillageCenter(current.id(), current.name(), current.world(), current.x(),
                        current.y(), current.z(), current.yaw(), current.pitch(), active, current.createdAt()));
            }
        })).exceptionally(error -> {
            logger.error("Impossible de (dés)activer le centre de village {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    public Optional<String> removeVillage(String id) {
        if (!villagesById.containsKey(id)) {
            return Optional.of("Centre de village introuvable : " + id);
        }
        villagesById.remove(id);
        villageCenterRepository.delete(id).exceptionally(error -> {
            logger.error("Impossible de supprimer le centre de village {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    public List<VillageCenter> villages() {
        return List.copyOf(villagesById.values());
    }

    // ---- Menu : waypoints découverts ------------------------------------------------------

    void openWaypoints(Player player, int page, String filter) {
        List<Waypoint> all = waypointService.discoveredBy(player.getUniqueId());
        List<Waypoint> filtered = filter.isEmpty() ? all : all.stream()
                .filter(w -> normalize(prettyBiome(w.biomeKey()) + " " + w.world()).contains(filter))
                .toList();
        List<Waypoint> sorted = filtered.stream()
                .sorted(Comparator.comparing(Waypoint::biomeKey).thenComparing(Waypoint::id))
                .toList();

        int pageCount = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, pageCount - 1));
        int from = Math.min(sorted.size(), clampedPage * PAGE_SIZE);
        int to = Math.min(sorted.size(), from + PAGE_SIZE);
        List<Waypoint> pageItems = sorted.subList(from, to);

        Inventory inventory = createMenu(BeaconMenuHolder.Kind.WAYPOINTS, 54, "Waypoints découverts");
        if (pageItems.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, "<red>Aucun waypoint</red>",
                    filter.isEmpty()
                            ? "<gray>Tu n'as encore rien découvert.</gray>"
                            : "<gray>Aucun résultat pour cette recherche.</gray>"));
        } else {
            for (int i = 0; i < pageItems.size(); i++) {
                Waypoint waypoint = pageItems.get(i);
                inventory.setItem(i, waypointIcon(waypoint));
            }
        }
        inventory.setItem(45, icon(Material.ARROW, "<yellow>Retour</yellow>", ""));
        inventory.setItem(47, icon(Material.NAME_TAG, "<aqua>Rechercher</aqua>",
                "<gray>Ouvre une saisie de nom (sans commande, sans coût).</gray>"));
        if (clampedPage > 0) {
            inventory.setItem(48, icon(Material.SPECTRAL_ARROW, "<yellow>Page précédente</yellow>", ""));
        }
        inventory.setItem(49, icon(Material.PAPER, "<white>Page " + (clampedPage + 1) + " / " + pageCount + "</white>", ""));
        if (clampedPage < pageCount - 1) {
            inventory.setItem(50, icon(Material.SPECTRAL_ARROW, "<yellow>Page suivante</yellow>", ""));
        }
        inventory.setItem(53, icon(Material.BARRIER, "<red>Fermer</red>", ""));

        sessions.put(player.getUniqueId(), new BeaconMenuSession(clampedPage, filter));
        player.openInventory(inventory);
    }

    void handleWaypointsClick(Player player, int slot, BeaconMenuSession session) {
        switch (slot) {
            case 45 -> openRoot(player);
            case 47 -> openSearch(player);
            case 48 -> openWaypoints(player, session.page() - 1, session.filter());
            case 50 -> openWaypoints(player, session.page() + 1, session.filter());
            case 53 -> player.closeInventory();
            default -> {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    selectWaypointAtSlot(player, session, slot);
                }
            }
        }
    }

    private void selectWaypointAtSlot(Player player, BeaconMenuSession session, int slot) {
        List<Waypoint> all = waypointService.discoveredBy(player.getUniqueId());
        List<Waypoint> filtered = session.filter().isEmpty() ? all : all.stream()
                .filter(w -> normalize(prettyBiome(w.biomeKey()) + " " + w.world()).contains(session.filter()))
                .toList();
        List<Waypoint> sorted = filtered.stream()
                .sorted(Comparator.comparing(Waypoint::biomeKey).thenComparing(Waypoint::id))
                .toList();
        int index = session.page() * PAGE_SIZE + slot;
        if (index < 0 || index >= sorted.size()) {
            return; // clic périmé (page changée entre-temps) : ignoré.
        }
        travelTo(player, sorted.get(index).id());
    }

    /** Revalidation stricte puis arrivée sûre — jamais via un clic périmé sur un waypoint non découvert. */
    void travelTo(Player player, String waypointId) {
        if (!waypointService.hasActivelyDiscovered(player.getUniqueId(), waypointId)) {
            player.sendMessage(MM.deserialize(
                    "<red>Ce waypoint n'est plus disponible (non découvert, désactivé ou supprimé).</red>"));
            return;
        }
        Waypoint waypoint = waypointService.byId(waypointId).orElse(null);
        if (waypoint == null) {
            player.sendMessage(MM.deserialize("<red>Ce waypoint n'existe plus.</red>"));
            return;
        }
        World world = plugin.getServer().getWorld(waypoint.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de cette destination n'est pas chargé.</red>"));
            return;
        }
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, waypoint.x(), waypoint.z());
        if (safe.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Arrivée dangereuse ou terrain indisponible près de cette destination — réessaie plus tard.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(safe.get());
        player.sendMessage(MM.deserialize(
                "<gold>Voyage vers</gold> <white><biome></white><gray>.</gray>",
                Placeholder.parsed("biome", prettyBiome(waypoint.biomeKey()))));
    }

    // ---- Recherche graphique (enclume virtuelle) -------------------------------------------

    void openSearch(Player player) {
        BeaconMenuHolder holder = new BeaconMenuHolder(BeaconMenuHolder.Kind.SEARCH);
        Inventory inventory = plugin.getServer().createInventory(holder, org.bukkit.event.inventory.InventoryType.ANVIL,
                MM.deserialize("<dark_gray>Rechercher un waypoint</dark_gray>"));
        holder.bind(inventory);
        ItemStack input = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = input.getItemMeta();
        meta.displayName(MM.deserialize("<white>Tape un nom...</white>"));
        input.setItemMeta(meta);
        inventory.setItem(0, input);
        player.openInventory(inventory);
    }

    /** Appelé par {@link TravelBeaconListener} sur {@code PrepareAnvilEvent} : jamais de coût XP. */
    void handlePrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        event.getInventory().setRepairCost(0);
        ItemStack first = event.getInventory().getItem(0);
        if (first == null) {
            return;
        }
        ItemStack result = first.clone();
        String renameText = event.getInventory().getRenameText();
        if (renameText != null && !renameText.isBlank()) {
            ItemMeta meta = result.getItemMeta();
            meta.displayName(Component.text(renameText));
            result.setItemMeta(meta);
        }
        event.setResult(result);
    }

    /** Clic sur le résultat (slot 2) de l'enclume de recherche : jamais d'objet réellement donné. */
    void handleSearchResultClick(Player player, ItemStack resultItem) {
        String typed = "";
        if (resultItem != null && resultItem.getItemMeta() != null && resultItem.getItemMeta().hasDisplayName()) {
            typed = PlainTextComponentSerializer.plainText().serialize(resultItem.getItemMeta().displayName());
        }
        openWaypoints(player, 0, normalize(typed));
    }

    void handleClose(Player player) {
        sessions.remove(player.getUniqueId());
    }

    BeaconMenuSession sessionOf(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ---- Rendu --------------------------------------------------------------------------

    private ItemStack waypointIcon(Waypoint waypoint) {
        return icon(Material.FILLED_MAP, "<gold>" + prettyBiome(waypoint.biomeKey()) + "</gold>",
                "<gray>Monde :</gray> <white>" + waypoint.world() + "</white>",
                "<gray>Clique pour voyager.</gray>");
    }

    private static ItemStack icon(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> loreLines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                loreLines.add(MM.deserialize(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
        }
        meta.lore(loreLines);
        stack.setItemMeta(meta);
        return stack;
    }

    private Inventory createMenu(BeaconMenuHolder.Kind kind, int size, String title) {
        BeaconMenuHolder holder = new BeaconMenuHolder(kind);
        Inventory inventory = plugin.getServer().createInventory(holder, size, MM.deserialize("<dark_gray>" + title + "</dark_gray>"));
        holder.bind(inventory);
        return inventory;
    }

    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String prettyBiome(String biomeKey) {
        int colon = biomeKey.indexOf(':');
        String name = colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey;
        return name.replace('_', ' ');
    }

    // ---- Structure physique (support waypoint + diamant + bouton bois) --------------------

    private void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        Block ground = world.getBlockAt(anchorX, anchorY - 1, anchorZ);
        if (!ground.getType().isSolid()) {
            ground.setType(Material.STONE, false);
        }
        set(world, anchorX, anchorY, anchorZ, Material.COBBLESTONE_WALL);
        set(world, anchorX, anchorY + 1, anchorZ, Material.DIAMOND_BLOCK);
        set(world, anchorX, anchorY + 2, anchorZ, Material.AIR);

        int bx = anchorX + facing.getModX();
        int bz = anchorZ + facing.getModZ();
        Block button = world.getBlockAt(bx, anchorY + 1, bz);
        button.setType(Material.OAK_BUTTON, false);
        try {
            BlockData data = button.getBlockData();
            if (data instanceof Switch sw) {
                sw.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                sw.setFacing(facing);
                button.setBlockData(sw, false);
            }
        } catch (RuntimeException ignored) {
            // BlockData non simulé (tests) : le type OAK_BUTTON suffit, l'identité de l'interacteur
            // est purement positionnelle côté service (comme WaypointModelV1).
        }
        set(world, bx, anchorY + 2, bz, Material.AIR);
    }

    private boolean areaFree(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        for (int[] offset : new int[][] {{0, -1, 0}, {0, 0, 0}, {0, 1, 0},
                {facing.getModX(), 1, facing.getModZ()}}) {
            int x = anchorX + offset[0];
            int y = anchorY + offset[1];
            int z = anchorZ + offset[2];
            Material type = world.getBlockAt(x, y, z).getType();
            boolean groundLevel = offset[1] == -1;
            if (groundLevel ? !type.isSolid() && !isReplaceable(type) : !isReplaceable(type)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReplaceable(Material material) {
        return material.isAir() || !material.isSolid();
    }

    private static void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    private static BlockFace cardinalFacing(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;
        if (normalized >= 315 || normalized < 45) {
            return BlockFace.SOUTH;
        } else if (normalized < 135) {
            return BlockFace.WEST;
        } else if (normalized < 225) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private void index(TravelBeacon beacon) {
        byId.put(beacon.id(), beacon);
        BlockFace facing = parseFacing(beacon.facing());
        int ix = beacon.x() + facing.getModX();
        int iy = beacon.y() + 1;
        int iz = beacon.z() + facing.getModZ();
        byInteractorBlock.put(blockKey(beacon.world(), ix, iy, iz), beacon);
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y() - 1, beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y(), beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y() + 1, beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), ix, iy, iz));
    }

    private static BlockFace parseFacing(String raw) {
        try {
            BlockFace face = BlockFace.valueOf(raw);
            return switch (face) {
                case NORTH, SOUTH, EAST, WEST -> face;
                default -> BlockFace.NORTH;
            };
        } catch (IllegalArgumentException error) {
            return BlockFace.NORTH;
        }
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    private void runSync(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
