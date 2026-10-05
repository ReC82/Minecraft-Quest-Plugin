package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.content.Diagnostic;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestValidator;
import com.lodygames.rpgquest.panel.content.RefData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rendu et validation du choix d'un objet (issue #196) : ce que l'éditeur propose réellement, et
 * ce qu'il répond quand le choix est impossible.
 */
class MaterialPickerTest {

    private static RefData withCatalog() {
        return RefData.empty().withItemCatalog(new RefData.ItemCatalog(
                List.of("WOODEN_SWORD", "NETHERITE_SWORD", "GOLD_INGOT", "BOOK", "COMMAND_BLOCK"),
                List.of("WATER", "FIRE"), "1.21.11"));
    }

    @Test
    @DisplayName("La liste émet les objets du serveur avec leur libellé français")
    void datalistCarriesFrenchLabels() {
        String html = ContentEditorPages.sharedDatalists(withCatalog());

        assertTrue(html.contains("value=\"WOODEN_SWORD\" label=\"Épée en bois\""), html);
        assertTrue(html.contains("value=\"NETHERITE_SWORD\" label=\"Épée en netherite\""), html);
        assertTrue(html.contains("value=\"GOLD_INGOT\" label=\"Lingot d&#39;or\"")
                || html.contains("value=\"GOLD_INGOT\" label=\"Lingot d'or\""), html);
    }

    @Test
    @DisplayName("La provenance de la liste et la version Minecraft voyagent avec elle")
    void datalistDeclaresItsProvenance() {
        assertTrue(ContentEditorPages.sharedDatalists(withCatalog())
                .contains("data-material-source=\"server\""));
        assertTrue(ContentEditorPages.sharedDatalists(withCatalog())
                .contains("data-mc-version=\"1.21.11\""));

        String fallback = ContentEditorPages.sharedDatalists(RefData.empty());
        assertTrue(fallback.contains("data-material-source=\"fallback\""), fallback);
        assertFalse(fallback.contains("data-mc-version="),
                "sans relevé, aucune version ne doit être annoncée");
    }

    @Test
    @DisplayName("Un bloc sans forme d'objet figure dans la liste, marqué comme non proposable")
    void blocksWithoutItemAreMarkedNotHidden() {
        String html = ContentEditorPages.sharedDatalists(withCatalog());

        assertTrue(html.contains("value=\"WATER\" data-noitem=\"1\""), html);
        assertTrue(html.contains("aucun objet"), "le motif doit être visible dans le libellé");
    }

    @Test
    @DisplayName("Un objet réservé au créatif est signalé, sans être retiré")
    void creativeOnlyIsMarked() {
        String html = ContentEditorPages.sharedDatalists(withCatalog());

        assertTrue(html.contains("value=\"COMMAND_BLOCK\""), "il reste proposable");
        assertTrue(html.contains("data-creative=\"1\""), html);
    }

    @Test
    @DisplayName("Une icône qui est un bloc sans objet est REFUSÉE avec son motif, pas « inconnue »")
    void iconBlockWithoutItemIsRejectedWithReason() {
        QuestDraft draft = draft();
        draft.icon = "WATER";

        List<Diagnostic> diagnostics = QuestValidator.validate(draft, withCatalog());

        Diagnostic found = diagnostics.stream()
                .filter(d -> "icon".equals(d.field()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Diagnostic.Level.ERROR, found.level());
        assertTrue(found.message().contains("aucune forme d'objet"), found.message());
        assertFalse(found.message().contains("inconnu"),
                "dire « inconnu » enverrait chercher une faute de frappe inexistante");
    }

    @Test
    @DisplayName("Une icône absente du catalogue relevé est signalée en citant la version")
    void unknownIconNamesTheVersion() {
        QuestDraft draft = draft();
        draft.icon = "PAS_UN_OBJET";

        List<Diagnostic> diagnostics = QuestValidator.validate(draft, withCatalog());

        Diagnostic found = diagnostics.stream()
                .filter(d -> "icon".equals(d.field()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Diagnostic.Level.WARNING, found.level());
        assertTrue(found.message().contains("1.21.11"), found.message());
    }

    @Test
    @DisplayName("Sans relevé, une icône hors du repli ne déclenche AUCUN avertissement")
    void noWarningWithoutCatalog() {
        QuestDraft draft = draft();
        draft.icon = "NETHERITE_SWORD";  // absent du repli curé, parfaitement valide pourtant

        List<Diagnostic> diagnostics = QuestValidator.validate(draft, RefData.empty());

        assertTrue(diagnostics.stream().noneMatch(d -> "icon".equals(d.field())),
                "un faux positif ici apprendrait à ignorer les avertissements");
    }

    @Test
    @DisplayName("Une récompense d'objet qui est un bloc sans objet est refusée avec son motif")
    void rewardBlockWithoutItemIsRejected() {
        QuestDraft draft = draft();
        Map<String, String> reward = new LinkedHashMap<>();
        reward.put("kind", "ITEM");
        reward.put("material", "FIRE");
        reward.put("amount", "1");
        draft.rewards.add(reward);

        List<Diagnostic> diagnostics = QuestValidator.validate(draft, withCatalog());

        assertTrue(diagnostics.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR
                        && d.message().contains("aucune forme d'objet")),
                diagnostics.toString());
    }

    /** Brouillon minimal valide : seuls les champs testés varient. */
    private static QuestDraft draft() {
        QuestDraft d = new QuestDraft();
        d.id = "test_quest";
        d.title = "Quête de test";
        d.description = "Description de test.";
        d.category = "tutorial";
        d.icon = "BOOK";
        return d;
    }
}
