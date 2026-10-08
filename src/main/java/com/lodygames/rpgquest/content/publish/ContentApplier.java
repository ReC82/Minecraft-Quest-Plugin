package com.lodygames.rpgquest.content.publish;

import com.lodygames.rpgquest.content.reload.ReloadFamily;
import java.util.List;

/**
 * Ce que la publication a besoin de savoir faire du moteur de contenu (issue #47).
 *
 * <h2>Pourquoi cette interface existe</h2>
 *
 * <p>Le rechargement réel exige tous les moteurs du plugin — quêtes, stories, dialogues, PNJ,
 * objets, mobs — donc Bukkit. Dépendre de lui directement rendrait les tests de publication
 * <em>dépendants des moteurs</em> : on vérifierait le chargement d'un YAML de quête là où l'on veut
 * vérifier l'<strong>ordre des opérations</strong>, la détection de conflit, la sauvegarde avant
 * écriture et le refus d'accorder « Synchronisé » sans preuve.</p>
 *
 * <p>Avec cette frontière, toute la logique de #47 s'exécute dans des tests ordinaires, sans
 * serveur. L'implémentation réelle, {@link ReloadServiceApplier}, ne fait que déléguer.</p>
 */
public interface ContentApplier {

    /** Le résultat d'un rechargement, réduit à ce que la publication en fait. */
    record ApplyResult(boolean applied, String code, String message, int loaded, int issues,
                       String runtimeHash) {
    }

    /** Recharge une seule famille. */
    ApplyResult reload(ReloadFamily family);

    /** Le moteur porte-t-il cet identifiant ? Comparaison sur la forme « nue ». */
    boolean runtimeHas(ReloadFamily family, String id);

    /** Les identifiants réellement chargés, pour l'affichage et le diagnostic. */
    List<String> loadedIds(ReloadFamily family);

    /** Empreinte du runtime courant. */
    String runtimeHash();
}
