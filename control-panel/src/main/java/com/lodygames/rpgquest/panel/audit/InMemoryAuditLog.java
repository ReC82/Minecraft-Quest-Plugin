package com.lodygames.rpgquest.panel.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Journal d'audit en mémoire — tests, et repli si la base du panel est indisponible. */
public final class InMemoryAuditLog implements AuditLog {

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        List<AuditEntry> copy = new ArrayList<>(entries);
        Collections.reverse(copy);
        return copy.size() > limit ? copy.subList(0, limit) : copy;
    }
}
