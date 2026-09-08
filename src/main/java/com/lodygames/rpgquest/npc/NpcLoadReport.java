package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.util.List;

/** Résultat d'un chargement de définitions PNJ : celles valides + un problème par fichier rejeté. */
public record NpcLoadReport(List<NpcDefinition> loaded, List<NpcLoadIssue> issues) {

    public NpcLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
