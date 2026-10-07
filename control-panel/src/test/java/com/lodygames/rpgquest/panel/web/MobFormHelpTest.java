package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #169 — aide par champ des formulaires « Mobs spéciaux & boss ».
 *
 * <p>Ce qui est vérifié ici n'est pas la présence de jolies phrases, mais trois propriétés qui
 * engagent la correction du contenu :</p>
 *
 * <ol>
 *   <li>chaque champ porte une aide <strong>visible sous le champ</strong> (un bloc
 *       {@code form-text}), pas un simple {@code placeholder} ni un {@code title} ;</li>
 *   <li>à la <strong>création</strong>, seuls les défauts réellement appliqués par le moteur sont
 *       préremplis — les champs sans défaut restent vides, parce qu'une valeur préremplie serait
 *       enregistrée telle quelle ({@code mob.definition.update} remplace le profil entier) ;</li>
 *   <li>à la <strong>modification</strong>, les valeurs du profil existant sont conservées, et les
 *       exemples cités dans l'aide ne contaminent jamais les champs.</li>
 * </ol>
 */
class MobFormHelpTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Profil complet : toutes les statistiques renseignées et les deux capacités actives. */
    private static final String MOB_DETAILS = "{\"total\":1,\"enabledCount\":1,"
            + "\"spawnSettings\":{\"enabled\":true,\"chance\":0.05,\"maxSimultaneousSpecial\":20},"
            + "\"profiles\":[{\"id\":\"rpgquest:swamp_king\",\"category\":\"BOSS\",\"enabled\":true,"
            + "\"entityType\":\"ZOMBIE\",\"displayName\":\"<red>Roi des Marais</red>\",\"spawnChance\":0.0,"
            + "\"worlds\":[\"wild\"],\"biomes\":[\"swamp\"],\"zones\":[],"
            + "\"health\":300.0,\"damage\":12.0,\"speed\":0.28,\"armor\":10.0,"
            + "\"knockbackResistance\":0.75,\"scale\":1.8,\"creeperExplosionRadius\":null,"
            + "\"particle\":\"SOUL\",\"sound\":\"ENTITY_WITHER_SPAWN\",\"xpReward\":500,"
            + "\"maxPopulation\":1,\"enragedHealthFraction\":0.35,\"enragedSpeedMultiplier\":1.6,"
            + "\"enragedDamageMultiplier\":2.5,\"summonEntityType\":\"HUSK\",\"summonAmount\":3,"
            + "\"summonChance\":0.3,\"summonCooldownSeconds\":12,\"summonMaxAlive\":6}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- 1. Une aide visible sous chaque champ ------------------------------------------------

    @Test
    void everyProfileFieldCarriesVisibleHelpWithDefaultAndEmptyBehaviour() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        // L'aide est un bloc rendu dans le flux, et les méta-données sont étiquetées.
        assertTrue(page.contains("class=\"form-text\""), "aide rendue sous le champ");
        assertTrue(page.contains("class=\"fmeta\""), "méta-données du champ");
        assertTrue(page.contains("<b>Exemple</b>"), "exemple étiqueté");
        assertTrue(page.contains("<b>Défaut</b>"), "défaut étiqueté");
        assertTrue(page.contains("<b>Si vide</b>"), "comportement à vide étiqueté");

        // Chaque champ du formulaire de création a une aide : on vérifie un repère propre à chacun.
        for (String marker : new String[] {
            "Identifiant interne du profil",          // mob_id
            "Décide si le profil entre dans le tirage", // category
            "Créature vanilla servant de base",       // entity_type
            "Nom affiché au-dessus du mob",           // display_name
            "retiré du tirage aléatoire du Wild",     // enabled
            "Probabilité propre à ce profil",         // spawn_chance
            "Mondes où ce profil peut apparaître",    // worlds
            "Biomes où ce profil peut apparaître",    // biomes
            "Zones RPGQuest autorisées",              // zones
            "Nombre maximum de mobs issus de",        // max_population
            "Points de vie maximum",                  // health
            "Dégâts infligés par une attaque",        // damage
            "Attribut vanilla de vitesse",            // speed
            "Réduit les dégâts reçus",                // armor
            "Part du recul annulée",                  // knockback_resistance
            "Multiplie la taille du modèle",          // scale
            "Portée de l'explosion, en blocs",        // creeper_explosion_radius
            "une seule fois, à l'apparition",         // particle / sound
            "Points d'expérience lâchés",             // xp_reward
            "Fraction de la vie maximum sous laquelle", // enraged_health_fraction
            "Multiplie la vitesse une fois enragé",   // enraged_speed_multiplier
            "Multiplie les dégâts une fois enragé",   // enraged_damage_multiplier
            "Créature invoquée en renfort",           // summon_entity_type
            "Combien de renforts apparaissent",       // summon_amount
            "Chance de déclencher l'invocation",      // summon_chance
            "avant qu'une nouvelle invocation soit",  // summon_cooldown_seconds
            "Plafond de renforts encore en vie",      // summon_max_alive
            "Interrupteur général",                   // spawn settings : enabled
            "Filtre global évalué",                   // spawn settings : chance
            "Plafond du nombre total de mobs",        // spawn settings : max
        }) {
            assertTrue(page.contains(marker), "aide manquante : " + marker);
        }
    }

    @Test
    void helpNamesTheUnitsAndTheSupportedSpecialValues() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        // Unités explicites là où elles existent réellement.
        assertTrue(page.contains("2 points = 1 cœur"), "unité de la vie");
        assertTrue(page.contains("en <strong>secondes</strong>"), "unité du cooldown");
        assertTrue(page.contains("Portée de l'explosion, en blocs"), "unité du rayon");

        // Valeurs spéciales : annoncées uniquement là où le moteur les accepte vraiment.
        assertTrue(page.contains("<strong>0 signifie explicitement aucune "), "xp_reward : 0 supporté");
        assertTrue(page.contains("<strong>0 = aucun délai</strong>"), "cooldown : 0 supporté");
        assertTrue(page.contains("1 = totalement immobile"), "résistance au recul : sens de 0 et 1");
        // Bornes strictes, dites comme telles.
        assertTrue(page.contains("Strictement entre 0 et 1</strong> — ni 0 ni 1"), "seuil de rage exclusif");
        assertTrue(page.contains("0 est "), "chance d'invocation : 0 refusé");
    }

    /** Le champ « Particule » ne pilote pas l'aura continue d'un boss : l'aide doit le dire. */
    @Test
    void helpDoesNotPromiseABossAuraDrivenByTheParticleField() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("une seule fois, à l'apparition</strong> du mob"),
                "la particule du profil est un effet d'entrée");
        assertTrue(page.contains("indépendant de ce champ"), "l'aura continue est un effet fixe du moteur");
        assertFalse(page.contains("particules colorées en continu"), "ancienne promesse inexacte retirée");
    }

    /**
     * Garde-fou de balisage : les blocs d'aide sont insérés au milieu de chaînes HTML construites à
     * la main, où une balise mal fermée casserait silencieusement la mise en page du reste de la
     * page. Un élément {@code <form>} est auto-suffisant : tous les {@code <div>} ouverts dedans
     * doivent y être refermés.
     */
    @Test
    void bothFormsStayBalancedAfterInsertingTheHelpBlocks() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        for (String uid : new String[] {"mob-new-def", "mob-edit-0"}) {
            String form = formOf(page, uid);
            assertEquals(count(form, "<div"), count(form, "</div>"),
                    "balisage déséquilibré dans le formulaire " + uid);
            assertFalse(form.contains("<div class=\"form-text\"></div>"), "bloc d'aide vide : " + uid);
            assertTrue(form.contains("class=\"fmeta\""), "aide structurée absente de : " + uid);
        }
    }

    /** Le {@code <form>} complet qui suit le conteneur {@code id="<uid>"}, bornes incluses. */
    private static String formOf(String page, String uid) {
        int anchor = page.indexOf("id=\"" + uid + "\"");
        if (anchor < 0) {
            throw new IllegalStateException("conteneur introuvable : " + uid);
        }
        int start = page.indexOf("<form", anchor);
        int end = page.indexOf("</form>", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("formulaire introuvable pour : " + uid);
        }
        return page.substring(start, end);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ---- 2. Création : préremplir les vrais défauts, et seulement eux ------------------------

    @Test
    void createFormPrefillsOnlyTheDefaultsTheEngineReallyApplies() throws Exception {
        start();
        login();
        String form = createForm(get("/mobs?agent=" + TestConfig.AGENT_ID).body());

        // Défauts réels : « enabled » vrai (parseur : enabled par défaut) et la catégorie SPECIAL.
        assertTrue(form.contains("name=\"enabled\" value=\"true\" checked"), "profil actif par défaut");
        assertTrue(form.contains("<b>Défaut</b> coché à la création"), "défaut de la case annoncé");
        assertTrue(form.contains("<b>Défaut</b> Spécial"), "catégorie par défaut annoncée");

        // Aucun défaut moteur n'existe pour les statistiques : les champs restent VIDES, sinon la
        // valeur serait enregistrée et le mob ne serait plus « comme le vanilla ».
        for (String name : new String[] {"health", "damage", "speed", "armor", "knockback_resistance",
            "scale", "creeper_explosion_radius", "xp_reward", "max_population",
            "enraged_health_fraction", "enraged_speed_multiplier", "enraged_damage_multiplier",
            "summon_amount", "summon_chance", "summon_cooldown_seconds", "summon_max_alive"}) {
            assertTrue(form.contains("name=\"" + name + "\" value=\"\""),
                    "champ prérempli à tort à la création : " + name);
        }
        // Les champs sans défaut l'annoncent, sans inventer de valeur.
        assertTrue(form.contains("<b>Défaut</b> Obligatoire si la capacité est activée"),
                "capacité : obligatoire plutôt qu'un défaut inventé");
        assertTrue(form.contains("<b>Défaut</b> aucun — la vie vanilla du type choisi"),
                "statistique : défaut réel énoncé");
        // La suggestion du panel pour la chance est annoncée comme telle, pas comme un défaut moteur.
        assertTrue(form.contains("aucun côté moteur — obligatoire ; le panel propose 0.01"),
                "la suggestion 0.01 n'est pas présentée comme un défaut du moteur");
    }

    @Test
    void examplesNeverLeakIntoTheSubmittedValues() throws Exception {
        start();
        login();
        String form = createForm(get("/mobs?agent=" + TestConfig.AGENT_ID).body());

        // Les exemples vivent dans l'aide ; aucun attribut value= ne les porte.
        assertTrue(form.contains("<b>Exemple</b> swamp_king"), "exemple d'identifiant dans l'aide");
        assertFalse(form.contains("value=\"swamp_king\""), "exemple jamais injecté dans le champ");
        assertTrue(form.contains("<b>Exemple</b> ZOMBIE"), "exemple de renfort dans l'aide");
        assertFalse(form.contains("name=\"summon_entity_type\" value=\"ZOMBIE\""),
                "exemple de renfort jamais injecté");
        assertFalse(form.contains("placeholder=\"Exemple :"), "plus d'exemple caché dans un placeholder");
    }

    // ---- 3. Modification : conserver, jamais remplacer ---------------------------------------

    @Test
    void editFormKeepsEveryStoredValueAndNeverSubstitutesADefault() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        String form = editForm(page);

        // Chaque valeur enregistrée est reconduite telle quelle dans le champ.
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("health", "300.0");
        stored.put("damage", "12.0");
        stored.put("speed", "0.28");
        stored.put("armor", "10.0");
        stored.put("knockback_resistance", "0.75");
        stored.put("scale", "1.8");
        stored.put("xp_reward", "500");
        stored.put("max_population", "1");
        stored.put("enraged_health_fraction", "0.35");
        stored.put("enraged_speed_multiplier", "1.6");
        stored.put("enraged_damage_multiplier", "2.5");
        stored.put("summon_amount", "3");
        stored.put("summon_chance", "0.3");
        stored.put("summon_cooldown_seconds", "12");
        stored.put("summon_max_alive", "6");
        for (Map.Entry<String, String> e : stored.entrySet()) {
            assertTrue(form.contains("name=\"" + e.getKey() + "\" value=\"" + e.getValue() + "\""),
                    "valeur enregistrée perdue ou remplacée : " + e.getKey() + " = " + e.getValue());
        }
        assertTrue(form.contains("name=\"summon_entity_type\" value=\"HUSK\""), "type de renfort conservé");

        // Un champ vide côté profil le reste : pas de défaut substitué à l'édition.
        assertTrue(form.contains("name=\"creeper_explosion_radius\" value=\"\""),
                "champ absent du profil laissé vide");
        // Et la valeur littérale « null » n'apparaît jamais dans un champ.
        assertFalse(form.contains("value=\"null\""), "aucune valeur « null » affichée");
    }

    @Test
    void theGlobalDrawFormShowsTheServerValuesNotTheDefaults() throws Exception {
        start();
        login();
        runListWithSuccess();
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();

        // Valeurs réelles du serveur (0.05 / 20), pas les défauts du moteur (1 / aucun).
        assertTrue(page.contains("name=\"chance\" \n") || page.contains("value=\"0.05\""),
                "chance globale du serveur affichée");
        assertTrue(page.contains("value=\"20\""), "plafond global du serveur affiché");
        // Le défaut du moteur est documenté sans être imposé au champ.
        assertTrue(page.contains("<b>Défaut</b> 1 — toute apparition éligible est examinée"),
                "défaut du moteur annoncé");
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Le formulaire de création, isolé du reste de la page. */
    private static String createForm(String page) {
        return slice(page, "id=\"mob-new-def\"", "</form>");
    }

    /** Le premier formulaire de modification de profil, isolé du reste de la page. */
    private static String editForm(String page) {
        return slice(page, "id=\"mob-edit-0\"", "</form>");
    }

    private static String slice(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) {
            throw new IllegalStateException("fragment introuvable : " + from);
        }
        int b = s.indexOf(to, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private void runListWithSuccess() throws Exception {
        String token = csrf(get("/mobs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=mob.list&agent=" + TestConfig.AGENT_ID
                + "&return=/mobs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action en file : " + poll.body());
        }
        String actionId = m.group(1);
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":" + MOB_DETAILS + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("aucun jeton CSRF dans la page");
        }
        return m.group(1);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((x, y) -> x + "; " + y).orElse(""));
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : res.headers().allValues("Set-Cookie")) {
            String[] f = sc.split(";", 2);
            int eq = f[0].indexOf('=');
            String n = f[0].substring(0, eq).trim();
            String v = f[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }

}
