package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.npc.NpcNameTokens;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Résumé <strong>compact et borné</strong> de la quête suivie, pour la bossbar (issue #71).
 *
 * <h2>Le défaut corrigé</h2>
 *
 * <p>La bossbar concaténait la description de chaque objectif de l'étape (« Tuer SPIDER · Tuer
 * ZOMBIE · Tuer SKELETON · Tuer CREEPER ») : identifiants techniques bruts, et une ligne qui
 * déborde de l'écran dès quatre objectifs — impossible à étendre à 10 types de mobs ou 20
 * ressources. La bossbar n'affiche plus que ce qui tient toujours :</p>
 *
 * <ul>
 *   <li>un seul objectif : « Titre — Araignée 2/5 » (le nom vient d'{@link ObjectiveLabels}, traduit
 *       côté client — jamais {@code SPIDER} ni un id de PNJ) ;</li>
 *   <li>plusieurs objectifs : « Titre — 1/4 objectifs », un décompte d'<strong>objectifs</strong>
 *       terminés (jamais une somme d'unités hétérogènes) ;</li>
 *   <li>« Étape 2/3 » en tête quand la quête a plusieurs étapes.</li>
 * </ul>
 *
 * <p>Les compteurs individuels restent dans l'ActionBar à chaque progression et dans le journal,
 * qui liste tout.</p>
 *
 * <h2>Largeur bornée</h2>
 *
 * <p>Minecraft choisit l'échelle d'interface de sorte que l'écran fasse au moins {@value
 * #MIN_SCALED_SCREEN_PX} pixels « mis à l'échelle » de large : c'est la plus petite fenêtre
 * possible, quelle que soit l'échelle GUI. Le résumé est mesuré avec la police par défaut
 * ({@link #widthOf}) et ne dépasse jamais {@value #MAX_WIDTH_PX} px. Seul le <strong>titre</strong>
 * est raccourci (ellipse) ; le compteur, seule information de quantité, ne l'est jamais. La
 * troncature se fait sur le texte brut, déjà débarrassé de ses balises : aucune balise
 * MiniMessage ne peut être coupée en deux.</p>
 */
final class TrackedQuestSummary {

    /** Largeur minimale de l'écran, en pixels mis à l'échelle (règle d'échelle GUI vanilla). */
    static final int MIN_SCALED_SCREEN_PX = 320;

    /** Largeur maximale du résumé, avec une marge sous {@link #MIN_SCALED_SCREEN_PX}. */
    static final int MAX_WIDTH_PX = 300;

    /**
     * Un nom traduit par le client ne peut pas être mesuré côté serveur : on l'estime d'après sa clé
     * ({@code entity.minecraft.cave_spider} → 11 caractères), majorée de 40 % — un nom français est
     * plus long que l'identifiant anglais (« spider » → « Araignée ») — en glyphes de 6 px.
     */
    private static final double TRANSLATION_LENGTH_FACTOR = 1.4;
    private static final int MIN_TRANSLATED_CHARS = 8;

    private static final ComponentFlattener ESTIMATING_FLATTENER = ComponentFlattener.basic().toBuilder()
            .mapper(TranslatableComponent.class, TrackedQuestSummary::translatedPlaceholder)
            .build();

    /** Nom de PNJ affiché au plus sur ce nombre de caractères (« à remettre à … »). */
    static final int MAX_NPC_NAME_CHARS = 16;

    /** Le titre n'est jamais raccourci en dessous de ce nombre de caractères. */
    static final int MIN_TITLE_CHARS = 8;

    static final String ELLIPSIS = "…";
    private static final String SEPARATOR = " — ";
    private static final String STEP_SEPARATOR = " · ";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Le texte de la bossbar et le remplissage de sa barre, cohérent avec ce texte. */
    record Summary(Component name, float progress) {
    }

    private TrackedQuestSummary() {
    }

    static Summary compose(QuestDefinition quest, QuestStepProgressView view, List<QuestObjective> objectives,
                           Function<String, Optional<String>> npcNames) {
        List<ObjectiveProgressView> progress = view.objectives();
        Body body = body(quest, view.stepId(), progress, objectives, npcNames);

        String title = plainTitle(quest, npcNames);
        int titleBudget = MAX_WIDTH_PX - body.widthPx() - (body.isEmpty() ? 0 : widthOf(SEPARATOR));
        Component name = Component.text(truncate(title, titleBudget), NamedTextColor.GOLD);
        if (!body.isEmpty()) {
            name = name.append(Component.text(SEPARATOR, NamedTextColor.GRAY)).append(body.component());
        }
        return new Summary(name, progressOf(progress));
    }

    /** Partie droite : étape, puis l'objectif unique ou le décompte d'objectifs. */
    private record Body(Component component, int widthPx) {
        boolean isEmpty() {
            return widthPx == 0;
        }
    }

    private static Body body(QuestDefinition quest, String stepId, List<ObjectiveProgressView> progress,
                             List<QuestObjective> objectives, Function<String, Optional<String>> npcNames) {
        Component component = Component.empty().color(NamedTextColor.GRAY);
        int width = 0;
        int stepIndex = stepIndex(quest, stepId);
        if (quest.steps().size() > 1 && stepIndex >= 0) {
            String step = "Étape " + (stepIndex + 1) + "/" + quest.steps().size();
            component = component.append(Component.text(step));
            width += widthOf(step);
        }
        if (progress.isEmpty()) {
            return new Body(component, width);
        }
        if (width > 0) {
            component = component.append(Component.text(STEP_SEPARATOR, NamedTextColor.DARK_GRAY));
            width += widthOf(STEP_SEPARATOR);
        }
        if (progress.size() == 1) {
            ObjectiveProgressView only = progress.get(0);
            QuestObjective objective = objectives.size() == 1 ? objectives.get(0) : null;
            if (objective == null) {
                // Définition et progression désaccordées (reload en cours) : jamais la description
                // brute du moteur, qui peut contenir un identifiant ; le décompte reste exact.
                String count = countLabel(only.current() >= only.total() ? 1 : 0, 1);
                return new Body(component.append(Component.text(count, NamedTextColor.WHITE)), width + widthOf(count));
            }
            String counter = ObjectiveLabels.isCountable(objective)
                    ? " " + Math.min(only.current(), only.total()) + "/" + only.total() : "";
            int fixed = width + widthOf(counter);
            Component label = ObjectiveLabels.targetName(objective, shortNpcNames(npcNames));
            if (fixed + estimatedWidthOf(label) > MAX_WIDTH_PX - minTitleWidth()) {
                // Le nom du PNJ ne tient pas à côté d'un titre lisible : libellé générique
                // (« Cactus (à remettre) ») ; le journal et le dialogue nomment le destinataire.
                label = ObjectiveLabels.targetName(objective);
            }
            component = component.append(label.colorIfAbsent(NamedTextColor.YELLOW))
                    .append(Component.text(counter, NamedTextColor.WHITE));
            return new Body(component, fixed + estimatedWidthOf(label));
        }
        String count = countLabel(completedCount(progress), progress.size());
        return new Body(component.append(Component.text(count, NamedTextColor.WHITE)), width + widthOf(count));
    }

    /** Largeur d'un titre raccourci au minimum (« La chass… — »). */
    private static int minTitleWidth() {
        return MIN_TITLE_CHARS * 6 + widthOf(ELLIPSIS) + widthOf(SEPARATOR);
    }

    private static String countLabel(int done, int total) {
        return done + "/" + total + (total > 1 ? " objectifs" : " objectif");
    }

    private static int completedCount(List<ObjectiveProgressView> progress) {
        int done = 0;
        for (ObjectiveProgressView objective : progress) {
            if (objective.current() >= objective.total()) {
                done++;
            }
        }
        return done;
    }

    /**
     * Un objectif seul : sa propre avancée. Plusieurs : la part d'objectifs terminés, la même
     * mesure que le texte « k/n objectifs » — additionner 3 araignées et 12 cactus n'aurait aucun sens.
     */
    static float progressOf(List<ObjectiveProgressView> progress) {
        if (progress.isEmpty()) {
            return 0f;
        }
        if (progress.size() == 1) {
            ObjectiveProgressView only = progress.get(0);
            return only.total() <= 0 ? 0f : clamp(only.current() / (float) only.total());
        }
        return clamp(completedCount(progress) / (float) progress.size());
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int stepIndex(QuestDefinition quest, String stepId) {
        List<QuestStep> steps = quest.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    /** Titre en texte brut : jetons {@code %npc_name:…%} résolus (#475), balises retirées. */
    private static String plainTitle(QuestDefinition quest, Function<String, Optional<String>> npcNames) {
        String resolved = NpcNameTokens.resolve(quest.title().base(), npcNames);
        return MM.stripTags(resolved).replace("\\<", "<").strip();
    }

    private static Function<String, Optional<String>> shortNpcNames(Function<String, Optional<String>> npcNames) {
        return id -> npcNames.apply(id).map(name -> truncateChars(name, MAX_NPC_NAME_CHARS));
    }

    private static String truncateChars(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars - 1)).stripTrailing() + ELLIPSIS;
    }

    /** Raccourcit {@code text} à {@code maxPx} (ellipse comprise), sans descendre sous {@link #MIN_TITLE_CHARS}. */
    static String truncate(String text, int maxPx) {
        if (widthOf(text) <= maxPx) {
            return text;
        }
        int budget = maxPx - widthOf(ELLIPSIS);
        int end = 0;
        int width = 0;
        int chars = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int next = width + advance(codePoint);
            if (next > budget && chars >= MIN_TITLE_CHARS) {
                break;
            }
            width = next;
            end += Character.charCount(codePoint);
            chars++;
        }
        return end >= text.length() ? text : text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** Largeur d'un composant ; chaque nom traduit par le client compte pour son estimation. */
    static int estimatedWidthOf(Component component) {
        StringBuilder text = new StringBuilder();
        ESTIMATING_FLATTENER.flatten(component, text::append);
        return widthOf(text.toString());
    }

    private static String translatedPlaceholder(TranslatableComponent component) {
        String key = component.key();
        String name = key.substring(key.lastIndexOf('.') + 1);
        int chars = Math.max(MIN_TRANSLATED_CHARS, (int) Math.ceil(name.length() * TRANSLATION_LENGTH_FACTOR));
        return "a".repeat(chars);
    }

    /**
     * Largeur estimée, en pixels mis à l'échelle, d'un texte dans la police par défaut de
     * Minecraft (avance de chaque glyphe, espacement inclus). Les lettres accentuées ont la largeur
     * de leur lettre de base ; un glyphe hors table compte large, pour ne jamais sous-estimer.
     */
    static int widthOf(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += advance(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static int advance(int codePoint) {
        return switch (codePoint) {
            case '!', ',', '.', ':', ';', '|', 'i', '\'', 'î', 'ï' -> 2;
            case '`', 'l' -> 3;
            case ' ', 'I', 't', '[', ']', '(', ')', '{', '}', '"', '*' -> 4;
            case 'f', 'k', '<', '>' -> 5;
            case '@', '~' -> 7;
            default -> codePoint < 128 || Character.isLetterOrDigit(codePoint) ? 6 : 9;
        };
    }
}
