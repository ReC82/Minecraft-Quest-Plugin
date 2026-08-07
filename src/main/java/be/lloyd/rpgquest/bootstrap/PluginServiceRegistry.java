package be.lloyd.rpgquest.bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * Démarre les services un à un, dans l'ordre où {@link #start(PluginService)}
 * est appelé, et les arrête dans l'ordre inverse (LIFO). Si un service échoue
 * à démarrer, tous les services déjà démarrés sont arrêtés avant que l'échec
 * ne soit propagé à l'appelant — jamais de service à moitié initialisé.
 */
public final class PluginServiceRegistry {

    private final List<PluginService> started = new ArrayList<>();
    private final Logger logger;

    public PluginServiceRegistry(Logger logger) {
        this.logger = logger;
    }

    public void start(PluginService service) {
        try {
            logger.info("Démarrage du service : {}", service.name());
            service.start();
            started.add(service);
        } catch (RuntimeException e) {
            logger.error("Échec du démarrage du service {} : arrêt des services déjà démarrés.", service.name(), e);
            stopAll();
            throw e;
        }
    }

    public void stopAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            PluginService service = started.get(i);
            try {
                logger.info("Arrêt du service : {}", service.name());
                service.stop();
            } catch (RuntimeException e) {
                logger.error("Erreur lors de l'arrêt du service {}", service.name(), e);
            }
        }
        started.clear();
    }
}
