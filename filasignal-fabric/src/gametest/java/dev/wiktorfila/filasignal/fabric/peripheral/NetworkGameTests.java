package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Mode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual server sensor → controller → redstone path through two interfaces. */
public final class NetworkGameTests {
    @GameTest(maxTicks = 160)
    public void sensorDrivesAnOutputAcrossTwoInterfaces(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        BlockPos computer = new BlockPos(1, 1, 1);
        BlockPos relay = new BlockPos(2, 1, 1);
        BlockPos output = new BlockPos(3, 1, 1);
        BlockPos sensor = new BlockPos(4, 1, 1);
        BlockPos lamp = output.relative(Direction.SOUTH);
        test.setBlock(computer, BuiltInRegistries.BLOCK.getValue(PeripheralBlocks.id("programmable_computer")));
        test.setBlock(relay, PeripheralBlocks.networkInterface);
        test.setBlock(output, PeripheralBlocks.networkInterface);
        test.setBlock(sensor, PeripheralBlocks.sensor);
        test.setBlock(lamp, Blocks.REDSTONE_LAMP);
        var first = new AtomicReference<ServerPlayer>();
        var second = new AtomicReference<ServerPlayer>();
        test.runBeforeTestEnd(() -> {
            remove(test, first.get());
            remove(test, second.get());
        });
        test.startSequence().thenIdle(6)
                .thenExecute(() -> {
                    var root = runtime.requireDevice(level, test.absolutePos(computer));
                    var router = runtime.requireDevice(level, test.absolutePos(relay));
                    var endpoint = runtime.requireDevice(level, test.absolutePos(output));
                    var trigger = runtime.requireDevice(level, test.absolutePos(sensor));
                    runtime.link(root.id(), router.id(), false);
                    runtime.link(router.id(), endpoint.id(), false);
                    runtime.link(endpoint.id(), trigger.id(), false);
                    runtime.alias(trigger, "entrance");
                    runtime.port(endpoint, Direction.SOUTH, Mode.OUTPUT, "door");
                    runtime.port(router, Direction.NORTH, Mode.INPUT, "test_input");
                    test.setBlock(relay.relative(Direction.NORTH), Blocks.REDSTONE_BLOCK);
                    try {
                        ComputerLifecycle.program(level, test.absolutePos(computer), """
                                global detected = 0
                                on sensor.detected("entrance") {
                                    detected++
                                    available, problem = devices.connected("entrance")
                                    boost, inputProblem = redstone.read("test_input")
                                    if available and problem is nil and inputProblem is nil {
                                        redstone.set("door", boost)
                                    }
                                }
                                on sensor.cleared("entrance") { redstone.set("door", detected - 1) }
                                """, true);
                    } catch (IOException error) {
                        throw test.assertionException(computer, error.getMessage());
                    }
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    first.set(player(test, sensor));
                    second.set(player(test, sensor));
                })
                .thenWaitUntil(() -> test.assertTrue(test.getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Sensor never lit the lamp"))
                .thenExecute(() -> {
                    remove(test, first.get());
                    first.set(null);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    test.assertTrue(test.getBlockState(lamp)
                            .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "First departure cleared detection");
                    remove(test, second.get());
                    second.set(null);
                })
                .thenWaitUntil(() -> test.assertTrue(!test.getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Last departure did not clear detection"))
                .thenExecute(() -> {
                    var node = runtime.requireDevice(level, test.absolutePos(sensor));
                    test.setBlock(relay, Blocks.AIR);
                    test.assertTrue(!runtime.available(node), "Removed relay still routed events");
                }).thenSucceed();
    }

    @GameTest(maxTicks = 100)
    public void risingAndFallingInputsDispatchWithCheckedHostResults(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var computer = new BlockPos(1, 1, 1);
        var router = computer.east();
        var input = router.north();
        var lamp = router.south();
        test.setBlock(computer, BuiltInRegistries.BLOCK.getValue(PeripheralBlocks.id("programmable_computer")));
        test.setBlock(router, PeripheralBlocks.networkInterface);
        test.setBlock(lamp, Blocks.REDSTONE_LAMP);
        test.startSequence().thenIdle(6)
                .thenExecute(() -> {
                    var root = runtime.requireDevice(level, test.absolutePos(computer));
                    var endpoint = runtime.requireDevice(level, test.absolutePos(router));
                    runtime.link(root.id(), endpoint.id(), false);
                    runtime.port(endpoint, Direction.NORTH, Mode.INPUT, "input");
                    runtime.port(endpoint, Direction.SOUTH, Mode.OUTPUT, "lamp");
                    try {
                        ComputerLifecycle.program(level, test.absolutePos(computer), """
                                global changes = 0
                                on redstone.changed("input") { changes++ }
                                on redstone.rising("input") {
                                    absent, missing = redstone.read("missing")
                                    ignored, mode = redstone.set("input", 1)
                                    rejected, range = redstone.set("lamp", 16)
                                    if changes is 1 and not (missing is nil) and not (mode is nil) and not (range is nil) {
                                        redstone.set("lamp", event.payload)
                                    }
                                }
                                on redstone.falling("input") {
                                    if changes is 2 { redstone.set("lamp", event.payload) }
                                }
                                """, true);
                    } catch (IOException error) {
                        throw test.assertionException(computer, error.getMessage());
                    }
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    test.setBlock(input, Blocks.REDSTONE_BLOCK);
                    test.setBlock(input, Blocks.AIR);
                })
                .thenWaitUntil(() -> test.assertTrue(test.getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Input rise did not light lamp"))
                .thenWaitUntil(() -> test.assertTrue(!test.getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Input fall did not clear lamp"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void linkToolAndProgramCommandRejectNonOperators(GameTestHelper test) {
        var level = test.getLevel();
        var pos = new BlockPos(1, 1, 1);
        test.setBlock(pos, BuiltInRegistries.BLOCK.getValue(PeripheralBlocks.id("programmable_computer")));
        test.runAfterDelay(5, () -> {
            var player = test.makeMockServerPlayerInLevel();
            var tool = new ItemStack(BuiltInRegistries.ITEM.getValue(PeripheralBlocks.id("linking_tool")));
            var at = test.absolutePos(pos);
            var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
            try {
                var result = tool.getItem().useOn(new net.minecraft.world.item.context.UseOnContext(level, player,
                        net.minecraft.world.InteractionHand.MAIN_HAND, tool, hit));
                test.assertTrue(result == net.minecraft.world.InteractionResult.FAIL, "Non-operator tool use succeeded");
                try {
                    level.getServer().getCommands().getDispatcher().execute(
                            "filasignal program-reset " + at.getX() + " " + at.getY() + " " + at.getZ() + " on startup {}",
                            player.createCommandSourceStack().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS));
                    test.fail("Unauthorized program command succeeded");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                    // An inaccessible command is rejected by Brigadier before any edit reaches the runtime.
                }
            } finally {
                remove(test, player);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void operatorToolLinksAndUnlinksLiveDevices(GameTestHelper test) {
        var level = test.getLevel();
        var root = new BlockPos(1, 1, 1);
        var interfacePos = root.east();
        test.setBlock(root, BuiltInRegistries.BLOCK.getValue(PeripheralBlocks.id("programmable_computer")));
        test.setBlock(interfacePos, PeripheralBlocks.networkInterface);
        test.runAfterDelay(5, () -> {
            var player = test.makeMockServerPlayerInLevel();
            var players = level.getServer().getPlayerList();
            var profile = new net.minecraft.server.players.NameAndId(player.getGameProfile());
            try {
                players.op(profile, java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER),
                        java.util.Optional.empty());
                var tool = new ItemStack(BuiltInRegistries.ITEM.getValue(PeripheralBlocks.id("linking_tool")));
                var runtime = PeripheralLifecycle.runtime(level.getServer());
                var receiver = runtime.requireDevice(level, test.absolutePos(interfacePos));
                click(test, player, tool, root);
                click(test, player, tool, interfacePos);
                test.assertTrue(runtime.available(runtime.directory().node(receiver.id())), "Tool did not link devices");
                click(test, player, tool, root);
                player.setShiftKeyDown(true);
                click(test, player, tool, interfacePos);
                test.assertTrue(!runtime.available(runtime.directory().node(receiver.id())), "Tool did not unlink devices");
            } finally {
                players.deop(profile);
                remove(test, player);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void nonOperatorPlacementAndTrustPermitConfiguration(GameTestHelper test) {
        var level = test.getLevel();
        var computer = new BlockPos(1, 1, 1);
        var router = computer.east();
        var rootBlock = BuiltInRegistries.BLOCK.getValue(PeripheralBlocks.id("programmable_computer"));
        test.setBlock(computer, rootBlock);
        test.setBlock(router, PeripheralBlocks.networkInterface);
        var owner = player(test, computer);
        var guest = player(test, router);
        test.runBeforeTestEnd(() -> {
            remove(test, owner);
            remove(test, guest);
        });
        var rootPos = test.absolutePos(computer);
        var routerPos = test.absolutePos(router);
        rootBlock.setPlacedBy(level, rootPos, level.getBlockState(rootPos), owner, ItemStack.EMPTY);
        PeripheralBlocks.networkInterface.setPlacedBy(level, routerPos, level.getBlockState(routerPos), owner, ItemStack.EMPTY);
        var tool = new ItemStack(BuiltInRegistries.ITEM.getValue(PeripheralBlocks.id("linking_tool")));
        click(test, owner, tool, computer);
        click(test, owner, tool, router);

        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var root = runtime.requireDevice(level, rootPos);
        var endpoint = runtime.requireDevice(level, routerPos);
        test.assertTrue(runtime.available(endpoint), "An ordinary owner could not link the interface");
        test.assertTrue(!NetworkPermission.allows(runtime.directory(), endpoint, NetworkPermission.Actor.from(guest), false),
                "A stranger could configure a linked endpoint");
        run(test, owner, "filasignal trust " + coordinates(rootPos) + " " + guest.getUUID());
        run(test, guest, "filasignal alias " + coordinates(routerPos) + " trusted_router");
        var book = new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(java.util.List.of(
                        net.minecraft.server.network.Filterable.passThrough("on startup {}"))));
        guest.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, book);
        run(test, guest, "filasignal install " + coordinates(rootPos));
        test.assertTrue("trusted_router".equals(runtime.directory().node(endpoint.id()).alias()), "Trusted edit did not apply");
        run(test, owner, "filasignal untrust " + coordinates(rootPos) + " " + guest.getUUID());
        test.assertTrue(!NetworkPermission.allows(runtime.directory(), endpoint, NetworkPermission.Actor.from(guest), false)
                && runtime.directory().access(root.id()).owner().equals(owner.getUUID()), "Revoking trust changed ownership");
        test.succeed();
    }

    static void run(GameTestHelper test, ServerPlayer player, String command) {
        try {
            int result = test.getLevel().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
            test.assertTrue(result == 1, "Command failed: " + command);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static void click(GameTestHelper test, ServerPlayer player, ItemStack tool, BlockPos position) {
        var at = test.absolutePos(position);
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        var context = new net.minecraft.world.item.context.UseOnContext(test.getLevel(), player,
                net.minecraft.world.InteractionHand.MAIN_HAND, tool, hit);
        test.assertTrue(tool.getItem().useOn(context) == net.minecraft.world.InteractionResult.SUCCESS,
                "Operator tool click failed at " + at);
    }

    @GameTest(maxTicks = 900)
    public void networkRecordSurvivesWorldFlushAndDeviceChunkReload(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var runtime = PeripheralLifecycle.runtime(server);
        var remote = test.absolutePos(new BlockPos(896, 1, 896));
        int x = remote.getX() >> 4;
        int z = remote.getZ() >> 4;
        var computer = new BlockPos((x << 4) + 6, remote.getY(), (z << 4) + 8);
        var endpoint = computer.east();
        var id = new AtomicReference<java.util.UUID>();
        level.setChunkForced(x, z, true);
        boolean leaveFixture = "1".equals(System.getenv("FILASIGNAL_RESTART_FIXTURE"));
        test.runBeforeTestEnd(() -> { if (!leaveFixture) level.setChunkForced(x, z, false); });
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(LoadedChunks.contains(level, computer), "Device chunk still loading"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(computer, BuiltInRegistries.BLOCK.getValue(
                            PeripheralBlocks.id("programmable_computer")).defaultBlockState());
                    level.setBlockAndUpdate(endpoint, PeripheralBlocks.networkInterface.defaultBlockState());
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    var root = runtime.requireDevice(level, computer);
                    var router = runtime.requireDevice(level, endpoint);
                    id.set(router.id());
                    runtime.link(root.id(), router.id(), false);
                    runtime.alias(router, "saved_router");
                    runtime.port(router, Direction.WEST, Mode.INPUT, "saved_input");
                    server.saveEverything(true, true, true);
                    var data = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("dimensions/minecraft/overworld/data");
                    try (var disk = new net.minecraft.world.level.storage.SavedDataStorage(data,
                            net.minecraft.util.datafix.DataFixers.getDataFixer(), level.registryAccess())) {
                        var persisted = disk.get(NetworkSave.TYPE);
                        test.assertTrue(persisted != null && persisted.enabled(), "Disk topology did not decode");
                        var copy = persisted.directory();
                        test.assertTrue("saved_router".equals(copy.node(router.id()).alias())
                                && copy.port(root.id(), "saved_input") != null
                                && !copy.reachable(copy.node(router.id())), "Disk topology or offline baseline was incorrect");
                    }
                    level.setChunkForced(x, z, false);
                })
                .thenWaitUntil(() -> test.assertTrue(!LoadedChunks.contains(level, endpoint)
                        && runtime.live(id.get()) == null, "Saved device chunk did not unload"))
                .thenExecute(() -> {
                    test.assertTrue(runtime.directory().node(id.get()) != null, "Unload deleted the known device");
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    level.setChunkForced(x, z, true);
                })
                .thenWaitUntil(() -> test.assertTrue(runtime.available(runtime.directory().node(id.get())),
                        "Saved network did not become reachable"))
                .thenExecute(() -> {
                    var restored = runtime.requireDevice(level, endpoint);
                    test.assertTrue(restored.id().equals(id.get()) && "saved_router".equals(restored.alias())
                            && runtime.available(restored), "Reload changed identity, alias or reachability");
                    if (leaveFixture) {
                        leaveRestartFixture(test, runtime, computer, endpoint);
                    } else {
                        level.setBlockAndUpdate(endpoint, Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(computer, Blocks.AIR.defaultBlockState());
                    }
                }).thenIdle(leaveFixture ? 30 : 0).thenSucceed();
    }

    private static void leaveRestartFixture(GameTestHelper test, PeripheralRuntime runtime, BlockPos computer, BlockPos relay) {
        var level = test.getLevel();
        var endpoint = relay.east();
        var sensor = endpoint.east();
        var barrel = endpoint.north();
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        ((net.minecraft.world.Container) level.getBlockEntity(barrel)).setItem(0,
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 5));
        level.setBlockAndUpdate(endpoint, PeripheralBlocks.networkInterface.defaultBlockState());
        level.setBlockAndUpdate(sensor, PeripheralBlocks.sensor.defaultBlockState());
        test.runAfterDelay(10, () -> {
            var root = runtime.requireDevice(level, computer);
            var first = runtime.requireDevice(level, relay);
            var last = runtime.requireDevice(level, endpoint);
            var detector = runtime.requireDevice(level, sensor);
            runtime.link(first.id(), last.id(), false);
            runtime.link(last.id(), detector.id(), false);
            runtime.alias(detector, "restart_sensor");
            runtime.port(last, Direction.SOUTH, Mode.OUTPUT, "restart_port");
            runtime.access(root.id(), new NetworkAccess(root.id(), java.util.Set.of(first.id())));
            runtime.inventory(runtime.directory().node(last.id()), Direction.NORTH, "restart_stock");
            try {
                ComputerLifecycle.program(level, computer, """
                        global boots = 0
                        on startup {
                            boots++
                            found, error = devices.connected("restart_sensor")
                            power, problem = redstone.read("restart_port")
                            stock, stockError = inventory.from("restart_stock")
                            count = nil
                            if stockError is nil { count, readError = stock.count("minecraft:iron_ingot") }
                            marker, encodeError = json.stringify(["restart-fixture", boots, found, power, count])
                            print(marker)
                            redstone.set("restart_port", 15)
                        }
                        on sensor.detected("restart_sensor") { print("restart-detected") }
                        """, true);
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
            org.slf4j.LoggerFactory.getLogger("filasignal/restart-fixture").info(
                    "Fixture positions {} {} {} {} network {} ids {} {} {}", computer, relay, endpoint, sensor,
                    root.id(), first.id(), last.id(), detector.id());
        });
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos pos) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(Vec3.atCenterOf(test.absolutePos(pos)).add(0, 1, 0));
        return player;
    }

    private static void remove(GameTestHelper test, ServerPlayer player) {
        if (player != null && !player.isRemoved()) {
            test.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }
}
