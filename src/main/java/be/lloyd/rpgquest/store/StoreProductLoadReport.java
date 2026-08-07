package be.lloyd.rpgquest.store;

import be.lloyd.rpgquest.store.model.StoreProductDefinition;
import java.util.List;

public record StoreProductLoadReport(List<StoreProductDefinition> loaded, List<StoreProductLoadIssue> issues) {
    public StoreProductLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }
}
