package com.lodygames.rpgquest.content.publish;

import com.lodygames.rpgquest.content.reload.ContentReloadService;
import com.lodygames.rpgquest.content.reload.ReloadFamily;
import java.util.List;
import java.util.Set;

/**
 * {@link ContentApplier} adossé au {@link ContentReloadService} réel (issue #47).
 *
 * <p>Volontairement sans logique : elle délègue, et c'est tout. Toute décision prise ici serait une
 * décision soustraite aux tests.</p>
 */
public final class ReloadServiceApplier implements ContentApplier {

    private final ContentReloadService reloadService;

    public ReloadServiceApplier(ContentReloadService reloadService) {
        this.reloadService = reloadService;
    }

    @Override
    public ApplyResult reload(ReloadFamily family) {
        return reload(Set.of(family));
    }

    @Override
    public ApplyResult reload(Set<ReloadFamily> families) {
        return view(reloadService.reloadWithDependencies(families));
    }

    @Override
    public ApplyResult check(Set<ReloadFamily> families) {
        return view(reloadService.previewWithDependencies(families));
    }

    private static ApplyResult view(ContentReloadService.ReloadResult result) {
        return new ApplyResult(result.applied(), result.code(), result.message(),
                result.totalLoaded(), result.totalIssues(), result.runtimeHash(),
                new ReloadDetail(result.referenceErrors(), result.preexistingReferenceErrors(),
                        wires(result.familiesInvolved()), wires(result.autoIncludedFamilies()),
                        wires(result.suggestedFamilies()), contentIssues(result)));
    }

    /** Les erreurs du validateur, famille par famille, telles que le moteur les a formulées. */
    private static List<String> contentIssues(ContentReloadService.ReloadResult result) {
        return result.families().stream()
                .filter(outcome -> !outcome.clean())
                .flatMap(outcome -> outcome.messages().stream()
                        .map(message -> outcome.family().wire() + "/" + message))
                .toList();
    }

    private static List<String> wires(List<ReloadFamily> families) {
        return families.stream().map(ReloadFamily::wire).toList();
    }

    @Override
    public boolean runtimeHas(ReloadFamily family, String id) {
        return reloadService.runtimeHas(family, id);
    }

    @Override
    public List<String> loadedIds(ReloadFamily family) {
        return reloadService.loadedIds(family);
    }

    @Override
    public String runtimeHash() {
        return reloadService.runtimeHash();
    }
}
