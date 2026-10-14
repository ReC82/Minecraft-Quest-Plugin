package com.lodygames.rpgquest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

/**
 * Issue #91 : sur une casse/pose de bloc, toute protection qui peut annuler l'événement doit passer
 * AVANT tout effet de jeu (XP, quêtes, positions posées). Sinon, à priorité égale, l'ordre
 * d'enregistrement du bootstrap décide — c'est ce qui affichait « +XP » sur une casse refusée hors claim.
 */
class BlockEventPriorityContractTest {

    private static final List<String> PROTECTIONS = List.of(
            "com.lodygames.rpgquest.claim.ClaimProtectionListener",
            "com.lodygames.rpgquest.claim.ClaimsWorldRulesListener",
            "com.lodygames.rpgquest.hub.HubWorldProtectionListener",
            "com.lodygames.rpgquest.zone.ZoneProtectionListener",
            "com.lodygames.rpgquest.travel.beacon.TravelBeaconProtectionListener",
            "com.lodygames.rpgquest.waypoint.WaypointProtectionListener");

    private static final List<String> CONSUMERS = List.of(
            "com.lodygames.rpgquest.progression.listener.MiningXpListener",
            "com.lodygames.rpgquest.progression.listener.FarmingXpListener",
            "com.lodygames.rpgquest.progression.PlacedBlockTracker",
            "com.lodygames.rpgquest.quest.progress.QuestBlockBreakListener",
            "com.lodygames.rpgquest.quest.progress.QuestBlockPlaceListener");

    @Test
    void everyBlockProtectionRunsBeforeEveryGameplayConsumer() throws Exception {
        for (Class<?> eventType : List.of(BlockBreakEvent.class, BlockPlaceEvent.class)) {
            int latestProtection = Integer.MIN_VALUE;
            for (String name : PROTECTIONS) {
                for (EventHandler handler : handlers(name, eventType)) {
                    latestProtection = Math.max(latestProtection, handler.priority().getSlot());
                }
            }
            for (String name : CONSUMERS) {
                for (EventHandler handler : handlers(name, eventType)) {
                    assertTrue(handler.priority().getSlot() > latestProtection,
                            name + " écoute " + eventType.getSimpleName() + " en " + handler.priority()
                                    + " : il doit passer après toutes les protections");
                    assertTrue(handler.ignoreCancelled(), name + " doit ignorer les événements annulés");
                }
            }
        }
    }

    private static List<EventHandler> handlers(String className, Class<?> eventType) throws ClassNotFoundException {
        return Arrays.stream(Class.forName(className).getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class))
                .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == eventType)
                .map(method -> method.getAnnotation(EventHandler.class))
                .toList();
    }
}
