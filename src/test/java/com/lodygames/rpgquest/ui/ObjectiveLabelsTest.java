package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Lisibilité du journal pour les objectifs de remise (issue #123) : le joueur doit pouvoir
 * distinguer « ramasser du cuir » de « remettre du cuir au PNJ », sinon deux lignes identiques
 * attendraient deux actions différentes.
 *
 * <p>MockBukkit est nécessaire : la clé de traduction d'un {@code Material} passe par
 * l'implémentation du serveur.</p>
 */
class ObjectiveLabelsTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        MockBukkit.load(RPGQuestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String plain(QuestObjective objective) {
        return PlainTextComponentSerializer.plainText().serialize(ObjectiveLabels.targetName(objective));
    }

    @Test
    void aDeliveryIsVisiblyDistinctFromACollect() {
        QuestObjective deliver = new DeliverItemToNpcObjective("guard", Material.LEATHER, 4);
        QuestObjective collect = new CollectItemObjective(Material.LEATHER, 4);

        assertTrue(plain(deliver).contains("à remettre"),
                () -> "le libellé doit annoncer une remise, obtenu : " + plain(deliver));
        assertFalse(plain(collect).contains("à remettre"));
        assertNotEquals(plain(collect), plain(deliver),
                "deux objectifs qui attendent des actions différentes ne doivent pas se lire pareil");
    }

    @Test
    void aDeliveryIsCountableSoTheJournalShowsItsProgress() {
        assertTrue(ObjectiveLabels.isCountable(new DeliverItemToNpcObjective("guard", Material.LEATHER, 4)),
                "le compteur « remis/demandé » est l'information principale d'une remise");
        assertFalse(ObjectiveLabels.isCountable(new TalkToNpcObjective("guard")));
    }
}
