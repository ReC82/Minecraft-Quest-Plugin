package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** Issue #211 — le modèle d'histoire téléchargeable : contenu, liste blanche et archive. */
class StoryKitTest {

    @Test
    void everyKitFileIsServedAndUnknownNamesResolveNothing() {
        for (StoryKit.KitFile f : StoryKit.FILES) {
            assertTrue(StoryKit.content(f.name()).filter(c -> !c.isBlank()).isPresent(), f.name());
        }
        assertTrue(StoryKit.content("../build.gradle.kts").isEmpty());
        assertTrue(StoryKit.content("guide-ia-base.md").isEmpty(), "seul le guide assemblé est servi");
    }

    /** Le guide embarque le contrat GÉNÉRÉ : il ne peut pas oublier ni inventer un type. */
    @Test
    void theGuideCarriesEveryRealObjectiveRewardActionAndCondition() {
        String guide = StoryKit.content(StoryKit.GUIDE).orElseThrow();

        assertTrue(guide.startsWith("# Guide pour une IA"));
        for (var catalog : java.util.List.of(Descriptors.OBJECTIVES, Descriptors.REWARDS,
                Descriptors.DIALOGUE_ACTIONS, Descriptors.DIALOGUE_CONDITIONS)) {
            for (Descriptors.Descriptor d : catalog) {
                assertTrue(guide.contains("`" + d.kind() + "`"), "type absent du guide : " + d.kind());
            }
        }
    }

    /** Fiche (auteur) et pack (machine) ne doivent jamais être confondus. */
    @Test
    void narrativeSheetsSayTheyAreNotImportableAndThePromptAsksForJsonOnly() {
        for (String sheet : new String[] {"modele-histoire-lily.md", "modele-histoire-vierge.md"}) {
            assertTrue(StoryKit.content(sheet).orElseThrow().contains("n'est PAS importable"), sheet);
        }
        String prompt = StoryKit.prompt();
        assertTrue(prompt.contains("UNIQUEMENT le fichier JSON"));
        assertTrue(prompt.contains("N'invente jamais de coordonnées"));
        assertTrue(prompt.contains("Ne crée AUCUN type"));
    }

    @Test
    void theArchiveHoldsTheReadmeAndEveryFile() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(StoryKit.zip()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(StoryKit.FILES.size() + 1, entries.size(), entries.keySet().toString());
        assertTrue(entries.get("LISEZ-MOI.txt").contains("Seuls les fichiers .json sont importables"));
        assertEquals(StoryKit.lilyPack(), entries.get(StoryKit.LILY_PACK));
    }
}
