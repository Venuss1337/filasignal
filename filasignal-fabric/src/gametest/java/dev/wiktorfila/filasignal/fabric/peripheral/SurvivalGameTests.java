package dev.wiktorfila.filasignal.fabric.peripheral;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.computer.ProgramHost;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Survival play: recipes, mining and drops, complete block and item assets, and the visual block states. */
public final class SurvivalGameTests {
    private static final String NAMESPACE = "filasignal";

    private static List<Block> blocks() {
        return BuiltInRegistries.BLOCK.keySet().stream().filter(id -> id.getNamespace().equals(NAMESPACE)).sorted()
                .map(BuiltInRegistries.BLOCK::getValue).toList();
    }

    private static List<Item> items() {
        return BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals(NAMESPACE)).sorted()
                .map(BuiltInRegistries.ITEM::getValue).toList();
    }

    private static JsonObject json(GameTestHelper test, String path) {
        try (InputStream in = SurvivalGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw test.assertionException("Missing resource " + path);
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) {
            throw test.assertionException(path + ": " + error.getMessage());
        }
    }

    private static boolean exists(String path) {
        return SurvivalGameTests.class.getResource(path) != null;
    }

    /** Every recipe file crafts its result from exactly its own ingredients, and every item has a recipe. */
    @GameTest(maxTicks = 20)
    public void everyItemIsCraftableFromItsRecipe(GameTestHelper test) {
        var level = test.getLevel();
        var manager = level.getServer().getRecipeManager();
        Set<Item> crafted = new HashSet<>();
        for (Item item : items()) {
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            JsonObject recipe = json(test, "/data/filasignal/recipe/" + name + ".json");
            var grid = new ArrayList<ItemStack>();
            int width = 3;
            int height = 3;
            if (recipe.has("pattern")) {
                var pattern = recipe.getAsJsonArray("pattern");
                height = pattern.size();
                width = pattern.get(0).getAsString().length();
                for (var row : pattern) {
                    for (char symbol : row.getAsString().toCharArray()) {
                        grid.add(symbol == ' ' ? ItemStack.EMPTY
                                : ingredient(recipe.getAsJsonObject("key").get(String.valueOf(symbol)).getAsString()));
                    }
                }
            } else {
                for (var ingredient : recipe.getAsJsonArray("ingredients")) {
                    grid.add(ingredient(ingredient.getAsString()));
                }
                while (grid.size() < 9) {
                    grid.add(ItemStack.EMPTY);
                }
            }
            var input = CraftingInput.of(width, height, grid);
            var found = manager.getRecipeFor(RecipeType.CRAFTING, input, level)
                    .orElseThrow(() -> test.assertionException("No recipe matches the ingredients of " + name));
            test.assertTrue(found.id().identifier().equals(Identifier.fromNamespaceAndPath(NAMESPACE, name)),
                    name + " ingredients matched " + found.id().identifier());
            ItemStack result = found.value().assemble(input);
            int count = recipe.getAsJsonObject("result").has("count") ? recipe.getAsJsonObject("result").get("count").getAsInt() : 1;
            test.assertTrue(result.is(item) && result.getCount() == count, name + " crafted " + result);
            test.assertTrue(exists("/data/filasignal/advancement/recipes/redstone/" + name + ".json"),
                    name + " has no recipe book unlock");
            crafted.add(item);
        }
        test.assertTrue(crafted.size() == items().size() && crafted.size() >= 17, "Items without recipes");
        test.succeed();
    }

    private static ItemStack ingredient(String id) {
        // A tag ingredient is tried with one member, such as any button.
        Identifier item = id.equals("#minecraft:buttons") ? Identifier.withDefaultNamespace("stone_button")
                : Identifier.parse(id);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(item));
    }

    /** A pickaxe mines every block in survival and the block drops itself. */
    @GameTest(maxTicks = 20)
    public void pickaxesMineEveryBlockAndBlocksDropThemselves(GameTestHelper test) {
        var level = test.getLevel();
        var pickaxe = new ItemStack(Items.STONE_PICKAXE);
        var position = test.absolutePos(new BlockPos(1, 1, 1));
        for (Block block : blocks()) {
            BlockState state = block.defaultBlockState();
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            test.assertTrue(pickaxe.isCorrectToolForDrops(state), name + " cannot be mined with a pickaxe");
            test.assertTrue(state.getDestroySpeed(level, position) > 0, name + " is unbreakable");
            var drops = Block.getDrops(state, level, position, null);
            test.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()), name + " drops " + drops);
        }
        test.succeed();
    }

    /** Every state of every block has exactly one blockstate variant whose models and textures exist. */
    @GameTest(maxTicks = 20)
    public void everyBlockStateAndItemHasAssets(GameTestHelper test) {
        for (Block block : blocks()) {
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            var variants = json(test, "/assets/filasignal/blockstates/" + name + ".json").getAsJsonObject("variants");
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                var matches = variants.keySet().stream().filter(key -> matches(state, key)).toList();
                test.assertTrue(matches.size() == 1, name + " state " + state + " matches " + matches);
                model(test, variants.getAsJsonObject(matches.getFirst()).get("model").getAsString());
            }
        }
        for (Item item : items()) {
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            var definition = json(test, "/assets/filasignal/items/" + name + ".json").getAsJsonObject("model");
            model(test, definition.get("model").getAsString());
        }
        test.succeed();
    }

    private static boolean matches(BlockState state, String key) {
        if (key.isEmpty()) {
            return state.getProperties().isEmpty();
        }
        for (String pair : key.split(",")) {
            String[] parts = pair.split("=");
            Property<?> property = state.getBlock().getStateDefinition().getProperty(parts[0]);
            if (property == null || !name(state, property).equals(parts[1])) {
                return false;
            }
        }
        return key.split(",").length == state.getProperties().size();
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Checks a FilaSignal model, its parents and its textures. Vanilla parents are part of the game. */
    private static void model(GameTestHelper test, String reference) {
        var id = Identifier.parse(reference);
        if (!id.getNamespace().equals(NAMESPACE)) {
            return;
        }
        var model = json(test, "/assets/filasignal/models/" + id.getPath() + ".json");
        if (model.has("parent")) {
            model(test, model.get("parent").getAsString());
        }
        if (model.has("textures")) {
            for (var entry : model.getAsJsonObject("textures").entrySet()) {
                String value = entry.getValue().getAsString();
                if (!value.startsWith("#")) {
                    var texture = Identifier.parse(value);
                    test.assertTrue(!texture.getNamespace().equals(NAMESPACE)
                                    || exists("/assets/filasignal/textures/" + texture.getPath() + ".png"),
                            reference + " uses a missing texture " + value);
                }
            }
        }
    }

    /** The computer screen shows running, stopped and failed programs; the Access Controller shows when open. */
    @GameTest(maxTicks = 120)
    public void blockStatesShowProgramAndAccessState(GameTestHelper test) {
        var computerPos = new BlockPos(1, 1, 1);
        var accessPos = new BlockPos(3, 1, 1);
        test.setBlock(computerPos, ComputerBlocks.block());
        test.setBlock(accessPos, PeripheralBlocks.block(Kind.ACCESS_CONTROLLER));
        var level = test.getLevel();
        var host = (ProgramHost) level.getBlockEntity(test.absolutePos(computerPos));
        test.startSequence()
                .thenWaitUntil(() -> status(test, computerPos, "running"))
                .thenExecute(() -> {
                    try {
                        ComputerLifecycle.control(level, host, false, "test");
                    } catch (IOException error) {
                        throw test.assertionException(error.getMessage());
                    }
                })
                .thenWaitUntil(() -> status(test, computerPos, "off"))
                .thenExecute(() -> {
                    try {
                        ComputerLifecycle.program(level, host, "on startup {\n    x = 1 / 0\n}\n", true, true);
                        ComputerLifecycle.control(level, host, true, "test");
                    } catch (IOException error) {
                        throw test.assertionException(error.getMessage());
                    }
                })
                .thenWaitUntil(() -> status(test, computerPos, "error"))
                .thenExecute(() -> {
                    var controller = test.getBlockEntity(accessPos, PeripheralEntity.class);
                    var runtime = PeripheralLifecycle.runtime(level.getServer());
                    runtime.pulses().open(controller, level, level.getGameTime() + 40);
                    test.assertTrue(test.getBlockState(accessPos).getValue(AccessControllerBlock.OPEN), "Not shown open");
                    runtime.pulses().close(controller, level);
                    test.assertTrue(!test.getBlockState(accessPos).getValue(AccessControllerBlock.OPEN), "Still shown open");
                    // Clients receive a display's text and nothing from other devices.
                    var registries = level.registryAccess();
                    test.assertTrue(controller.getUpdateTag(registries).isEmpty(), "Access data sent to clients");
                })
                .thenSucceed();
    }

    private static void status(GameTestHelper test, BlockPos relative, String expected) {
        BlockState state = test.getBlockState(relative);
        Property<?> status = state.getBlock().getStateDefinition().getProperty("status");
        if (status == null || !name(state, status).equals(expected)) {
            throw test.assertionException("Computer screen is " + (status == null ? "missing" : name(state, status))
                    + ", expected " + expected);
        }
    }
}
