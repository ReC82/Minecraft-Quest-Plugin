package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.dialogue.DialogueConditionYaml;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Lecture de {@code hub.stations.exceptions} (issue #675) :
 *
 * <pre>
 * hub:
 *   stations:
 *     exceptions:
 *       - id: salle_de_craft
 *         stations: [CRAFTING_TABLE, FURNACE]   # absent ou vide = tous les postes
 *         blocks:                               # absent ou vide = partout dans le Hub
 *           - { x: 12, y: 64, z: -30 }          # world: facultatif, hub.world par défaut
 *         required-level: 5                     # facultatif, niveau RPG (#532)
 *         conditions:                           # mêmes clés que access.conditions d'un portail (#567)
 *           - type: QUEST_STATE
 *             quest: rpgquest:guard_tier1
 *             state: COMPLETED
 *         message: "&lt;gray&gt;Termine la chasse au cristal pour utiliser cet établi.&lt;/gray&gt;"
 * </pre>
 *
 * <p>Toute erreur refuse le chargement de la configuration (message en français) : une exception mal
 * écrite n'ouvre jamais un poste par accident. Les types de conditions admis sont ceux des portails
 * ({@link PortalAccess#ALLOWED_TYPES}) — aucun second jeu de conditions.</p>
 */
public final class HubStationExceptionsYaml {

    public static final int MAX_EXCEPTIONS = 64;
    public static final int MAX_BLOCKS = 64;
    public static final int MAX_CONDITIONS = PortalAccess.MAX_RULES;
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,48}");

    private HubStationExceptionsYaml() {
    }

    /** @param hub section {@code hub} ({@code null} = aucune exception) */
    public static List<HubStationException> read(ConfigurationSection hub, String hubWorld) {
        if (hub == null || !hub.isSet("stations.exceptions")) {
            return List.of();
        }
        if (!hub.isList("stations.exceptions")) {
            throw new IllegalArgumentException("« hub.stations.exceptions » doit être une liste.");
        }
        List<Map<?, ?>> raw = hub.getMapList("stations.exceptions");
        if (raw.size() != hub.getList("stations.exceptions").size()) {
            throw new IllegalArgumentException("« hub.stations.exceptions » : chaque exception doit être une section.");
        }
        if (raw.size() > MAX_EXCEPTIONS) {
            throw new IllegalArgumentException("« hub.stations.exceptions » : " + MAX_EXCEPTIONS + " exceptions au plus.");
        }
        List<HubStationException> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String context = "hub.stations.exceptions[" + i + "]";
            HubStationException exception = readOne(toSection(raw.get(i)), context, hubWorld);
            if (!ids.add(exception.id())) {
                throw new IllegalArgumentException("« " + context + ".id » : « " + exception.id() + " » est déjà utilisé.");
            }
            out.add(exception);
        }
        return List.copyOf(out);
    }

    private static HubStationException readOne(ConfigurationSection s, String context, String hubWorld) {
        String id = s.getString("id", "");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("« " + context + ".id » est obligatoire : minuscules, chiffres, _ ou -"
                    + " (48 caractères au plus).");
        }
        String at = context + " (" + id + ")";
        Integer level = null;
        if (s.isSet("required-level")) {
            if (!s.isInt("required-level") || s.getInt("required-level") < 1
                    || s.getInt("required-level") > PortalAccess.MAX_LEVEL) {
                throw new IllegalArgumentException("« " + at + ".required-level » doit être un entier entre 1 et "
                        + PortalAccess.MAX_LEVEL + ".");
            }
            level = s.getInt("required-level");
        }
        String message = s.getString("message", "");
        if (message.length() > PortalAccess.MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("« " + at + ".message » : " + PortalAccess.MAX_MESSAGE_LENGTH
                    + " caractères au plus.");
        }
        return new HubStationException(id, stations(s, at), blocks(s, at, hubWorld), level, conditions(s, at), message);
    }

    private static Set<HubStation> stations(ConfigurationSection s, String at) {
        if (s.isSet("stations") && !s.isList("stations")) {
            throw new IllegalArgumentException("« " + at + ".stations » doit être une liste de postes.");
        }
        Set<HubStation> stations = EnumSet.noneOf(HubStation.class);
        for (String name : s.getStringList("stations")) {
            try {
                stations.add(HubStation.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("« " + at + ".stations » : poste inconnu « " + name + " » (connus : "
                        + java.util.Arrays.toString(HubStation.values()) + ").");
            }
        }
        return stations;
    }

    private static List<HubStationException.BlockRef> blocks(ConfigurationSection s, String at, String hubWorld) {
        if (s.isSet("blocks") && !s.isList("blocks")) {
            throw new IllegalArgumentException("« " + at + ".blocks » doit être une liste de positions.");
        }
        List<Map<?, ?>> raw = s.getMapList("blocks");
        if (raw.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("« " + at + ".blocks » : " + MAX_BLOCKS + " blocs au plus.");
        }
        List<HubStationException.BlockRef> blocks = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            ConfigurationSection b = toSection(raw.get(i));
            String where = at + ".blocks[" + i + "]";
            for (String axis : List.of("x", "y", "z")) {
                if (!b.isInt(axis)) {
                    throw new IllegalArgumentException("« " + where + "." + axis + " » doit être un entier.");
                }
            }
            String world = b.getString("world", hubWorld).trim();
            if (!world.equals(hubWorld)) {
                throw new IllegalArgumentException("« " + where + ".world » : « " + world
                        + " » n'est pas le monde Hub (« " + hubWorld + " ») — les postes ne sont fermés que dans le Hub.");
            }
            blocks.add(new HubStationException.BlockRef(world, b.getInt("x"), b.getInt("y"), b.getInt("z")));
        }
        return blocks;
    }

    private static List<DialogueCondition> conditions(ConfigurationSection s, String at) {
        if (s.isSet("conditions") && !s.isList("conditions")) {
            throw new IllegalArgumentException("« " + at + ".conditions » doit être une liste.");
        }
        List<Map<?, ?>> raw = s.getMapList("conditions");
        if (raw.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException("« " + at + ".conditions » : " + MAX_CONDITIONS + " conditions au plus.");
        }
        List<DialogueCondition> conditions = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String where = at + ".conditions[" + i + "]";
            List<String> errors = new ArrayList<>();
            DialogueCondition condition = DialogueConditionYaml.parse(toSection(raw.get(i)), where, errors);
            if (condition == null || !errors.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ; ", errors.isEmpty()
                        ? List.of(where + " : condition invalide.") : errors));
            }
            if (!PortalAccess.ALLOWED_TYPES.contains(condition.type())) {
                throw new IllegalArgumentException("« " + where + " » : condition « " + condition.type()
                        + " » non proposée (admises : " + PortalAccess.ALLOWED_TYPES + ").");
            }
            conditions.add(condition);
        }
        return conditions;
    }

    private static ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }
}
