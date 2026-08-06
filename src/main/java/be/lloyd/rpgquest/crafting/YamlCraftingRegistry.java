package be.lloyd.rpgquest.crafting;

import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.crafting.model.CustomItemIngredient;
import be.lloyd.rpgquest.crafting.model.CustomItemResult;
import be.lloyd.rpgquest.crafting.model.RecipeDefinition;
import be.lloyd.rpgquest.crafting.model.RecipeIngredient;
import be.lloyd.rpgquest.crafting.model.RecipeIngredientEntry;
import be.lloyd.rpgquest.crafting.model.RecipeResult;
import be.lloyd.rpgquest.crafting.model.ShapedRecipeDefinition;
import be.lloyd.rpgquest.crafting.model.ShapelessRecipeDefinition;
import be.lloyd.rpgquest.crafting.model.VanillaIngredient;
import be.lloyd.rpgquest.crafting.model.VanillaResult;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.slf4j.Logger;

/**
 * Charge les recettes personnalisées depuis {@code plugins/RPGQuest/recipes/}
 * et les enregistre auprès du serveur (recettes Bukkit réelles, visibles dans
 * le livre de recettes vanilla). Même conception que {@code
 * YamlCustomItemRegistry}/{@code YamlQuestEngine} : ne dépend que de {@link
 * Path}/{@link Logger} au-delà de {@link YamlCustomItemRegistry} (nécessaire
 * pour résoudre/valider les ingrédients et résultats personnalisés).
 *
 * <p><b>Substituts vanilla empêchés à la source</b> : un ingrédient
 * personnalisé est représenté par un {@link RecipeChoice.ExactChoice} sur
 * l'{@link ItemStack} canonique produit par {@code
 * YamlCustomItemRegistry#create} (donc avec son PersistentDataContainer) —
 * un objet vanilla qui imite juste le nom/lore/matériau ne correspond jamais
 * à ce choix. {@code RecipeCraftGuardListener} ajoute une vérification en
 * profondeur au moment de la préparation, en défense supplémentaire.</p>
 */
public final class YamlCraftingRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {
            "forest_blade_recipe.yml", "refined_crystal_recipe.yml", "miner_pickaxe_recipe.yml"
    };

    private final Path recipesDirectory;
    private final YamlCustomItemRegistry itemRegistry;
    private final Logger logger;
    private final RecipeLoader loader = new RecipeLoader();

    private volatile List<RecipeDefinition> recipes = List.of();
    private volatile RecipeLoadReport lastReport = new RecipeLoadReport(List.of(), List.of());

    public YamlCraftingRegistry(Path recipesDirectory, YamlCustomItemRegistry itemRegistry, Logger logger) {
        this.recipesDirectory = recipesDirectory;
        this.itemRegistry = itemRegistry;
        this.logger = logger;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        unregisterAll();
    }

    /** Recharge depuis le disque, désenregistre les anciennes recettes Bukkit et enregistre les nouvelles. */
    public RecipeLoadReport reload() {
        unregisterAll();

        RecipeLoadReport structural = loader.loadDirectory(recipesDirectory);
        List<RecipeLoadIssue> issues = new ArrayList<>(structural.issues());
        List<RecipeDefinition> registered = new ArrayList<>();

        for (RecipeDefinition definition : structural.loaded()) {
            Optional<org.bukkit.inventory.Recipe> bukkitRecipe = buildBukkitRecipe(definition, issues);
            if (bukkitRecipe.isPresent()) {
                Bukkit.addRecipe(bukkitRecipe.get());
                registered.add(definition);
            }
        }

        this.recipes = registered;
        this.lastReport = new RecipeLoadReport(registered, issues);
        logReport("Chargement", lastReport);
        return lastReport;
    }

    /** Charge et valide (structure + références aux objets personnalisés) sans toucher aux recettes enregistrées. */
    public RecipeLoadReport validate() {
        RecipeLoadReport structural = loader.loadDirectory(recipesDirectory);
        List<RecipeLoadIssue> issues = new ArrayList<>(structural.issues());
        List<RecipeDefinition> valid = new ArrayList<>();
        for (RecipeDefinition definition : structural.loaded()) {
            if (buildBukkitRecipe(definition, issues).isPresent()) {
                valid.add(definition);
            }
        }
        RecipeLoadReport report = new RecipeLoadReport(valid, issues);
        logReport("Validation", report);
        return report;
    }

    public List<RecipeDefinition> recipes() {
        return recipes;
    }

    public RecipeLoadReport lastReport() {
        return lastReport;
    }

    public Optional<RecipeDefinition> find(NamespacedKey id) {
        return recipes.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    private void unregisterAll() {
        for (RecipeDefinition definition : recipes) {
            Bukkit.removeRecipe(definition.id());
        }
    }

    private Optional<org.bukkit.inventory.Recipe> buildBukkitRecipe(RecipeDefinition definition, List<RecipeLoadIssue> issues) {
        Optional<ItemStack> result = resolveResult(definition.result(), issues, definition.id());
        if (result.isEmpty()) {
            return Optional.empty();
        }

        return switch (definition) {
            case ShapedRecipeDefinition shaped -> buildShaped(shaped, result.get(), issues);
            case ShapelessRecipeDefinition shapeless -> buildShapeless(shapeless, result.get(), issues);
        };
    }

    private Optional<org.bukkit.inventory.Recipe> buildShaped(
            ShapedRecipeDefinition definition, ItemStack result, List<RecipeLoadIssue> issues) {
        ShapedRecipe recipe = new ShapedRecipe(definition.id(), result);
        recipe.shape(definition.pattern().toArray(new String[0]));

        boolean ok = true;
        for (var entry : definition.key().entrySet()) {
            Optional<RecipeChoice> choice = resolveChoice(entry.getValue(), issues, definition.id());
            if (choice.isEmpty()) {
                ok = false;
                continue;
            }
            recipe.setIngredient(entry.getKey(), choice.get());
        }
        return ok ? Optional.of(recipe) : Optional.empty();
    }

    private Optional<org.bukkit.inventory.Recipe> buildShapeless(
            ShapelessRecipeDefinition definition, ItemStack result, List<RecipeLoadIssue> issues) {
        ShapelessRecipe recipe = new ShapelessRecipe(definition.id(), result);

        boolean ok = true;
        for (RecipeIngredientEntry entry : definition.ingredients()) {
            Optional<RecipeChoice> choice = resolveChoice(entry.ingredient(), issues, definition.id());
            if (choice.isEmpty()) {
                ok = false;
                continue;
            }
            // ShapelessRecipe n'expose pas d'overload (int, RecipeChoice) : une entrée avec une
            // quantité > 1 s'ajoute en répétant l'ingrédient, comme le ferait un appel addIngredient(n, ...).
            for (int i = 0; i < entry.amount(); i++) {
                recipe.addIngredient(choice.get());
            }
        }
        return ok ? Optional.of(recipe) : Optional.empty();
    }

    private Optional<RecipeChoice> resolveChoice(RecipeIngredient ingredient, List<RecipeLoadIssue> issues, NamespacedKey recipeId) {
        return switch (ingredient) {
            case VanillaIngredient vanilla -> Optional.of(new RecipeChoice.MaterialChoice(vanilla.material()));
            case CustomItemIngredient custom -> {
                Optional<ItemStack> stack = itemRegistry.create(custom.itemId(), 1);
                if (stack.isEmpty()) {
                    issues.add(new RecipeLoadIssue(recipeId.toString(),
                            "ingrédient personnalisé inconnu « " + custom.itemId() + " »."));
                    yield Optional.empty();
                }
                yield Optional.of(new RecipeChoice.ExactChoice(stack.get()));
            }
        };
    }

    private Optional<ItemStack> resolveResult(RecipeResult result, List<RecipeLoadIssue> issues, NamespacedKey recipeId) {
        return switch (result) {
            case VanillaResult vanilla -> Optional.of(new ItemStack(vanilla.material(), vanilla.amount()));
            case CustomItemResult custom -> {
                Optional<ItemStack> stack = itemRegistry.create(custom.itemId(), custom.amount());
                if (stack.isEmpty()) {
                    issues.add(new RecipeLoadIssue(recipeId.toString(),
                            "résultat personnalisé inconnu ou quantité invalide « " + custom.itemId() + " »."));
                }
                yield stack;
            }
        };
    }

    private void logReport(String action, RecipeLoadReport report) {
        logger.info("{} des recettes personnalisées : {} chargée(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (RecipeLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(recipesDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de recettes {}.", recipesDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = recipesDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlCraftingRegistry.class.getResourceAsStream("/recipes/" + example)) {
                if (in == null) {
                    logger.warn("Recette d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer la recette d'exemple {}.", example, e);
            }
        }
    }
}
