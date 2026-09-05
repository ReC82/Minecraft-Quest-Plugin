package com.lodygames.rpgquest.permission;

/**
 * Classe d'un monde vis-à-vis des permissions de build RPGQuest (issue #27) — résolue par {@link
 * BuildPermissionService#areaFor(String)} à partir de {@code permissions.build-areas} puis, à
 * défaut, des noms de mondes déjà connus de {@code config.yml} (Hub, Wild, monde des claims).
 *
 * @param kind catégorie de la zone de build
 * @param id   identifiant à l'intérieur de la catégorie : l'id du Hub pour {@link Kind#HUB}
 *             (« 0 » par défaut), la clé du monde pour {@link Kind#WORLD} ; vide pour
 *             {@link Kind#WILD} et {@link Kind#UNMANAGED}
 */
public record BuildArea(Kind kind, String id) {

    public enum Kind {
        /** Un Hub : permission {@code rpgquest.build.hub.<id>} (ou {@code rpgquest.build.hub.*}). */
        HUB,
        /** Le monde d'exploration : permission {@code rpgquest.build.wild}. */
        WILD,
        /** Un autre monde géré (ex. le monde des claims) : permission {@code rpgquest.build.world.<id>}. */
        WORLD,
        /** Monde hors du système de build RPGQuest : aucune permission de build ne s'y applique. */
        UNMANAGED
    }

    public static final BuildArea UNMANAGED = new BuildArea(Kind.UNMANAGED, "");

    public static BuildArea hub(String id) {
        return new BuildArea(Kind.HUB, id);
    }

    public static BuildArea wild() {
        return new BuildArea(Kind.WILD, "");
    }

    public static BuildArea world(String id) {
        return new BuildArea(Kind.WORLD, id);
    }
}
