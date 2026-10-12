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
                         int maxWidth, int maxHeight, String buildingId, String design) {

        /** Sans conception détaillée (#289) : le plan part du seul brief. */
        public Demand(String siteId, String siteName, String facing, String brief, int maxWidth,
                      int maxHeight, String buildingId) {
            this(siteId, siteName, facing, brief, maxWidth, maxHeight, buildingId, "");
        }

        /** La même demande, avec la conception détaillée à traduire (#289). */
        public Demand withDesign(String text) {
            return new Demand(siteId, siteName, facing, brief, maxWidth, maxHeight, buildingId, text);
        }

        /** La même demande, avec un brief complété (écueils d'une conception neuve). */
        public Demand withBrief(String text) {
            return new Demand(siteId, siteName, facing, text, maxWidth, maxHeight, buildingId, design);
        }

        /**
         * Forme antérieure à #267 : l'identifiant de bibliothèque était dérivé du nom.
         *
         * @param siteId   depuis #267, l'identifiant du PROJET d'atelier (nom conservé pour ne pas
         *                 casser les appelants)
         */
        public Demand(String siteId, String siteName, String facing, String brief, int maxWidth,
                      int maxHeight) {
            this(siteId, siteName, facing, brief, maxWidth, maxHeight, "");
        }

        public Demand {
            design = design == null ? "" : design.trim();
            buildingId = buildingId == null ? "" : buildingId.trim();
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

        /**
         * Identifiant de bâtiment à imposer au modèle — jamais inventé par lui. Depuis #267, celui
         * que l'administrateur a choisi dans le projet ; à défaut, dérivé du nom.
         */
        public String suggestedId() {
            if (!buildingId.isEmpty()) {
                return buildingId;
            }
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
                3. N'utilise que des blocs Minecraft VANILLA 1.21.11, en minuscules, forme
                   « minecraft:nom ». N'écris PAS de propriétés entre crochets : le moteur les
                   calcule, et une propriété inventée fait échouer la génération. « face=bottom »
                   sur un escalier, par exemple, n'existe pas — la propriété s'appelle « half ».
                   Le serveur vérifie CHAQUE identifiant dans le registre du jeu avant tout
                   verdict. Les lits, vitres teintées, laines, tapis, bougies, bannières et bétons
                   n'existent QU'EN COULEUR : minecraft:pink_bed, minecraft:pink_stained_glass_pane,
                   minecraft:white_carpet. « minecraft:bed », « minecraft:stained_glass_pane » et
                   « minecraft:carpet » N'EXISTENT PAS et font refuser le plan entier.
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
                anchor:                # OPTIONNEL — par défaut le centre de la base, y: 0
                  x: 4
                  y: 1                 # la couche posée AU NIVEAU DU TERRAIN (voir l'entrée)
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
                - op: light     → une source de lumière. Clés : « from », « to » (des points
                                  [x, y, z]), « spacing », « block » (minecraft:lantern,
                                  minecraft:torch…).
                - op: staircase → L'ESCALIER ENTRE DEUX NIVEAUX. Tu donnes le pied, la
                                  direction et le plancher à atteindre ; le moteur calcule le
                                  nombre de marches, PERCE LA TRÉMIE dans le plancher, dégage la
                                  hauteur libre, le départ et le palier, et protège ce volume contre
                                  le mobilier. Clés : « from » (la PREMIÈRE MARCHE, posée sur le sol
                                  du niveau de départ : y = y du sol + 1), « direction » (le POINT
                                  CARDINAL vers lequel on monte : north, south, east ou west),
                                  « floor » (le y du PLANCHER de l'étage, le bloc sur lequel on
                                  marche en haut), « width » (1 à 3 voies, élargit vers +z pour une
                                  montée est/ouest, vers +x pour une montée nord/sud), « block »
                                  (un bloc d'escalier). La volée compte floor - y + 1 marches et
                                  occupe autant de cellules dans sa direction, plus une cellule de
                                  départ avant et une de palier après : toutes doivent être À
                                  L'INTÉRIEUR, à au moins une cellule des murs extérieurs. Ne perce
                                  JAMAIS la trémie toi-même.
                - op: bed       → UN LIT, assemblage de deux cellules : le pied et la tête. Clés :
                                  « from » (le PIED, [x, y, z] posé sur le sol), « direction »
                                  (north, south, east ou west : la direction de la TÊTE vue depuis
                                  le pied ; la tête occupe la cellule voisine dans cette direction),
                                  « block » (un lit COLORÉ : minecraft:pink_bed, minecraft:blue_bed…).
                                  JAMAIS un lit posé par « op: box » : il le serait en moitiés sans
                                  leur paire, détruites à la pose — le plan est refusé.
                - op: ladder    → une échelle VERTICALE adossée à un mur, qui regarde vers
                                  l'intérieur, et dont le débouché du haut est dégagé. Clés :
                                  « facade » (le mur contre lequel elle s'adosse — il ne fixe QUE
                                  son orientation, jamais sa position), « from » et « to » (le bas
                                  et le haut, en points COMPLETS [x, y, z] : trois nombres, mêmes
                                  x et z, seul y change), « block ». Place-la sur la cellule
                                  INTÉRIEURE juste contre ce mur : dans un bâtiment « front: NORTH »
                                  de profondeur 7, le mur arrière (BACK) est en z = 6, donc
                                  l'échelle est en z = 5.

                Seules « door » et « window » se repèrent par « facade » et « offset ». TOUTES les
                autres instructions prennent des coordonnées COMPLÈTES [x, y, z] — trois nombres,
                jamais deux.

                « facade » prend FRONT, BACK, LEFT ou RIGHT. JAMAIS une coordonnée, JAMAIS un point
                cardinal : FRONT est la façade d'entrée, quelle que soit l'orientation du bâtiment,
                et LEFT/RIGHT sont vues depuis l'extérieur, face au bâtiment.

                « offset » est la coordonnée LE LONG de la façade, en coordonnées du plan : x pour
                une façade nord ou sud, z pour une façade est ou ouest. Le moteur perce l'ouverture
                dans le MUR CONSTRUIT (le « walls », « shell » ou « box » de ce côté) qui contient
                cette position — PAS sur la limite du volume. Si ton bâtiment est en retrait dans
                son volume (murs de [3,1,3] à [15,4,15] dans un volume de 19), « offset » doit
                tomber entre les angles de ce mur, ici de 4 à 14 ; hors de cet intervalle, le plan
                est refusé. Si plusieurs corps se trouvent à cette position (une tour devant la
                maison), l'ouverture va dans le plus extérieur ; « wall: <coordonnée> » désigne
                explicitement un autre mur (le z ou le x de ce mur).

                UN EXEMPLE MINIMAL PAR INSTRUCTION D'ARCHITECTURE

                (Pour un bâtiment « front: NORTH » de taille x 9, y 9, z 7 — celui de l'exemple
                complet plus bas. Chaque bloc est valide tel quel.)

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
                  - op: light
                    from: [2, 4, 3]
                    to: [6, 4, 3]
                    spacing: 4
                    block: minecraft:lantern
                  - op: staircase
                    from: [2, 1, 5]
                    direction: east
                    floor: 5
                    width: 1
                    block: minecraft:oak_stairs
                  - op: bed
                    from: [6, 1, 5]
                    direction: east
                    block: minecraft:red_bed
                  - op: ladder
                    facade: BACK
                    from: [1, 1, 5]
                    to: [1, 4, 5]
                    block: minecraft:ladder
                  - op: pillars
                    from: [0, 1, 0]
                    to: [8, 4, 6]
                    spacing: 4
                    block: minecraft:oak_log

                L'ORDRE COMPTE

                Les instructions s'appliquent dans l'ordre, et une instruction postérieure recouvre
                une antérieure sur la même cellule :

                  1. pose le sol et les murs ;
                  2. « clear » l'intérieur pour creuser les pièces ;
                  3. pose les portes et les fenêtres (elles percent et dégagent elles-mêmes) ;
                  4. pose les lumières, puis le mobilier ; l'escalier « staircase » peut être
                     écrit n'importe où : le moteur l'applique toujours en dernier ;
                  5. termine par le toit.

                LE CONTRAT ARCHITECTURAL — VÉRIFIE CHAQUE LIGNE AVANT DE RÉPONDRE

                Le serveur mesure ces points sur la grille réellement construite, pas sur tes
                intentions. Un plan qui en manque un est REFUSÉ et rien n'est écrit.

                  [ ] UNE ENTRÉE PRATICABLE. Une porte (« op: door ») DANS un mur extérieur, qu'un
                      joueur venu du terrain franchit jusqu'au sol intérieur SANS SAUTER. Le
                      terrain est juste sous la couche « anchor.y » : avec un sol en y=0, mets
                      « anchor: y: 1 » (la fondation s'enfonce, l'entrée est de plain-pied), ou
                      pose devant la porte, DANS le volume, des marches d'escalier tournées vers
                      elle. Une porte posée hors des murs ne compte pas.
                  [ ] DES VITRAGES DANS LES MURS, sur au moins DEUX façades différentes. Utilise
                      « op: window » ; une vitre posée hors d'un mur ne compte pas.
                  [ ] DE LA LUMIÈRE, à moins de 8 blocs de tout point intérieur.
                  [ ] UNE ENVELOPPE CLOSE : pas de trou dans les murs ni dans le toit, pignons
                      compris. « op: gable » avec « fill » les ferme pour toi.
                  [ ] TOUT TIENT APRÈS LA POSE. Une lanterne se pose SUR un bloc plein (table,
                      sol) ou se suspend SOUS un plafond, éventuellement au bout d'une chaîne
                      (minecraft:iron_chain) ; jamais en l'air, jamais sur une autre lanterne.
                      Torches murales, échelles et panneaux muraux s'adossent à un mur plein ;
                      tapis, fleurs et torches reposent sur un bloc. Lits et portes se posent
                      avec « op: bed » et « op: door ». Un bloc qui tomberait est REFUSÉ.
                  [ ] TOUS LES ÉTAGES ACCESSIBLES. Chaque plancher intermédiaire reçoit UN
                      « op: staircase » dont « floor » est le y de ce plancher. N'écris ni trémie
                      (« clear ») ni escalier marche par marche pour monter : le moteur s'en
                      charge. Un étage offre au moins 3 blocs libres sous son plafond.
                  [ ] DES ÉTAGES PRATICABLES. Le mobilier se range le long des murs, en rangées
                      séparées par des allées d'au moins 2 blocs. Une « box » de meubles qui couvre
                      tout un plancher est REFUSÉE : au moins 30 % du plancher doit rester libre.
                  [ ] AU MOINS TROIS MATÉRIAUX dans l'enveloppe, et aucun au-delà de 70 %.
                  [ ] DU MOBILIER : au moins quelques cellules de contenu intérieur.

                UN EXEMPLE COMPLET ET VALIDE — DEUX ÉTAGES

                id: exemple_maison_scribe
                name: "Maison du scribe"
                kind: habitable
                front: NORTH
                size:
                  x: 11
                  y: 13
                  z: 9
                anchor:
                  x: 5
                  y: 1
                  z: 4
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 4, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 6, 0]
                    to: [10, 9, 8]
                    block: minecraft:oak_planks
                  - op: box
                    from: [0, 5, 0]
                    to: [10, 5, 8]
                    block: minecraft:spruce_planks
                  - op: pillars
                    from: [0, 1, 0]
                    to: [10, 9, 8]
                    spacing: 5
                    block: minecraft:oak_log
                  - op: door
                    facade: FRONT
                    offset: 5
                    block: minecraft:spruce_door
                  - op: window
                    facade: FRONT
                    offset: 2
                    y: 2
                    width: 2
                    height: 2
                    block: minecraft:glass_pane
                  - op: window
                    facade: RIGHT
                    offset: 3
                    y: 2
                    width: 2
                    height: 2
                    block: minecraft:glass_pane
                  - op: window
                    facade: FRONT
                    offset: 3
                    y: 7
                    width: 5
                    height: 2
                    block: minecraft:glass_pane
                  - op: window
                    facade: BACK
                    offset: 3
                    y: 7
                    width: 3
                    height: 2
                    block: minecraft:glass_pane
                  - op: light
                    from: [2, 4, 2]
                    to: [8, 4, 2]
                    spacing: 6
                    block: minecraft:lantern
                  - op: light
                    from: [2, 6, 2]
                    to: [8, 6, 2]
                    spacing: 6
                    block: minecraft:lantern
                  - op: staircase
                    from: [2, 1, 6]
                    direction: east
                    floor: 5
                    width: 2
                    block: minecraft:spruce_stairs
                  - op: box
                    from: [9, 1, 2]
                    to: [9, 2, 4]
                    block: minecraft:bookshelf
                  - op: box
                    from: [1, 1, 2]
                    to: [1, 1, 2]
                    block: minecraft:barrel
                  - op: box
                    from: [1, 6, 1]
                    to: [1, 7, 4]
                    block: minecraft:bookshelf
                  - op: box
                    from: [9, 6, 1]
                    to: [9, 7, 4]
                    block: minecraft:bookshelf
                  - op: box
                    from: [5, 6, 3]
                    to: [5, 6, 3]
                    block: minecraft:lectern
                  - op: gable
                    from: [0, 10, 0]
                    to: [10, 12, 8]
                    axis: x
                    fill: minecraft:oak_planks
                    block: minecraft:spruce_stairs

                Remarque ce que cet exemple NE fait pas : il ne calcule aucune coordonnée de porte,
                aucune orientation de marche, aucune propriété de bloc, aucune trémie. Le plancher
                de l'étage est en y=5, donc « floor: 5 » ; la première marche est sur le sol (y=1),
                dans un coin, et le mobilier longe les murs. « anchor: y: 1 » enterre la fondation
                (y=0) : on entre de plain-pied. Il décrit, et le moteur calcule.

                CONTRAINTE DE LONGUEUR — LIS CECI

                Ta réponse a un budget de sortie limité. Un document COUPÉ EN PLEIN MILIEU est
                totalement inutilisable : il n'y a pas de « plan partiel » exploitable. Si tu dois
                choisir, fais un bâtiment plus SOBRE mais COMPLET, et qui satisfait les neuf points
                du contrat — c'est ce qui est contrôlé. N'ajoute des détails décoratifs que si le
                plan tient déjà entièrement. Entre 30 et 80 instructions est la fourchette utile.

                """ + ENGINE_CAPABILITIES;
    }

    /** La demande concrète : les faits de l'emplacement, puis le brief de l'administrateur. */
    public static String userPrompt(Demand demand) {
        return """
                PROJET

                Référence : %s
                Nom : %s
                Façade d'entrée : %s  ← déclare « front: %s » dans ton plan
                Emprise maximale : %d blocs en x, %d blocs en z
                Hauteur maximale : %d blocs

                IDENTIFIANT À UTILISER

                id: %s

                N'invente pas d'autre identifiant : c'est celui sous lequel la bibliothèque rangera
                ce bâtiment.

                LE BÂTIMENT SOUHAITÉ, DÉCRIT PAR L'ADMINISTRATEUR

                %s
                %s
                Produis maintenant le plan YAML, et rien d'autre.
                """.formatted(demand.siteId(), demand.siteName(), demand.facing(), demand.facing(),
                demand.maxWidth(), demand.maxWidth(), demand.maxHeight(),
                demand.suggestedId(), demand.brief(), designSection(demand));
    }

    /** La conception détaillée à traduire, quand elle existe (#289). */
    private static String designSection(Demand demand) {
        if (demand.design().isEmpty()) {
            return "";
        }
        return """

                LA CONCEPTION DÉTAILLÉE À TRADUIRE (étape 1, validée et conservée par le panel)

                %s

                Traduis-la FIDÈLEMENT dans le vocabulaire du plan : chaque pièce, chaque meuble,
                chaque élément de décoration et chaque accès qu'elle prévoit doit apparaître, à sa
                place et dans sa matière. Si un élément est impossible avec les instructions du
                moteur, fais l'équivalent le plus proche et garde tout le reste. Le brief ci-dessus
                reste la référence en cas de doute.
                """.formatted(demand.design());
    }

    /**
     * Ce que le moteur sait réellement poser (#289) : dit à la conception ET au plan, pour qu'aucun
     * des deux ne promette un tableau ou une armure qui n'existera jamais.
     */
    static final String ENGINE_CAPABILITIES = """
            CE QUE LE MOTEUR SAIT POSER — des BLOCS vanilla 1.21.11, rien d'autre

              Lit                 op: bed, lit coloré (minecraft:pink_bed…) — deux cellules, pied et tête
              Porte               op: door — deux moitiés, orientation et dégagement calculés
              Fenêtres, vitraux   instruction window, minecraft:glass_pane ou <couleur>_stained_glass_pane
              Lumière             op: light (lanternes posées ou suspendues, minecraft:iron_chain), torches
              Escalier d'étage    op: staircase ; marches de perron : blocs d'escalier tournés vers la porte
              Tapis               minecraft:<couleur>_carpet, posé SUR le sol (il lui faut un bloc dessous)
              Plantes en pot      minecraft:potted_<plante> (potted_poppy, potted_fern, potted_azalea_bush…)
              Cuisine             minecraft:furnace, smoker, crafting_table, barrel, cauldron
              Rangements          minecraft:chest, barrel, bookshelf, chiseled_bookshelf, lectern
              Tables, sièges      dalles, escaliers, barrières (oak_fence) et trappes (volets, assises)
              Façades             dalles et escaliers (corniches, appuis), trappes (volets), murets, jardinières
              Plantes du jardin   fleurs (poppy, dandelion, *_tulip…), haies de feuilles, fougères — sur un bloc

            CE QU'IL NE SAIT PAS POSER : tableaux, cadres, supports d'armure, animaux et toute ENTITÉ ;
            le texte d'un panneau. Ne les promets pas : propose un équivalent en blocs ou omets-les en
            le disant.
            """;

    /** Le rôle de l'étape 1 (#289) : un architecte qui conçoit, sans écrire de YAML. */
    public static String designSystemPrompt() {
        return """
                Tu es un architecte et décorateur Minecraft. Tu prépares la CONCEPTION DÉTAILLÉE d'un
                bâtiment, avant que quelqu'un d'autre ne l'écrive en plan technique. Tu n'écris AUCUN
                YAML : un texte structuré, précis, en français, sous les titres imposés.

                Le plan technique sera écrit à partir de ton texte. Tout ce que tu ne nommes pas
                n'existera pas : sois concret — positions en coordonnées du plan (x de … à …, z de …
                à …, étage), matériaux par leur identifiant réel minecraft:…, meubles un par un.

                %s
                RÈGLES DU TERRAIN : le volume va de 0 à size-1 sur chaque axe, sans dépasser
                l'emprise et la hauteur maximales. Le terrain est sous la couche « anchor.y » : une
                entrée doit s'atteindre depuis lui sans sauter (perron et marches DANS l'emprise,
                ou fondation enterrée). Les meubles se rangent le long des murs, avec des allées de 2
                blocs. Ne propose pas un matériau de plus pour le principe : la variété sert le style
                demandé, elle n'est pas un but.
                """.formatted(ENGINE_CAPABILITIES);
    }

    /** La demande de l'étape 1 : le brief entier, les limites, et les titres attendus. */
    public static String designUserPrompt(Demand demand) {
        return """
                PROJET : %s (identifiant %s)
                Façade d'entrée : %s. Emprise maximale : %d × %d blocs, hauteur maximale : %d blocs.

                LE BÂTIMENT SOUHAITÉ, DÉCRIT PAR L'ADMINISTRATEUR (à respecter intégralement)

                %s

                Rédige la conception sous EXACTEMENT ces titres :

                1. PROGRAMME ET ESPACES — chaque pièce demandée : étage, emprise en coordonnées du
                   plan, fonction.
                2. VOLUMES, FAÇADES ET TOITURE — corps de bâtiment (dimensions, retrait dans le
                   volume), hauteurs d'étage, porche, débords, toiture et pignons.
                3. PALETTE JUSTIFIÉE — chaque matériau (identifiant réel) et son rôle.
                4. ACCÈS ET CIRCULATION — porte (façade, position), seuil, perron et marches dans
                   l'emprise, escaliers entre étages (pied, direction, plancher), allées.
                5. MOBILIER, PIÈCE PAR PIÈCE — chaque meuble, sa matière, sa place, la primitive du
                   moteur qui le pose (lit → op: bed, etc.).
                6. DÉCORATION INTÉRIEURE ET EXTÉRIEURE — tapis, plantes, vitraux, luminaires, jardin.
                7. LIMITES DU MOTEUR — ce que le brief demande et qui ne peut pas être posé
                   (entités…), et ce que tu proposes à la place.
                8. CORRESPONDANCE AVEC LE BRIEF — chaque exigence du brief, et l'endroit exact de la
                   conception qui la satisfait, ou la raison pour laquelle elle ne l'est pas.
                """.formatted(demand.siteName(), demand.suggestedId(), demand.facing(),
                demand.maxWidth(), demand.maxWidth(), demand.maxHeight(), demand.brief());
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
                et l'intention du brief. Ne réécris pas le plan de zéro : repars de celui-ci, et
                CONSERVE tout ce qui est déjà juste — pièces, mobilier, décoration, palette : une
                correction technique ne retire aucun élément voulu par le brief ou la conception. Si le
                motif parle d'une porte, d'une fenêtre, d'une lumière, d'un escalier ou d'une
                échelle, utilise l'instruction d'architecture correspondante plutôt que de calculer
                une coordonnée.

                Les coordonnées citées par le motif sont MESURÉES sur la grille construite : elles
                disent exactement quelle cellule bloque et quelle instruction l'a posée. Si le motif
                contient « RÉPARATION VÉRIFIÉE PAR LE MOTEUR », recopie EXACTEMENT l'instruction
                proposée et supprime les escaliers, échelles et « clear » de plancher qui ne mènent
                nulle part. Si une zone sort du volume, ramène-la dans les dimensions déclarées
                (de 0 à size-1). Un plan identique au précédent est refusé sans être lu.
                Produis le plan YAML corrigé, et rien d'autre.
                """.formatted(userPrompt(demand), budget,
                refusal == null || refusal.isBlank() ? "motif non transmis" : refusal.trim(),
                measured,
                previousPlan == null ? "" : previousPlan.trim());
    }

    /**
     * La passe de <strong>critique architecturale</strong> (issue #282) : le plan est déjà conforme,
     * on demande au modèle de le rendre beau et fidèle au brief, sans rien casser.
     *
     * <p>Le propriétaire l'a dit sans détour : « un résultat techniquement conforme mais laid ou
     * inutile n'est pas un succès ». Les trois bibliothèques du 12/10/2026 étaient des blocs
     * rectangulaires au toit uniforme et au mobilier réparti mécaniquement. Cette passe est le
     * second regard d'un architecte : elle n'est demandée qu'APRÈS la conformité, et son plan est
     * vérifié comme un autre — s'il régresse, la version conforme précédente est conservée.</p>
     */
    public static String critiquePrompt(Demand demand, String plan, String metrics, String warnings) {
        return """
                %s

                TON PLAN A PASSÉ LES CONTRÔLES TECHNIQUES DU SERVEUR. IL RESTE À LE RENDRE FIDÈLE.

                Les contrôles du serveur sont GÉOMÉTRIQUES : entrée, fenêtres, lumière, toit, étages,
                stabilité. Ils ne disent rien de la fidélité au brief ni de la beauté. Tu es
                l'architecte en chef qui compare le plan accepté au BRIEF et à la CONCEPTION ci-dessus.

                1. Écris d'abord, en texte simple et SANS bloc de code, sous le titre
                   « OMISSIONS ET ÉCARTS » : chaque exigence du brief ou de la conception absente du plan, mal placée
                   ou incompatible avec le moteur, une par ligne, avec ce que tu fais pour y remédier.
                   Si rien ne manque, écris « Aucune omission ».
                2. Puis donne le plan YAML amélioré, COMPLET, dans UN SEUL bloc ```yaml.

                Améliore dans cet ordre : (a) ce qui manque au brief et à la conception — pièces,
                meubles, décoration, accès ; (b) l'aménagement — meubles par fonction, le long des
                murs, allées de 2 blocs ; (c) les façades et la silhouette demandées. Ne multiplie
                pas les matériaux pour le principe : la variété sert le style demandé.

                CE QUI NE DOIT PAS CHANGER : l'identifiant, le nom, « front », la taille déclarée
                (les zones restent de 0 à size-1), la porte et ses marches, chaque « op: staircase »
                avec son « floor », et tout élément déjà conforme au brief. Ne retire aucune fenêtre
                ni aucune lumière. Si tu ajoutes un étage, ajoute son « staircase ».

                CE QUE LE SERVEUR A MESURÉ SUR LA VERSION ACCEPTÉE

                %s
                %s
                Plan accepté :
                %s

                Le plan amélioré sera vérifié : s'il est refusé, la version actuelle est conservée.
                """.formatted(userPrompt(demand),
                metrics == null || metrics.isBlank() ? "(aucune mesure transmise)" : metrics.trim(),
                warnings == null || warnings.isBlank() ? ""
                        : "\nRemarques du serveur à traiter :\n" + warnings.trim() + "\n",
                plan == null ? "" : plan.trim());
    }

    /** Le texte de la critique qui précède son plan : la liste des omissions relevées (#289). */
    public static String critiqueFindings(String raw) {
        if (raw == null) {
            return "";
        }
        int fence = raw.indexOf("```");
        String head = (fence < 0 ? "" : raw.substring(0, fence)).trim();
        return head.length() > 4_000 ? head.substring(0, 4_000) + " […]" : head;
    }

    /**
     * Ajoute à une demande la consigne sur les images de référence jointes (issue #283).
     *
     * <p>L'image est une <strong>référence de style</strong>. Le propriétaire voulait des
     * bibliothèques moins « bloc rectangulaire » ; il ne veut pas un plan qui essaie de reproduire
     * une photo au pixel près, ce que le vocabulaire fermé ne permet d'ailleurs pas.</p>
     */
    public static String withReferences(String prompt, int images) {
        if (images <= 0) {
            return prompt;
        }
        return prompt + """

                IMAGES DE RÉFÉRENCE

                L'administrateur a joint %d image(s). Ce sont des RÉFÉRENCES DE STYLE : silhouette,
                proportions, forme et débord du toit, rythme des fenêtres, matériaux et couleurs,
                ambiance. Inspire-t'en pour les choix d'architecture. Ne cherche PAS à les recopier
                bloc pour bloc : le plan reste dans les dimensions de la demande, en blocs Minecraft
                vanilla, avec les seules instructions autorisées. Si une image montre plus que ce
                qu'elles permettent (courbes, ornements fins, végétation), choisis l'équivalent le
                plus proche et garde la lisibilité du bâtiment.
                """.formatted(images);
    }
}
