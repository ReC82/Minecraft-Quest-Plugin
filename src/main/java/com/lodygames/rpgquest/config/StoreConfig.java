package com.lodygames.rpgquest.config;

/**
 * Paramètres du sondage de livraisons boutique (mission étape 22). Le
 * jeton serveur-à-serveur n'est jamais ici : {@code
 * store.StoreDeliveryService} le lit directement depuis la variable
 * d'environnement {@code RPGQUEST_WEB_API_TOKEN} (même secret que web-api,
 * mission point 9), jamais depuis {@code config.yml}.
 */
public record StoreConfig(boolean enabled, String webApiBaseUrl, int pollIntervalSeconds) {
}
