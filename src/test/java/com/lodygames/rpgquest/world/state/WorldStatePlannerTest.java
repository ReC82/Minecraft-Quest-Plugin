package com.lodygames.rpgquest.world.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.state.WorldStatePlanner.Folder;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Operation;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Plan;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.PortalTarget;
import com.lodygames.rpgquest.world.state.WorldStatePlanner.Request;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tous les refus de l'issue #563, sur un faux serveur. Aucun monde réel. */
class WorldStatePlannerTest {

    private final WorldStateFixtures.FakeGateway server = new WorldStateFixtures.FakeGateway();

    private Plan plan(Operation op, String world) {
        return plan(op, world, "", true, List.of());
    }

    private Plan plan(Operation op, String world, String env, boolean autoLoad, List<Folder> folders) {
        return WorldStatePlanner.plan(new Request(op, world, env, autoLoad), server.context(folders));
    }

    private static void refused(Plan p, String fragment) {
        assertFalse(p.ok(), p.toString());
        assertTrue(p.errors().stream().anyMatch(e -> e.contains(fragment)), p.errors() + " ∌ " + fragment);
    }

    @Test
    void mainWorldAndItsDimensionsAreNeverTouched() {
        server.withLoaded("world_nether", 0).withLoaded("world_the_end", 0);
        for (Operation op : Operation.values()) {
            refused(plan(op, "world"), "monde principal");
        }
        refused(plan(Operation.UNLOAD, "world_nether"), "dimension du monde principal");
        refused(plan(Operation.UNLOAD, "WORLD_THE_END"), "dimension du monde principal");
    }

    @Test
    void roleWorldsAreRefusedEvenWhenUnloaded() {
        refused(plan(Operation.UNLOAD, "hub"), "rôle HUB");
        refused(plan(Operation.UNLOAD, "Claims"), "rôle CLAIMS");
        refused(plan(Operation.AUTOLOAD, "hub", "", false, List.of()), "rôle HUB");
        server.players.remove("claims");
        refused(plan(Operation.LOAD, "claims"), "rôle CLAIMS");
    }

    @Test
    void rpgquestManagedWorldIsRefused() {
        Plan p = plan(Operation.UNLOAD, "wild");
        refused(p, "rôle WILD");
        refused(p, "worlds.yml");
    }

    @Test
    void unloadRefusedWithPlayersPresent() {
        server.withLoaded("terres", 2);
        refused(plan(Operation.UNLOAD, "terres"), "2 joueur(s)");
    }

    @Test
    void unloadRefusedWhileAnActivePortalLeadsThere() {
        server.withLoaded("terres", 0);
        server.portals.add(new PortalTarget("hub_to_terres", "hub", "terres", true));
        refused(plan(Operation.UNLOAD, "terres"), "hub_to_terres");
    }

    @Test
    void inactivePortalOrPortalLeavingOnlyWarns() {
        server.withLoaded("terres", 0);
        server.portals.add(new PortalTarget("brouillon", "hub", "terres", false));
        server.portals.add(new PortalTarget("terres_to_hub", "terres", "hub", true));
        Plan p = plan(Operation.UNLOAD, "terres");
        assertTrue(p.ok(), p.errors().toString());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("terres_to_hub")));
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("rien n'est supprimé")));
    }

    @Test
    void unloadRefusedWhenNotLoadedOrNotManagedByMultiverse() {
        refused(plan(Operation.UNLOAD, "absent"), "n'est pas chargé");
        server.players.put("libre", 0);
        refused(plan(Operation.UNLOAD, "libre"), "pas géré par Multiverse-Core");
    }

    @Test
    void loadNeedsAnUnloadedMultiverseWorld() {
        server.withLoaded("terres", 0).withUnloaded("archives", false);
        refused(plan(Operation.LOAD, "terres"), "déjà chargé");
        refused(plan(Operation.LOAD, "inconnu"), "l'importer");
        assertTrue(plan(Operation.LOAD, "archives").ok());
    }

    @Test
    void importChecksFolderNameAndDimension() {
        List<Folder> folders = List.of(new Folder("ancien", "NORMAL"), new Folder("enfer", "NETHER"),
                new Folder("vide", ""), new Folder("plugins", "NORMAL"));
        assertTrue(plan(Operation.IMPORT, "ancien", "NORMAL", true, folders).ok());
        refused(plan(Operation.IMPORT, "ancien", "NETHER", true, folders), "pas un monde du Nether");
        refused(plan(Operation.IMPORT, "enfer", "NORMAL", true, folders), "contient un monde du Nether");
        refused(plan(Operation.IMPORT, "vide", "NORMAL", true, folders), "déterminer sa dimension");
        refused(plan(Operation.IMPORT, "absent", "NORMAL", true, folders), "Aucun dossier");
        refused(plan(Operation.IMPORT, "plugins", "NORMAL", true, folders), "dossier du serveur");
        refused(plan(Operation.IMPORT, "ancien", "CUSTOM", true, folders), "Dimension à choisir");
        server.withUnloaded("ancien", true);
        refused(plan(Operation.IMPORT, "ancien", "NORMAL", true, folders), "déjà connu");
    }

    @Test
    void namesWithPathsAreRefused() {
        for (String bad : List.of("../world", "a/b", "", ".cache", "x y")) {
            refused(plan(Operation.IMPORT, bad, "NORMAL", true, List.of(new Folder(bad, "NORMAL"))), "Nom de monde");
        }
    }

    @Test
    void everythingRefusedWithoutMultiverse() {
        server.withLoaded("terres", 0);
        server.multiverse = false;
        refused(plan(Operation.UNLOAD, "terres"), "Multiverse-Core n'est pas utilisable");
    }

    @Test
    void autoLoadMustChangeSomething() {
        server.withUnloaded("archives", false);
        refused(plan(Operation.AUTOLOAD, "archives", "", false, List.of()), "déjà désactivé");
        assertTrue(plan(Operation.AUTOLOAD, "archives", "", true, List.of()).ok());
        refused(plan(Operation.AUTOLOAD, "inconnu", "", true, List.of()), "ne connaît pas");
    }

    @Test
    void importableHidesLoadedKnownAndServerFolders() {
        server.withUnloaded("archives", true);
        var c = server.context(List.of(new Folder("archives", "NORMAL"), new Folder("hub", "NORMAL"),
                new Folder("logs", ""), new Folder("ancien", "NORMAL")));
        assertEquals(List.of(new Folder("ancien", "NORMAL")), WorldStatePlanner.importable(c));
    }
}
