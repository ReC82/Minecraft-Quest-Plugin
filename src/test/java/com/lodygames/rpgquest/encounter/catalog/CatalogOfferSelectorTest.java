package com.lodygames.rpgquest.encounter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector.Candidate;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector.Decision;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector.Kind;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector.QuestStatus;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogOfferSelectorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<Candidate> FIVE = List.of(new Candidate("q:a", 10), new Candidate("q:b", 10),
            new Candidate("q:c", 10), new Candidate("q:d", 10), new Candidate("q:e", 10));

    private static Map<String, QuestStatus> all(Status status) {
        Map<String, QuestStatus> map = new LinkedHashMap<>();
        FIVE.forEach(c -> map.put(c.questId(), new QuestStatus(status, null)));
        return map;
    }

    private static Decision decide(UUID player, Map<String, QuestStatus> statuses, Map<String, Instant> completed,
                                   int avoidRecent) {
        return CatalogOfferSelector.decide(player, "encounter_bandit", FIVE, statuses, completed, avoidRecent);
    }

    @Test
    void theSameInputsAlwaysGiveTheSameOffer() {
        Decision first = decide(PLAYER, all(Status.AVAILABLE), Map.of(), 1);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, decide(PLAYER, all(Status.AVAILABLE), Map.of(), 1));
        }
        assertEquals(Kind.OFFER, first.kind());
    }

    @Test
    void differentPlayersAndDifferentCompletionsSpreadOverTheCatalog() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(decide(UUID.randomUUID(), all(Status.AVAILABLE), Map.of(), 1).questId());
        }
        assertEquals(5, seen.size(), "le tirage couvre tout le catalogue");

        Set<String> overTime = new HashSet<>();
        for (int i = 1; i <= 100; i++) {
            Map<String, Instant> completed = Map.of("q:a", Instant.ofEpochSecond(1_000_000L * i));
            overTime.add(decide(PLAYER, all(Status.AVAILABLE), completed, 0).questId());
        }
        assertTrue(overTime.size() >= 4, "chaque complétion fait tourner la sélection : " + overTime);
    }

    @Test
    void anActiveQuestIsRecalledInsteadOfOfferingAnother() {
        Map<String, QuestStatus> statuses = all(Status.AVAILABLE);
        statuses.put("q:c", new QuestStatus(Status.ACTIVE, null));
        assertEquals(new Decision(Kind.ACTIVE, "q:c", null), decide(PLAYER, statuses, Map.of(), 1));
    }

    @Test
    void nothingEligibleReportsTheSoonestCooldown() {
        Map<String, QuestStatus> statuses = all(Status.UNAVAILABLE);
        statuses.put("q:a", new QuestStatus(Status.ON_COOLDOWN, Duration.ofHours(5)));
        statuses.put("q:b", new QuestStatus(Status.ON_COOLDOWN, Duration.ofHours(2)));
        Decision d = decide(PLAYER, statuses, Map.of(), 1);
        assertEquals(Kind.NOTHING, d.kind());
        assertEquals(Duration.ofHours(2), d.retryAfter());

        Decision never = decide(PLAYER, all(Status.UNAVAILABLE), Map.of(), 1);
        assertEquals(Kind.NOTHING, never.kind());
        assertNull(never.retryAfter());
    }

    @Test
    void theMostRecentlyCompletedQuestsAreAvoidedWhileAnotherRemains() {
        Map<String, Instant> completed = new HashMap<>();
        completed.put("q:a", Instant.ofEpochSecond(300));
        completed.put("q:b", Instant.ofEpochSecond(200));
        completed.put("q:c", Instant.ofEpochSecond(100));
        for (int i = 0; i < 50; i++) {
            String offered = decide(UUID.randomUUID(), all(Status.AVAILABLE), completed, 2).questId();
            assertNotEquals("q:a", offered);
            assertNotEquals("q:b", offered);
        }
        Map<String, QuestStatus> onlyA = all(Status.UNAVAILABLE);
        onlyA.put("q:a", new QuestStatus(Status.AVAILABLE, null));
        assertEquals("q:a", decide(PLAYER, onlyA, completed, 2).questId(),
                "dernière quête possible : proposée malgré l'anti-répétition");
    }

    @Test
    void weightsBiasTheDraw() {
        List<Candidate> skewed = List.of(new Candidate("q:rare", 1), new Candidate("q:common", 50));
        Map<String, QuestStatus> statuses = Map.of("q:rare", new QuestStatus(Status.AVAILABLE, null),
                "q:common", new QuestStatus(Status.AVAILABLE, null));
        int common = 0;
        for (int i = 0; i < 400; i++) {
            if ("q:common".equals(CatalogOfferSelector.decide(UUID.randomUUID(), "s", skewed, statuses, Map.of(), 0)
                    .questId())) {
                common++;
            }
        }
        assertTrue(common > 340, "poids 50 contre 1 : " + common + "/400");
    }

    @Test
    void removingAnotherCandidateDoesNotChangeTheOffer() {
        Decision before = decide(PLAYER, all(Status.AVAILABLE), Map.of(), 0);
        Map<String, QuestStatus> statuses = all(Status.AVAILABLE);
        FIVE.stream().map(Candidate::questId).filter(id -> !id.equals(before.questId())).findFirst()
                .ifPresent(other -> statuses.put(other, new QuestStatus(Status.UNAVAILABLE, null)));
        assertEquals(before.questId(), decide(PLAYER, statuses, Map.of(), 0).questId());
    }
}
