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
        ContentReloadService.ReloadResult result = reloadService.reload(Set.of(family));
        return new ApplyResult(result.applied(), result.code(), result.message(),
                result.totalLoaded(), result.totalIssues(), result.runtimeHash());
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
