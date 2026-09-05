package com.lodygames.rpgquest.bootstrap;

/**
 * Contrat de cycle de vie commun à tous les services du plugin
 * (configuration, base de données, futurs moteurs de quêtes/dialogues...).
 */
public interface PluginService {

    void start();

    void stop();

    default String name() {
        return getClass().getSimpleName();
    }
}
