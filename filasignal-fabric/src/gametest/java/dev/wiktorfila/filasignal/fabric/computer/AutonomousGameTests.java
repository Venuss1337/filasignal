package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.drive.DriveStore;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralEntity;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The autonomous-device gameplay loop on a real server: a computer writes a drive, a detector runs it with no computer
 * reachable, the computer later reads what was recorded, and the drive keeps its state in a second detector.
 */
public final class AutonomousGameTests {
    private static final HostRegistry.Context CONTEXT = new HostRegistry.Context() {
        @Override public void charge(long operations) { }
        @Override public void log(String line) { }
        @Override public void print(Value value) { }
    };

    private static final String PROGRAM = """
            global scans = 0
            on timer.every(5) {
                scans++
                seen, err = device.census("other")
                if err is not nil { log.error(err); return }
                storage.write("scans", scans)
                storage.append("history", seen.count)
            }
            """;

    @GameTest(maxTicks = 400)
    public void driveProgramRunsAutonomouslyAndTravelsBetweenDetectors(GameTestHelper test) {
        var level = test.getLevel();
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(2, 1, 1);
        var first = new BlockPos(3, 1, 3);
        var second = new BlockPos(5, 1, 5);
        test.setBlock(computer, ComputerBlocks.computer);
        test.setBlock(repeater, block("network_repeater"));
        test.setBlock(first, block("entity_detector"));
        test.setBlock(second, block("entity_detector"));
        var owner = test.makeMockServerPlayerInLevel();
        owner.setNoGravity(true);
        owner.snapTo(Vec3.atCenterOf(test.absolutePos(repeater)).add(0, 1, 0));
        var stands = new ArrayList<Entity>();
        for (int i = 0; i < 3; i++) {
            stands.add(test.spawn(net.minecraft.world.entity.EntityTypes.ARMOR_STAND, first.offset(1, 0, 0)));
        }
        test.runBeforeTestEnd(() -> {
            stands.forEach(Entity::discard);
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        });
        var drive = new AtomicReference<UUID>();
        var recorded = new AtomicReference<Integer>();
        test.startSequence()
                .thenWaitUntil(() -> require(test, computer, repeater, first, second))
                .thenExecute(() -> { try {
                    for (var pos : List.of(computer, repeater, first, second)) {
                        var at = test.absolutePos(pos);
                        level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
                    }
                    link(test, owner, computer, repeater, false);
                    link(test, owner, repeater, first, false);
                    alias(test, owner, first, "gate");

                    // 1. The computer writes the program onto a blank drive.
                    use(test, owner, new ItemStack(Drives.DRIVE), computer);
                    ProgramEditing.driveAction(owner, test.absolutePos(computer), "target", "entity_detector");
                    ProgramEditing.driveAction(owner, test.absolutePos(computer), "label", "Gate log");
                    var written = ProgramEditing.inspect(owner, test.absolutePos(computer), ProgramEditing.DRIVE);
                    try {
                        ProgramEditing.submit(owner, test.absolutePos(computer), ProgramEditing.DRIVE, written.revision(),
                                "on startup { gate, err = detectors.from(\"gate\") }", true, false);
                        test.fail("A drive program used a computer-only API");
                    } catch (IllegalArgumentException expected) {
                        // Device programs only see their own hardware.
                    }
                    ProgramEditing.submit(owner, test.absolutePos(computer), ProgramEditing.DRIVE, written.revision(), PROGRAM, true, false);
                    ProgramEditing.driveAction(owner, test.absolutePos(computer), "eject", "");
                    ItemStack programmed = owner.getInventory().getItem(driveSlot(owner));
                    var tag = Drives.tag(programmed);
                    test.assertTrue(tag != null && tag.label().equals("Gate log") && tag.target().equals("entity_detector"),
                            "The drive label or target was not written");
                    drive.set(tag.id());

                    // 2. Installing does not run it. An authorized player starts it.
                    use(test, owner, programmed, first);
                    var detector = device(test, first);
                    test.assertFalse(ComputerLifecycle.active(level.getServer(), detector), "Inserting a drive started it");
                    start(test, owner, first);
                } catch (IOException error) {
                    throw test.assertionException(error.getMessage());
                } })
                .thenIdle(12)
                .thenExecute(() -> {
                    test.assertTrue(ComputerLifecycle.active(level.getServer(), device(test, first)), "The detector did not start");
                    // 3. Cut the computer off. The detector keeps recording on its own.
                    link(test, owner, computer, repeater, true);
                    recorded.set(history(test, drive.get()));
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    int now = history(test, drive.get());
                    test.assertTrue(now > recorded.get(), "The detector stopped recording without its computer");
                    test.assertTrue(lastEntry(test, drive.get()) == 3, "The detector recorded the wrong count");
                    // 4. Reconnect and read the records through the computer's own API.
                    link(test, owner, computer, repeater, false);
                    var hosts = PeripheralLifecycle.computer(level, level.getBlockEntity(test.absolutePos(computer)),
                            ((ComputerBlockEntity) level.getBlockEntity(test.absolutePos(computer))).activateIdentity());
                    var handle = (HostHandle) ok(test, hosts.lookup("drives.from").handler().invoke(List.of(new Text("gate")), CONTEXT));
                    var history = (ListValue) ok(test, member(hosts, handle, "read", new Text("history")));
                    test.assertTrue(history.size() >= now, "Remote read missed records");
                    var info = (DictValue) ok(test, member(hosts, handle, "info"));
                    test.assertTrue(info.get("label").equals(new Text("Gate log")) && info.get("running").equals(new Bool(true)),
                            "Remote metadata");
                    var missing = member(hosts, handle, "read", new Text("nothing"));
                    test.assertTrue(missing.getLast() instanceof ErrorValue error && error.code().equals("storage.missing"), "Missing key");
                    var repeaterHandle = hosts.lookup("drives.from").handler().invoke(List.of(new Text("router")), CONTEXT);
                    test.assertTrue(repeaterHandle.getLast() instanceof ErrorValue error
                            && (error.code().equals("device.type") || error.code().equals("device.unknown")), "A repeater offered a drive");
                    // A computer program can stop the device remotely. The change applies before the next dispatch.
                    test.assertTrue(ok(test, member(hosts, handle, "stop")).equals(new Bool(true)), "Remote stop request");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    test.assertFalse(ComputerLifecycle.active(level.getServer(), device(test, first)), "Remote stop was ignored");
                    var hosts = PeripheralLifecycle.computer(level, level.getBlockEntity(test.absolutePos(computer)),
                            ((ComputerBlockEntity) level.getBlockEntity(test.absolutePos(computer))).activateIdentity());
                    var handle = (HostHandle) ok(test, hosts.lookup("drives.from").handler().invoke(List.of(new Text("gate")), CONTEXT));

                    // 5. Removing the drive stops the program and returns the item. Old handles stop working.
                    var removed = DeviceConfigNetwork.respond(owner, new DeviceConfigNetwork.Edit(test.absolutePos(first),
                            device(test, first).identity(), DeviceConfigNetwork.DRIVE_EJECT, "", ""));
                    test.assertTrue(!removed.title().isEmpty(), "Eject failed: " + removed.status());
                    test.assertFalse(ComputerLifecycle.active(level.getServer(), device(test, first)), "Removed drive kept running");
                    var stale = member(hosts, handle, "keys");
                    test.assertTrue(stale.getLast() instanceof ErrorValue error && error.code().equals("drive.removed"), "Stale handle");
                    recorded.set(history(test, drive.get()));

                    // 6. The same drive in another detector continues with its globals and storage.
                    int slot = driveSlot(owner);
                    test.assertTrue(slot >= 0, "The drive was not returned");
                    use(test, owner, owner.getInventory().getItem(slot), second);
                    // A one-block radius keeps the armor stands out of the second detector's view.
                    var radius = DeviceConfigNetwork.respond(owner, new DeviceConfigNetwork.Edit(test.absolutePos(second),
                            device(test, second).identity(), DeviceConfigNetwork.RADIUS, "1", ""));
                    test.assertTrue(!radius.title().isEmpty(), "Radius: " + radius.status());
                    start(test, owner, second);
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    var record = DriveStore.of(level.getServer()).find(drive.get());
                    test.assertTrue(record.storage().has("history") && history(test, drive.get()) > recorded.get(),
                            "Storage did not follow the drive");
                    double scans = ((Num) record.storage().read("scans")).value();
                    test.assertTrue(scans > recorded.get(), "Persistent globals restarted instead of continuing: " + scans);
                    test.assertTrue(lastEntry(test, drive.get()) == 0, "The second detector should see no armor stands");
                })
                .thenSucceed();
    }

    /**
     * Debugging a device program pauses only that device. Removing the drive ends the session and releases the
     * paused handler, and the program does not stay paused anywhere.
     */
    @GameTest(maxTicks = 200)
    public void removingADriveEndsItsDebuggerSession(GameTestHelper test) {
        var level = test.getLevel();
        var station = new BlockPos(2, 1, 2);
        var busy = new BlockPos(5, 1, 5);
        test.setBlock(station, block("weather_station"));
        test.setBlock(busy, ComputerBlocks.computer);
        var owner = test.makeMockServerPlayerInLevel();
        owner.setNoGravity(true);
        owner.snapTo(Vec3.atCenterOf(test.absolutePos(station)).add(0, 1, 0));
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED));
        var handlers = new AtomicReference<Long>();
        test.startSequence()
                .thenWaitUntil(() -> require(test, station, busy))
                .thenExecute(() -> { try {
                    var at = test.absolutePos(station);
                    level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
                    // An unrelated computer runs a timer through the same scheduler.
                    ComputerLifecycle.program(level, test.absolutePos(busy), "global n = 0\non timer.every(1) { n++ }", true);
                    use(test, owner, new ItemStack(Drives.DRIVE), station);
                    var snapshot = ProgramEditing.inspect(owner, at, ProgramEditing.LOCAL);
                    ProgramEditing.submit(owner, at, ProgramEditing.LOCAL, snapshot.revision(), """
                            global ticks = 0
                            on timer.every(1) {
                                ticks++
                                storage.write("ticks", ticks)
                            }
                            """, true, false);
                    var attach = ProgramDebugging.respond(owner, new EditorNetwork.Request(at, UUID.randomUUID(),
                            EditorNetwork.Request.DEBUG_ATTACH, UUID.randomUUID(), "", ProgramEditing.LOCAL));
                    test.assertTrue(attach.success(), attach.message());
                    ProgramDebugging.respond(owner, new EditorNetwork.Request(at, UUID.randomUUID(),
                            EditorNetwork.Request.DEBUG_BREAKPOINTS, UUID.randomUUID(), "4", ProgramEditing.LOCAL));
                    start(test, owner, station);
                } catch (IOException error) {
                    throw test.assertionException(error.getMessage());
                } })
                .thenIdle(6)
                .thenExecute(() -> {
                    var at = test.absolutePos(station);
                    var paused = ComputerLifecycle.debugView(level, device(test, station), at, EditorNetwork.EMPTY_REQUEST, "");
                    test.assertTrue(paused.status().state() == EditorNetwork.DebugState.PAUSED && paused.pause().line() == 4,
                            "The device program did not pause: " + paused.status().headline());
                    handlers.set(ComputerLifecycle.statistics(level.getServer(),
                            (ComputerBlockEntity) level.getBlockEntity(test.absolutePos(busy))).handlers());
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    var at = test.absolutePos(station);
                    test.assertTrue(ComputerLifecycle.statistics(level.getServer(), (ComputerBlockEntity) level.getBlockEntity(
                            test.absolutePos(busy))).handlers() > handlers.get(), "Pausing a device paused the computer");
                    var removed = DeviceConfigNetwork.respond(owner, new DeviceConfigNetwork.Edit(at, device(test, station).identity(),
                            DeviceConfigNetwork.DRIVE_EJECT, "", ""));
                    test.assertTrue(!removed.title().isEmpty(), removed.status());
                    var view = ComputerLifecycle.debugView(level, device(test, station), at, EditorNetwork.EMPTY_REQUEST, "");
                    test.assertTrue(view.status().state() != EditorNetwork.DebugState.PAUSED && view.breakpoints().isEmpty(),
                            "The debugger survived drive removal: " + view.status().headline());
                    test.assertFalse(ComputerLifecycle.active(level.getServer(), device(test, station)), "The program kept running");
                    // The drive comes back with its globals, and its next start is not paused.
                    use(test, owner, owner.getInventory().getItem(driveSlot(owner)), station);
                    start(test, owner, station);
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    var view = ComputerLifecycle.debugView(level, device(test, station), test.absolutePos(station),
                            EditorNetwork.EMPTY_REQUEST, "");
                    test.assertTrue(view.status().state() == EditorNetwork.DebugState.RUNNING, view.status().headline());
                })
                .thenSucceed();
    }

    static int driveSlot(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (Drives.isDrive(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int history(GameTestHelper test, UUID drive) {
        var record = DriveStore.of(test.getLevel().getServer()).find(drive);
        return record == null || !record.storage().has("history") ? 0 : ((ListValue) record.storage().read("history")).size();
    }

    private static int lastEntry(GameTestHelper test, UUID drive) {
        var list = (ListValue) DriveStore.of(test.getLevel().getServer()).find(drive).storage().read("history");
        return (int) ((Num) list.get(list.size() - 1)).value();
    }

    static void start(GameTestHelper test, ServerPlayer player, BlockPos relative) {
        try {
            var at = test.absolutePos(relative);
            var snapshot = ProgramEditing.inspect(player, at, ProgramEditing.LOCAL);
            ProgramEditing.control(player, at, ProgramEditing.LOCAL, snapshot.revision(), true);
        } catch (IOException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    static PeripheralEntity device(GameTestHelper test, BlockPos relative) {
        return (PeripheralEntity) test.getLevel().getBlockEntity(test.absolutePos(relative));
    }

    static net.minecraft.world.level.block.Block block(String name) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("filasignal", name));
    }

    private static void require(GameTestHelper test, BlockPos... positions) {
        for (BlockPos pos : positions) {
            if (test.getLevel().getBlockEntity(test.absolutePos(pos)) == null) {
                throw test.assertionException("Blocks are still loading");
            }
        }
    }

    /** Uses an item on a block as the player would, including the block's own access checks. */
    static void use(GameTestHelper test, ServerPlayer player, ItemStack stack, BlockPos relative) {
        var at = test.absolutePos(relative);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        var result = player.gameMode.useItemOn(player, test.getLevel(), stack, InteractionHand.MAIN_HAND, hit);
        test.assertTrue(result.consumesAction(), "Using the item at " + relative + " was refused");
    }

    static void link(GameTestHelper test, ServerPlayer player, BlockPos a, BlockPos b, boolean unlink) {
        var tool = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("filasignal", "linking_tool")));
        player.setShiftKeyDown(unlink);
        for (BlockPos pos : List.of(a, b)) {
            var at = test.absolutePos(pos);
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
            tool.useOn(new UseOnContext(test.getLevel(), player, InteractionHand.MAIN_HAND, tool,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
        }
        player.setShiftKeyDown(false);
    }

    static void alias(GameTestHelper test, ServerPlayer player, BlockPos relative, String name) {
        var at = test.absolutePos(relative);
        var device = device(test, relative);
        var form = DeviceConfigNetwork.respond(player, new DeviceConfigNetwork.Edit(at, device.identity(),
                DeviceConfigNetwork.ALIAS, name, ""));
        test.assertTrue(!form.title().isEmpty(), "Naming failed: " + form.status());
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
}
