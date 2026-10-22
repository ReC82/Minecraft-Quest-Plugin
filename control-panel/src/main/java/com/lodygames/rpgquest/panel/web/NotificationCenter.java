package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Centre de notifications de la topbar (issue #93) : une cloche + un menu déroulant Bootstrap
 * listant les <strong>dernières actions agent</strong>. Aucune donnée nouvelle — lecture directe
 * de {@link AgentStore#recentActions} (source de vérité {@code agent_action}).
 *
 * <p>Le badge signale ce qui <em>mérite l'attention</em> : actions non terminales (en cours) +
 * échecs des dernières 24 h. Jamais le total des succès historiques. Le menu est rafraîchi côté
 * client par {@code panel.js} via {@code /agents/actions.json} (polling léger).</p>
 */
public final class NotificationCenter {

    private static final int MENU_SIZE = 8;
    private static final int SCAN = 40;
    private static final Duration FAILED_WINDOW = Duration.ofHours(24);

    private final AgentStore store;
    private final AgentRegistry registry;
    /** Issue #293 — lectures par compte ; {@code null} = aucune notion de lecture (comportement d'origine). */
    private final NotificationReadStore reads;

    public NotificationCenter(AgentStore store, AgentRegistry registry) {
        this(store, registry, null);
    }

    /** Pseudos connus des joueurs ciblés (retour du 22/10) ; {@code null} = cible brute. */
    private final PlayerNameDirectory names;

    public NotificationCenter(AgentStore store, AgentRegistry registry, NotificationReadStore reads) {
        this(store, registry, reads, null);
    }

    NotificationCenter(AgentStore store, AgentRegistry registry, NotificationReadStore reads,
                       PlayerNameDirectory names) {
        this.store = store;
        this.registry = registry;
        this.reads = reads;
        this.names = names;
    }

    /**
     * Les {@code SCAN} dernières actions <strong>opérateur</strong>, tous agents confondus, triées de
     * la plus récente à la plus ancienne. Les relevés de catalogue ré-enfilés automatiquement après
     * une mutation ({@code created_by = "auto"}, issues #112 / #115 / #116 / #119 / #120) sont exclus :
     * ils sont un rouage interne de resynchronisation, pas une action à signaler.
     */
    public List<AgentActionRow> recent() {
        List<AgentActionRow> all = new ArrayList<>();
        for (AgentIdentity a : registry.all()) {
            for (AgentActionRow row : store.recentActions(a.id(), SCAN)) {
                if (!"auto".equals(row.createdBy())) {
                    all.add(row);
                }
            }
        }
        all.sort(Comparator.comparing(AgentActionRow::createdAt).reversed());
        return all.size() > SCAN ? all.subList(0, SCAN) : all;
    }

    /** Nombre à signaler : actions en cours + échecs des dernières 24 h. {@code 0} = pas de badge. */
    public int badgeCount(List<AgentActionRow> recent, Instant now) {
        return badgeCount(recent, now, java.util.Set.of());
    }

    /**
     * Issue #293 — même règle, pour un compte : un échec qu'il a <strong>lu</strong> ne compte plus.
     * Une action en cours compte toujours : elle n'est pas une nouvelle, c'est un état qui se
     * résoudra seul.
     */
    public int badgeCount(List<AgentActionRow> recent, Instant now, String userId) {
        return badgeCount(recent, now, readAmong(userId, recent));
    }

    private int badgeCount(List<AgentActionRow> recent, Instant now, java.util.Set<String> read) {
        int n = 0;
        for (AgentActionRow a : recent) {
            if (!a.status().terminal() || (unreadable(a, now) && !read.contains(a.id()))) {
                n++;
            }
        }
        return n;
    }

    /** Un échec récent : ce qui peut être « non lu ». */
    private static boolean unreadable(AgentActionRow a, Instant now) {
        return switch (a.status()) {
            case FAILED, REJECTED -> a.createdAt().isAfter(now.minus(FAILED_WINDOW));
            default -> false;
        };
    }

    /** Marque comme lus, pour ce compte, tous les échecs actuellement signalés (« Tout marquer comme lu »). */
    public int markAllRead(String userId, Instant now) {
        if (reads == null) {
            return 0;
        }
        List<String> ids = recent().stream().filter(a -> unreadable(a, now)).map(AgentActionRow::id).toList();
        reads.markRead(userId, ids);
        return ids.size();
    }

    /** Marque une notification comme lue — appelée à l'ouverture de sa fiche. */
    public void markRead(String userId, String actionId) {
        if (reads != null && actionId != null) {
            reads.markRead(userId, List.of(actionId));
        }
    }

    private java.util.Set<String> readAmong(String userId, List<AgentActionRow> recent) {
        return reads == null ? java.util.Set.of()
                : reads.readAmong(userId, recent.stream().map(AgentActionRow::id).toList());
    }

    /**
     * Cloche + <strong>panneau off-canvas</strong> (fix #93 bug 1 : le dropdown était trop
     * étroit sur desktop, texte cassé mot par mot). L'off-canvas offre une largeur confortable
     * (≈ 420 px desktop, ≈ 92 vw mobile), un scroll vertical naturel et un pied de panneau
     * toujours visible. À insérer dans {@code .topbar-r} ; {@code defaultAgentId} pilote le polling.
     */
    public String bellHtml(String defaultAgentId, Instant now) {
        return bellHtml(defaultAgentId, now, "", "");
    }

    /** Issue #293 — la cloche d'un compte : badge des seuls non-lus, et « Tout marquer comme lu ». */
    public String bellHtml(String defaultAgentId, Instant now, String userId, String csrfToken) {
        List<AgentActionRow> recent = recent();
        java.util.Set<String> read = readAmong(userId, recent);
        int badge = badgeCount(recent, now, read);

        StringBuilder items = new StringBuilder();
        PlayerNames known = names == null ? PlayerNames.EMPTY : names.current();
        int shown = 0;
        for (AgentActionRow a : recent) {
            if (shown++ >= MENU_SIZE) {
                break;
            }
            items.append(ActionView.notifItemHtml(a, now, known));
        }
        if (shown == 0) {
            items.append("<p class=\"notif-empty\">Aucune action pour le moment.</p>");
        }

        String badgeHtml = "<span class=\"notif-badge badge rounded-pill text-bg-danger\" data-notif-badge"
                + (badge == 0 ? " hidden" : "") + ">" + badge + "</span>";

        return "<div class=\"notif-center\" id=\"notif-center\" data-notif-agent=\""
                + Http.esc(defaultAgentId == null ? "" : defaultAgentId) + "\">"
                + "<button class=\"iconbtn notif-bell\" type=\"button\" data-bs-toggle=\"offcanvas\" "
                + "data-bs-target=\"#notif-panel\" aria-controls=\"notif-panel\" "
                + "aria-label=\"Notifications\" title=\"Notifications\">"
                + Icons.icon("bell") + badgeHtml + "</button>"
                + "</div>"
                + "<aside class=\"offcanvas offcanvas-end notif-panel\" tabindex=\"-1\" id=\"notif-panel\" "
                + "aria-labelledby=\"notif-panel-title\">"
                + "<div class=\"offcanvas-header\">"
                + "<h2 class=\"offcanvas-title notif-panel-t\" id=\"notif-panel-title\">"
                + Icons.icon("bell") + "<span>Dernières actions</span></h2>"
                + "<button type=\"button\" class=\"btn-close\" data-bs-dismiss=\"offcanvas\" aria-label=\"Fermer\"></button>"
                + "</div>"
                + "<div class=\"offcanvas-body notif-body-wrap\">"
                + "<div class=\"notif-list\" data-notif-list>" + items + "</div>"
                + "</div>"
                + "<div class=\"notif-foot-wrap\">"
                + (reads == null || csrfToken == null || csrfToken.isEmpty() ? ""
                        : "<form method=\"post\" action=\"/notifications/read-all\" class=\"notif-readall\">"
                                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(csrfToken) + "\">"
                                + "<button class=\"btn btn-sm btn-link\" type=\"submit\">Tout marquer comme lu</button>"
                                + "</form>")
                + "<a class=\"notif-foot\" href=\"/actions\">" + Icons.icon("history")
                + "<span>Voir toutes les actions</span>" + Icons.icon("chevron") + "</a>"
                + "</div>"
                + "</aside>";
    }
}
