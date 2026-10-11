package com.lodygames.rpgquest.panel.ai;

import java.util.Locale;

/**
 * Le contrat passé au modèle pour qu'il écrive un <strong>plan de bâtiment</strong> (issue #94).
 *
 * <h2>Pourquoi un vocabulaire fermé, et pas « décris-moi les blocs »</h2>
 *
 * <p>Un bâtiment de 19 × 12 × 15 compte 3 420 cellules. Demander leur énumération serait lent,
 * coûteux, et surtout fragile : une coordonnée de travers dans un mur de deux cents blocs produit
 * un trou que personne ne relit. Le modèle décrit donc une <em>intention</em> — « un mur creux de
 * là à là, en pierre » — et le moteur déterministe du plugin la remplit toujours de la même
 * façon.</p>
 *
 * <p>C'est aussi la réponse à la contrainte de sécurité du ticket : le modèle ne produit ni code,
 * ni chemin, ni commande. Il produit cinq mots-clés et des entiers. Tout le reste est refusé par
 * le plugin, qui est le validateur autoritaire.</p>
 *
 * <h2>Ce que le prompt fait dire au modèle, et ce qu'il lui interdit</h2>
 *
 * <p>Le plan exige la taille, l'orientation de référence et les instructions ; l'ancre est
 * optionnelle et vaut par défaut le centre de la base, parce que c'est la convention naturelle
 * pour poser un bâtiment sur un point marqué au sol. Tout le reste est interdit : pas de clé
 * inventée, pas de bloc d'un mod, pas de prose autour du document.</p>
 */
public final class BuildingPromptBuilder {

    private BuildingPromptBuilder() {
    }

    /**
     * Ce que l'écran demande.
     *
     * @param siteId    emplacement visé, pour le rappeler dans le plan et l'audit
     * @param siteName  nom de l'emplacement : sert de base au nom du bâtiment
     * @param facing    orientation enregistrée de l'emplacement — c'est elle que le plan doit
     *                  prendre comme façade de référence, sinon le bâtiment sera posé de travers
     * @param brief     le brief architectural de l'administrateur
     * @param maxWidth  emprise maximale autorisée en X et Z, en blocs
     * @param maxHeight hauteur maximale autorisée
     */
    public record Demand(String siteId, String siteName, String facing, String brief,
                         int maxWidth, int maxHeight) {

        public Demand {
            siteId = siteId == null ? "" : siteId.trim();
            siteName = siteName == null ? "" : siteName.trim();
            facing = facing == null || facing.isBlank() ? "NORTH"
                    : facing.trim().toUpperCase(Locale.ROOT);
            brief = brief == null ? "" : brief.trim();
            maxWidth = Math.min(64, Math.max(5, maxWidth));
            maxHeight = Math.min(64, Math.max(4, maxHeight));
        }

        /** Un brief est indispensable : sans lui, il n'y a rien à générer. */
        public boolean usable() {
            return !brief.isEmpty() && !siteId.isEmpty();
        }

        /** Identifiant de bâtiment suggéré, dérivé de l'emplacement — jamais inventé par le modèle. */
        public String suggestedId() {
            String base = siteName.toLowerCase(Locale.ROOT)
                    .replaceAll("[àáâä]", "a").replaceAll("[èéêë]", "e")
                    .replaceAll("[ìíîï]", "i").replaceAll("[òóôö]", "o")
                    .replaceAll("[ùúûü]", "u").replace('ç', 'c')
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (base.length() < 3) {
                base = siteId.replaceAll("[^a-z0-9]+", "_");
            }
            return base.length() > 48 ? base.substring(0, 48) : base;
        }
    }

    /** Le contrat : invariable, il décrit la langue du plan et rien d'autre. */
    public static String systemPrompt() {
        return """
                Tu produis un PLAN DE BÂTIMENT pour LodyQuests, un plugin Minecraft. Un moteur
                déterministe transforme ensuite ton plan en blocs : tu décris des volumes, jamais
                des blocs un par un.

                RÈGLES ABSOLUES

                1. Ta réponse est UNIQUEMENT un document YAML. Aucune phrase avant, aucune phrase
                   après, aucun bloc de code, aucune explication.
                2. N'utilise QUE les cinq instructions listées ci-dessous. Une instruction plausible
                   mais absente de la liste fait échouer la génération.
                3. N'utilise que des blocs Minecraft VANILLA, en minuscules, forme
                   « minecraft:nom » ou « minecraft:nom[propriete=valeur] ». Aucun bloc de mod.
                4. Toutes les coordonnées sont LOCALES, entières, et comprises dans la taille que tu
                   déclares : de 0 à size-1 inclus. Une zone qui sort du volume fait échouer la
                   génération.
                5. Ne dépasse PAS les dimensions maximales données dans la demande.

                FORME DU DOCUMENT

                id: identifiant_minuscule        # 3 à 48 caractères, [a-z0-9_] seulement
                name: "Nom affiché du bâtiment"
                front: NORTH | EAST | SOUTH | WEST   # la façade, telle que ton plan la dessine
                size:
                  x: 9    # largeur  (axe est-ouest)
                  y: 8    # hauteur
                  z: 7    # profondeur (axe nord-sud)
                anchor:                # OPTIONNEL — par défaut le centre de la base
                  x: 4
                  y: 0
                  z: 3
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 6]
                    block: minecraft:stone_bricks

                LES CINQ INSTRUCTIONS

                - op: box     → remplit toute la zone.
                - op: shell   → ne pose que l'enveloppe (les six faces), intérieur vide.
                - op: walls   → ne pose que les quatre murs verticaux : ni sol, ni plafond.
                - op: clear   → VIDE la zone. Pas de clé « block ». Sert à percer portes, fenêtres
                                et pièces dans un volume déjà posé.
                - op: gable   → toit à deux pentes. Exige « axis: x » ou « axis: z » (l'axe du
                                faîte). Utilise un bloc d'escalier : l'orientation des marches est
                                calculée automatiquement, ne la précise pas.

                Chaque instruction prend « from » et, facultativement, « to ». Sans « to »,
                l'instruction vise UN SEUL bloc — pratique pour un détail (lanterne, pupitre).
                Les bornes sont INCLUSES des deux côtés : from [0,0,0] to [0,0,0] vaut un bloc.

                L'ORDRE COMPTE

                Les instructions s'appliquent dans l'ordre, et une instruction postérieure recouvre
                une antérieure sur la même cellule. C'est la bonne manière de travailler :

                  1. pose le sol et les murs ;
                  2. « clear » l'intérieur pour creuser les pièces ;
                  3. « clear » les ouvertures (portes, fenêtres) ;
                  4. repose les détails (escaliers, étagères, comptoir, lanternes) ;
                  5. termine par le toit.

                CE QUI FAIT UN BON BÂTIMENT

                - un intérieur réellement praticable : au moins 3 blocs de hauteur libre par étage,
                  et une porte de 2 blocs de haut ;
                - un escalier ou une échelle si le bâtiment a plusieurs étages — un étage
                  inaccessible est un défaut ;
                - des fenêtres, pour que l'intérieur ne soit pas une boîte noire ;
                - un toit qui dépasse d'un bloc de chaque côté, et qui tient dans la hauteur
                  déclarée ;
                - entre 30 et 80 instructions. C'est la fourchette utile : en dessous le bâtiment
                  est une boîte, au-dessus tu décris des blocs un par un au lieu de regrouper.

                CONTRAINTE DE LONGUEUR — LIS CECI

                Ta réponse a un budget de sortie limité. Un document COUPÉ EN PLEIN MILIEU est
                totalement inutilisable : il n'y a pas de « plan partiel » exploitable. Si tu dois
                choisir, fais un bâtiment plus SOBRE mais COMPLET — sol, murs, ouvertures, escalier
                si besoin, toit, et le document fermé proprement. N'ajoute des détails décoratifs
                que si le plan tient déjà entièrement.
                """;
    }

    /** La demande concrète : les faits de l'emplacement, puis le brief de l'administrateur. */
    public static String userPrompt(Demand demand) {
        return """
                EMPLACEMENT VISÉ

                Identifiant : %s
                Nom : %s
                Façade attendue : %s  ← déclare « front: %s » dans ton plan
                Emprise maximale : %d blocs en x, %d blocs en z
                Hauteur maximale : %d blocs

                IDENTIFIANT À UTILISER

                id: %s

                N'invente pas d'autre identifiant : celui-ci est dérivé de l'emplacement et c'est
                lui que la bibliothèque attend.

                BRIEF DE L'ADMINISTRATEUR

                %s

                Produis maintenant le plan YAML, et rien d'autre.
                """.formatted(demand.siteId(), demand.siteName(), demand.facing(), demand.facing(),
                demand.maxWidth(), demand.maxWidth(), demand.maxHeight(),
                demand.suggestedId(), demand.brief());
    }

    /**
     * Relance après un refus, avec le motif <strong>réel</strong> renvoyé par le serveur.
     *
     * <p>Le modèle voit ce qui n'allait pas et ce qui ne doit pas changer. C'est ce qui rend la
     * reprise utile : sans le motif, il se contenterait de réécrire le même plan autrement.</p>
     */
    public static String correctionPrompt(Demand demand, String previousPlan, String refusal) {
        return """
                %s

                TA PROPOSITION PRÉCÉDENTE A ÉTÉ REFUSÉE PAR LE SERVEUR

                Motif exact :
                %s

                Plan refusé :
                %s

                Corrige UNIQUEMENT ce que le motif désigne. Garde l'identifiant, le nom, la façade
                et l'intention du brief. Produis le plan YAML corrigé, et rien d'autre.
                """.formatted(userPrompt(demand),
                refusal == null || refusal.isBlank() ? "motif non transmis" : refusal.trim(),
                previousPlan == null ? "" : previousPlan.trim());
    }
}
