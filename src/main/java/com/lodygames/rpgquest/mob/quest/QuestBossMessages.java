package com.lodygames.rpgquest.mob.quest;

import com.lodygames.rpgquest.mob.community.CommunityBossMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Issue #171 — textes joueur des boss de quête (MiniMessage), envoyés au seul propriétaire. La
 * localisation réutilise celle des boss communautaires ({@link CommunityBossMessages#where}) : biome
 * traduit côté client, waypoint réel le plus proche, sinon coordonnées arrondies — jamais un nom
 * inventé. Le statut découvert / non découvert du waypoint est dit, sans rien déverrouiller.
 */
public final class QuestBossMessages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private QuestBossMessages() {
    }

    /** « dans le biome X, à ~120 blocs au nord du waypoint Y (déjà découvert) » — ou sans waypoint. */
    public static Component where(QuestBossInstance i, boolean waypointDiscovered) {
        Component where = CommunityBossMessages.where(i.biome(), i.x(), i.z(), i.waypointName(),
                i.waypointDistance(), i.waypointDirection());
        if (i.waypointName() == null) {
            return where;
        }
        return where.append(MM.deserialize(waypointDiscovered
                ? " <dark_gray>(waypoint déjà découvert)</dark_gray>"
                : " <dark_gray>(waypoint pas encore découvert : il faut s'y rendre à pied)</dark_gray>"));
    }

    public static Component spawned(QuestBossInstance i, String displayName, boolean waypointDiscovered) {
        return MM.deserialize("<gold>⚔ Votre boss de quête</gold> <name> <yellow>vous attend</yellow> <where>",
                Placeholder.component("name", MM.deserialize(displayName)),
                Placeholder.component("where", where(i, waypointDiscovered)));
    }

    /** Ligne du journal sous l'objectif : où se trouve le boss. */
    public static Component journalLine(QuestBossInstance i, boolean waypointDiscovered) {
        return Component.text("   ").append(where(i, waypointDiscovered));
    }

    public static Component searching() {
        return MM.deserialize("<gray>   Recherche d'un emplacement pour votre boss…</gray>");
    }

    public static Component problemLine(String problem) {
        return MM.deserialize("<red>   <problem></red>", Placeholder.unparsed("problem", problem));
    }

    public static Component problem(String problem) {
        return MM.deserialize("<gold>⚔ Boss de quête :</gold> <red><problem></red>",
                Placeholder.unparsed("problem", problem));
    }

    public static Component defeated(String displayName) {
        return MM.deserialize("<gold>⚔</gold> <name> <green>est vaincu : objectif rempli.</green>",
                Placeholder.component("name", MM.deserialize(displayName)));
    }

    public static Component lostWithoutOwner(String displayName, int radius, long retrySeconds) {
        return MM.deserialize("<gold>⚔</gold> <name> <gray>est mort sans que vous soyez à moins de <radius> blocs : "
                        + "l'objectif n'est pas rempli. Un nouveau boss apparaîtra dans environ <delay> s.</gray>",
                Placeholder.component("name", MM.deserialize(displayName)),
                Placeholder.unparsed("radius", String.valueOf(radius)),
                Placeholder.unparsed("delay", String.valueOf(retrySeconds)));
    }
}
