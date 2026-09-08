package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.database.NpcBindingRepository.Binding;
import java.util.List;
import java.util.UUID;

/**
 * Décide, <strong>sans effet de bord ni dépendance Bukkit</strong>, ce qu'il faut faire quand on
 * demande de lier une définition PNJ ({@code npcId}) à un PNJ Citizens ({@code citizensUuid} /
 * {@code citizensNumericId}), en fonction des liaisons {@code npc_citizens_bindings} déjà en base
 * (issue #81, phase 1 — pas de rebind, pas de spawn).
 *
 * <p>Règles :</p>
 * <ul>
 *   <li>liaison identique déjà présente → {@link Action#NOOP} (succès explicite) ;</li>
 *   <li>ce PNJ Citizens est déjà lié à un <em>autre</em> {@code npc_id} → {@link Action#REJECT}
 *       {@code CITIZENS_TAKEN} (jamais de réaffectation silencieuse) ;</li>
 *   <li>ce {@code npc_id} est déjà lié à un <em>autre</em> PNJ Citizens → {@link Action#REJECT}
 *       {@code NPC_ID_TAKEN} ;</li>
 *   <li>sinon → {@link Action#INSERT}.</li>
 * </ul>
 */
public final class CitizensBindPlanner {

    private CitizensBindPlanner() {
    }

    public enum Action { INSERT, NOOP, REJECT }

    public record Plan(Action action, String code, String message) {
    }

    public static Plan plan(String npcId, UUID citizensUuid, int citizensNumericId, List<Binding> existing) {
        for (Binding b : existing) {
            if (b.citizensUuid().equals(citizensUuid)) {
                if (b.npcId().equals(npcId)) {
                    return new Plan(Action.NOOP, "NOOP",
                            "Citizens #" + citizensNumericId + " est déjà lié à « " + npcId + " » — rien à faire.");
                }
                return new Plan(Action.REJECT, "CITIZENS_TAKEN",
                        "Citizens #" + b.citizensNumericId() + " est déjà lié à npc_id=" + b.npcId()
                                + ". Aucune réaffectation dans cette phase.");
            }
        }
        for (Binding b : existing) {
            if (b.npcId().equals(npcId)) {
                return new Plan(Action.REJECT, "NPC_ID_TAKEN",
                        "npc_id=" + npcId + " est déjà lié à Citizens #" + b.citizensNumericId()
                                + ". Aucun rebind dans cette phase.");
            }
        }
        return new Plan(Action.INSERT, "INSERT", "Liaison à créer.");
    }
}
