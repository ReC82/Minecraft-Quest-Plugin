package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.web.ContentEditorPages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #177 tranche 2 — sélecteur de table dans l'éditeur de quête et vérification de la référence. */
class RewardTableReferenceTest {

    private static RefData withTables() {
        return RefData.empty().withRewardTables(Map.of("butin_ogre", "Butin de l'ogre",
                "vieux_coffre", "Vieux coffre" + RefData.DISABLED_TABLE_SUFFIX), true);
    }

    private static List<Diagnostic> validate(String table, RefData ref) {
        QuestDraft d = new QuestDraft();
        d.id = "test_quest";
        d.title = "Quête de test";
        d.description = "Description de test.";
        d.category = "tutorial";
        d.icon = "BOOK";
        Map<String, String> reward = new LinkedHashMap<>();
        reward.put("kind", "TABLE");
        reward.put("table", table);
        d.rewards.add(reward);
        return QuestValidator.validate(d, ref);
    }

    private static boolean has(List<Diagnostic> all, Diagnostic.Level level, String text) {
        return all.stream().anyMatch(d -> d.level() == level && d.message().contains(text));
    }

    @Test
    void theTableFieldIsASearchableListOfServerTables() {
        Descriptors.Field field = Descriptors.reward("TABLE").orElseThrow().fields().get(0);
        assertTrue(field.type() == Descriptors.FieldType.SELECT && "reward-table".equals(field.selectSource()));
        String html = ContentEditorPages.sharedDatalists(withTables(), false);
        assertTrue(html.contains("<datalist id=\"dl-reward-table\">"), html);
        assertTrue(html.contains("<option value=\"butin_ogre\" label=\"Butin de l&#39;ogre\">"), html);
    }

    @Test
    void unknownDisabledAndUnverifiableTablesAreSaidClearly() {
        assertTrue(has(validate("nulle_part", withTables()), Diagnostic.Level.WARNING, "introuvable sur le serveur"));
        assertTrue(has(validate("vieux_coffre", withTables()), Diagnostic.Level.WARNING, "est désactivée"));
        List<Diagnostic> ok = validate("butin_ogre", withTables());
        assertFalse(ok.stream().anyMatch(d -> d.field().startsWith("rewards") && d.level() != Diagnostic.Level.INFO),
                ok.toString());
        assertTrue(has(validate("butin_ogre", RefData.empty()), Diagnostic.Level.INFO, "Impossible de vérifier la table"));
    }
}
