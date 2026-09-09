package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Modèle pur de l'annuaire joueurs (#96) : parsing du payload {@code player.catalog}, tri
 * (connectés d'abord puis dernière connexion décroissante), recherche pseudo / UUID, filtres
 * Tous / En ligne / Hors ligne / Bannis, tri Nom A-Z, pagination.
 */
class PlayerCatalogTest {

    private static Map<String, Object> row(String uuid, String name, boolean online, boolean banned,
                                           Long firstPlayed, Long lastSeen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", uuid);
        m.put("name", name);
        m.put("online", online);
        m.put("banned", banned);
        m.put("firstPlayed", firstPlayed);
        m.put("lastSeen", lastSeen);
        if (online) {
            m.put("world", "world_hub");
            m.put("x", 1L);
            m.put("y", 64L);
            m.put("z", 2L);
        }
        if (banned) {
            m.put("banReason", "raison " + name);
        }
        return m;
    }

    private static final List<Object> RAW = List.of(
            row("11111111-1111-1111-1111-111111111111", "LoDyMcFly", true, false, 1_000L, null),
            row("22222222-2222-2222-2222-222222222222", "Steve", false, false, 900L, 5_000L),
            row("33333333-3333-3333-3333-333333333333", "Alex", false, false, 800L, 9_000L),
            row("44444444-4444-4444-4444-444444444444", "Grief_Kid", false, true, 700L, 2_000L),
            row("55555555-5555-5555-5555-555555555555", null, false, false, 600L, null));

    @Test
    void parseReadsEveryField() {
        List<PlayerCatalog.Entry> all = PlayerCatalog.parse(RAW);
        assertEquals(5, all.size());
        PlayerCatalog.Entry lody = all.get(0);
        assertEquals("LoDyMcFly", lody.name());
        assertTrue(lody.online());
        assertEquals(Long.valueOf(1_000L), lody.firstPlayed());
        assertEquals("world_hub", lody.world());
        PlayerCatalog.Entry grief = all.get(3);
        assertTrue(grief.banned());
        assertEquals("raison Grief_Kid", grief.banReason());
        assertEquals("(pseudo inconnu)", all.get(4).displayName(), "repli explicite, jamais l'UUID");
    }

    @Test
    void parseToleratesGarbageAndMissingFields() {
        List<Object> weird = new ArrayList<>();
        weird.add("not a map");
        Map<String, Object> noUuid = new LinkedHashMap<>();
        noUuid.put("name", "NoIdentity");
        weird.add(noUuid);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("uuid", "66666666-6666-6666-6666-666666666666");
        legacy.put("name", "Legacy");
        weird.add(legacy); // ni online, ni banned, ni dates
        List<PlayerCatalog.Entry> all = PlayerCatalog.parse(weird);
        assertEquals(1, all.size());
        assertEquals("Legacy", all.get(0).name());
        assertFalse(all.get(0).online());
        assertFalse(all.get(0).banned());
        assertEquals(null, all.get(0).lastSeen());
    }

    @Test
    void defaultSortIsOnlineFirstThenLastSeenDescending() {
        PlayerCatalog.Page p = PlayerCatalog.view(PlayerCatalog.parse(RAW), "", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.RECENT, 1, 50);
        List<String> order = p.entries().stream().map(PlayerCatalog.Entry::displayName).toList();
        // LoDyMcFly (online) d'abord ; puis Alex (9000) > Steve (5000) > Grief_Kid (2000) > (pseudo inconnu) (null)
        assertEquals(List.of("LoDyMcFly", "Alex", "Steve", "Grief_Kid", "(pseudo inconnu)"), order);
        assertEquals(1, p.online());
        assertEquals(4, p.offline());
        assertEquals(1, p.banned());
        assertEquals(5, p.total());
        assertEquals(5, p.matched());
    }

    @Test
    void nameSortIsCaseInsensitiveAlphabetical() {
        PlayerCatalog.Page p = PlayerCatalog.view(PlayerCatalog.parse(RAW), "", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.NAME, 1, 50);
        assertEquals(List.of("(pseudo inconnu)", "Alex", "Grief_Kid", "LoDyMcFly", "Steve"),
                p.entries().stream().map(PlayerCatalog.Entry::displayName).toList());
    }

    @Test
    void searchMatchesNameCaseInsensitiveAndUuid() {
        List<PlayerCatalog.Entry> all = PlayerCatalog.parse(RAW);
        assertEquals(1, PlayerCatalog.view(all, "lodymcfly", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(1, PlayerCatalog.view(all, "STEVE", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(1, PlayerCatalog.view(all, "44444444-4444", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(0, PlayerCatalog.view(all, "zzz", PlayerCatalog.Filter.ALL,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
    }

    @Test
    void filtersAreExclusiveAndCombineWithSearch() {
        List<PlayerCatalog.Entry> all = PlayerCatalog.parse(RAW);
        assertEquals(1, PlayerCatalog.view(all, "", PlayerCatalog.Filter.ONLINE,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(4, PlayerCatalog.view(all, "", PlayerCatalog.Filter.OFFLINE,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(1, PlayerCatalog.view(all, "", PlayerCatalog.Filter.BANNED,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        // filtre + recherche = ET
        assertEquals(0, PlayerCatalog.view(all, "steve", PlayerCatalog.Filter.BANNED,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
        assertEquals(1, PlayerCatalog.view(all, "grief", PlayerCatalog.Filter.BANNED,
                PlayerCatalog.Sort.RECENT, 1, 50).matched());
    }

    @Test
    void filterParsingIsForgiving() {
        assertEquals(PlayerCatalog.Filter.ONLINE, PlayerCatalog.Filter.of("online"));
        assertEquals(PlayerCatalog.Filter.OFFLINE, PlayerCatalog.Filter.of("Hors-ligne"));
        assertEquals(PlayerCatalog.Filter.BANNED, PlayerCatalog.Filter.of("bannis"));
        assertEquals(PlayerCatalog.Filter.ALL, PlayerCatalog.Filter.of(null));
        assertEquals(PlayerCatalog.Filter.ALL, PlayerCatalog.Filter.of("nonsense"));
    }

    @Test
    void paginationSlicesAndClamps() {
        List<PlayerCatalog.Entry> many = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            many.add(new PlayerCatalog.Entry("uuid-" + String.format("%03d", i), "P" + String.format("%03d", i),
                    false, false, null, null, (long) (1_000 + i), null, null, null, null));
        }
        PlayerCatalog.Page p1 = PlayerCatalog.view(many, "", PlayerCatalog.Filter.ALL, PlayerCatalog.Sort.NAME, 1, 50);
        assertEquals(50, p1.entries().size());
        assertEquals(3, p1.pageCount());
        assertEquals("P000", p1.entries().get(0).displayName());

        PlayerCatalog.Page p3 = PlayerCatalog.view(many, "", PlayerCatalog.Filter.ALL, PlayerCatalog.Sort.NAME, 3, 50);
        assertEquals(30, p3.entries().size());
        assertEquals("P100", p3.entries().get(0).displayName());

        // page hors bornes -> ramenée à la dernière
        PlayerCatalog.Page p99 = PlayerCatalog.view(many, "", PlayerCatalog.Filter.ALL, PlayerCatalog.Sort.NAME, 99, 50);
        assertEquals(3, p99.page());
        assertEquals(30, p99.entries().size());
    }
}
