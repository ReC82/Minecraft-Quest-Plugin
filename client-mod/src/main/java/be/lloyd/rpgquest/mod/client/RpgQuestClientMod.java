package be.lloyd.rpgquest.mod.client;

import be.lloyd.rpgquest.mod.content.ModContent;
import be.lloyd.rpgquest.mod.network.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prototype de mod client (mission étape 23) — jamais empaqueté dans le
 * plugin Paper, projet Gradle entièrement séparé (voir client-mod/ et
 * docs/CLIENT_MOD.md). Aucune logique de jeu critique : le serveur reste
 * l'autorité pour la progression, les drops, l'économie, les droits et les
 * achats (mission point 6). Ce mod ne fait qu'afficher du contenu et
 * annoncer sa propre présence/version au serveur — jamais l'inverse
 * (mission point 7).
 */
public final class RpgQuestClientMod implements ClientModInitializer {

    public static final String MOD_ID = "rpgquest_client";
    public static final Logger LOGGER = LoggerFactory.getLogger("RPGQuest Client");

    @Override
    public void onInitializeClient() {
        ModContent.register();
        ModNetworking.register();
        ModHud.register();
        LOGGER.info("RPGQuest client mod (prototype) initialisé.");
    }
}
