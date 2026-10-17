package com.lodygames.rpgquest.panel.mob;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Issue #214 — prompt prêt à copier pour qu'une IA externe remplisse un profil de mob à partir
 * d'une demande en français. PlugAdmin n'appelle aucune IA : l'administrateur colle ce texte dans
 * l'IA de son choix, puis réimporte le fichier rendu (vérifié par le parseur réel du plugin).
 *
 * <p>Le prompt embarque le modèle <em>du moteur installé</em> et les valeurs que ce serveur accepte
 * réellement (mondes, biomes, créatures, récompenses, identifiants déjà pris) : une IA ne peut
 * deviner ni le nom du monde Wild, ni les clés du catalogue des récompenses.</p>
 */
public final class MobAiPrompt {

    public static final String REQUEST_PLACEHOLDER =
            "<<< ÉCRIS ICI ta demande en français, par exemple : « Crée un boss zombie facile pour joueur débutant » >>>";

    private MobAiPrompt() {
    }

    /**
     * @param base     le modèle à remplir, ou le profil actuel en mode modification
     * @param editing  {@code true} : modifier ce profil en gardant son identifiant
     */
    public static String prompt(String base, boolean editing, MobImportContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(editing
                ? "Tu modifies un profil de créature existant pour le serveur Minecraft LodyQuests (plugin RPGQuest).\n"
                : "Tu remplis un profil de créature (mob spécial ou boss) pour le serveur Minecraft LodyQuests (plugin RPGQuest).\n");
        sb.append("\nCE QUE JE VEUX :\n").append(REQUEST_PLACEHOLDER).append("\n");
        sb.append("\nRÈGLES OBLIGATOIRES :\n");
        sb.append("1. Réponds UNIQUEMENT avec le fichier YAML complet, dans un seul bloc ```yaml, sans texte avant ni après.\n");
        sb.append("2. N'utilise QUE les clés présentes dans le fichier ci-dessous, avec leur orthographe exacte. "
                + "Toute autre clé est refusée à l'import.\n");
        if (editing) {
            sb.append("3. Garde exactement la même valeur pour « id » et « category ». Ne change que ce que demande ma "
                    + "demande ; recopie tel quel tout le reste (capacités, butin, récompenses compris).\n");
        } else {
            sb.append("3. Remplis « id » (minuscules, chiffres, « _ » « . » « - », court et parlant) et « name ».");
            List<String> taken = ctx.takenIds();
            sb.append(taken.isEmpty() ? "\n" : " Identifiants déjà pris, à NE PAS utiliser : "
                    + String.join(", ", taken) + ".\n");
            sb.append("   Garde la valeur de « category » (et de « boss-mode » s'il est présent) du modèle.\n");
        }
        sb.append("4. Une ligne commencée par « # clé: » est facultative : retire le « # » pour l'utiliser. "
                + "Pour une capacité ou un butin, décommente la ligne « abilities: » / « drops: » et TOUTES les lignes "
                + "de l'entrée choisie. Laisse en commentaire ce que tu n'utilises pas.\n");
        sb.append("5. Respecte les bornes écrites en commentaire. Reste jouable : vitesse au plus 0.35, une invocation a "
                + "toujours un « max-alive » petit (4 au plus), « max-population: 1 » pour un boss.\n");
        sb.append("6. Valeurs acceptées par CE serveur (n'en invente aucune autre) :\n");
        sb.append("   - mondes (« worlds ») : ").append(listOrUnknown(ctx.worlds()));
        if (!ctx.wildWorld().isBlank()) {
            sb.append(" — le Wild est « ").append(ctx.wildWorld()).append(" »");
        }
        sb.append('\n');
        sb.append("   - biomes (« biomes », ou [] pour tous) : ").append(listOrUnknown(ctx.biomes())).append('\n');
        sb.append("   - créatures (« entity-type », « summon-entity-type ») : ").append(listOrUnknown(ctx.entityTypes()))
                .append('\n');
        sb.append("   - récompenses (« participation-rewards ») : ").append(rewards(ctx)).append('\n');
        sb.append("   - butin « custom-item » : ne l'utilise pas sauf si je te donne l'identifiant exact ; préfère « material ».\n");
        sb.append(editing ? "\nPROFIL ACTUEL À MODIFIER :\n" : "\nMODÈLE À REMPLIR :\n");
        sb.append("```yaml\n").append(base.strip()).append("\n```\n");
        return sb.toString();
    }

    private static String listOrUnknown(List<String> values) {
        return values.isEmpty() ? "(liste non relevée : charger « Catalogues Minecraft » dans PlugAdmin)"
                : String.join(", ", values);
    }

    private static String rewards(MobImportContext ctx) {
        if (!ctx.rewardsLoaded()) {
            return "(catalogue non relevé) : laisse participation-rewards: []";
        }
        Map<String, String> r = ctx.rewards();
        if (r.isEmpty()) {
            return "aucune n'existe sur ce serveur : laisse participation-rewards: []";
        }
        return r.entrySet().stream()
                .map(e -> e.getValue().isBlank() ? e.getKey() : e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", ")) + " — ou [] pour aucune";
    }
}
