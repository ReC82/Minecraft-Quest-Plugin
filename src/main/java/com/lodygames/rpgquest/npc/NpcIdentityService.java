package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.NpcIdRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * Identité stable d'un PNJ : une entité vivante quelconque, marquée par
 * {@code /rpgadmin npc tag} via une clé {@link PersistentDataContainer}
 * ({@code rpgquest:npc_id}, {@link PersistentDataType#STRING}), totalement
 * indépendante de son nom personnalisé affiché ({@code Entity#customName()}),
 * qui reste **purement cosmétique** et peut être renommé librement sans
 * jamais casser le lien.
 *
 * <p>Avant cette classe, {@code QuestNpcInteractListener} et
 * {@code DialogueNpcInteractListener} lisaient directement le nom personnalisé
 * de l'entité comme identifiant — {@code DialogueNpcInteractListener} allait
 * jusqu'à le passer tel quel au constructeur {@link NamespacedKey}, qui exige
 * des caractères {@code [a-z0-9_.-/]} : renommer une entité avec une
 * majuscule ou une espace (ex. « Guide ») faisait lever
 * {@code IllegalArgumentException} au clic droit. Cette classe supprime la
 * classe de bug entière : l'identité n'est plus jamais dérivée d'un texte
 * choisi librement par un joueur/administrateur pour l'affichage.</p>
 */
public final class NpcIdentityService {

    private static final String NAMESPACE = "rpgquest";

    private final RPGQuestPlugin plugin;
    private final NpcIdRepository repository;
    private final NamespacedKey idKey;

    public NpcIdentityService(RPGQuestPlugin plugin, NpcIdRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.idKey = new NamespacedKey(plugin, "npc_id");
    }

    /** Identifiant stable actuellement porté par l'entité, ou vide si elle n'est pas marquée. */
    public Optional<String> currentId(Entity entity) {
        return Optional.ofNullable(entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    /**
     * Vrai si {@code candidate} peut servir de fragment de {@link NamespacedKey} (mêmes règles que
     * Bukkit). Toujours vérifié **avant** tout appel à {@link #tag}, jamais après — c'est cette
     * construction non gardée d'une clé à partir d'un texte non validé qui provoquait le bug
     * historique décrit ci-dessus.
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
     * {@code requestedId} — un ré-étiquetage explicite doit d'abord passer par {@link #untag}, pour
     * ne jamais faire dériver silencieusement le lien vers une nouvelle identité.
     */
    public CompletableFuture<TagResult> tag(Entity entity, @Nullable String requestedId) {
        Optional<String> existing = currentId(entity);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(new TagResult(false, existing.get()));
        }
        if (requestedId != null) {
            entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, requestedId);
            return CompletableFuture.completedFuture(new TagResult(true, requestedId));
        }

        CompletableFuture<TagResult> result = new CompletableFuture<>();
        repository.allocateId()
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
    public boolean untag(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(idKey, PersistentDataType.STRING)) {
            return false;
        }
        pdc.remove(idKey);
        return true;
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** @param created {@code false} si l'entité était déjà marquée (id existant renvoyé tel quel). */
    public record TagResult(boolean created, String npcId) {
    }
}
