package com.lodygames.rpgquest.panel;

import com.lodygames.rpgquest.panel.audit.AuditLog;
import com.lodygames.rpgquest.panel.audit.SqliteAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.config.PanelConfig;
import com.lodygames.rpgquest.panel.config.PanelConfigException;
import com.lodygames.rpgquest.panel.config.PanelConfigLoader;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import com.lodygames.rpgquest.panel.web.PanelApp;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Point d'entrée du RPGQuest Control Panel.
 *
 * <pre>
 *   java -jar control-panel.jar                 # démarre le panel (config par env + control-panel.properties)
 *   java -jar control-panel.jar hash-password   # génère un hash pour RPGQUEST_PANEL_OWNER_HASH
 * </pre>
 */
public final class PanelMain {

    private PanelMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("hash-password")) {
            hashPassword(args);
            return;
        }

        PanelConfig config;
        try {
            config = new PanelConfigLoader().load();
        } catch (PanelConfigException e) {
            System.err.println("[control-panel] Configuration invalide : " + e.getMessage());
            System.exit(2);
            return;
        }

        AuditLog audit = new SqliteAuditLog(config.panelDbPath());
        PanelApp app = new PanelApp(config, audit, new BridgeClient());
        int port = app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "rpgquest-panel-shutdown"));

        System.out.println("[control-panel] RPGQuest Control Panel à l'écoute sur http://" + config.bind() + ":" + port);
        System.out.println("[control-panel] cible par défaut : " + config.defaultTarget().label()
                + " (" + config.defaultTarget().bridgeBaseUrl() + ")");
        Thread.currentThread().join();
    }

    private static void hashPassword(String[] args) throws Exception {
        String password;
        if (args.length >= 2) {
            password = args[1];
        } else {
            Console console = System.console();
            if (console != null) {
                char[] chars = console.readPassword("Mot de passe owner : ");
                password = new String(chars);
            } else {
                System.err.print("Mot de passe owner (stdin) : ");
                password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
        }
        if (password == null || password.isBlank()) {
            System.err.println("[control-panel] mot de passe vide, abandon.");
            System.exit(1);
            return;
        }
        String hash = new PasswordHasher().hash(password);
        System.out.println();
        System.out.println("RPGQUEST_PANEL_OWNER_HASH=" + hash);
    }
}
