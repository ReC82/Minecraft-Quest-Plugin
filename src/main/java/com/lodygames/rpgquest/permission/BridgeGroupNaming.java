package com.lodygames.rpgquest.permission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Nommage des groupes LuckPerms <strong>appartenant au pont</strong> (issue #200).
 *
 * <h2>Pourquoi des groupes dédiés plutôt que des nœuds posés sur l'utilisateur</h2>
 *
 * <p>Première version du pont : il écrivait les nœuds de permission directement sur l'utilisateur
 * et reconnaissait « les siens » par un <strong>préfixe</strong>
 * ({@code rpgquest.build.}, {@code rpgquest.bypass.}…). Ce critère est faux, et le défaut est
 * sérieux : un administrateur qui accorde <em>à la main</em>
 * {@code rpgquest.build.hub.world_hub} dans le contexte {@code world=world_hub} pose un nœud
 * <strong>identique</strong> à celui du pont. Une synchronisation ultérieure, ne voyant pas ce droit
 * dans l'état voulu, l'aurait <strong>supprimé</strong> — détruisant une décision externe sans
 * aucun signal.</p>
 *
 * <p>La provenance doit donc être <strong>structurelle</strong> et non devinée. Le pont :</p>
 * <ul>
 *   <li>place les droits gérés dans des <strong>groupes LuckPerms qui lui appartiennent</strong>,
 *       reconnaissables à leur préfixe de nom réservé ;</li>
 *   <li>ne manipule sur un utilisateur que des nœuds d'<strong>héritage</strong>
 *       ({@code group.<nom réservé>}) ;</li>
 *   <li>ne touche donc <strong>jamais</strong> un nœud de permission posé sur un utilisateur, qu'il
 *       porte le même nom et le même contexte ou non.</li>
 * </ul>
 *
 * <h2>Longueur : la contrainte réelle de LuckPerms, mesurée</h2>
 *
 * <p>LuckPerms impose {@code MAX_GROUP_NAME_LENGTH = 36} (constante lue dans
 * {@code DataConstraints} du jar 5.5.87) et lève {@code IllegalArgumentException} au-delà. La
 * première version concaténait le préfixe et les <strong>32 caractères hexadécimaux</strong> d'un
 * identifiant de groupe PlugAdmin : {@code rpgq-} + 32 = <strong>37</strong>, donc systématiquement
 * refusé dès qu'un <em>vrai</em> identifiant était utilisé. Le défaut est passé inaperçu parce que la
 * sonde de validation employait un identifiant court ({@code rpgq-tc253probe}, 15 caractères).</p>
 *
 * <p>Le nom dérive désormais d'une <strong>empreinte</strong> courte et stable de l'identifiant
 * (SHA-256 tronqué), et non d'une troncature de l'identifiant lui-même : une empreinte répartit
 * uniformément, là où deux identifiants partageant un préfixe se retrouveraient avec le même nom
 * tronqué. {@link #MAX_LUCKPERMS_GROUP_NAME} reste vérifié explicitement, pour que l'erreur soit
 * <strong>lisible</strong> si la contrainte changeait un jour.</p>
 *
 * <h2>Nom stable, affichage lisible</h2>
 *
 * <p>Le nom technique dérive de l'<strong>identifiant</strong> du groupe PlugAdmin, pas de son
 * libellé : renommer un groupe dans le panel ne doit pas orpheliner un groupe LuckPerms ni faire
 * perdre ses droits à ses membres. Le libellé lisible est poussé séparément comme nom d'affichage,
 * ce qui rend l'origine reconnaissable dans LuckPerms malgré un nom technique opaque.</p>
 */
public final class BridgeGroupNaming {

    private BridgeGroupNaming() {
    }

    /**
     * Préfixe réservé. Un groupe LuckPerms qui ne le porte pas n'appartient <strong>pas</strong> au
     * pont et n'est jamais modifié ni retiré, même s'il accorde exactement les mêmes droits.
     */
    public static final String PREFIX = "rpgq-";

    /**
     * Longueur maximale d'un nom de groupe acceptée par LuckPerms
     * ({@code DataConstraints.MAX_GROUP_NAME_LENGTH}, mesurée sur la version 5.5.87). Au-delà,
     * l'API lève {@code IllegalArgumentException}.
     */
    public static final int MAX_LUCKPERMS_GROUP_NAME = 36;

    /**
     * Nombre de caractères hexadécimaux d'empreinte conservés. 16 pour 64 bits : avec quelques
     * dizaines de groupes, la probabilité de collision est de l'ordre de 10⁻¹⁶ — négligeable, et le
     * nom complet reste court ({@code rpgq-} + 16 = 21 caractères).
     */
    private static final int FINGERPRINT_LENGTH = 16;

    /**
     * Nom technique du groupe LuckPerms correspondant à un groupe PlugAdmin.
     *
     * <p>Déterministe : le même identifiant donne toujours le même nom, y compris après un
     * redémarrage ou un renommage du groupe dans le panel.</p>
     */
    public static String groupNameFor(String panelGroupId) {
        String normalized = panelGroupId == null ? "" : panelGroupId.trim().toLowerCase(Locale.ROOT);
        return PREFIX + fingerprint(normalized);
    }

    /**
     * {@code null} si ce nom est utilisable, sinon le motif du refus. Vérifié avant tout appel à
     * LuckPerms, pour qu'un dépassement de longueur produise un message compréhensible plutôt
     * qu'une {@code IllegalArgumentException} nue remontée jusqu'au panel.
     */
    public static String refusalReason(String luckPermsGroupName) {
        if (luckPermsGroupName == null || luckPermsGroupName.isBlank()) {
            return "nom de groupe vide";
        }
        if (luckPermsGroupName.length() > MAX_LUCKPERMS_GROUP_NAME) {
            return "nom de groupe trop long pour LuckPerms (" + luckPermsGroupName.length()
                    + " caractères, maximum " + MAX_LUCKPERMS_GROUP_NAME + ")";
        }
        return null;
    }

    /** {@code true} si ce groupe LuckPerms appartient au pont. */
    public static boolean isBridgeGroup(String luckPermsGroupName) {
        return luckPermsGroupName != null
                && luckPermsGroupName.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /**
     * Empreinte hexadécimale courte et stable. SHA-256 est disponible dans toute JVM ; son absence
     * serait une JVM cassée, d'où l'échec franc plutôt qu'un repli silencieux sur un nom deviné.
     */
    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(FINGERPRINT_LENGTH);
            for (int i = 0; hex.length() < FINGERPRINT_LENGTH && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible : JVM inattendue", e);
        }
    }
}
