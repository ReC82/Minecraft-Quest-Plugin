package com.lodygames.rpgquest.building.plan;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ce qu'un bloc <strong>fait</strong>, par déclaration explicite (issue #94, lot A).
 *
 * <h2>Pourquoi ce registre existe, et ce qu'il refuse d'être</h2>
 *
 * <p>L'audit architectural doit répondre à des questions comme « y a-t-il une vitre dans cette
 * façade ? » ou « cette cellule est-elle franchissable ? ». La tentation serait d'écrire
 * {@code state.contains("glass")}. C'est <strong>interdit ici</strong>, et pas par principe : une
 * recherche de sous-chaîne produit des validations fausses dans les deux sens. Elle manquerait
 * {@code glowstone}, {@code shroomlight}, {@code redstone_lamp} et {@code campfire} pour
 * l'éclairage ; elle compterait {@code glass_bottle} comme un vitrage ; elle prendrait
 * {@code soul_torch} pour une source utile alors que son niveau de lumière est de 10, pas 14.</p>
 *
 * <p>Ici, la sémantique est une <strong>donnée relue</strong> : une table d'identifiants exacts, et
 * une table de familles déclarées sur la grammaire des identifiants Minecraft. La seconde n'est pas
 * une devinette : les identifiants du jeu sont systématiques
 * ({@code <couleur>_stained_glass_pane}, {@code <bois>_door}, {@code <pierre>_stairs}), et énumérer
 * les seize couleurs de verre teinté n'apporterait rien d'autre que du bruit. La différence avec un
 * {@code contains} est que la règle est <em>écrite une fois, au même endroit, et testable</em> —
 * pas dispersée dans les contrôles.</p>
 *
 * <h2>Un bloc sans rôle déclaré est un bloc plein</h2>
 *
 * <p>Choix conservateur, et c'est le défaut normal : la très grande majorité des blocs d'un bâtiment
 * — briques, planches, pierre taillée — n'ont aucun rôle architectural particulier, ils font de la
 * matière. Le registre ne décrit donc que ce qui <strong>sort</strong> de l'ordinaire. Un bloc absent
 * de ces tables ne comptera ni comme vitrage, ni comme lumière, ni comme passage : l'audit ne doit
 * jamais <em>inventer</em> une qualité.</p>
 *
 * <h2>Extensibilité (lot B)</h2>
 *
 * <p>Les primitives sémantiques du lot B émettront des blocs concrets — {@code oak_door},
 * {@code glass_pane}, {@code lantern}, {@code ladder}. Elles seront donc reconnues <strong>sans
 * modification de l'audit</strong>, parce que c'est ce registre qui porte le sens, et non les
 * contrôles. C'est l'intérêt de séparer les deux.</p>
 */
public final class BlockSemantics {

    private BlockSemantics() {
    }

    /** Ce qu'un bloc apporte à l'architecture. Un bloc peut cumuler plusieurs rôles. */
    public enum Role {
        /** Transparent et <strong>non franchissable</strong> : c'est une fenêtre, pas un trou. */
        GLAZING,
        /** Émet de la lumière à un niveau utile (voir {@link #USEFUL_LIGHT_LEVEL}). */
        LIGHT,
        /**
         * Un joueur peut traverser cette cellule. L'air absent du plan l'est par défaut ; une porte
         * l'est aussi — elle s'ouvre. C'est ce qui permet au contrôle d'entrée de reconnaître
         * indifféremment un percement brut et une vraie porte du lot B.
         */
        PASSABLE,
        /** On peut y monter : échelle, échafaudage, vigne. */
        CLIMBABLE,
        /** Mobilier et aménagement : sert à juger si l'intérieur est meublé, jamais s'il est beau. */
        FURNISHING
    }

    /**
     * Niveau de lumière minimal pour qu'un bloc compte comme <strong>source d'éclairage</strong>.
     *
     * <p>Une bougie (3) ou un champignon lumineux (1) décorent ; ils n'éclairent pas une salle. Le
     * seuil à 10 retient torches, lanternes, pierre lumineuse, lanterne des mers, lumière des
     * profondeurs et feu de camp, et écarte le décoratif.</p>
     */
    public static final int USEFUL_LIGHT_LEVEL = 10;

    /** Identifiants exacts, avec leur niveau de lumière quand ils en émettent. */
    private static final Map<String, Entry> EXACT = exact();

    /**
     * Familles déclarées sur la grammaire des identifiants Minecraft, testées par suffixe.
     *
     * <p>Ordre significatif : le suffixe le plus long gagne, pour que {@code _glass_pane} soit lu
     * comme un panneau et non comme du verre plein.</p>
     */
    private static final List<Family> FAMILIES = families();

    /** Rôles et niveau de lumière d'un bloc. */
    public record Entry(Set<Role> roles, int lightLevel) {

        public Entry {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        static Entry of(Role... roles) {
            return new Entry(set(roles), 0);
        }

        static Entry light(int level, Role... roles) {
            EnumSet<Role> all = set(roles);
            all.add(Role.LIGHT);
            return new Entry(all, level);
        }

        /**
         * {@code EnumSet.copyOf} lève sur une collection vide : une source de lumière déclarée
         * sans autre rôle passerait par là. On construit donc l'ensemble vide explicitement.
         */
        private static EnumSet<Role> set(Role... roles) {
            EnumSet<Role> all = EnumSet.noneOf(Role.class);
            all.addAll(List.of(roles));
            return all;
        }
    }

    private record Family(String suffix, Entry entry) {
    }

    /** L'entrée du registre pour cet état de bloc, ou {@code null} si inconnue. */
    private static Entry lookup(String state) {
        String type = BlockState.type(state);
        if (type.isEmpty()) {
            return null;
        }
        String id = type.toLowerCase(Locale.ROOT);
        Entry exact = EXACT.get(id);
        if (exact != null) {
            return exact;
        }
        for (Family family : FAMILIES) {
            if (id.endsWith(family.suffix())) {
                return family.entry();
            }
        }
        return null;
    }

    /**
     * Ce bloc porte-t-il un <strong>rôle déclaré</strong> ?
     *
     * <p>À ne pas lire comme « ce bloc existe » : un mur de briques n'a aucun rôle et renvoie donc
     * {@code false}, ce qui est correct. Utile au lot D (#252), qui devra savoir de quels types il
     * connaît les propriétés valides.</p>
     */
    public static boolean hasDeclaredRole(String state) {
        return lookup(state) != null;
    }

    public static boolean has(String state, Role role) {
        Entry entry = lookup(state);
        return entry != null && entry.roles().contains(role);
    }

    /**
     * Une cellule est-elle franchissable ? {@code null} représente l'absence de bloc, donc de
     * l'air : c'est la convention du {@link com.lodygames.rpgquest.building.model.Blueprint}, où
     * seule la matière est listée.
     */
    public static boolean passable(String state) {
        return state == null || has(state, Role.PASSABLE);
    }

    /** Un vitrage : transparent, mais qui ferme l'enveloppe. */
    public static boolean glazing(String state) {
        return state != null && has(state, Role.GLAZING);
    }

    /** Une source de lumière réellement utile — voir {@link #USEFUL_LIGHT_LEVEL}. */
    public static boolean lightSource(String state) {
        Entry entry = lookup(state);
        return entry != null && entry.roles().contains(Role.LIGHT)
                && entry.lightLevel() >= USEFUL_LIGHT_LEVEL;
    }

    public static boolean climbable(String state) {
        return state != null && has(state, Role.CLIMBABLE);
    }

    public static boolean furnishing(String state) {
        return state != null && has(state, Role.FURNISHING);
    }

    /**
     * Une cellule porte-t-elle un joueur debout ? Tout ce qui n'est ni franchissable ni grimpable
     * soutient : un escalier, une dalle et un mur plein portent tous.
     */
    public static boolean support(String state) {
        return state != null && !passable(state) && !climbable(state);
    }

    // ---- Les tables -----------------------------------------------------------------------------

    private static Map<String, Entry> exact() {
        Map<String, Entry> m = new LinkedHashMap<>();

        // Vitrages pleins et teintés. « tinted_glass » est opaque à la lumière mais reste une
        // fenêtre du point de vue architectural : on la compte comme vitrage.
        m.put("glass", Entry.of(Role.GLAZING));
        m.put("tinted_glass", Entry.of(Role.GLAZING));
        // « glass_pane » doit être déclaré en exact : il ne finit PAS par « _glass_pane », donc la
        // famille par suffixe ne l'attrape pas. Attrapé par un test, et c'est exactement le genre
        // d'angle mort qu'une recherche de sous-chaîne aurait masqué en paraissant fonctionner.
        m.put("glass_pane", Entry.of(Role.GLAZING));

        // Sources de lumière, avec leur niveau réel. Le niveau décide, pas le nom : « soul_torch »
        // et « soul_lantern » éclairent à 10, « candle » à 3 — seul le premier groupe passe le seuil.
        m.put("torch", Entry.light(14));
        m.put("wall_torch", Entry.light(14));
        m.put("soul_torch", Entry.light(10));
        m.put("soul_wall_torch", Entry.light(10));
        m.put("lantern", Entry.light(15));
        m.put("soul_lantern", Entry.light(10));
        m.put("glowstone", Entry.light(15));
        m.put("sea_lantern", Entry.light(15));
        m.put("shroomlight", Entry.light(15));
        m.put("redstone_lamp", Entry.light(15));
        m.put("ochre_froglight", Entry.light(15));
        m.put("verdant_froglight", Entry.light(15));
        m.put("pearlescent_froglight", Entry.light(15));
        m.put("campfire", Entry.light(15));
        m.put("soul_campfire", Entry.light(10));
        m.put("end_rod", Entry.light(14));
        m.put("jack_o_lantern", Entry.light(15));
        // Décoratifs sous le seuil : déclarés pour que l'audit sache qu'il les connaît, et ne les
        // compte pas comme éclairage d'une salle.
        m.put("candle", Entry.light(3));
        m.put("glow_lichen", Entry.light(7));

        // Franchissables.
        m.put("air", Entry.of(Role.PASSABLE));
        m.put("cave_air", Entry.of(Role.PASSABLE));
        m.put("water", Entry.of(Role.PASSABLE));

        // Grimpables.
        m.put("ladder", Entry.of(Role.CLIMBABLE));
        m.put("scaffolding", Entry.of(Role.CLIMBABLE));
        m.put("vine", Entry.of(Role.CLIMBABLE));
        m.put("twisting_vines", Entry.of(Role.CLIMBABLE));

        // Mobilier et aménagement.
        for (String furniture : new String[] {"bookshelf", "chiseled_bookshelf", "lectern",
            "crafting_table", "barrel", "chest", "trapped_chest", "furnace", "smoker",
            "blast_furnace", "anvil", "cartography_table", "smithing_table", "loom", "composter",
            "cauldron", "brewing_stand", "enchanting_table", "flower_pot", "armor_stand",
            "grindstone", "stonecutter", "bell", "beacon", "jukebox", "note_block"}) {
            m.put(furniture, Entry.of(Role.FURNISHING));
        }

        return Map.copyOf(m);
    }

    private static List<Family> families() {
        // Du suffixe le plus long au plus court : « _stained_glass_pane » doit gagner sur
        // « _glass_pane », qui doit gagner sur « _glass ».
        return List.of(
                new Family("_stained_glass_pane", Entry.of(Role.GLAZING)),
                new Family("_stained_glass", Entry.of(Role.GLAZING)),
                new Family("_glass_pane", Entry.of(Role.GLAZING)),
                new Family("_glass", Entry.of(Role.GLAZING)),
                // Une porte et un portillon ferment l'enveloppe mais s'ouvrent : franchissables au
                // sens architectural. C'est ce qui fera reconnaître les portes du lot B sans
                // toucher à l'audit.
                new Family("_door", Entry.of(Role.PASSABLE)),
                new Family("_trapdoor", Entry.of(Role.PASSABLE)),
                new Family("_fence_gate", Entry.of(Role.PASSABLE)),
                // Mobilier systématique.
                new Family("_bed", Entry.of(Role.FURNISHING)),
                new Family("_banner", Entry.of(Role.FURNISHING)),
                new Family("_carpet", Entry.of(Role.PASSABLE, Role.FURNISHING)),
                new Family("_sign", Entry.of(Role.FURNISHING)),
                new Family("_candle", Entry.light(3)),
                // Végétation traversable.
                new Family("_sapling", Entry.of(Role.PASSABLE)));
    }
}
