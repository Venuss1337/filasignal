package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Mode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;

public final class InventoryGameTests {
    @GameTest(maxTicks = 120)
    public void sensorMovesComponentVariantAndPowersLampThroughHandle(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var root = new BlockPos(1, 1, 1);
        var relay = root.east();
        var router = relay.east();
        var sensor = router.east();
        var input = router.north();
        var output = router.above();
        var lamp = router.south();
        test.setBlock(root, ComputerBlocks.block());
        test.setBlock(relay, PeripheralBlocks.networkInterface);
        test.setBlock(router, PeripheralBlocks.networkInterface);
        test.setBlock(sensor, PeripheralBlocks.sensor);
        test.setBlock(input, Blocks.BARREL);
        test.setBlock(output, Blocks.BARREL);
        test.setBlock(lamp, Blocks.REDSTONE_LAMP);
        var source = container(test, input);
        var destination = container(test, output);
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 12);
        iron.set(DataComponents.CUSTOM_NAME, Component.literal("Marked ingot"));
        source.setItem(0, iron);
        destination.setItem(0, iron.copyWithCount(60));
        for (int i = 1; i < destination.getContainerSize(); i++) {
            destination.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        var owner = test.makeMockServerPlayerInLevel();
        owner.setNoGravity(true);
        owner.snapTo(Vec3.atCenterOf(test.absolutePos(sensor)).add(20, 1, 0));
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED));
        place(test, owner, root);
        place(test, owner, relay);
        place(test, owner, router);
        place(test, owner, sensor);
        var tool = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(PeripheralBlocks.id("linking_tool")));
        link(test, owner, tool, root, relay);
        link(test, owner, tool, relay, router);
        link(test, owner, tool, router, sensor);
        var endpoint = runtime.requireDevice(level, test.absolutePos(router));
        runtime.alias(runtime.requireDevice(level, test.absolutePos(sensor)), "entrance");
        runtime.port(endpoint, Direction.SOUTH, Mode.OUTPUT, "lamp");
        runtime.alias(endpoint, "router");
        runtime.inventory(endpoint, Direction.NORTH, "storage");
        runtime.inventory(endpoint, Direction.UP, "output");
        NetworkGameTests.run(test, owner, "filasignal program " + NetworkGameTests.coordinates(test.absolutePos(root)) + " "
                + """
                on sensor.detected("entrance") {
                    storage, err = inventory.from("storage")
                    output, outError = inventory.from("output")
                    door, doorError = redstone.from("lamp")
                    router, deviceError = devices.from("router")
                    if deviceError is not nil { return }
                    if not router.connected or router.position.dimension is not "minecraft:overworld" { return }
                    if err is not nil or outError is not nil or doorError is not nil { return }
                    total, countError = storage.count("minecraft:iron_ingot")
                    item, slotError = storage.getSlot(0)
                    empty, emptyError = storage.getSlot(1)
                    slots, sizeError = storage.size()
                    full, fullnessError = output.fullness()
                    sourceEmpty, sourceError = storage.isEmpty()
                    invalid, amountError = storage.moveTo(output, "minecraft:iron_ingot", 0)
                    if countError is not nil or slotError is not nil or emptyError is not nil or sizeError is not nil { return }
                    if fullnessError is not nil or sourceError is not nil or amountError is nil { return }
                    if total is 12 and item.id is "minecraft:iron_ingot" and item.count is 12 and
                        empty is nil and slots is 27 and not sourceEmpty and full < 1 {
                        moved, moveError = storage.moveTo(output, "minecraft:iron_ingot", 16)
                        blocked, blockedError = storage.moveTo(output, "minecraft:iron_ingot", 16)
                        if moved is 4 and moveError is nil and blocked is 0 and blockedError is nil { door.set(15) }
                    }
                }
                """);
        test.startSequence().thenIdle(6)
                .thenExecute(() -> owner.snapTo(Vec3.atCenterOf(test.absolutePos(sensor)).add(0, 1, 0)))
                .thenWaitUntil(() -> test.assertTrue(test.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                        "Sensor handler did not complete the partial transfer and drive the lamp"))
                .thenExecute(() -> {
                    test.assertTrue(source.getItem(0).getCount() == 8 && destination.getItem(0).getCount() == 64,
                            "Transfer lost or duplicated items");
                    test.assertTrue(Component.literal("Marked ingot").equals(destination.getItem(0).get(DataComponents.CUSTOM_NAME)),
                            "Transfer lost the item component");
                    var binding = runtime.directory().inventories().byAlias(endpoint.network(), "output");
                    runtime.inventory(endpoint, Direction.UP, "renamed_output");
                    var old = runtime.directory().inventories().byAlias(endpoint.network(), "renamed_output");
                    test.assertTrue(binding.id().equals(old.id()), "Renaming changed an inventory's handle identity");
                    var pos = test.absolutePos(output);
                    var ownerId = owner.getUUID();
                    InventoryProtection.CHECK.register((world, target, id, write) ->
                            !write || !ownerId.equals(id) || !pos.equals(target));
                    var inventory = new InventoryService(runtime);
                    test.assertTrue(inventory.open(endpoint.network(), old, false).slots() == 27, "Read was incorrectly denied");
                    try {
                        inventory.open(endpoint.network(), old, true);
                        test.fail("Existing grant ignored a protection change");
                    } catch (HostFailure rejection) {
                        test.assertTrue(rejection.code().equals("host.denied"), "Permission veto returned the wrong error");
                    }
                    test.setBlock(output, Blocks.AIR);
                    test.setBlock(output, Blocks.BARREL);
                    try {
                        new InventoryService(runtime).open(endpoint.network(), old, false);
                        test.fail("Replaced barrel inherited the inventory grant");
                    } catch (HostFailure rejection) {
                        test.assertTrue(rejection.code().equals("inventory.stale"), "Replacement returned the wrong error");
                    }
                    test.setBlock(relay, Blocks.AIR);
                    test.assertTrue(!runtime.available(endpoint), "Removing a relay left endpoint reachable");
                }).thenSucceed();
    }

    private static Container container(GameTestHelper test, BlockPos pos) {
        return (Container) test.getLevel().getBlockEntity(test.absolutePos(pos));
    }

    private static void place(GameTestHelper test, ServerPlayer owner, BlockPos relative) {
        var level = test.getLevel();
        var pos = test.absolutePos(relative);
        var state = level.getBlockState(pos);
        state.getBlock().setPlacedBy(level, pos, state, owner, ItemStack.EMPTY);
    }

    private static void link(GameTestHelper test, ServerPlayer owner, ItemStack tool, BlockPos first, BlockPos second) {
        NetworkGameTests.click(test, owner, tool, first);
        NetworkGameTests.click(test, owner, tool, second);
    }
}
