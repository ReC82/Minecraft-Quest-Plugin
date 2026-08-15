package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Identité stable d'un PNJ, totalement indépendante de son nom personnalisé
 * affiché (purement cosmétique). Deux stratégies de stockage selon le type
 * d'entité, invisibles des appelants ({@code currentId}/{@code tag}/
 * {@code untag} ont le même contrat quel que soit le PNJ) :
 *
 * <ul>
 * <li><b>PNJ Citizens</b> (prioritaire quand Citizens est installé et actif) —
 * la liaison {@code NPC#getUniqueId()} ↔ id RPGQuest est persistée dans la
 * base SQLite de RPGQuest ({@link NpcBindingRepository}), jamais uniquement
 * sur l'entité Bukkit. C'est une nécessité, pas un choix : Citizens recrée
 * une <b>nouvelle</b> entité Bukkit éphémère à chaque (re)spawn (y compris
 * au redémarrage du serveur), donc tout ce qui est posé directement sur
 * cette entité (comme un {@link PersistentDataContainer}) est perdu dès le
 * prochain redémarrage — c'est exactement le bug initialement rapporté.
 * {@code NPC#getUniqueId()} est utilisé comme clé (garanti unique/stable par
 * Citizens lui-même) plutôt que {@code NPC#getId()} (dont la javadoc
 * CitizensAPI précise qu'il « n'est pas garanti unique entre sessions »).</li>
 * <li><b>Entité vanilla ordinaire</b> (Citizens absent, ou entité non gérée
 * par Citizens) — inchangé par rapport à l'implémentation d'origine : id
 * stocké dans le {@link PersistentDataContainer} de l'entité elle-même
 * ({@code rpgquest:npc_id}), qui survit nativement aux redémarrages car
 * cette entité-là n'est jamais recréée par un tiers.</li>
 * </ul>
 *
 * <p>Toute référence à un type Citizens (import {@code net.citizensnpcs.*})
 * est isolée dans {@link CitizensNpcBridge}, qui n'est instanciée — donc
 * jamais chargée par la JVM — que si Citizens est réellement présent et actif
 * (détecté ici via les seuls types Bukkit, jamais un type Citizens). C'est ce
 * qui permet à RPGQuest de fonctionner normalement sur un serveur sans
 * Citizens installé malgré la dépendance {@code compileOnly}.</p>
 */
public final class NpcIdentityService {

    private static final String NAMESPACE = "rpgquest";

    private final RPGQuestPlugin plugin;
    private final NpcIdRepository sequenceRepository;
    private final NpcBindingRepository citizensBindingRepository;
    private final NamespacedKey idKey;
    private final @Nullable CitizensNpcBridge citizensBridge;
    private final Map<UUID, String> citizensCache = new ConcurrentHashMap<>();

    public NpcIdentityService(RPGQuestPlugin plugin, NpcIdRepository sequenceRepository,
                               NpcBindingRepository citizensBindingRepository) {
        this.plugin = plugin;
        this.sequenceRepository = sequenceRepository;
        this.citizensBindingRepository = citizensBindingRepository;
        this.idKey = new NamespacedKey(plugin, "npc_id");
        this.citizensBridge = citizensActive(plugin) ? new CitizensNpcBridge() : null;

        if (citizensBridge != null) {
            citizensBindingRepository.loadAll().thenAccept(bindings ->
                    bindings.forEach(binding -> citizensCache.put(binding.citizensUuid(), binding.npcId())))
                    .exceptionally(error -> {
                        plugin.getSLF4JLogger().error("Impossible de charger les liaisons PNJ Citizens.", error);
                        return null;
                    });
        }
    }

    /** Vérifie via les seuls types Bukkit (Plugin/PluginManager) — jamais un type Citizens. */
    private static boolean citizensActive(RPGQuestPlugin plugin) {
        Plugin citizens = plugin.getServer().getPluginManager().getPlugin("Citizens");
        return citizens != null && citizens.isEnabled();
    }

    /** Vrai si Citizens est installé et actif : détermine si les écouteurs dédiés Citizens doivent être enregistrés. */
    public boolean citizensAvailable() {
        return citizensBridge != null;
    }

    /** Vrai si {@code entity} est un PNJ géré par Citizens (jamais vrai si Citizens n'est pas actif). */
    public boolean isCitizensNpc(Entity entity) {
        return citizensBridge != null && citizensBridge.isNpc(entity);
    }

    /** Identifiant stable actuellement porté par l'entité, ou vide si elle n'est pas marquée. */
    public Optional<String> currentId(Entity entity) {
        if (citizensBridge != null) {
            Optional<UUID> citizensUuid = citizensBridge.citizensUniqueId(entity);
            if (citizensUuid.isPresent()) {
                return Optional.ofNullable(citizensCache.get(citizensUuid.get()));
            }
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    /** Id numérique Citizens de l'entité, pour affichage admin uniquement (jamais utilisé comme clé). */
    public Optional<Integer> citizensNumericId(Entity entity) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.resolve(entity).map(CitizensNpcBridge.Ref::citizensNumericId);
    }

    /**
     * Vrai si {@code candidate} peut servir de fragment de {@link NamespacedKey} (mêmes règles que
     * Bukkit). Toujours vérifié **avant** tout appel à {@link #tag}, jamais après — c'est cette
     * construction non gardée d'une clé à partir d'un texte non validé qui provoquait le bug
     * historique décrit dans l'ancienne implémentation (nom affiché utilisé comme identifiant).
     */
    public static boolean isValidId(String candidate) {
        try {
            new NamespacedKey(NAMESPACE, candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Forme canonique d'un identifiant auto-généré (secours quand aucun id explicite n'est fourni). */
    public static String generatedId(int sequence) {
        return "npc_" + sequence;
    }

    /**
     * Marque {@code entity} avec {@code requestedId} (déjà validé par {@link #isValidId}), ou un
     * identifiant auto-généré ({@link #generatedId}) si {@code requestedId} est {@code null}.
     * Idempotent : une entité déjà marquée conserve son id existant quel que soit
     * {@code requestedId} — un ré-étiquetage explicite doit d'abord passer par {@link #untag}.
     */
    public CompletableFuture<TagResult> tag(Entity entity, @Nullable String requestedId) {
        if (citizensBridge != null) {
            Optional<CitizensNpcBridge.Ref> ref = citizensBridge.resolve(entity);
            if (ref.isPresent()) {
                return tagCitizens(ref.get(), requestedId);
            }
        }
        return tagPlainEntity(entity, requestedId);
    }

    private CompletableFuture<TagResult> tagCitizens(CitizensNpcBridge.Ref ref, @Nullable String requestedId) {
        String existing = citizensCache.get(ref.citizensUuid());
        if (existing != null) {
            return CompletableFuture.completedFuture(new TagResult(false, existing));
        }
        if (requestedId != null) {
            return citizensBindingRepository.upsert(ref.citizensUuid(), ref.citizensNumericId(), requestedId)
                    .thenApply(ignored -> {
                        citizensCache.put(ref.citizensUuid(), requestedId);
                        return new TagResult(true, requestedId);
                    });
        }
        return sequenceRepository.allocateId().thenCompose(sequence -> {
            String npcId = generatedId(sequence);
            return citizensBindingRepository.upsert(ref.citizensUuid(), ref.citizensNumericId(), npcId)
                    .thenApply(ignored -> {
                        citizensCache.put(ref.citizensUuid(), npcId);
                        return new TagResult(true, npcId);
                    });
        });
    }

    private CompletableFuture<TagResult> tagPlainEntity(Entity entity, @Nullable String requestedId) {
        Optional<String> existing = currentId(entity);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(new TagResult(false, existing.get()));
        }
        if (requestedId != null) {
            entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, requestedId);
            return CompletableFuture.completedFuture(new TagResult(true, requestedId));
        }

        CompletableFuture<TagResult> result = new CompletableFuture<>();
        sequenceRepository.allocateId()
                .thenAccept(sequence -> runOnMainThread(() -> {
                    String npcId = generatedId(sequence);
                    entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, npcId);
                    result.complete(new TagResult(true, npcId));
                }))
                .exceptionally(error -> {
                    result.completeExceptionally(error);
                    return null;
                });
        return result;
    }

    /** Retire l'identifiant de l'entité. Un id auto-généré n'est jamais réutilisé après un untag. */
    public CompletableFuture<Boolean> untag(Entity entity) {
        if (citizensBridge != null) {
            Optional<CitizensNpcBridge.Ref> ref = citizensBridge.resolve(entity);
            if (ref.isPresent()) {
                UUID citizensUuid = ref.get().citizensUuid();
                if (citizensCache.remove(citizensUuid) == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return citizensBindingRepository.delete(citizensUuid).thenApply(ignored -> true);
            }
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(idKey, PersistentDataType.STRING)) {
            return CompletableFuture.completedFuture(false);
        }
        pdc.remove(idKey);
        return CompletableFuture.completedFuture(true);
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** @param created {@code false} si l'entité était déjà marquée (id existant renvoyé tel quel). */
    public record TagResult(boolean created, String npcId) {
    }
}
