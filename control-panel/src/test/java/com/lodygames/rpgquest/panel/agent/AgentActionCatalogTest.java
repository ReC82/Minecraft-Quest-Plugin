package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Liste blanche côté panel : types autorisés, permission, bornes des paramètres, confirmation. */
class AgentActionCatalogTest {

    @Test
    void unknownTypeIsRejected() {
        assertFalse(AgentActionCatalog.isWhitelisted("console.run"));
        assertFalse(AgentActionCatalog.validate("console.run", Map.of()).valid());
    }

    @Test
    void arbitraryCommandShapeIsNotWhitelisted() {
        assertFalse(AgentActionCatalog.isWhitelisted("rcon"));
        assertFalse(AgentActionCatalog.isWhitelisted("server.command"));
    }

    @Test
    void readActionNeedsNoConfirmation() {
        AgentActionCatalog.Validation v = AgentActionCatalog.validate("player.list", Map.of());
        assertTrue(v.valid());
        assertTrue(v.params().isEmpty());
    }

    @Test
    void mutationRequiresExplicitConfirm() {
        Map<String, String> noConfirm = Map.of("player", "LoDyMcFly", "quest_id", "rpgquest:crystal_hunt");
        assertFalse(AgentActionCatalog.validate("quest.complete", noConfirm).valid());

        Map<String, String> withConfirm = Map.of("player", "LoDyMcFly",
                "quest_id", "rpgquest:crystal_hunt", "confirm", "true");
        assertTrue(AgentActionCatalog.validate("quest.complete", withConfirm).valid());
    }

    @Test
    void giveAmountIsBounded() {
        assertFalse(AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "0", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "65", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("player.item.give",
                Map.of("player", "X", "item_id", "rpgquest:rune_rappel", "amount", "3", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("3", ok.params().get("amount"));
    }

    @Test
    void playerRefIsValidated() {
        assertFalse(AgentActionCatalog.validate("quest.player.status", Map.of("player", "bad name!")).valid());
        assertTrue(AgentActionCatalog.validate("quest.player.status", Map.of("player", "LoDyMcFly")).valid());
    }

    @Test
    void storyIdIsNormalisedAndValidated() {
        assertFalse(AgentActionCatalog.validate("story.advance",
                Map.of("player", "X", "story_id", "Not Valid", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("story.advance",
                Map.of("player", "X", "story_id", "Main_Story", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("main_story", ok.params().get("story_id"));
    }

    @Test
    void variableSetKeepsKeyPatternAndBoundsValue() {
        assertFalse(AgentActionCatalog.validate("player.variable.set",
                Map.of("player", "X", "key", "bad key", "value", "true", "confirm", "true")).valid());
        assertTrue(AgentActionCatalog.validate("player.variable.set",
                Map.of("player", "X", "key", "CLAIM_TIER_1", "value", "true", "confirm", "true")).valid());
    }

    @Test
    void permissionsAreMappedPerType() {
        assertEquals(Permission.ACTION_QUEST, AgentActionCatalog.spec("quest.start").orElseThrow().permission());
        assertEquals(Permission.ACTION_STORY, AgentActionCatalog.spec("story.complete").orElseThrow().permission());
        assertEquals(Permission.ACTION_ITEM_GIVE, AgentActionCatalog.spec("player.item.give").orElseThrow().permission());
        assertEquals(Permission.PLAYERS_READ, AgentActionCatalog.spec("player.list").orElseThrow().permission());
        assertEquals(Permission.ACTION_PLAYER_RESET, AgentActionCatalog.spec("player.resetnew.confirm").orElseThrow().permission());
    }

    // ---- Éditeur guidé de dialogue (issue #82 phase 1) --------------------------------------

    @Test
    void dialogueEditActionsRequireDialogueWrite() {
        for (String type : new String[] {"dialogue.node.create", "dialogue.node.update",
                "dialogue.choice.add", "dialogue.choice.update", "dialogue.choice.delete"}) {
            assertEquals(Permission.DIALOGUE_WRITE, AgentActionCatalog.spec(type).orElseThrow().permission());
            assertTrue(AgentActionCatalog.spec(type).orElseThrow().mutation());
        }
    }

    @Test
    void reversibleContentEditsNeedNoHiddenConfirmButDestructiveOnesDo() {
        // Édition de contenu normale et réversible (issues #111 / #113 / #118) : aucune case cachée.
        for (String type : new String[] {"npc.definition.create", "npc.definition.update", "quest.giver.set",
                "npc.citizens.link", "dialogue.definition.create", "dialogue.node.create", "dialogue.node.update",
                "dialogue.choice.add", "dialogue.choice.update", "mob.definition.create", "mob.definition.update",
                "mob.definition.toggle", "mob.spawn-settings.set"}) {
            assertFalse(AgentActionCatalog.spec(type).orElseThrow().sensitive(), type + " ne doit pas être « sensible »");
        }
        assertTrue(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "speaker", "G", "text", "T")).valid(),
                "aucune confirmation cachée pour une édition réversible");

        // Actions réellement sensibles / destructrices : confirmation explicite conservée.
        for (String type : new String[] {"dialogue.choice.delete", "npc.citizens.create", "player.ban",
                "player.unban", "player.resetnew.confirm", "quest.reset", "mob.test.spawn", "mob.test.clear"}) {
            assertTrue(AgentActionCatalog.spec(type).orElseThrow().sensitive(), type + " doit rester « sensible »");
        }
        assertFalse(AgentActionCatalog.validate("dialogue.choice.delete", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "0")).valid(), "confirm requis");
    }

    /**
     * Issue #165 — le panel ne doit jamais transmettre une commande libre : seul un lien MineSkin
     * strict est accepté, et il est revalidé côté plugin.
     */
    @Test
    void theSkinActionAcceptsOnlyARealMineSkinLink() {
        assertTrue(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "npc_id", "help",
                "skin_url", "https://minesk.in/de347dcfb215477db7ae52d780e68757")).valid(),
                "le lien fourni par MineSkin doit passer");

        // La commande Citizens collée telle quelle (erreur la plus probable de l'admin) est refusée.
        assertFalse(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "npc_id", "help",
                "skin_url", "/npc skin --url https://minesk.in/de347dcfb215477db7ae52d780e68757")).valid(),
                "une commande, même contenant une URL valide, doit être refusée");
        // Un autre domaine ferait de l'action un téléchargeur générique côté serveur.
        assertFalse(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "npc_id", "help", "skin_url", "https://example.com/evil.png")).valid());
        assertFalse(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "npc_id", "help", "skin_url", "http://minesk.in/de347dcfb215477db")).valid(),
                "http simple refusé");
        assertFalse(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "npc_id", "help", "skin_url", "")).valid());
        // Le PNJ est toujours ciblé explicitement : pas d'id, pas d'action.
        assertFalse(AgentActionCatalog.validate("npc.citizens.skin", Map.of(
                "skin_url", "https://minesk.in/de347dcfb215477db7ae52d780e68757")).valid(),
                "aucune action sans PNJ ciblé — jamais de sélection implicite");
    }

    @Test
    void theRenameActionBoundsTheDisplayNameAndAlwaysTargetsAnNpc() {
        assertTrue(AgentActionCatalog.validate("npc.citizens.rename", Map.of(
                "npc_id", "help", "name", "Aide du village")).valid());
        assertFalse(AgentActionCatalog.validate("npc.citizens.rename", Map.of(
                "npc_id", "help", "name", "")).valid(), "nom vide refusé");
        assertFalse(AgentActionCatalog.validate("npc.citizens.rename", Map.of(
                "npc_id", "help", "name", "x".repeat(49))).valid(), "nom trop long refusé");
        assertFalse(AgentActionCatalog.validate("npc.citizens.rename", Map.of(
                "name", "Aide")).valid(), "aucune action sans PNJ ciblé");
    }

    @Test
    void contentMutationsDeclareTheCatalogsTheyInvalidate() {
        assertEquals(java.util.List.of("npc.list"),
                AgentActionCatalog.spec("npc.definition.update").orElseThrow().refreshTypes());
        assertEquals(java.util.List.of("npc.list", "npc.citizens.list"),
                AgentActionCatalog.spec("npc.citizens.link").orElseThrow().refreshTypes());
        assertEquals(java.util.List.of("dialogue.list"),
                AgentActionCatalog.spec("dialogue.definition.create").orElseThrow().refreshTypes());
        assertTrue(AgentActionCatalog.spec("player.variable.get").orElseThrow().refreshTypes().isEmpty(),
                "une lecture n'invalide aucun catalogue");
    }

    @Test
    void dialogueColorPaletteWrapsSimpleTextAndRejectsUnknownColor() {
        AgentActionCatalog.Validation wrapped = AgentActionCatalog.validate("dialogue.definition.create", Map.of(
                "key", "intro", "speaker", "Robert", "text_color", "yellow", "text", "Bonjour."));
        assertTrue(wrapped.valid());
        assertEquals("<yellow>Bonjour.</yellow>", wrapped.params().get("text"));

        // MiniMessage déjà présent : on n'enrobe pas, le texte avancé reste intact.
        AgentActionCatalog.Validation manual = AgentActionCatalog.validate("dialogue.definition.create", Map.of(
                "key", "intro", "speaker", "Robert", "text_color", "yellow", "text", "<red>Halte !</red>"));
        assertTrue(manual.valid());
        assertEquals("<red>Halte !</red>", manual.params().get("text"));

        assertFalse(AgentActionCatalog.validate("dialogue.definition.create", Map.of(
                "key", "intro", "speaker", "Robert", "text_color", "turquoise", "text", "Bonjour.")).valid());
    }

    @Test
    void dialogueNodeUpdateNormalisesIdsAndRejectsBadInput() {
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "Guard", "node_id", "Greeting", "speaker", "Capitaine",
                "text", "<y>Salut</y>", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("rpgquest:guard", ok.params().get("dialogue_id"));
        assertEquals("greeting", ok.params().get("node_id"));

        assertFalse(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "bad node", "speaker", "G", "text", "T", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("dialogue.node.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "speaker", "G",
                "text", "a\nb", "confirm", "true")).valid(), "texte multi-ligne");
    }

    @Test
    void dialogueChoiceAddNeedsExactlyOneTarget() {
        assertFalse(AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour",
                "next_node_id", "greeting", "close", "true", "confirm", "true")).valid());
        AgentActionCatalog.Validation next = AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Retour",
                "next_node_id", "greeting", "confirm", "true"));
        assertTrue(next.valid());
        assertEquals("greeting", next.params().get("next_node_id"));
        AgentActionCatalog.Validation close = AgentActionCatalog.validate("dialogue.choice.add", Map.of(
                "dialogue_id", "guard", "node_id", "accepted", "choice_text", "Fin", "close", "true", "confirm", "true"));
        assertTrue(close.valid());
        assertEquals("true", close.params().get("close"));
        assertFalse(close.params().containsKey("next_node_id"));
    }

    @Test
    void dialogueChoiceUpdateAndDeleteBoundTheIndex() {
        assertFalse(AgentActionCatalog.validate("dialogue.choice.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "-1",
                "choice_text", "X", "close", "true", "confirm", "true")).valid());
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("dialogue.choice.update", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "2",
                "choice_text", "X", "next_node_id", "accepted", "confirm", "true"));
        assertTrue(ok.valid());
        assertEquals("2", ok.params().get("choice_index"));
        assertFalse(AgentActionCatalog.validate("dialogue.choice.delete", Map.of(
                "dialogue_id", "guard", "node_id", "greeting", "choice_index", "abc", "confirm", "true")).valid());
    }

    // ---- content.export (issue #108) --------------------------------------------------

    @Test
    void contentExportIsAReadNeedingContentExportPermissionAndNoConfirm() {
        AgentActionCatalog.Spec spec = AgentActionCatalog.spec("content.export").orElseThrow();
        assertEquals(Permission.CONTENT_EXPORT, spec.permission());
        assertFalse(spec.mutation());
        assertFalse(spec.sensitive());
        assertTrue(spec.refreshTypes().isEmpty());
        AgentActionCatalog.Validation v = AgentActionCatalog.validate("content.export", Map.of());
        assertTrue(v.valid());
        assertEquals("all", v.params().get("family"));
    }

    @Test
    void contentExportValidatesFamilyAndSelection() {
        assertFalse(AgentActionCatalog.validate("content.export", Map.of("family", "bogus")).valid());

        AgentActionCatalog.Validation fam = AgentActionCatalog.validate("content.export", Map.of("family", "quests"));
        assertTrue(fam.valid());
        assertEquals("quests", fam.params().get("family"));
        assertFalse(fam.params().containsKey("ids"));

        AgentActionCatalog.Validation sel = AgentActionCatalog.validate("content.export",
                Map.of("family", "quests", "ids", "rpgquest:first_steps  rpgquest:crystal_hunt"));
        assertTrue(sel.valid());
        assertEquals("rpgquest:first_steps,rpgquest:crystal_hunt", sel.params().get("ids"));

        // Une sélection d'ids n'a pas de sens avec « all ».
        assertFalse(AgentActionCatalog.validate("content.export",
                Map.of("family", "all", "ids", "rpgquest:first_steps")).valid());

        // Id malformé.
        assertFalse(AgentActionCatalog.validate("content.export",
                Map.of("family", "quests", "ids", "bad id!!")).valid());
    }

    // ---- Mobs spéciaux / boss (issue #169, lot 1) -------------------------------------------

    @Test
    void mobDefinitionWriteRequiresIdCategoryEntityTypeNameAndSpawnChance() {
        assertFalse(AgentActionCatalog.validate("mob.definition.create", Map.of()).valid());
        assertFalse(AgentActionCatalog.validate("mob.definition.create", Map.of(
                "mob_id", "swamp_king", "category", "NOT_A_CATEGORY", "entity_type", "ZOMBIE",
                "display_name", "Roi", "spawn_chance", "1.0")).valid());
        assertFalse(AgentActionCatalog.validate("mob.definition.create", Map.of(
                "mob_id", "swamp_king", "category", "BOSS", "entity_type", "ZOMBIE",
                "display_name", "Roi", "spawn_chance", "1.5")).valid(), "spawn_chance hors bornes");

        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("mob.definition.create", Map.of(
                "mob_id", "Swamp_King", "category", "boss", "entity_type", "zombie",
                "display_name", "Roi des Marais", "spawn_chance", "0.25"));
        assertTrue(ok.valid(), ok.error());
        assertEquals("swamp_king", ok.params().get("mob_id"));
        assertEquals("BOSS", ok.params().get("category"));
        assertEquals("ZOMBIE", ok.params().get("entity_type"));
        assertEquals("false", ok.params().get("enabled"), "enabled absent du formulaire = false (case non cochée)");
    }

    @Test
    void mobDefinitionWriteKeepsEnragedAndSummonFieldsOnlyWhenTheirToggleIsChecked() {
        Map<String, String> base = Map.of("mob_id", "x", "category", "SPECIAL", "entity_type", "ZOMBIE",
                "display_name", "X", "spawn_chance", "0.1");

        AgentActionCatalog.Validation withoutToggle = AgentActionCatalog.validate("mob.definition.create", base);
        assertTrue(withoutToggle.valid());
        assertFalse(withoutToggle.params().containsKey("enraged_health_fraction"));

        Map<String, String> withEnraged = new java.util.LinkedHashMap<>(base);
        withEnraged.put("enraged_enabled", "true");
        withEnraged.put("enraged_health_fraction", "0.3");
        withEnraged.put("enraged_speed_multiplier", "1.5");
        withEnraged.put("enraged_damage_multiplier", "2.0");
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("mob.definition.create", withEnraged);
        assertTrue(ok.valid(), ok.error());
        assertEquals("0.3", ok.params().get("enraged_health_fraction"));

        Map<String, String> incomplete = new java.util.LinkedHashMap<>(base);
        incomplete.put("enraged_enabled", "true");
        incomplete.put("enraged_health_fraction", "0.3");
        assertFalse(AgentActionCatalog.validate("mob.definition.create", incomplete).valid(),
                "capacité activée mais incomplète : rejet");
    }

    @Test
    void mobDefinitionToggleAndSpawnSettingsValidateTheirOwnFields() {
        assertFalse(AgentActionCatalog.validate("mob.definition.toggle", Map.of()).valid());
        AgentActionCatalog.Validation t = AgentActionCatalog.validate("mob.definition.toggle",
                Map.of("mob_id", "swamp_king", "enabled", "true"));
        assertTrue(t.valid());
        assertEquals("true", t.params().get("enabled"));

        assertFalse(AgentActionCatalog.validate("mob.spawn-settings.set", Map.of("chance", "1.5")).valid());
        AgentActionCatalog.Validation s = AgentActionCatalog.validate("mob.spawn-settings.set",
                Map.of("enabled", "true", "chance", "0.05", "max_simultaneous_special", "10"));
        assertTrue(s.valid());
        assertEquals("0.05", s.params().get("chance"));
        assertEquals("10", s.params().get("max_simultaneous_special"));
    }

    @Test
    void mobTestSpawnNeedsPlayerAndMobIdAndConfirmation() {
        assertFalse(AgentActionCatalog.validate("mob.test.spawn", Map.of("player", "Steve")).valid(),
                "mob_id manquant");
        assertFalse(AgentActionCatalog.validate("mob.test.spawn",
                Map.of("player", "Steve", "mob_id", "swamp_king")).valid(), "confirmation manquante");
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("mob.test.spawn",
                Map.of("player", "Steve", "mob_id", "swamp_king", "confirm", "true"));
        assertTrue(ok.valid(), ok.error());
        assertEquals("swamp_king", ok.params().get("mob_id"));

        AgentActionCatalog.Validation clear = AgentActionCatalog.validate("mob.test.clear", Map.of("confirm", "true"));
        assertTrue(clear.valid());
    }

    @Test
    void mobIdAcceptsTheFullNamespacedFormReturnedByMobList() {
        // Reproduit le blocage réel #169 : mob.list renvoie toujours l'id namespacé complet
        // (NamespacedKey#asString(), ex. "rpgquest:creeper_pig") et les formulaires d'édition,
        // de bascule et de spawn de test le réinjectent tel quel -- jamais seulement la forme
        // courte tapée à la création. Le "." pattern doit accepter le ":" ou TOUT profil existant
        // est rejeté "Identifiant de profil manquant ou invalide", alors même que le catalogue le
        // liste bien.
        AgentActionCatalog.Validation toggle = AgentActionCatalog.validate("mob.definition.toggle",
                Map.of("mob_id", "rpgquest:creeper_pig", "enabled", "false"));
        assertTrue(toggle.valid(), toggle.error());
        assertEquals("rpgquest:creeper_pig", toggle.params().get("mob_id"));

        AgentActionCatalog.Validation spawn = AgentActionCatalog.validate("mob.test.spawn",
                Map.of("player", "Steve", "mob_id", "rpgquest:creeper_pig", "confirm", "true"));
        assertTrue(spawn.valid(), spawn.error());
        assertEquals("rpgquest:creeper_pig", spawn.params().get("mob_id"));

        AgentActionCatalog.Validation update = AgentActionCatalog.validate("mob.definition.update", Map.of(
                "mob_id", "rpgquest:creeper_pig", "category", "SPECIAL", "entity_type", "PIG",
                "display_name", "Cochon Creeper", "spawn_chance", "0.015"));
        assertTrue(update.valid(), update.error());
        assertEquals("rpgquest:creeper_pig", update.params().get("mob_id"));
    }

    @Test
    void mobPermissionsAreMappedPerType() {
        assertEquals(Permission.MOB_READ, AgentActionCatalog.spec("mob.list").orElseThrow().permission());
        assertEquals(Permission.MOB_WRITE, AgentActionCatalog.spec("mob.definition.create").orElseThrow().permission());
        assertEquals(Permission.MOB_WRITE, AgentActionCatalog.spec("mob.spawn-settings.set").orElseThrow().permission());
        assertEquals(Permission.MOB_TEST_SPAWN, AgentActionCatalog.spec("mob.test.spawn").orElseThrow().permission());
        assertEquals(Permission.MOB_TEST_SPAWN, AgentActionCatalog.spec("mob.test.clear").orElseThrow().permission());
    }

    // ---- Exploitation serveur (issue #95) --------------------------------------------

    @Test
    void announceIsAcceptedWithItsChannelAndNormalizedCase() {
        AgentActionCatalog.Validation valid = AgentActionCatalog.validate("server.announce",
                Map.of("message", "  Redémarrage dans 5 minutes.  ", "channel", "TITLE", "confirm", "true"));

        assertTrue(valid.valid(), valid.error());
        assertEquals("Redémarrage dans 5 minutes.", valid.params().get("message"));
        assertEquals("title", valid.params().get("channel"));
    }

    @Test
    void announceDefaultsToChat() {
        AgentActionCatalog.Validation valid = AgentActionCatalog.validate("server.announce",
                Map.of("message", "Bonjour", "confirm", "true"));

        assertTrue(valid.valid(), valid.error());
        assertEquals("chat", valid.params().get("channel"));
    }

    @Test
    void announceRequiresAnExplicitConfirmation() {
        // Une annonce est vue par tout le monde immédiatement et ne peut pas être reprise :
        // elle est marquée « sensible », donc la confirmation n'est pas optionnelle.
        AgentActionCatalog.Validation missing = AgentActionCatalog.validate("server.announce",
                Map.of("message", "Bonjour"));

        assertFalse(missing.valid());
        assertTrue(missing.error().contains("Confirmation"), missing.error());
    }

    @Test
    void announceRefusesACommandAnEmptyMessageAndAnOversizedOne() {
        assertFalse(AgentActionCatalog.validate("server.announce",
                Map.of("message", "/say coucou", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("server.announce",
                Map.of("message", "   ", "confirm", "true")).valid());
        AgentActionCatalog.Validation tooLong = AgentActionCatalog.validate("server.announce",
                Map.of("message", "x".repeat(AgentActionCatalog.MAX_ANNOUNCE_CHARS + 1), "confirm", "true"));
        assertFalse(tooLong.valid());
        assertTrue(tooLong.error().contains("trop longue"), tooLong.error());
    }

    @Test
    void announceRefusesAnUnsupportedChannel() {
        AgentActionCatalog.Validation invalid = AgentActionCatalog.validate("server.announce",
                Map.of("message", "test", "channel", "bossbar", "confirm", "true"));

        assertFalse(invalid.valid());
        assertTrue(invalid.error().contains("chat"), invalid.error());
    }

    @Test
    void theOfferedChannelsAreExactlyTheSupportedOnes() {
        // Miroir strict du serveur : proposer un canal de plus serait un bouton qui ne fait rien.
        assertEquals(java.util.List.of("chat", "actionbar", "title"), AgentActionCatalog.ANNOUNCE_CHANNELS);
        for (String channel : AgentActionCatalog.ANNOUNCE_CHANNELS) {
            assertTrue(AgentActionCatalog.validate("server.announce",
                    Map.of("message", "test", "channel", channel, "confirm", "true")).valid());
        }
    }

    @Test
    void everyQuickTemplateIsItselfAValidAnnounce() {
        // Un modèle proposé en un clic qui serait refusé à l'envoi serait un piège.
        for (String template : AgentActionCatalog.ANNOUNCE_TEMPLATES) {
            AgentActionCatalog.Validation valid = AgentActionCatalog.validate("server.announce",
                    Map.of("message", template, "confirm", "true"));
            assertTrue(valid.valid(), () -> template + " -> " + valid.error());
        }
    }

    @Test
    void logsTailAcceptsACursorAndALimitWithinBounds() {
        AgentActionCatalog.Validation valid = AgentActionCatalog.validate("server.logs.tail",
                Map.of("after", "42", "limit", "100"));

        assertTrue(valid.valid(), valid.error());
        assertEquals("42", valid.params().get("after"));
        assertEquals("100", valid.params().get("limit"));
    }

    @Test
    void logsTailRefusesNonNumericNegativeAndOutOfBoundsValues() {
        assertFalse(AgentActionCatalog.validate("server.logs.tail", Map.of("after", "abc")).valid());
        assertFalse(AgentActionCatalog.validate("server.logs.tail", Map.of("after", "-1")).valid());
        assertFalse(AgentActionCatalog.validate("server.logs.tail", Map.of("limit", "0")).valid());
        assertFalse(AgentActionCatalog.validate("server.logs.tail", Map.of("limit", "501")).valid());
    }

    @Test
    void logsTailNeedsNoConfirmationBecauseItChangesNothing() {
        assertTrue(AgentActionCatalog.validate("server.logs.tail", Map.of()).valid());
        assertFalse(AgentActionCatalog.spec("server.logs.tail").orElseThrow().mutation());
    }

    @Test
    void opsPermissionsAreMappedPerType() {
        assertEquals(Permission.OPS_ANNOUNCE,
                AgentActionCatalog.spec("server.announce").orElseThrow().permission());
        assertEquals(Permission.OPS_LOGS,
                AgentActionCatalog.spec("server.logs.tail").orElseThrow().permission());
        // L'annonce est une mutation sensible ; la console est un simple relevé.
        assertTrue(AgentActionCatalog.spec("server.announce").orElseThrow().sensitive());
        assertFalse(AgentActionCatalog.spec("server.logs.tail").orElseThrow().sensitive());
    }
}
