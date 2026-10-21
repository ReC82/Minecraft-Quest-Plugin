package com.lodygames.rpgquest.travel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

/**
 * Issue #569 : les valeurs par défaut doivent être complètes quel que soit l'ordre de chargement.
 * Sur DEV, {@code Warning} était initialisée la première (via {@code PortalMessagesYaml}) : {@link
 * PortalMessages#NONE} recevait alors un avertissement {@code null}, un portail sans personnalisation
 * n'était plus « vide » et son empreinte changeait.
 */
class PortalMessagesTest {

    @Test
    void theDefaultsAreCompleteWhenWarningIsLoadedFirst() throws Exception {
        URL classes = PortalMessages.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> warning = Class.forName(PortalMessages.Warning.class.getName(), true, isolated);
            assertNotNull(warning.getField("DEFAULT").get(null));
            Class<?> messages = Class.forName(PortalMessages.class.getName(), true, isolated);
            Object none = messages.getField("NONE").get(null);
            assertNotNull(messages.getMethod("warning").invoke(none), "NONE sans avertissement par défaut");
            assertTrue((Boolean) messages.getMethod("isEmpty").invoke(none));
        }
    }

    @Test
    void anUncustomizedPortalIsEmpty() {
        assertEquals(PortalMessages.Warning.DEFAULT, PortalMessages.NONE.warning());
        assertTrue(PortalMessages.NONE.withWarning(PortalMessages.Warning.DEFAULT).isEmpty());
    }
}
