package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.drive.DriveStore;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Drive installation, authority, isolation, storage limits and lifecycle of autonomous devices. */
public final class AutonomousDeviceGameTests {
    private static final HostRegistry.Context CONTEXT = new HostRegistry.Context() {
        @Override public void charge(long operations) { }
        @Override public void log(String line) { }
        @Override public void print(Value value) { }
    };

    @GameTest(maxTicks = 80)
    public void drivesAreAuthorizedIsolatedAndDroppedOnce(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(2, 1, 1);
        var detector = new BlockPos(1, 1, 5);
        var spare = new BlockPos(3, 1, 5);
        var module = new BlockPos(5, 1, 3);
        test.setBlock(computer, dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks.block());
        test.setBlock(repeater, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(detector, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(spare, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(module, PeripheralBlocks.block(Kind.INVENTORY_MODULE));
        test.setBlock(module.north(), Blocks.BARREL);
        test.setBlock(module.south(), Blocks.BARREL);
        ((Container) level.getBlockEntity(test.absolutePos(module.north()))).setItem(0, new ItemStack(Items.IRON_INGOT, 7));
        ((Container) level.getBlockEntity(test.absolutePos(module.south()))).setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        var owner = player(test, new BlockPos(3, 1, 3));
        var guest = player(test, new BlockPos(3, 1, 3));
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(guest, Entity.RemovalReason.DISCARDED);
        });
        test.startSequence().thenIdle(2).thenExecute(() -> {
            for (var pos : List.of(computer, repeater, detector, spare, module)) {
                var at = test.absolutePos(pos);
                level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
            }
            var gate = (PeripheralEntity) level.getBlockEntity(test.absolutePos(detector));
            var other = (PeripheralEntity) level.getBlockEntity(test.absolutePos(spare));
            var store = (PeripheralEntity) level.getBlockEntity(test.absolutePos(module));

            // Only players with access to the device may install a drive.
            var blank = new ItemStack(Drives.DRIVE);
            test.assertFalse(use(test, guest, blank, detector), "A stranger installed a drive");
            test.assertTrue(blank.getCount() == 1 && gate.drive().isEmpty(), "A refused drive was consumed");
            test.assertTrue(use(test, owner, blank, detector), "The owner could not install a drive");
            test.assertTrue(gate.program().unavailable() == null && !gate.program().enabled(), "A blank drive is passive");
            test.assertFalse(ComputerLifecycle.active(level.getServer(), gate), "A blank drive started");
            try {
                ComputerLifecycle.control(level, gate, true, "test");
                test.fail("A drive without a program started");
            } catch (IllegalArgumentException | IOException expected) {
                // Nothing to run yet.
            }

            // A copy of an installed drive cannot be installed in a second device at the same time.
            var copy = gate.drive().copy();
            test.assertFalse(use(test, owner, copy, spare), "A duplicated drive was installed twice");

            // A drive written for another kind stays recoverable but cannot run.
            var foreign = new ItemStack(Drives.DRIVE);
            foreign.set(Drives.TAG, new dev.wiktorfila.filasignal.fabric.drive.DriveTag(UUID.randomUUID(), "", "weather_station"));
            test.assertTrue(use(test, owner, foreign, spare), "An incompatible drive could not be inserted");
            test.assertTrue(other.program().unavailable().startsWith("Incompatible drive"), "Incompatible drive accepted");
            var ejected = DeviceConfigNetwork.respond(owner, new DeviceConfigNetwork.Edit(test.absolutePos(spare), other.identity(),
                    DeviceConfigNetwork.DRIVE_EJECT, "", ""));
            test.assertTrue(!ejected.title().isEmpty() && other.drive().isEmpty(), "The incompatible drive was not returned");

            // An inventory module's own program reaches only the faces the owner approved on it.
            var root = runtime.requireDevice(level, test.absolutePos(computer));
            runtime.link(root.id(), runtime.requireDevice(level, test.absolutePos(repeater)).id(), false);
            runtime.link(runtime.requireDevice(level, test.absolutePos(repeater)).id(), store.identity(), false);
            runtime.inventory(runtime.directory().node(store.identity()), Direction.NORTH, "ore");
            store.insertDrive(new ItemStack(Drives.DRIVE));
            var local = LocalHosts.registry(runtime, store);
            test.assertTrue(local.lookup("detectors.from") == null && local.lookup("inventory.from") == null
                    && local.lookup("devices.list") == null, "A device program can address other devices");
            test.assertTrue(((ListValue) ok(test, call(local, "device.inventories"))).size() == 1, "Approved faces");
            expectError(test, call(local, "device.inventory", new Text("south")), "inventory.unknown");
            var ore = (HostHandle) ok(test, call(local, "device.inventory", new Text("north")));
            test.assertTrue(ok(test, member(local, ore, "count", new Text("minecraft:iron_ingot"))).equals(new Num(7)), "Local inventory");
            // Unlinking the module from its computer does not stop its own access to its approved face.
            runtime.link(runtime.requireDevice(level, test.absolutePos(repeater)).id(), store.identity(), true);
            test.assertTrue(ok(test, member(local, ore, "count", new Text("minecraft:iron_ingot"))).equals(new Num(7)),
                    "Local inventory access depended on the network");

            // Storage rejects unsupported values and overflow without losing earlier data.
            ok(test, call(local, "storage.write", new Text("keep"), new Text("safe")));
            expectError(test, call(local, "storage.write", new Text("big"), new Text("x".repeat(NamedStorage.CAPACITY))), "storage.full");
            expectError(test, call(local, "storage.write", new Text("handle"), ore), "storage.value");
            expectError(test, call(local, "storage.read", new Text("absent")), "storage.missing");
            ok(test, call(local, "storage.append", new Text("log"), new Num(1)));
            expectError(test, call(local, "storage.append", new Text("keep"), new Num(1)), "storage.type");
            test.assertTrue(ok(test, call(local, "storage.read", new Text("keep"))).equals(new Text("safe")), "Earlier data lost");
            test.assertTrue(((ListValue) ok(test, call(local, "storage.keys"))).size() == 2, "Storage keys");

            // Breaking a device drops its drive exactly once and releases the drive.
            UUID id = gate.driveId();
            test.setBlock(detector, Blocks.AIR);
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(test.absolutePos(detector)).inflate(2),
                    item -> Drives.isDrive(item.getItem()));
            test.assertTrue(drops.size() == 1, "Expected one dropped drive, found " + drops.size());
            test.assertTrue(DriveStore.of(level.getServer()).holder(id) == null, "A broken device kept its drive claim");
            drops.forEach(Entity::discard);
        }).thenSucceed();
    }

    @GameTest(maxTicks = 900)
    public void autonomousProgramSurvivesChunkUnloadAndRestartsAuthorized(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        // Negative offsets stay clear of the grid of other test structures and their loaded chunks.
        var far = test.absolutePos(new BlockPos(-800, 1, -800));
        int x = far.getX() >> 4;
        int z = far.getZ() >> 4;
        BlockPos at = new BlockPos((x << 4) + 8, far.getY(), (z << 4) + 8);
        var original = new AtomicReference<PeripheralEntity>();
        var drive = new AtomicReference<UUID>();
        var before = new AtomicReference<Double>();
        level.setChunkForced(x, z, true);
        test.runBeforeTestEnd(() -> level.setChunkForced(x, z, false));
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(x, z) != null, "Chunk loading"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(at, PeripheralBlocks.block(Kind.WEATHER_STATION).defaultBlockState());
                    original.set((PeripheralEntity) level.getBlockEntity(at));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    var station = original.get();
                    station.insertDrive(new ItemStack(Drives.DRIVE));
                    drive.set(station.driveId());
                    try {
                        ComputerLifecycle.program(level, station, """
                                global samples = 0
                                on timer.every(2) {
                                    samples++
                                    raining, err = device.raining()
                                    storage.write("samples", samples)
                                }
                                """, false, true);
                        ComputerLifecycle.control(level, station, true, "test");
                    } catch (IOException error) {
                        throw test.assertionException(error.getMessage());
                    }
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    test.assertTrue(ComputerLifecycle.active(server, original.get()), "The station did not run");
                    before.set(samples(server, drive.get()));
                    test.assertTrue(before.get() > 0, "Nothing was stored");
                    server.saveEverything(true, true, true);
                    level.setChunkForced(x, z, false);
                })
                // The chunk leaves the loaded map slightly before its block entities unload, so wait for both.
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(x, z) == null
                        && !ComputerLifecycle.active(server, original.get()), "The chunk did not unload its program: active="
                        + ComputerLifecycle.active(server, original.get()) + ", removed=" + original.get().isRemoved() + ", "
                        + ChunkDiagnostics.describe(level, x, z)))
                .thenExecute(() -> {
                    test.assertTrue(DriveStore.of(server).holder(drive.get()) == null, "Unloading kept the drive claim");
                    double unloaded = samples(server, drive.get());
                    test.assertTrue(unloaded >= before.get(), "Unloading lost stored data: " + unloaded + " < " + before.get()
                            + ", original removed=" + original.get().isRemoved() + ", " + ChunkDiagnostics.describe(level, x, z));
                    before.set(unloaded);
                    level.setChunkForced(x, z, true);
                })
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(x, z) != null, "Chunk reloading"))
                .thenIdle(12)
                .thenExecute(() -> {
                    var reloaded = level.getBlockEntity(at);
                    test.assertTrue(reloaded instanceof PeripheralEntity station && station != original.get()
                            && drive.get().equals(station.driveId()), "The drive did not reload with the device");
                    test.assertTrue(ComputerLifecycle.active(server, (PeripheralEntity) reloaded),
                            "The authorized program did not restart after reload");
                    test.assertTrue(samples(server, drive.get()) > before.get(), "Globals restarted instead of continuing");
                })
                .thenSucceed();
    }

    /** The repository examples behave as documented when installed on real devices. */
    @GameTest(maxTicks = 200)
    public void exampleProgramsRunOnDevices(GameTestHelper test) {
        var level = test.getLevel();
        var counter = new BlockPos(1, 1, 1);
        var latch = new BlockPos(4, 1, 4);
        var gate = new BlockPos(6, 1, 1);
        test.setBlock(counter, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(latch, PeripheralBlocks.block(Kind.REDSTONE_CONTROLLER));
        test.setBlock(gate, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(latch.south(), Blocks.REDSTONE_LAMP);
        var difficulty = level.getDifficulty();
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        var intruder = new AtomicReference<Entity>();
        test.runBeforeTestEnd(() -> {
            if (intruder.get() != null) {
                intruder.get().discard();
            }
            level.getServer().setDifficulty(difficulty, true);
        });
        test.startSequence().thenIdle(3).thenExecute(() -> {
            install(test, counter, "portable-counter");
            install(test, latch, "local-redstone");
            install(test, gate, "autonomous-detector");
            var husk = test.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.HUSK, gate.offset(2, 0, 0));
            husk.setPersistenceRequired();
            intruder.set(husk);
        }).thenIdle(6).thenExecute(() -> {
            test.setBlock(latch.north(), Blocks.REDSTONE_BLOCK);
            var device = (PeripheralEntity) level.getBlockEntity(test.absolutePos(counter));
            test.assertTrue(device.driveRecord(false).storage().read("boots").equals(new Num(1)), "Counter did not start");
            try {
                ComputerLifecycle.control(level, device, true, "test");
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
        }).thenWaitUntil(() -> {
            test.assertTrue(test.getBlockState(latch.south()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                    "The latch did not toggle its output");
            var device = (PeripheralEntity) level.getBlockEntity(test.absolutePos(counter));
            test.assertTrue(device.driveRecord(false).storage().read("boots").equals(new Num(2)), "Restart did not count");
            var log = ((PeripheralEntity) level.getBlockEntity(test.absolutePos(gate))).driveRecord(false);
            test.assertTrue(log != null && log.storage().has("history")
                    && ((DictValue) log.storage().read("last_detection")).get("nearest").equals(new Text("minecraft:husk")),
                    "The detector example did not record the husk");
        }).thenSucceed();
    }

    private static void install(GameTestHelper test, BlockPos relative, String example) {
        var level = test.getLevel();
        var device = (PeripheralEntity) level.getBlockEntity(test.absolutePos(relative));
        device.insertDrive(new ItemStack(Drives.DRIVE));
        try {
            String source = java.nio.file.Files.readString(HardwareGameTests.examples(test).resolve(example + ".fsig"));
            // The detector example logs every 100 ticks; a shorter period keeps the test quick without changing its logic.
            source = source.replace("timer.every(100)", "timer.every(5)");
            ComputerLifecycle.program(level, device, source, false, true);
            ComputerLifecycle.control(level, device, true, "test");
        } catch (IOException error) {
            throw test.assertionException(example + ": " + error.getMessage());
        }
    }

    private static double samples(net.minecraft.server.MinecraftServer server, UUID drive) {
        var record = DriveStore.of(server).find(drive);
        return record == null || !record.storage().has("samples") ? 0 : ((Num) record.storage().read("samples")).value();
    }

    private static boolean use(GameTestHelper test, ServerPlayer player, ItemStack stack, BlockPos relative) {
        var at = test.absolutePos(relative);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        return player.gameMode.useItemOn(player, test.getLevel(), stack, InteractionHand.MAIN_HAND, hit).consumesAction();
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos relative) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(Vec3.atCenterOf(test.absolutePos(relative)).add(0, 1, 0));
        return player;
    }

    private static List<Value> call(HostRegistry hosts, String name, Value... arguments) {
        return hosts.lookup(name).handler().invoke(List.of(arguments), CONTEXT);
    }

    private static List<Value> member(HostRegistry hosts, HostHandle handle, String name, Value... arguments) {
        var all = new ArrayList<Value>();
        all.add(handle);
        all.addAll(List.of(arguments));
        return hosts.object(handle.type()).methods().get(name).handler().invoke(all, CONTEXT);
    }

    private static Value ok(GameTestHelper test, List<Value> result) {
        if (!(result.getLast() instanceof Value.Nil)) {
            throw test.assertionException("Unexpected error " + result.getLast());
        }
        return result.getFirst();
    }

    private static void expectError(GameTestHelper test, List<Value> result, String code) {
        test.assertTrue(result.getLast() instanceof ErrorValue error && error.code().equals(code),
                "Expected " + code + " but got " + result);
    }
}
