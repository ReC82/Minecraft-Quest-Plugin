package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vue <strong>lecture seule</strong> du contenu tel qu'il existe dans le <em>checkout Git source</em>
 * (issue #144). Complète le dernier relevé runtime de l'agent ({@code quest.list} / {@code story.list})
 * : une quête tout juste enregistrée depuis {@code /quests/new} vit d'abord uniquement dans la source
 * et ne sera visible côté serveur qu'après un rechargement RPGQuest. Le catalogue du Control Panel
 * doit donc fusionner les deux origines et afficher un état explicite (« source uniquement »,
 * « hors source »…), sans jamais laisser croire que le contenu source est déjà actif en jeu.
 *
 * <p>Chaque fichier est relu via {@link QuestYaml#read} / {@link StoryYaml#read} (best-effort, même
 * relecteur ciblé que l'éditeur). Un fichier illisible n'est jamais masqué : il ressort avec
 * {@code parseOk == false} pour qu'un diagnostic puisse le signaler.</p>
 */
public final class SourceCatalog {

    private final ContentWorkspace workspace;

    public SourceCatalog(ContentWorkspace workspace) {
        this.workspace = workspace;
    }

    /** Une quête vue dans la source : slug de fichier + brouillon relu + succès de relecture. */
    public record QuestSource(String slug, QuestDraft draft, boolean parseOk) {

        /** Id « nu » (sans préfixe {@code rpgquest:}) servant de clé de fusion avec le runtime. */
        public String plainId() {
            String id = draft != null && draft.id != null && !draft.id.isBlank() ? draft.id : slug;
            return QuestYaml.plainId(id);
        }
    }

    /** Une story vue dans la source : slug de fichier + brouillon relu + succès de relecture. */
    public record StorySource(String slug, StoryDraft draft, boolean parseOk) {

        public String plainId() {
            String id = draft != null && draft.id != null && !draft.id.isBlank() ? draft.id : slug;
            return id.trim().toLowerCase(Locale.ROOT);
        }
    }

    public boolean available() {
        return workspace != null && workspace.configured();
    }

    /** Toutes les quêtes présentes dans {@code <root>/quests/*.yml}, triées par slug. Jamais {@code null}. */
    public List<QuestSource> quests() {
        List<QuestSource> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        List<ContentWorkspace.ContentFile> files;
        try {
            files = workspace.list("quests");
        } catch (RuntimeException e) {
            return out;
        }
        for (ContentWorkspace.ContentFile f : files) {
            QuestYaml.ReadResult r = QuestYaml.read(f.text());
            QuestDraft d = r.draft() != null ? r.draft() : new QuestDraft();
            if (d.id == null || d.id.isBlank()) {
                d.id = f.slug();
            }
            out.add(new QuestSource(f.slug(), d, r.draft() != null && r.problems().isEmpty()));
        }
        return out;
    }

    /** Toutes les stories présentes dans {@code <root>/stories/*.yml}, triées par slug. Jamais {@code null}. */
    public List<StorySource> stories() {
        List<StorySource> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        List<ContentWorkspace.ContentFile> files;
        try {
            files = workspace.list("stories");
        } catch (RuntimeException e) {
            return out;
        }
        for (ContentWorkspace.ContentFile f : files) {
            StoryYaml.ReadResult r = StoryYaml.read(f.text());
            StoryDraft d = r.draft() != null ? r.draft() : new StoryDraft();
            if (d.id == null || d.id.isBlank()) {
                d.id = f.slug();
            }
            out.add(new StorySource(f.slug(), d, r.draft() != null && r.problems().isEmpty()));
        }
        return out;
    }
}
