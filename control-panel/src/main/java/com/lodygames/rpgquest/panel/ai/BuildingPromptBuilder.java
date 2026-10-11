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
 * ni chemin, ni commande. Il produit un vocabulaire fermé de mots-clés et des entiers. Tout le reste
 * est refusé par le plugin, qui est le validateur autoritaire.</p>
 *
 * <h2>Pourquoi le vocabulaire a grandi (lot F de #94)</h2>
 *
 * <p>La première bibliothèque générée était un bloc hermétique : ni porte, ni fenêtre, ni lumière,
 * un étage inaccessible. Le diagnostic a montré que le prompt n'était pas le fautif principal — le
 * <strong>vocabulaire</strong> l'était. Avec cinq instructions de volume, « une porte » ne pouvait
 * s'écrire qu'en calculant soi-même une coordonnée de façade, et le modèle s'est trompé de cinq
 * blocs. Les primitives du lot B retirent ce calcul au modèle ; ce prompt le lui interdit
 * explicitement, et le contrat architectural qu'il énonce est exactement celui que le serveur
 * mesure sur la grille.</p>
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
                déterministe transforme ensuite ton plan en blocs : tu décris des volumes et des
                INTENTIONS, jamais des blocs un par un.

                LA RÈGLE QUI GOUVERNE TOUT LE RESTE

                Tu conçois la structure. Le moteur calcule les éléments architecturaux fiables.

                Concrètement : tu ne calcules JAMAIS une coordonnée de façade, l'orientation d'une
                marche, les deux moitiés d'une porte, le sens d'une charnière, ni les propriétés
                d'un bloc. Tu dis « une porte sur la façade avant, troisième cellule », et le moteur
                en déduit tout le reste. Chaque fois que tu calcules à sa place, tu introduis une
                erreur que personne ne relira.

                RÈGLES ABSOLUES

                1. Ta réponse est UNIQUEMENT un document YAML. Aucune phrase avant, aucune phrase
                   après, aucun bloc de code, aucune explication.
                2. N'utilise QUE les instructions listées ci-dessous. Une instruction plausible mais
                   absente de la liste fait échouer la génération.
                3. N'utilise que des blocs Minecraft VANILLA, en minuscules, forme
                   « minecraft:nom ». N'écris PAS de propriétés entre crochets : le moteur les
                   calcule, et une propriété inventée fait échouer la génération. « face=bottom »
                   sur un escalier, par exemple, n'existe pas — la propriété s'appelle « half ».
                4. Toutes les coordonnées sont LOCALES, entières, et comprises dans la taille que tu
                   déclares : de 0 à size-1 inclus. Une zone qui sort du volume fait échouer la
                   génération.
                5. Ne dépasse PAS les dimensions maximales données dans la demande.

                FORME DU DOCUMENT

                id: identifiant_minuscule        # 3 à 48 caractères, [a-z0-9_] seulement
                name: "Nom affiché du bâtiment"
                kind: habitable                  # habitable | decoration | portal
                front: NORTH | EAST | SOUTH | WEST   # la façade d'entrée
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

                « kind » décide des contrôles appliqués : « habitable » exige une entrée, de la
                lumière, des fenêtres et des étages accessibles ; « decoration » et « portal » sont
                jugés plus librement. Un bâtiment où l'on entre est « habitable ».

                LES INSTRUCTIONS DE VOLUME

                - op: box     → remplit toute la zone.
                - op: shell   → ne pose que l'enveloppe (les six faces), intérieur vide.
                - op: walls   → ne pose que les quatre murs verticaux : ni sol, ni plafond.
                - op: clear   → VIDE la zone. Pas de clé « block ».
                - op: pillars → une colonne verticale tous les « spacing » blocs le long de la
                                zone. Clés : « spacing » (défaut 4, minimum 2).
                - op: gable   → toit à deux pentes. Exige « axis: x » ou « axis: z » — l'axe du
                                FAÎTE, pas une direction — et un bloc d'ESCALIER. Ajoute « fill: » avec un bloc plein :
                                c'est le matériau des deux triangles de pignon, et sans lui le
                                pignon est fait d'escaliers, ce qui laisse des creux visibles.

                Ces instructions prennent « from » et, facultativement, « to ». Sans « to »,
                l'instruction vise UN SEUL bloc. Les bornes sont INCLUSES des deux côtés :
                from [0,0,0] to [0,0,0] vaut un bloc.

                LES INSTRUCTIONS D'ARCHITECTURE — PRÉFÈRE-LES TOUJOURS

                Celles-ci ne prennent PAS de coordonnées. Elles se placent sur une façade, et le
                moteur fait le calcul. C'est le moyen le plus sûr d'obtenir une vraie porte, une
                vraie fenêtre et un vrai escalier.

                - op: door      → une porte complète : les deux moitiés, la bonne orientation, la
                                  charnière, ET le dégagement intérieur. Clés : « facade »,
                                  « offset » (position le long de la façade), « y » (défaut 1),
                                  « block » (un bloc de porte, ex. minecraft:oak_door).
                - op: window    → une baie vitrée percée dans le mur et vitrée. Clés : « facade »,
                                  « offset », « y », « width », « height », « block » (un vitrage).
                - op: light     → une source de lumière. Clés : « from », « to », « spacing »,
                                  « block » (minecraft:lantern, minecraft:torch…).
                - op: stair_run → une volée d'escalier praticable entre deux niveaux, avec deux
                                  blocs de hauteur libre au-dessus de chaque marche et un palier en
                                  haut. Clés : « from » (le pied de la volée), « steps » (nombre de
                                  marches, défaut 4), « direction » (le POINT CARDINAL vers lequel
                                  on monte : north, south, east ou west — jamais un axe),
                                  « block » (un bloc d'escalier).
                - op: ladder    → une échelle adossée à un mur, qui regarde vers l'intérieur, et
                                  dont le débouché du haut est dégagé. Clés : « facade » (la façade
                                  contre laquelle elle est adossée), « from », « to » (le bas et le
                                  haut de l'échelle), « block ».

                « facade » prend FRONT, BACK, LEFT ou RIGHT. JAMAIS une coordonnée, JAMAIS un point
                cardinal : FRONT est la façade d'entrée, quelle que soit l'orientation du bâtiment,
                et LEFT/RIGHT sont vues depuis l'extérieur, face au bâtiment. « offset » part
                toujours du coin de coordonnée minimale, de 0 à la largeur de la façade moins 1.

                L'ORDRE COMPTE

                Les instructions s'appliquent dans l'ordre, et une instruction postérieure recouvre
                une antérieure sur la même cellule :

                  1. pose le sol et les murs ;
                  2. « clear » l'intérieur pour creuser les pièces ;
                  3. pose les portes et les fenêtres (elles percent et dégagent elles-mêmes) ;
                  4. pose les escaliers, les échelles, les lumières, puis le mobilier ;
                  5. termine par le toit.

                LE CONTRAT ARCHITECTURAL — VÉRIFIE CHAQUE LIGNE AVANT DE RÉPONDRE

                Le serveur mesure ces points sur la grille réellement construite, pas sur tes
                intentions. Un plan qui en manque un est REFUSÉ et rien n'est écrit.

                  [ ] UNE ENTRÉE. Une ouverture de 2 blocs de haut traversant une façade, avec de
                      l'air derrière. Utilise « op: door » : c'est la seule façon de ne pas se
                      tromper.
                  [ ] DES VITRAGES, sur au moins DEUX façades différentes. Utilise « op: window ».
                  [ ] DE LA LUMIÈRE, à moins de 8 blocs de tout point intérieur.
                  [ ] UNE ENVELOPPE CLOSE : pas de trou dans les murs ni dans le toit, pignons
                      compris. « op: gable » avec « fill » les ferme pour toi.
                  [ ] TOUS LES ÉTAGES ACCESSIBLES. Si tu poses un plancher intermédiaire, il faut un
                      « op: stair_run » ou un « op: ladder » qui y mène, ET une trémie dans le
                      plancher (« op: clear ») pour y passer.
                  [ ] AU MOINS TROIS MATÉRIAUX dans l'enveloppe, et aucun au-delà de 70 %.
                  [ ] DU MOBILIER : au moins quelques cellules de contenu intérieur.

                UN EXEMPLE COMPLET ET VALIDE

                id: exemple_atelier
                name: "Atelier du forgeron"
                kind: habitable
                front: NORTH
                size:
                  x: 9
                  y: 7
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 6]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [8, 4, 6]
                    block: minecraft:stone_bricks
                  - op: pillars
                    from: [0, 1, 0]
                    to: [8, 4, 6]
                    spacing: 4
                    block: minecraft:oak_log
                  - op: box
                    from: [0, 5, 0]
                    to: [8, 5, 6]
                    block: minecraft:oak_planks
                  - op: door
                    facade: FRONT
                    offset: 4
                    block: minecraft:oak_door
                  - op: window
                    facade: LEFT
                    offset: 2
                    y: 2
                    width: 2
                    height: 2
                    block: minecraft:glass_pane
                  - op: window
                    facade: RIGHT
                    offset: 2
                    y: 2
                    width: 2
                    height: 2
                    block: minecraft:glass_pane
                  - op: window
                    facade: BACK
                    offset: 4
                    y: 2
                    width: 1
                    height: 2
                    block: minecraft:glass_pane
                  - op: light
                    from: [2, 4, 3]
                    to: [6, 4, 3]
                    spacing: 4
                    block: minecraft:lantern
                  - op: box
                    from: [1, 1, 5]
                    to: [7, 1, 5]
                    block: minecraft:smithing_table
                  - op: box
                    from: [1, 1, 1]
                    to: [1, 2, 1]
                    block: minecraft:barrel
                  - op: gable
                    from: [0, 5, 0]
                    to: [8, 8, 6]
                    axis: x
                    fill: minecraft:stone_bricks
                    block: minecraft:oak_stairs

                Remarque ce que cet exemple NE fait pas : il ne calcule aucune coordonnée de porte,
                aucune orientation de marche, aucune propriété de bloc. Il décrit, et le moteur
                calcule.

                CONTRAINTE DE LONGUEUR — LIS CECI

                Ta réponse a un budget de sortie limité. Un document COUPÉ EN PLEIN MILIEU est
                totalement inutilisable : il n'y a pas de « plan partiel » exploitable. Si tu dois
                choisir, fais un bâtiment plus SOBRE mais COMPLET, et qui satisfait les sept points
                du contrat — c'est ce qui est contrôlé. N'ajoute des détails décoratifs que si le
                plan tient déjà entièrement. Entre 30 et 80 instructions est la fourchette utile.
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
        return correctionPrompt(demand, previousPlan, refusal, "", 0, 0);
    }

    /**
     * Relance manuelle : le motif, les mesures, et <strong>pas</strong> de décompte de tentatives.
     *
     * <p>Une correction demandée au clic n'a pas de budget à annoncer — l'administrateur décide à
     * chaque fois. Lui faire lire « tentative 1 sur 1, c'est la dernière » serait faux : il peut
     * recommencer autant qu'il le veut, et c'est lui qui paie en connaissance de cause.</p>
     */
    public static String correctionPrompt(Demand demand, String previousPlan, String refusal,
                                          String metrics) {
        return correctionPrompt(demand, previousPlan, refusal, metrics, 0, 0);
    }

    /**
     * Relance, avec le motif réel, les <strong>mesures</strong> prises sur la grille, et le rang de
     * la tentative.
     *
     * <p>Les mesures sont la pièce la plus utile de ce message. Un modèle est volontiers convaincu
     * d'avoir posé des fenêtres ; « vitrages comptés sur la grille : 0 » le détrompe sans
     * discussion. Et dire combien de tentatives restent évite deux comportements opposés et aussi
     * mauvais : la réécriture complète du plan à chaque passe, et l'entêtement sur un détail.</p>
     *
     * @param metrics mesures du serveur, déjà mises en forme ; peut être vide
     * @param attempt rang de cette tentative, à partir de 1
     * @param maxAttempts budget total de tentatives accordé par l'administrateur
     */
    public static String correctionPrompt(Demand demand, String previousPlan, String refusal,
                                          String metrics, int attempt, int maxAttempts) {
        String measured = metrics == null || metrics.isBlank() ? ""
                : """

                        CE QUE LE SERVEUR A MESURÉ SUR LA GRILLE CONSTRUITE

                        %s

                        Ces chiffres sont des faits, pas des avis : ils décrivent le bâtiment que
                        ton plan produit réellement. S'ils contredisent ton intention, c'est ton
                        plan qui a tort.
                        """.formatted(metrics.trim());
        int remaining = Math.max(0, maxAttempts - attempt);
        String budget = attempt <= 0 || maxAttempts <= 0 ? "" : ("\n\nTentative " + attempt
                + " sur " + maxAttempts + "." + (remaining == 0
                        ? " C'est la DERNIÈRE : au-delà, la génération est abandonnée."
                        : " Il en restera " + remaining + " après celle-ci."));
        return """
                %s

                TA PROPOSITION PRÉCÉDENTE A ÉTÉ REFUSÉE PAR LE SERVEUR
                %s

                Motif exact :
                %s
                %s
                Plan refusé :
                %s

                Corrige UNIQUEMENT ce que le motif désigne. Garde l'identifiant, le nom, la façade
                et l'intention du brief. Ne réécris pas le plan de zéro : repars de celui-ci. Si le
                motif parle d'une porte, d'une fenêtre, d'une lumière, d'un escalier ou d'une
                échelle, utilise l'instruction d'architecture correspondante plutôt que de calculer
                une coordonnée. Produis le plan YAML corrigé, et rien d'autre.
                """.formatted(userPrompt(demand), budget,
                refusal == null || refusal.isBlank() ? "motif non transmis" : refusal.trim(),
                measured,
                previousPlan == null ? "" : previousPlan.trim());
    }
}
