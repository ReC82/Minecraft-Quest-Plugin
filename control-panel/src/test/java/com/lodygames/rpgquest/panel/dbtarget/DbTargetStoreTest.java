package com.lodygames.rpgquest.panel.dbtarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chiffrement et stockage de la cible de migration (issue #42). */
class DbTargetStoreTest {

    private static final String MASTER = "un-secret-maitre-de-test-0123456789";
    private static final String PURPOSE = "db-migration-target-password";

    @TempDir
    Path tmp;

    @Test
    void secretBoxRoundTripsAndNeverRepeatsCiphertext() {
        SecretBox box = new SecretBox(MASTER, PURPOSE);
        String a = box.seal("p@ss é");
        String b = box.seal("p@ss é");

        assertNotEquals(a, b, "IV aléatoire");
        assertFalse(a.contains("p@ss"));
        assertEquals(Optional.of("p@ss é"), box.open(a));
    }

    @Test
    void secretBoxRejectsOtherKeyOtherPurposeAndTampering() {
        String sealed = new SecretBox(MASTER, PURPOSE).seal("secret");

        assertTrue(new SecretBox(MASTER + "x", PURPOSE).open(sealed).isEmpty(), "autre secret maître");
        assertTrue(new SecretBox(MASTER, "autre-usage").open(sealed).isEmpty(), "autre usage");
        // Altérer un OCTET décodé, pas un caractère Base64 : le dernier caractère avant « == » porte
        // des bits ignorés au décodage, l'échanger ne modifiait parfois rien (test instable).
        String prefix = sealed.substring(0, sealed.indexOf(':') + 1);
        byte[] raw = java.util.Base64.getDecoder().decode(sealed.substring(prefix.length()));
        raw[raw.length / 2] ^= 0x01;
        String tampered = prefix + java.util.Base64.getEncoder().encodeToString(raw);
        assertTrue(new SecretBox(MASTER, PURPOSE).open(tampered).isEmpty(), "altération détectée");
        assertTrue(new SecretBox(MASTER, PURPOSE).open("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SecretBox("court", PURPOSE));
    }

    @Test
    void storeKeepsPasswordOnEmptyInputAndInvalidatesTestOnChange() {
        DbTargetStore store = new DbTargetStore(tmp.resolve("cp.db").toString(), new SecretBox(MASTER, PURPOSE));
        store.save("db.example", 3306, "rpgquest", "rpg", "trust", "pw1", "owner");
        store.test((s, p) -> DbConnectionTester.Result.success("ok"));
        assertTrue(store.get().lastTestOk());

        store.save("db.example", 3306, "rpgquest", "rpg", "trust", "", "owner");
        assertEquals(Optional.of("pw1"), store.password(), "vide = conserver");
        assertTrue(store.get().lastTestOk(), "rien n'a changé : le test reste valable");

        store.save("autre.example", 3306, "rpgquest", "rpg", "trust", "", "owner");
        assertFalse(store.get().lastTestOk(), "un OK ne vaut que pour la cible testée");
        assertEquals("", store.get().lastTestAt());
    }

    /** Issue #43 : la base du panel vit dans sa propre table, avec son propre usage de chiffrement. */
    @Test
    void panelTargetIsIndependentFromMigrationTarget() {
        String db = tmp.resolve("cp.db").toString();
        DbTargetStore migration = new DbTargetStore(db, new SecretBox(MASTER, DbTargetKind.MIGRATION.secretPurpose()));
        DbTargetStore panel = new DbTargetStore(db, new SecretBox(MASTER, DbTargetKind.PANEL.secretPurpose()),
                DbTargetKind.PANEL);

        migration.save("db.example", 3306, "rpgquest", "rpg", "trust", "pw-plugin", "owner");
        panel.save("db.example", 3306, "rpgquest_control", "rpg_panel", "trust", "pw-panel", "owner");
        panel.clearPassword("owner");

        assertEquals("rpgquest", migration.get().database());
        assertEquals("rpgquest_control", panel.get().database());
        assertEquals(Optional.of("pw-plugin"), migration.password(), "effacer le panel ne touche pas l'autre cible");
        assertTrue(panel.password().isEmpty());

        // Un chiffré recopié d'une cible à l'autre ne s'ouvre pas : usages distincts.
        String sealed = new SecretBox(MASTER, DbTargetKind.MIGRATION.secretPurpose()).seal("pw");
        assertTrue(new SecretBox(MASTER, DbTargetKind.PANEL.secretPurpose()).open(sealed).isEmpty());
    }

    @Test
    void panelInventoryListsTablesReadOnly() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        new DbTargetStore(db, new SecretBox(MASTER, PURPOSE));
        new DbTargetStore(db, new SecretBox(MASTER, DbTargetKind.PANEL.secretPurpose()), DbTargetKind.PANEL);
        long sizeBefore = java.nio.file.Files.size(Path.of(db));

        java.util.List<PanelDbInventory.Table> tables = PanelDbInventory.read(db);

        assertEquals(java.util.List.of(new PanelDbInventory.Table("db_migration_target", 1),
                new PanelDbInventory.Table("panel_db_target", 1)), tables);
        assertEquals(sizeBefore, java.nio.file.Files.size(Path.of(db)));
        assertTrue(PanelDbInventory.read(tmp.resolve("absente.db").toString()).isEmpty(), "jamais créée");
        assertFalse(java.nio.file.Files.exists(tmp.resolve("absente.db")));
    }

    @Test
    void passwordSealedWithAnotherMasterIsReportedUnreadable() {
        String db = tmp.resolve("cp.db").toString();
        new DbTargetStore(db, new SecretBox(MASTER, PURPOSE)).save("h", 3306, "d", "u", "disable", "pw", "o");

        DbTargetStore rotated = new DbTargetStore(db, new SecretBox(MASTER + "-rotated", PURPOSE));

        assertTrue(rotated.get().passwordConfigured());
        assertTrue(rotated.get().passwordUnreadable());
        DbConnectionTester.Result result = rotated.test((s, p) -> DbConnectionTester.Result.success("jamais appelé"));
        assertFalse(result.ok());
        assertTrue(result.summary().contains("illisible"));
    }

    @Test
    void validationRejectsJdbcInjectionAndBadValues() {
        assertNull(DbTargetSettings.validate("db.example.com", "3306", "rpgquest", "rpg_user", "verify-full"));
        assertNull(DbTargetSettings.validate("10.0.0.5", "3306", "rpgquest", "rpg", "disable"));
        assertTrue(DbTargetSettings.validate("h/x?allowLoadLocalInfile=true", "3306", "d", "u", "disable") != null);
        assertTrue(DbTargetSettings.validate("h", "0", "d", "u", "disable") != null);
        assertTrue(DbTargetSettings.validate("h", "3306", "d;drop", "u", "disable") != null);
        assertTrue(DbTargetSettings.validate("h", "3306", "d", "u", "insecure") != null);
    }

    @Test
    void redactionRemovesPasswordHostAndAccount() {
        DbTargetSettings settings = new DbTargetSettings("db.example", 3306, "d", "rpg", "disable",
                true, false, "", "", "", false, "");

        String out = DbConnectionTester.redact("Access denied for 'rpg'@'db.example' using hunter2", settings, "hunter2");

        assertFalse(out.contains("hunter2") || out.contains("db.example") || out.contains("'rpg'"), out);
    }
}
