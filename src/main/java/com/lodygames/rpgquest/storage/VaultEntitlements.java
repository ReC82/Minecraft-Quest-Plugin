package com.lodygames.rpgquest.storage;

import com.lodygames.rpgquest.config.PersonalVaultConfig;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Droits d'un joueur sur ses coffres personnels (issue #106) : accès et capacité.
 *
 * <p>Aucune mécanique de progression parallèle : les déblocages sont des <strong>variables
 * joueur</strong> ({@code player_variables}), comme {@code CLAIM_TIER_1}. Toute source existante
 * peut donc les accorder — récompense de quête {@code VARIABLE}, action de dialogue
 * {@code SET_VARIABLE}, {@code /rpgadmin player variable set}, outil variable de PlugAdmin — et
 * toute source future (événement, saison, VIP) n'a qu'à poser la même variable.</p>
 *
 * <ul>
 *   <li>{@code VAULT_UNLOCK_<ID>} = {@code true} : le coffre {@code <id>} est ouvert (les coffres de
 *       {@code personal-vaults.starter-vaults} le sont toujours).</li>
 *   <li>{@code VAULT_SLOTS_<ID>} = 9…54 (multiple de 9) : taille du coffre, si elle dépasse
 *       {@code personal-vaults.default-slots}.</li>
 * </ul>
 *
 * <p>Relu en base à chaque ouverture : un déblocage vaut aussitôt dans tous les Hubs, et retirer
 * un droit ne fait que refuser l'ouverture — le contenu n'est jamais touché.</p>
 */
public final class VaultEntitlements {

    public static final String UNLOCK_PREFIX = "VAULT_UNLOCK_";
    public static final String SLOTS_PREFIX = "VAULT_SLOTS_";

    private final PlayerVariableRepository variables;
    private final Supplier<PersonalVaultConfig> config;

    public VaultEntitlements(PlayerVariableRepository variables, Supplier<PersonalVaultConfig> config) {
        this.variables = variables;
        this.config = config;
    }

    /** Droits résolus pour un coffre : ouvert ou non, et nombre de cases offertes. */
    public record VaultAccess(boolean unlocked, int slots) {
    }

    public static String unlockKey(VaultId vaultId) {
        return UNLOCK_PREFIX + vaultId.value().toUpperCase(Locale.ROOT);
    }

    public static String slotsKey(VaultId vaultId) {
        return SLOTS_PREFIX + vaultId.value().toUpperCase(Locale.ROOT);
    }

    /** Lecture seule, sur l'exécuteur de la base. */
    public CompletableFuture<VaultAccess> resolve(UUID playerId, VaultId vaultId) {
        PersonalVaultConfig current = config.get();
        return variables.findAllForPlayer(playerId).thenApply(vars -> resolve(current, vars, vaultId));
    }

    static VaultAccess resolve(PersonalVaultConfig config, Map<String, String> vars, VaultId vaultId) {
        boolean unlocked = config.starterVaults().contains(vaultId.value())
                || "true".equals(trimmed(vars.get(unlockKey(vaultId))));
        return new VaultAccess(unlocked, Math.max(config.defaultSlots(), grantedSlots(vars.get(slotsKey(vaultId)))));
    }

    /** Une valeur illisible ou hors bornes n'accorde rien (et n'en retire rien). */
    private static int grantedSlots(String raw) {
        try {
            int slots = Integer.parseInt(trimmed(raw));
            return PersonalVaultConfig.isValidSlots(slots) ? slots : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
