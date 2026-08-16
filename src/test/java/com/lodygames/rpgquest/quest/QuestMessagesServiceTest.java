package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.io.File;
import java.nio.file.Files;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@code messages.yml} n'est jamais réécrit par {@code saveResource(false)} une fois présent sur
 * disque (voir {@link QuestMessagesService}) : un fichier laissé par une ancienne version du plugin,
 * n'ayant pas les clés ajoutées depuis, faisait apparaître littéralement en jeu des messages du type
 * {@code [message manquant : quest.completed-subtitle]}. Ces tests couvrent la fusion qui corrige ça.
 */
class QuestMessagesServiceTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aPreExistingFileMissingNewKeysGetsThemMergedInWithoutTouchingCustomizedOnes() throws Exception {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        Files.createDirectories(file.getParentFile().toPath());
        // Simule un messages.yml d'une ancienne version du plugin : "unknown" personnalisé par un
        // admin, et aucune des clés started-*/completed-*/objective-progress/reward-* ajoutées depuis.
        Files.writeString(file.toPath(), """
                quest:
                  unknown: "Personnalisé par l'admin"
                """);

        QuestMessagesService service = new QuestMessagesService(plugin);
        service.start();

        String customized = PlainTextComponentSerializer.plainText()
                .serialize(service.current().format("quest.unknown"));
        assertEquals("Personnalisé par l'admin", customized,
                "une clé déjà présente sur disque ne doit jamais être écrasée par la fusion");

        String completedSubtitle = PlainTextComponentSerializer.plainText()
                .serialize(service.current().format("quest.completed-subtitle"));
        assertFalse(completedSubtitle.contains("message manquant"),
                () -> "la clé manquante doit avoir été ajoutée par la fusion, pas laissée en fallback : " + completedSubtitle);

        String onDisk = Files.readString(file.toPath());
        assertTrue(onDisk.contains("completed-subtitle"),
                "la clé fusionnée doit être persistée sur disque, pas seulement tenue en mémoire");
    }

    @Test
    void aFreshlyCopiedFileNeedsNoMergeAndIsNotRewritten() throws Exception {
        QuestMessagesService service = new QuestMessagesService(plugin);
        service.start();

        File file = new File(plugin.getDataFolder(), "messages.yml");
        long sizeAfterFirstLoad = file.length();

        service.reload();
        assertEquals(sizeAfterFirstLoad, file.length(),
                "un fichier déjà complet ne doit pas être réécrit à chaque reload");

        String completedSubtitle = PlainTextComponentSerializer.plainText()
                .serialize(service.current().format("quest.completed-subtitle"));
        assertFalse(completedSubtitle.contains("message manquant"));
    }
}
