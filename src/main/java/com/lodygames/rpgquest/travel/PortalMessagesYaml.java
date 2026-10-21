package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.PortalMessages.Mode;
import com.lodygames.rpgquest.travel.model.PortalMessages.Warning;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Sections {@code messages:} et {@code warning:} d'un fichier {@code world-portals/<id>.yml}
 * (issue #569) ; toutes les clés sont facultatives, une valeur absente ou vide = texte par défaut :
 *
 * <pre>
 * messages:
 *   welcome: "&lt;green&gt;Bienvenue au Village.&lt;/green&gt;"
 *   preparing: ""
 *   success: ""
 *   world-unavailable: ""
 *   unsafe-arrival: ""
 *   refusal: ""
 * warning:
 *   mode: ALWAYS        # DEFAULT (vers le Wild seulement), ALWAYS, NEVER
 *   title: ""
 *   text: ""
 *   hint: ""
 *   enter: ""
 *   enter-and-hide: ""
 *   cancel: ""
 *   cancel-text: ""
 *   allow-hide: true
 * </pre>
 *
 * <p>Une section invalide fait <strong>ignorer le fichier</strong>, comme {@code access:} (#567).</p>
 */
final class PortalMessagesYaml {

    static final String MESSAGES = "messages";
    static final String WARNING = "warning";

    private PortalMessagesYaml() {
    }

    static PortalMessages read(ConfigurationSection root) {
        ConfigurationSection m = section(root, MESSAGES);
        ConfigurationSection w = section(root, WARNING);
        Warning warning = Warning.DEFAULT;
        if (w != null) {
            Mode mode;
            try {
                mode = Mode.valueOf(w.getString("mode", "DEFAULT").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("« warning.mode » doit valoir DEFAULT, ALWAYS ou NEVER.");
            }
            if (w.isSet("allow-hide") && !w.isBoolean("allow-hide")) {
                throw new IllegalArgumentException("« warning.allow-hide » doit valoir true ou false.");
            }
            warning = new Warning(mode, text(w, "title"), text(w, "text"), text(w, "hint"), text(w, "enter"),
                    text(w, "enter-and-hide"), text(w, "cancel"), text(w, "cancel-text"), w.getBoolean("allow-hide", true));
        }
        if (m == null) {
            return PortalMessages.NONE.withWarning(warning);
        }
        return new PortalMessages(text(m, "welcome"), text(m, "preparing"), text(m, "success"),
                text(m, "world-unavailable"), text(m, "unsafe-arrival"), text(m, "refusal"), warning);
    }

    /** Seules les valeurs personnalisées sont écrites : sans personnalisation, fichier identique à avant #569. */
    static void write(ConfigurationSection root, PortalMessages messages) {
        setIfPresent(root, MESSAGES + ".welcome", messages.welcome());
        setIfPresent(root, MESSAGES + ".preparing", messages.preparing());
        setIfPresent(root, MESSAGES + ".success", messages.success());
        setIfPresent(root, MESSAGES + ".world-unavailable", messages.unavailable());
        setIfPresent(root, MESSAGES + ".unsafe-arrival", messages.unsafe());
        setIfPresent(root, MESSAGES + ".refusal", messages.refusal());
        Warning w = messages.warning();
        if (w.isDefault()) {
            return;
        }
        root.set(WARNING + ".mode", w.mode().name());
        setIfPresent(root, WARNING + ".title", w.title());
        setIfPresent(root, WARNING + ".text", w.text());
        setIfPresent(root, WARNING + ".hint", w.hint());
        setIfPresent(root, WARNING + ".enter", w.enter());
        setIfPresent(root, WARNING + ".enter-and-hide", w.enterHide());
        setIfPresent(root, WARNING + ".cancel", w.cancel());
        setIfPresent(root, WARNING + ".cancel-text", w.cancelText());
        if (!w.allowHide()) {
            root.set(WARNING + ".allow-hide", false);
        }
    }

    /** Forme canonique stable (empreinte, journal). */
    static String canonical(PortalMessages m) {
        Warning w = m.warning();
        return String.join("\u001f", "welcome=" + m.welcome(), "preparing=" + m.preparing(), "success=" + m.success(),
                "unavailable=" + m.unavailable(), "unsafe=" + m.unsafe(), "refusal=" + m.refusal(),
                "mode=" + w.mode(), "title=" + w.title(), "text=" + w.text(), "hint=" + w.hint(),
                "enter=" + w.enter(), "enterHide=" + w.enterHide(), "cancel=" + w.cancel(),
                "cancelText=" + w.cancelText(), "allowHide=" + w.allowHide());
    }

    private static ConfigurationSection section(ConfigurationSection root, String key) {
        if (!root.isSet(key)) {
            return null;
        }
        ConfigurationSection s = root.getConfigurationSection(key);
        if (s == null) {
            throw new IllegalArgumentException("« " + key + " » doit être une section.");
        }
        return s;
    }

    private static String text(ConfigurationSection s, String key) {
        if (s.isSet(key) && !s.isString(key)) {
            throw new IllegalArgumentException("« " + s.getName() + "." + key + " » doit être un texte.");
        }
        return s.getString(key, "");
    }

    private static void setIfPresent(ConfigurationSection root, String path, String value) {
        if (!value.isEmpty()) {
            root.set(path, value);
        }
    }
}
