package com.lodygames.rpgquest.entitlement.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Administration du catalogue {@code entitlements.yml} depuis PlugAdmin (issue #474) : création du
 * fichier, récompenses (créer, modifier, dupliquer, activer / désactiver) et interrupteur général.
 * Mêmes garanties que l'éditeur de tables ({@link EntitlementsFile}) : fichier actuel valide,
 * relecture par le vrai chargeur avant écriture, écriture atomique, restauration si le rechargement
 * échoue. Chaque opération ne touche que sa clé : les autres récompenses, les tables et les
 * commentaires du fichier sont conservés.
 *
 * <p>Jamais d'activation implicite : le fichier est créé désactivé et vide, une copie de récompense
 * est créée désactivée. Pas de suppression (une récompense peut être citée par des tables, des boss,
 * des événements ou des droits déjà ouverts) : on la désactive. Appelé hors du thread principal.</p>
 */
public final class RewardCatalogEditor {

    /**
     * Clé d'une <em>nouvelle</em> récompense : comme le chargeur, sans point — YAML lirait
     * {@code gemme.rare} comme deux niveaux imbriqués.
     */
    public static final Pattern NEW_KEY = Pattern.compile("[a-z0-9_-]{1,64}");

    /** Contenu exact du fichier créé par « Créer le catalogue » : désactivé, sans récompense ni table. */
    public static final String INITIAL_TEXT = """
            # Catalogue des récompenses RPGQuest (entitlements.yml), créé depuis PlugAdmin (issue #474).
            # Il se gère depuis PlugAdmin → Récompenses : aucune modification à la main n'est nécessaire.
            # Créé DÉSACTIVÉ et vide : rien n'est promis ni remis tant qu'il n'est pas activé depuis PlugAdmin.
            enabled: false
            rewards: {}
            tables: {}
            """;

    /** Résultat : {@code code} stable pour PlugAdmin, {@code message} en français. */
    public record Outcome(boolean success, String code, String message, @Nullable RewardDefinition reward) {

        static Outcome refused(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    private final EntitlementsFile file;

    RewardCatalogEditor(EntitlementsFile file) {
        this.file = file;
    }

    /** Crée le fichier, désactivé et vide. Refusé si un fichier existe déjà, valide ou non. */
    public Outcome initialize() {
        synchronized (file) {
            if (file.exists()) {
                return Outcome.refused("EXISTS", "Un catalogue existe déjà sur ce serveur (entitlements.yml) : il n'a "
                        + "pas été touché. Recharger la page.");
            }
            EntitlementsFile.Failure failure = file.write(null, INITIAL_TEXT, new RewardCatalog(false, Map.of()),
                    "création du catalogue");
            if (failure != null) {
                return Outcome.refused(failure.code(), failure.message());
            }
            file.logger().info("Récompenses : catalogue entitlements.yml créé depuis PlugAdmin (désactivé, vide).");
            return new Outcome(true, "INITIALIZED", "Catalogue créé, désactivé et sans récompense. Ajouter des "
                    + "récompenses, puis l'activer quand tout est prêt.", null);
        }
    }

    /**
     * Crée ou remplace une récompense.
     *
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle)
     */
    public Outcome saveReward(RewardDefinition wanted, @Nullable String expectedVersion, boolean create) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            RewardDefinition existing = current.catalog().rewards().get(wanted.key());
            if (create) {
                Outcome refused = refuseNewKey(current.catalog(), wanted.key());
                if (refused != null) {
                    return refused;
                }
            } else if (existing == null) {
                return Outcome.refused("NOT_FOUND", "Récompense « " + wanted.key() + " » introuvable (renommée ou "
                        + "retirée du fichier ?). Recharger la page.");
            }
            if (existing != null && expectedVersion != null && !expectedVersion.isBlank()
                    && !existing.version().equals(expectedVersion)) {
                return Outcome.refused("STALE", "La récompense « " + wanted.key() + " » a changé depuis l'ouverture du "
                        + "formulaire : rien n'a été écrit. Recharger la page puis refaire la modification.");
            }
            if (wanted.equals(existing)) {
                return new Outcome(true, "UNCHANGED", "Aucune modification : la récompense « " + wanted.label()
                        + " » est déjà enregistrée ainsi.", existing);
            }
            return write(current, wanted, create ? "CREATED" : "UPDATED", (create ? "Récompense « "
                    : "Récompense modifiée : « ") + wanted.label() + " » enregistrée"
                    + (wanted.enabled() ? "." : " (désactivée : promise à personne tant qu'elle ne l'est pas).")
                    + (create ? "" : " Les droits déjà ouverts remettront ce nouveau contenu."));
        }
    }

    /** Copie {@code sourceKey} sous {@code newKey}, <strong>désactivée</strong> : à vérifier puis activer. */
    public Outcome duplicateReward(String sourceKey, String newKey, @Nullable String newLabel) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            RewardDefinition source = current.catalog().rewards().get(sourceKey);
            if (source == null) {
                return Outcome.refused("NOT_FOUND", "Récompense « " + sourceKey + " » introuvable : recharger la page.");
            }
            Outcome refused = refuseNewKey(current.catalog(), newKey);
            if (refused != null) {
                return refused;
            }
            String label = newLabel == null || newLabel.isBlank()
                    ? truncate(source.label(), RewardTableEditor.LABEL_SUFFIX_ROOM) + " (copie)" : newLabel.trim();
            return write(current, source.with(newKey, label, false), "DUPLICATED", "Copie « " + label + " » créée, "
                    + "désactivée : la modifier, puis l'activer. Aucune table ni aucun boss ne l'utilise encore.");
        }
    }

    /** Active ou désactive une récompense ; son contenu ne change pas. */
    public Outcome setRewardEnabled(String key, boolean enabled) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            RewardDefinition existing = current.catalog().rewards().get(key);
            if (existing == null) {
                return Outcome.refused("NOT_FOUND", "Récompense « " + key + " » introuvable : recharger la page.");
            }
            if (existing.enabled() == enabled) {
                return new Outcome(true, "UNCHANGED", "La récompense « " + existing.label() + " » est déjà "
                        + (enabled ? "active" : "désactivée") + ".", existing);
            }
            return write(current, existing.with(key, existing.label(), enabled), "TOGGLED", enabled
                    ? "Récompense « " + existing.label() + " » activée : les tables, boss et événements qui la "
                    + "citent peuvent de nouveau la promettre."
                    : "Récompense « " + existing.label() + " » désactivée : plus promise à personne (un tirage qui "
                    + "tombe dessus ne donne rien, un boss ne la déclare plus). Les droits déjà ouverts restent remis.");
        }
    }

    /** Interrupteur général {@code enabled} ; récompenses et tables inchangées. */
    public Outcome setCatalogEnabled(boolean enabled) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            RewardCatalog before = current.catalog();
            if (before.enabled() == enabled) {
                return new Outcome(true, "UNCHANGED", "Les récompenses sont déjà " + (enabled ? "actives" : "désactivées")
                        + " sur ce serveur.", null);
            }
            current.yaml().set("enabled", enabled);
            RewardCatalog expected = new RewardCatalog(enabled, before.rewards(), before.tables());
            EntitlementsFile.Failure failure = file.write(current.bytes(), current.yaml().saveToString(), expected,
                    "interrupteur général");
            if (failure != null) {
                return Outcome.refused(failure.code(), failure.message());
            }
            file.logger().info("Récompenses : catalogue {} depuis PlugAdmin.", enabled ? "activé" : "désactivé");
            return new Outcome(true, "TOGGLED", enabled
                    ? "Récompenses activées : les quêtes et boss tirent leurs tables actives, les droits en attente "
                    + "sont remis aux joueurs connectés."
                    : "Récompenses désactivées : plus aucun tirage ni nouveau droit (quêtes, boss, événements) ; les "
                    + "joueurs en sont avertis. Les droits déjà ouverts attendent la réactivation, rien n'est perdu.",
                    null);
        }
    }

    private static @Nullable Outcome refuseNewKey(RewardCatalog catalog, String key) {
        if (!NEW_KEY.matcher(key).matches()) {
            return Outcome.refused("INVALID", "Identifiant « " + key + " » invalide : minuscules, chiffres, _ ou - "
                    + "(64 caractères au plus), ex. epee_du_heros.");
        }
        if (RewardTableEditor.RESERVED_IDS.contains(key)) {
            return Outcome.refused("INVALID", "« " + key + " » ne peut pas servir d'identifiant (YAML le lirait comme "
                    + "oui/non) : en choisir un autre, par exemple « " + key + "_recompense ».");
        }
        if (catalog.rewards().containsKey(key)) {
            return Outcome.refused("EXISTS", "Une récompense « " + key + " » existe déjà : choisir un autre "
                    + "identifiant, ou modifier celle-ci.");
        }
        return null;
    }

    private Outcome write(EntitlementsFile.Current current, RewardDefinition reward, String code, String message) {
        String text;
        try {
            text = render(current.yaml(), reward);
        } catch (RuntimeException e) {
            return Outcome.refused("INVALID", "Récompense « " + reward.key() + " » impossible à écrire : "
                    + e.getMessage());
        }
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>(current.catalog().rewards());
        rewards.put(reward.key(), reward);
        RewardCatalog expected = new RewardCatalog(current.catalog().enabled(), rewards, current.catalog().tables());
        EntitlementsFile.Failure failure = file.write(current.bytes(), text, expected, "récompense " + reward.key());
        if (failure != null) {
            return Outcome.refused(failure.code(), failure.message());
        }
        file.logger().info("Récompenses : récompense « {} » v{} écrite depuis PlugAdmin ({}).", reward.key(),
                reward.version(), code);
        return new Outcome(true, code, message, reward);
    }

    /** Remplace (ou ajoute) {@code rewards.<clé>} et rien d'autre ; l'ordre des récompenses est conservé. */
    static String render(YamlConfiguration yaml, RewardDefinition reward) {
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards == null) {
            rewards = yaml.createSection("rewards");
        }
        ConfigurationSection section = rewards.createSection(reward.key());
        section.set("label", reward.label());
        section.set("enabled", reward.enabled());
        if (!reward.items().isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (RewardDefinition.Item item : reward.items()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("item", item.itemId());
                line.put("amount", item.amount());
                if (!item.enchantments().isEmpty()) {
                    line.put("enchantments", new LinkedHashMap<>(item.enchantments()));
                }
                if (item.potion() != null) {
                    line.put("potion", item.potion());
                }
                items.add(line);
            }
            section.set("items", items);
        }
        if (reward.perk() != null) {
            ConfigurationSection perk = section.createSection("perk");
            perk.set("key", reward.perk().key());
            perk.set("tier", reward.perk().tier());
        }
        return yaml.saveToString();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim();
    }
}
