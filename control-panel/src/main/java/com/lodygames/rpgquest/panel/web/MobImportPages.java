package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.mob.MobAiPrompt;
import com.lodygames.rpgquest.panel.mob.MobImportContext;
import com.lodygames.rpgquest.panel.mob.MobProfileDiff;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issue #214 — « Mobs spéciaux & boss → Modèles, IA et import de fichier ».
 *
 * <p>Le parcours : télécharger un modèle complet (issu du moteur installé) ou générer un brouillon
 * aléatoire ; le faire remplir par une IA externe avec le prompt prêt à copier ; importer le
 * fichier ; relire la vérification du serveur (erreurs, références, différences) ; confirmer. Rien
 * n'est écrit avant la confirmation, et rien n'apparaît en jeu, jamais.</p>
 */
public final class MobImportPages {

    /** Paramètres de la page. {@code check} : l'action de vérification à afficher, s'il y en a une. */
    public record View(String agentId, MobImportContext ctx, String kind, String fromProfile, String yaml,
                       String notice, AgentActionRow check, Optional<Map<String, Object>> checkDetails) {
    }

    private MobImportPages() {
    }

    /** Raccourcis affichés en tête de « Nouveau profil » : le formulaire n'est pas le seul chemin. */
    public static String shortcuts(String agentId) {
        String a = "agent=" + Http.esc(agentId);
        StringBuilder sb = new StringBuilder("<div class=\"mob-shortcuts\"><p class=\"npc-fs-h\">Sans remplir le "
                + "formulaire</p><p class=\"field-help\">Le formulaire ci-dessous ne couvre pas tout ce que le moteur "
                + "sait faire (butin, capacités avancées). Le <strong>modèle complet</strong> contient chaque option, "
                + "commentée en français : remplissez-le vous-même ou faites-le remplir par une IA, puis importez-le.</p>"
                + "<div class=\"btnrow\">");
        for (String kind : MobImportContext.KINDS) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/mobs/template?").append(a).append("&amp;kind=")
                    .append(kind).append("\">").append(Icons.icon("export")).append("Modèle ")
                    .append(Http.esc(MobImportContext.kindLabel(kind).replace(" (tirage aléatoire du Wild)", "")))
                    .append("</a>");
        }
        sb.append("<a class=\"btn btn-sm btn-primary\" href=\"/mobs/import?").append(a).append("\">")
                .append(Icons.icon("check")).append("Importer un fichier / IA</a>");
        sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/mobs/import?").append(a)
                .append("&amp;random=boss-community\">Générer aléatoirement</a>");
        return sb.append("</div></div>").toString();
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("mob", "Modèles, IA et import de profils",
                "Préparer un mob spécial ou un boss dans un fichier (à la main ou par une IA), le faire vérifier "
                        + "par le serveur, puis le créer. Rien n'apparaît en jeu.",
                "<a class=\"btn secondary\" href=\"/mobs?agent=" + Http.esc(v.agentId()) + "\">" + Icons.icon("back")
                        + "Retour aux profils</a>"));
        if (v.notice() != null && !v.notice().isBlank()) {
            sb.append(Ui.banner("info", Http.esc(v.notice())));
        }
        if (v.check() != null) {
            sb.append(checkCard(v));
        }
        MobImportContext ctx = v.ctx();
        if (!ctx.catalogsLoaded() || ctx.templates().isEmpty()) {
            sb.append(Ui.banner("warn", (ctx.catalogsLoaded()
                    ? "Le plugin installé ne fournit pas encore les modèles (version antérieure) : mettre à jour le plugin."
                    : "Les catalogues du serveur ne sont pas encore relevés : modèles, mondes, biomes et créatures "
                    + "manquent au prompt.") + refreshForm(v.agentId(), "mob.catalogs", "Charger les catalogues Minecraft")));
        }
        if (!ctx.rewardsLoaded()) {
            sb.append(Ui.banner("info", "Le catalogue des récompenses n'est pas relevé : le prompt demandera de ne "
                    + "mettre aucune récompense de participation."
                    + refreshForm(v.agentId(), "reward.catalog.status", "Charger les récompenses")));
        }
        sb.append(templatesCard(v));
        sb.append(promptCard(v));
        sb.append(uploadCard(v));
        return sb.toString();
    }

    private static String refreshForm(String agentId, String type, String label) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"d-inline ms-2\">%CSRF%"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"/mobs/import\">"
                + "<button class=\"btn btn-sm btn-warning\" type=\"submit\">" + Http.esc(label) + "</button></form>";
    }

    // ---- 1. Modèles ----------------------------------------------------------------------------

    private static String templatesCard(View v) {
        String a = "agent=" + Http.esc(v.agentId());
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("export", "1. Partir d'un modèle complet, ou d'un brouillon aléatoire"));
        sb.append("<p class=\"muted\">Chaque modèle contient <strong>toutes</strong> les options que le moteur installé "
                + "lit réellement — statistiques, effets, les cinq capacités, butin, récompenses, mode quête ou "
                + "communautaire — avec leurs bornes, commentées en français. Les options facultatives sont "
                + "précédées de « # » : on retire le « # » pour s'en servir. Tel quel, un modèle est refusé "
                + "(identifiant et nom vides).</p>");
        sb.append("<div class=\"mob-kinds\">");
        for (String kind : MobImportContext.KINDS) {
            boolean available = v.ctx().templates().containsKey(kind);
            sb.append("<div class=\"mob-kind\"><p class=\"npc-fs-h\">").append(Http.esc(MobImportContext.kindLabel(kind)))
                    .append("</p><div class=\"btnrow\">");
            if (available) {
                sb.append("<a class=\"btn btn-sm\" href=\"/mobs/template?").append(a).append("&amp;kind=").append(kind)
                        .append("\">").append(Icons.icon("export")).append("Télécharger le modèle</a>");
            } else {
                sb.append("<span class=\"muted\">Modèle indisponible (catalogues non relevés).</span>");
            }
            sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/mobs/import?").append(a).append("&amp;random=")
                    .append(kind).append("\">Générer aléatoirement</a>");
            sb.append("</div></div>");
        }
        sb.append("</div><p class=\"field-help\">« Générer aléatoirement » remplit la zone d'import (étape 3) avec un "
                + "profil complet aux valeurs raisonnables et un identifiant libre : rien n'est créé tant que vous ne "
                + "l'avez pas vérifié puis confirmé. Chaque clic donne un nouveau brouillon.</p>");
        return sb.append("</section>").toString();
    }

    // ---- 2. Prompt -----------------------------------------------------------------------------

    private static String promptCard(View v) {
        MobImportContext ctx = v.ctx();
        String a = "agent=" + Http.esc(v.agentId());
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        Optional<MobImportContext.Profile> from = v.fromProfile().isBlank() ? Optional.empty() : ctx.profile(v.fromProfile());
        sb.append(Ui.sectionTitle("docs", from.isPresent()
                ? "2. Faire modifier « " + MiniText.plain(from.get().displayName()) + " » par une IA"
                : "2. Faire remplir le modèle par une IA (facultatif)"));
        String base;
        boolean editing = from.isPresent() && !from.get().yaml().isBlank();
        if (editing) {
            base = from.get().yaml();
        } else {
            base = ctx.templates().get(v.kind());
            sb.append("<div class=\"btnrow\" role=\"group\" aria-label=\"Sorte de profil\">");
            for (String kind : MobImportContext.KINDS) {
                sb.append("<a class=\"btn btn-sm ").append(kind.equals(v.kind()) ? "btn-primary" : "btn-outline-secondary")
                        .append("\" href=\"/mobs/import?").append(a).append("&amp;kind=").append(kind).append("\"")
                        .append(kind.equals(v.kind()) ? " aria-current=\"true\"" : "").append(">")
                        .append(Http.esc(MobImportContext.kindLabel(kind))).append("</a>");
            }
            sb.append("</div>");
        }
        if (base == null || base.isBlank()) {
            sb.append(Ui.empty("docs", "Prompt indisponible : charger d'abord les catalogues Minecraft (modèles du moteur)."));
            return sb.append("</section>").toString();
        }
        sb.append("<ol class=\"ai-steps\">"
                + "<li><strong>Copier</strong> le prompt ci-dessous : il contient le "
                + (editing ? "profil actuel" : "modèle") + " et les valeurs que ce serveur accepte.</li>"
                + "<li><strong>Le coller</strong> dans une IA (ChatGPT, Claude…) en remplaçant la ligne "
                + "<code>&lt;&lt;&lt; ÉCRIS ICI … &gt;&gt;&gt;</code> par votre demande, en français.</li>"
                + "<li><strong>Copier</strong> le bloc YAML rendu par l'IA dans l'étape 3 (ou l'enregistrer dans un "
                + "fichier et le déposer), puis « Vérifier ».</li></ol>");
        sb.append("<p class=\"muted\">PlugAdmin n'appelle aucune IA, rien n'est payé ici. L'IA ne peut rien casser : "
                + "le fichier rendu est vérifié par le serveur et n'est créé qu'après votre confirmation.</p>");
        sb.append("<div class=\"field full\"><label for=\"f-mob-ai-prompt\">Prompt prêt à copier</label>"
                + "<textarea id=\"f-mob-ai-prompt\" class=\"code-input\" rows=\"14\" readonly spellcheck=\"false\">")
                .append(Http.esc(MobAiPrompt.prompt(base, editing, ctx))).append("</textarea></div>");
        return sb.append("</section>").toString();
    }

    // ---- 3. Import -----------------------------------------------------------------------------

    private static String uploadCard(View v) {
        StringBuilder sb = new StringBuilder("<section class=\"card\" id=\"mob-import-file\">");
        sb.append(Ui.sectionTitle("check", "3. Importer le fichier rempli"));
        sb.append("<p class=\"muted\">Déposer le fichier <code>.yml</code>, ou coller son contenu. « Vérifier » ne "
                + "modifie rien : le serveur relit le fichier avec son vrai lecteur de profils et vous montre le "
                + "résultat.</p>");
        sb.append("<form method=\"post\" action=\"/mobs/import\" class=\"actform\" enctype=\"multipart/form-data\">%CSRF%")
                .append("<input type=\"hidden\" name=\"step\" value=\"check\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(v.agentId())).append("\">");
        sb.append("<div class=\"field full\"><label for=\"f-mob-file\">Fichier du profil</label>"
                + "<input id=\"f-mob-file\" type=\"file\" name=\"yaml_file\" accept=\".yml,.yaml,text/yaml\">"
                + "<p class=\"field-help\">Un fichier déposé remplace le texte collé ci-dessous.</p></div>");
        sb.append("<div class=\"field full\"><label for=\"f-mob-yaml\">Contenu du profil</label>"
                + "<textarea id=\"f-mob-yaml\" class=\"code-input\" name=\"yaml\" rows=\"14\" spellcheck=\"false\" "
                + "placeholder=\"id: mon_boss&#10;category: BOSS&#10;…\">")
                .append(Http.esc(v.yaml() == null ? "" : v.yaml())).append("</textarea></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("check"))
                .append("Vérifier</button></div></form>");
        return sb.append("</section>").toString();
    }

    // ---- 4. Vérification -----------------------------------------------------------------------

    private static String checkCard(View v) {
        AgentActionRow row = v.check();
        StringBuilder sb = new StringBuilder("<section class=\"card\" id=\"mob-import-check\">");
        sb.append(Ui.sectionTitle("check", "Vérification par le serveur"));
        if (!row.status().terminal()) {
            sb.append("<meta http-equiv=\"refresh\" content=\"3\">");
            sb.append(Ui.banner("info", "Vérification en cours sur le serveur… la page se met à jour toute seule."));
            return sb.append("</section>").toString();
        }
        Map<String, Object> d = v.checkDetails().orElse(null);
        if (row.status() != AgentActionStatus.SUCCESS || d == null) {
            sb.append(Ui.banner("error", "La vérification n'a pas pu avoir lieu"
                    + (row.resultMessage() == null || row.resultMessage().isBlank() ? "" : " : " + Http.esc(row.resultMessage()))
                    + ". Rien n'a été écrit."));
            return sb.append("</section>").toString();
        }
        List<String> errors = strings(d.get("errors"));
        List<String> warnings = strings(d.get("warnings"));
        boolean valid = Boolean.TRUE.equals(d.get("valid"));
        if (!valid) {
            sb.append(Ui.banner("error", "<strong>Fichier refusé — rien n'a été écrit.</strong> Corriger le fichier "
                    + "(ou le redonner à l'IA avec ces messages), puis vérifier à nouveau."));
            sb.append("<ul class=\"mob-issues\">");
            errors.forEach(e -> sb.append("<li>").append(Http.esc(e)).append("</li>"));
            sb.append("</ul>");
        }
        if (!warnings.isEmpty()) {
            sb.append("<p class=\"npc-fs-h\">À savoir</p><ul class=\"mob-issues warn\">");
            warnings.forEach(w -> sb.append("<li>").append(Http.esc(w)).append("</li>"));
            sb.append("</ul>");
        }
        if (!valid) {
            return sb.append("</section>").toString();
        }
        String id = str(d.get("id"));
        boolean exists = !str(d.get("existingFile")).isBlank();
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Profil</span> ")
                .append(MiniText.html(str(d.get("displayName")))).append(' ').append(Ui.id(id)).append("</p>");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Sorte</span> ").append(Http.esc(kindOf(d)))
                .append(" · ").append(Http.esc(str(d.get("entityType")))).append("</p>");
        String canonical = str(d.get("canonical"));
        if (exists) {
            String before = str(d.get("existingCanonical"));
            sb.append(Ui.banner("warn", "Un profil <strong>" + Http.esc(id) + "</strong> existe déjà (fichier "
                    + Http.esc(str(d.get("existingFile"))) + "). Confirmer le <strong>remplace</strong> ; l'ancienne "
                    + "version est sauvegardée sur le serveur. Les autres profils ne sont pas touchés."));
            sb.append(diffTable(before, canonical));
        } else {
            sb.append(Ui.banner("success", "Nouveau profil : rien n'existe sous cet identifiant. Seul ce fichier sera créé."));
        }
        sb.append("<details><summary class=\"muted\">Fichier exact qui sera écrit</summary><div class=\"codeblock bounded\"><pre>")
                .append(Http.esc(canonical)).append("</pre></div></details>");
        sb.append("<p class=\"field-help\">Prend effet dès la création : le profil apparaît dans la liste et devient "
                + "utilisable (tirage du Wild pour un mob spécial, scheduler pour un boss communautaire activé, quêtes "
                + "pour un boss de quête). <strong>Aucune créature n'apparaît</strong> à l'import ; celles déjà présentes "
                + "en jeu gardent leurs anciennes valeurs. Les réglages des boss communautaires ne changent pas.</p>");
        sb.append("<form method=\"post\" action=\"/mobs/import\" class=\"actform\">%CSRF%")
                .append("<input type=\"hidden\" name=\"step\" value=\"apply\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(v.agentId())).append("\">")
                .append("<input type=\"hidden\" name=\"check\" value=\"").append(Http.esc(row.id())).append("\">");
        if (exists) {
            sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"f-mob-replace\" "
                    + "name=\"replace\" value=\"true\" required><label class=\"form-check-label\" for=\"f-mob-replace\">"
                    + "J'ai relu les différences : remplacer le profil existant</label></div>");
        }
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("save"))
                .append(exists ? "Remplacer ce profil" : "Créer ce profil").append("</button>")
                .append("<a class=\"btn secondary\" href=\"/mobs/import?agent=").append(Http.esc(v.agentId()))
                .append("\">Annuler (rien n'est écrit)</a></div></form>");
        return sb.append("</section>").toString();
    }

    private static String diffTable(String before, String after) {
        if (before.isBlank()) {
            return "<p class=\"muted\">Le profil actuel ne se relit pas : impossible d'en montrer les différences. "
                    + "Le remplacer le répare (il est sauvegardé avant).</p>";
        }
        List<MobProfileDiff.Change> changes = MobProfileDiff.changes(before, after);
        if (changes.isEmpty()) {
            return "<p class=\"muted\">Aucune différence avec le profil actuel : le remplacer ne change rien.</p>";
        }
        StringBuilder sb = new StringBuilder("<p class=\"npc-fs-h\">Différences avec le profil actuel (")
                .append(changes.size()).append(")</p>");
        sb.append(Ui.tableOpen("Propriété", "Actuel", "Après import"));
        for (MobProfileDiff.Change c : changes) {
            sb.append("<tr><td><code>").append(Http.esc(c.key())).append("</code>")
                    .append(c.added() ? " <span class=\"badge text-bg-success\">ajoutée</span>"
                            : c.removed() ? " <span class=\"badge text-bg-danger\">retirée</span>" : "")
                    .append("</td><td><pre class=\"mob-cell\">").append(Http.esc(c.before().isEmpty() ? "—" : c.before()))
                    .append("</pre></td><td><pre class=\"mob-cell\">").append(Http.esc(c.after().isEmpty() ? "—" : c.after()))
                    .append("</pre></td></tr>");
        }
        return sb.append(Ui.tableClose()).toString();
    }

    private static String kindOf(Map<String, Object> d) {
        if (!"BOSS".equals(str(d.get("category")))) {
            return MobImportContext.kindLabel("special");
        }
        return MobImportContext.kindLabel("COMMUNITY".equals(str(d.get("bossMode"))) ? "boss-community" : "boss-quest");
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
