package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.computer.ProgramHost;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native hardware events, custom events, authority revocation and end-to-end scenarios, on real
 * devices in a test world.
 */
public final class NativeEventGameTests {
    private static final HostRegistry.Context CONTEXT = new HostRegistry.Context() {
        @Override public void charge(long operations) { }
        @Override public void log(String line) { }
        @Override public void print(Value value) { }
    };

    /**
     * The essential beginner scenario: a computer reacts to an ordinary detector without a drive and sounds an alarm,
     * with no custom events or topics. Losing the detector raises disconnected, and stopping releases every listener.
     */
    @GameTest(maxTicks = 400, setupTicks = 20)
    public void beginnerAlarmReactsToADetectorWithoutADrive(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var runtime = PeripheralLifecycle.runtime(server);
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(3, 1, 1);
        var detector = new BlockPos(4, 1, 5);
        var alarm = new BlockPos(6, 1, 1);
        test.setBlock(computer, ComputerBlocks.block());
        test.setBlock(repeater, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(detector, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(alarm, PeripheralBlocks.block(Kind.ALARM));
        var owner = player(test, new BlockPos(0, 1, 0));
        var visitor = new AtomicReference<Entity>();
        var detectorId = new AtomicReference<UUID>();
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            if (visitor.get() != null) {
                visitor.get().discard();
            }
        });
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            place(test, owner, computer, repeater, detector, alarm);
            var root = runtime.requireDevice(level, test.absolutePos(computer)).id();
            var relay = entity(test, repeater).identity();
            var gate = entity(test, detector);
            // A small radius keeps neighbouring tests' entities out of range.
            gate.configureRadius(3);
            detectorId.set(gate.identity());
            runtime.link(root, relay, false);
            runtime.link(relay, gate.identity(), false);
            runtime.link(relay, entity(test, alarm).identity(), false);
            runtime.alias(runtime.directory().node(gate.identity()), "gate");
            runtime.alias(runtime.directory().node(entity(test, alarm).identity()), "siren");
            run(test, host(test, computer), example(test, "starter-intruder-alarm")
                    + "\non gate.disconnected {\n    print(\"gate lost\")\n}\n");
        }).thenIdle(40).thenExecute(() -> {
            test.assertTrue(runtime.events().wants(detectorId.get(), "entityEnter"), "The handler was not routed to the detector");
            test.assertFalse(runtime.events().wants(detectorId.get(), "entityLeave"), "Unhandled events were observed");
            var pig = test.spawnWithNoFreeWill(EntityTypes.PIG, detector.offset(2, 0, 0));
            pig.setPersistenceRequired();
            visitor.set(pig);
        }).thenWaitUntil(() -> {
            test.assertTrue(runtime.alarms().active(entity(test, alarm)), "The alarm did not sound");
            test.assertTrue(console(test, computer).contains("Pig is at the gate!"), "The handler did not print: " + console(test, computer));
        }).thenExecute(() -> test.setBlock(detector, Blocks.AIR))
                .thenWaitUntil(() -> test.assertTrue(console(test, computer).contains("gate lost"), "disconnected did not run"))
                .thenExecute(() -> {
                    try {
                        ComputerLifecycle.control(level, host(test, computer), false, "test");
                    } catch (IOException error) {
                        throw test.assertionException(error.getMessage());
                    }
                }).thenIdle(2).thenExecute(() -> {
                    test.assertTrue(runtime.events().listening(host(test, computer).executionId()) == 0,
                            "Stopping the program left listeners behind");
                    test.assertFalse(runtime.events().wantsAny(detectorId.get(), java.util.Set.of("entityEnter", "disconnected")),
                            "Stopping the program left routes behind");
                }).thenSucceed();
    }

    /** A device's own program receives its events with Direction and Entity constants and the detector's filter. */
    @GameTest(maxTicks = 300, setupTicks = 20)
    public void localEventsUseConstantsAndTheDetectorFilter(GameTestHelper test) {
        var level = test.getLevel();
        var controller = new BlockPos(2, 1, 2);
        var detector = new BlockPos(5, 1, 5);
        test.setBlock(controller, PeripheralBlocks.block(Kind.REDSTONE_CONTROLLER));
        test.setBlock(detector, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        var difficulty = level.getDifficulty();
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        var spawned = new ArrayList<Entity>();
        test.runBeforeTestEnd(() -> {
            spawned.forEach(Entity::discard);
            level.getServer().setDifficulty(difficulty, true);
        });
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            var redstone = entity(test, controller);
            redstone.insertDrive(new ItemStack(Drives.DRIVE));
            run(test, redstone, """
                    on device.inputChanged(face, power, previous) {
                        storage.write("last", {face: str(face), power: power, previous: previous})
                        if face is Direction.NORTH and power > 0 {
                            storage.write("north", true)
                        }
                    }
                    """);
            var gate = entity(test, detector);
            gate.configureRadius(3);
            gate.detectFilter("hostile");
            gate.insertDrive(new ItemStack(Drives.DRIVE));
            run(test, gate, """
                    on device.entityEnter(entity) {
                        storage.append("seen", entity.name)
                        if entity.category is Entity.HOSTILE and entity.hostile {
                            storage.write("hostile", true)
                        }
                    }
                    """);
        }).thenIdle(25).thenExecute(() -> {
            spawned.add(test.spawnWithNoFreeWill(EntityTypes.PIG, detector.offset(1, 0, 1)));
            var husk = test.spawnWithNoFreeWill(EntityTypes.HUSK, detector.offset(-1, 0, 1));
            husk.setPersistenceRequired();
            spawned.add(husk);
            test.setBlock(controller.north(), Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> {
            var redstone = entity(test, controller).driveRecord(false).storage();
            test.assertTrue(redstone.has("north"), "The controller event did not arrive");
            var last = (DictValue) redstone.read("last");
            test.assertTrue(last.get("face").equals(new Text("Direction.NORTH")) && last.get("power").equals(new Num(15))
                    && last.get("previous").equals(new Num(0)), "Event values: " + last.keys());
            var gate = entity(test, detector).driveRecord(false).storage();
            test.assertTrue(gate.has("hostile"), "The husk was not reported");
            test.assertTrue(((ListValue) gate.read("seen")).snapshot().equals(List.of(new Text("Husk"))),
                    "The detector filter was ignored: " + ((ListValue) gate.read("seen")).snapshot());
        }).thenSucceed();
    }

    /** An incomplete entity scan never reports a departure or a count; a complete one does. */
    @GameTest(maxTicks = 400, setupTicks = 20)
    public void truncatedScansNeverReportFalseDepartures(GameTestHelper test) {
        var level = test.getLevel();
        var detector = new BlockPos(4, 1, 4);
        test.setBlock(detector, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        var named = new ArrayList<Entity>();
        var crowd = new ArrayList<Entity>();
        test.runBeforeTestEnd(() -> {
            named.forEach(Entity::discard);
            crowd.forEach(Entity::discard);
        });
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            var gate = entity(test, detector);
            gate.configureRadius(4);
            gate.insertDrive(new ItemStack(Drives.DRIVE));
            run(test, gate, """
                    on device.entityEnter(entity) {
                        storage.append("entered", entity.name)
                    }
                    on device.entityLeave(entity) {
                        storage.append("left", entity.name)
                    }
                    on device.countChanged(count, previous) {
                        storage.write("count", count)
                    }
                    """);
        }).thenIdle(25).thenExecute(() -> {
            for (String name : List.of("Alpha", "Beta")) {
                var stand = test.spawn(EntityTypes.ARMOR_STAND, detector.offset(name.equals("Alpha") ? 1 : -1, 0, 0));
                stand.setCustomName(Component.literal(name));
                named.add(stand);
            }
        }).thenWaitUntil(() -> {
            var storage = entity(test, detector).driveRecord(false).storage();
            test.assertTrue(storage.has("entered") && ((ListValue) storage.read("entered")).size() == 2, "Named stands");
        }).thenExecute(() -> {
            // More than 256 entities in range makes every scan incomplete.
            for (int i = 0; i < 300; i++) {
                crowd.add(test.spawn(EntityTypes.ARMOR_STAND, detector.offset(i % 7 - 3, 0, i / 7 % 7 - 3)));
            }
        }).thenIdle(40).thenExecute(() -> {
            var storage = entity(test, detector).driveRecord(false).storage();
            test.assertFalse(storage.has("left"), "An incomplete scan reported a departure");
            crowd.forEach(Entity::discard);
            crowd.clear();
        }).thenIdle(30).thenExecute(() -> {
            var storage = entity(test, detector).driveRecord(false).storage();
            var left = storage.has("left") ? ((ListValue) storage.read("left")).snapshot() : List.<Value>of();
            test.assertFalse(left.contains(new Text("Alpha")) || left.contains(new Text("Beta")),
                    "A present entity was reported as leaving: " + left);
            named.getFirst().discard();
        }).thenWaitUntil(() -> {
            var storage = entity(test, detector).driveRecord(false).storage();
            test.assertTrue(storage.has("left") && ((ListValue) storage.read("left")).snapshot().contains(new Text("Alpha")),
                    "A complete scan did not report the departure");
            test.assertTrue(storage.read("count").equals(new Num(1)), "Count after a complete scan");
        }).thenSucceed();
    }

    /**
     * Custom events reach only programs on the same network that handle the name, carry server-made sender details,
     * cannot trigger native handlers, and are bounded in name, size and rate. A ping-pong loop cannot starve others.
     */
    @GameTest(maxTicks = 400, setupTicks = 20)
    public void customEventsAreAuthenticatedBoundedAndSeparate(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(3, 1, 1);
        var board = new BlockPos(3, 1, 3);
        var siren = new BlockPos(5, 1, 1);
        var ping = new BlockPos(5, 1, 3);
        var pong = new BlockPos(1, 1, 3);
        var otherComputer = new BlockPos(7, 1, 7);
        var otherRepeater = new BlockPos(7, 1, 5);
        var outsider = new BlockPos(5, 1, 7);
        test.setBlock(computer, ComputerBlocks.block());
        test.setBlock(otherComputer, ComputerBlocks.block());
        test.setBlock(repeater, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(otherRepeater, PeripheralBlocks.block(Kind.REPEATER));
        for (var pos : List.of(board, ping, pong, outsider)) {
            test.setBlock(pos, PeripheralBlocks.block(Kind.DISPLAY));
        }
        test.setBlock(siren, PeripheralBlocks.block(Kind.ALARM));
        var owner = player(test, new BlockPos(0, 1, 7));
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED));
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            place(test, owner, computer, otherComputer, repeater, otherRepeater, board, siren, ping, pong, outsider);
            var root = runtime.requireDevice(level, test.absolutePos(computer)).id();
            var otherRoot = runtime.requireDevice(level, test.absolutePos(otherComputer)).id();
            var relay = entity(test, repeater).identity();
            runtime.link(root, relay, false);
            for (var pos : List.of(board, siren, ping, pong)) {
                runtime.link(relay, entity(test, pos).identity(), false);
            }
            runtime.link(otherRoot, entity(test, otherRepeater).identity(), false);
            runtime.link(entity(test, otherRepeater).identity(), entity(test, outsider).identity(), false);
            name(runtime, test, board, "board");
            name(runtime, test, siren, "siren");
            name(runtime, test, ping, "ping_dev");
            name(runtime, test, pong, "pong_dev");
            name(runtime, test, outsider, "board_b");
            String receiver = """
                    on events.custom("hello", value, sender) {
                        storage.append("got", {value: value, kind: sender.kind, name: sender.name})
                    }
                    """;
            for (var pos : List.of(board, outsider)) {
                entity(test, pos).insertDrive(new ItemStack(Drives.DRIVE));
                run(test, entity(test, pos), receiver);
            }
            entity(test, siren).insertDrive(new ItemStack(Drives.DRIVE));
            run(test, entity(test, siren), """
                    on device.activated {
                        storage.write("native", true)
                    }
                    on events.custom("activated", value, sender) {
                        storage.write("custom", true)
                    }
                    """);
            entity(test, ping).insertDrive(new ItemStack(Drives.DRIVE));
            run(test, entity(test, ping), """
                    on events.custom("ping", n, sender) {
                        events.send("pong_dev", "pong", n + 1)
                    }
                    """);
            entity(test, pong).insertDrive(new ItemStack(Drives.DRIVE));
            run(test, entity(test, pong), """
                    global last = 0
                    on events.custom("pong", n, sender) {
                        last = n
                        storage.write("last", n)
                        events.send("ping_dev", "ping", n + 1)
                    }
                    """);
            run(test, host(test, computer), """
                    global sent = false
                    global ticks = 0
                    on timer.every(20) {
                        ticks++
                        if sent { return }
                        sent = true
                        reached = events.emit("hello", {count: 3, items: ["a", "b"]})
                        print("reached " + str(reached))
                        bad, err = events.emit("bad name!", 1)
                        print("name " + err.code)
                        deep = [1]
                        levels = 0
                        while levels < 10 {
                            deep = [deep]
                            levels++
                        }
                        large, err = events.emit("hello", deep)
                        print("size " + err.code)
                        direct, err = events.send("board", "hello", "direct")
                        print("direct " + str(direct))
                        away, err = events.send("board_b", "hello", "x")
                        print("other " + err.code)
                        events.emit("activated", true)
                        events.emit("device.activated", true)
                        events.send("ping_dev", "ping", 0)
                        limited = 0
                        count = 0
                        while count < 20 {
                            n, err = events.emit("spam", count)
                            if err is not nil and err.code is "event.limit" { limited++ }
                            count++
                        }
                        print("limited " + str(limited))
                    }
                    """);
        }).thenIdle(100).thenExecute(() -> {
            var lines = console(test, computer);
            for (String expected : List.of("reached 1", "name event.invalid", "size event.invalid", "direct 1",
                    "other device.unknown")) {
                test.assertTrue(lines.contains(expected), "Missing \"" + expected + "\" in " + lines);
            }
            // Earlier sends this tick count toward the limit too: 5 sends before the loop leave 11 of its 20.
            test.assertTrue(lines.stream().anyMatch(line -> line.startsWith("limited ") && !line.equals("limited 0")),
                    "The per-tick send limit was not applied: " + lines);
            var got = ((ListValue) entity(test, board).driveRecord(false).storage().read("got")).snapshot();
            test.assertTrue(got.size() == 2, "Expected the broadcast and the direct event, got " + got.size());
            var first = (DictValue) got.getFirst();
            test.assertTrue(first.get("kind").equals(new Text("computer")), "Sender details were not server-made");
            test.assertTrue(((DictValue) first.get("value")).get("items") instanceof ListValue, "Structured payload");
            test.assertFalse(entity(test, outsider).driveRecord(false).storage().has("got"), "Another network received the event");
            var siren0 = entity(test, siren).driveRecord(false).storage();
            test.assertTrue(siren0.has("custom") && !siren0.has("native"), "A custom event triggered a native handler");
            // The ping-pong loop runs at most one exchange per program per tick and does not stall other programs.
            var pongData = entity(test, pong).driveRecord(false).storage();
            test.assertTrue(pongData.has("last"), "The ping-pong exchange never ran");
            double exchanges = ((Num) pongData.read("last")).value();
            test.assertTrue(exchanges > 10 && exchanges < 200, "Unexpected ping-pong progress " + exchanges);
            test.assertTrue(ComputerLifecycle.active(level.getServer(), host(test, computer))
                    && ComputerLifecycle.active(level.getServer(), entity(test, ping)), "A program stopped during the loop");
        }).thenSucceed();
    }

    /**
     * Shared storage is reachable from computers and authorized device programs, honours read-only mode and its
     * capacity, and survives saving. A device program stops when its starter loses access, keeping its data.
     */
    @GameTest(maxTicks = 400, setupTicks = 20)
    public void storageServerIsSharedProtectedAndRevocationStopsPrograms(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var runtime = PeripheralLifecycle.runtime(server);
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(3, 1, 1);
        var storage = new BlockPos(3, 1, 3);
        var alarm = new BlockPos(5, 1, 1);
        test.setBlock(computer, ComputerBlocks.block());
        test.setBlock(repeater, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(storage, PeripheralBlocks.block(Kind.STORAGE_SERVER));
        test.setBlock(alarm, PeripheralBlocks.block(Kind.ALARM));
        var owner = player(test, new BlockPos(1, 1, 6));
        var helper = player(test, new BlockPos(2, 1, 6));
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(helper, Entity.RemovalReason.DISCARDED);
        });
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            place(test, owner, computer, repeater, storage, alarm);
            var root = runtime.requireDevice(level, test.absolutePos(computer)).id();
            var relay = entity(test, repeater).identity();
            runtime.link(root, relay, false);
            runtime.link(relay, entity(test, storage).identity(), false);
            runtime.link(relay, entity(test, alarm).identity(), false);
            name(runtime, test, storage, "records");
            name(runtime, test, alarm, "siren");
            runtime.access(root, runtime.directory().access(root).trust(helper.getUUID(), true));

            // A computer program uses a storage server like drive storage, with a larger capacity.
            var hosts = DeviceHosts.registry(runtime, root);
            var server0 = (HostHandle) ok(test, hosts.lookup("storageServers.from").handler().invoke(List.of(new Text("records")), CONTEXT));
            ok(test, member(hosts, server0, "write", new Text("diamonds"), new Num(64)));
            test.assertTrue(ok(test, member(hosts, server0, "read", new Text("diamonds"))).equals(new Num(64)), "Read back");
            int written = 0;
            List<Value> result;
            do {
                result = member(hosts, server0, "write", new Text("block" + written++), new Text("x".repeat(60_000)));
            } while (result.getLast() instanceof Nil && written < 10);
            test.assertTrue(result.getLast() instanceof ErrorValue error && error.code().equals("storage.full") && written == 5,
                    "Expected the fifth 60 KB value to exceed 256 KiB, wrote " + written);
            ok(test, member(hosts, server0, "delete", new Text("block0")));

            // The data belongs to the block and survives a save and load.
            var entity = entity(test, storage);
            var saved = entity.saveWithFullMetadata(level.registryAccess());
            var copy = (PeripheralEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(entity.getBlockPos(),
                    entity.getBlockState(), saved, level.registryAccess());
            test.assertTrue(copy != null && copy.served().read("diamonds").equals(new Num(64)), "Server data was not saved");

            // A device program started by a trusted player writes through its reference.
            var siren = entity(test, alarm);
            siren.insertDrive(new ItemStack(Drives.DRIVE));
            try {
                ComputerLifecycle.program(level, siren, """
                        const records = storageServers.named("records")
                        global writes = 0
                        on timer.every(5) {
                            writes++
                            ok, err = records.write("from_alarm", writes)
                            if err is not nil { storage.write("error", err.code) }
                        }
                        """, false, true);
                ComputerLifecycle.control(level, siren, true, "player helper", helper.getUUID());
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
        }).thenWaitUntil(() -> test.assertTrue(entity(test, storage).served().has("from_alarm"), "The device program did not write"))
                .thenExecute(() -> {
                    entity(test, storage).deviceWrites(false);
                })
                .thenWaitUntil(() -> {
                    var record = entity(test, alarm).driveRecord(false).storage();
                    test.assertTrue(record.has("error") && record.read("error").equals(new Text("host.denied")),
                            "A read-only server accepted a device write");
                })
                .thenExecute(() -> {
                    var root = runtime.requireDevice(level, test.absolutePos(computer)).id();
                    runtime.access(root, runtime.directory().access(root).trust(helper.getUUID(), false));
                })
                .thenWaitUntil(() -> test.assertFalse(ComputerLifecycle.active(server, entity(test, alarm)),
                        "The program kept running after its starter lost access"))
                .thenExecute(() -> {
                    var alarm0 = entity(test, alarm);
                    test.assertTrue(alarm0.driveRecord(false).storage().has("error"), "Stopping lost the drive data");
                    test.assertTrue(String.join(" ", ComputerLifecycle.console(level, alarm0, 8)).contains("no longer has access"),
                            "The stop reason was not reported: " + ComputerLifecycle.console(level, alarm0, 8));
                    test.assertFalse(alarm0.program().enabled(), "A revoked program would restart");
                    test.assertTrue(entity(test, storage).served().has("from_alarm"), "Committed data was lost");
                }).thenSucceed();
    }

    /**
     * An Access Controller works with no program: allowed players open it for its configured time, others are refused,
     * every attempt is remembered and raises events. A Control Panel raises button events for permitted players.
     */
    @GameTest(maxTicks = 300, setupTicks = 20)
    public void accessControllersAndControlPanelsWorkWithoutPrograms(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        var computer = new BlockPos(1, 1, 1);
        var repeater = new BlockPos(3, 1, 1);
        var door = new BlockPos(5, 1, 3);
        var panel = new BlockPos(3, 1, 3);
        test.setBlock(computer, ComputerBlocks.block());
        test.setBlock(repeater, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(door, PeripheralBlocks.block(Kind.ACCESS_CONTROLLER));
        test.setBlock(panel, PeripheralBlocks.block(Kind.CONTROL_PANEL));
        test.setBlock(door.east(), Blocks.REDSTONE_LAMP);
        var owner = player(test, new BlockPos(4, 1, 5));
        var friend = player(test, new BlockPos(4, 1, 5));
        var stranger = player(test, new BlockPos(4, 1, 5));
        test.runBeforeTestEnd(() -> List.of(owner, friend, stranger)
                .forEach(player -> level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED)));
        test.startSequence().thenWaitUntil(() -> loaded(test)).thenExecute(() -> {
            place(test, owner, computer, repeater, door, panel);
            var root = runtime.requireDevice(level, test.absolutePos(computer)).id();
            var relay = entity(test, repeater).identity();
            runtime.link(root, relay, false);
            runtime.link(relay, entity(test, door).identity(), false);
            runtime.link(relay, entity(test, panel).identity(), false);
            name(runtime, test, door, "front_door");
            name(runtime, test, panel, "panel");
            var controller = entity(test, door);
            test.assertFalse(controller.publicUse(), "Access Controllers must start restricted");
            controller.allow(friend.getUUID(), friend.getName().getString(), true);
            controller.output(1, "east");
            run(test, host(test, computer), """
                    const door = accessControllers.named("front_door")
                    const panel = controlPanels.named("panel")
                    on door.accessDenied(player) {
                        print("denied " + str(player.result))
                    }
                    on door.accessGranted(player) {
                        print("granted")
                    }
                    on panel.buttonPressed(button) {
                        if button.id is Button.TWO { print("two " + button.label) }
                    }
                    on panel.buttonThree(player) {
                        print("three")
                    }
                    """);
        }).thenIdle(25).thenExecute(() -> {
            AdvancedApis.attempt(runtime, stranger, entity(test, door));
            test.assertFalse(runtime.pulses().open(entity(test, door)), "A stranger opened the door");
            AdvancedApis.attempt(runtime, friend, entity(test, door));
            test.assertTrue(runtime.pulses().open(entity(test, door)), "A listed player was refused");
            test.assertTrue(entity(test, door).recent().size() == 2, "Attempts were not remembered");
            entity(test, panel).label(2, "Open Gate");
            try {
                AdvancedApis.press(runtime, stranger, entity(test, panel), 2);
                test.fail("A stranger pressed a restricted panel");
            } catch (IllegalArgumentException expected) {
                // Restricted by default.
            }
            AdvancedApis.press(runtime, owner, entity(test, panel), 2);
            entity(test, panel).publicUse(true);
            AdvancedApis.press(runtime, stranger, entity(test, panel), 3);
        }).thenWaitUntil(() -> {
            test.assertTrue(test.getBlockState(door.east()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                    "The door output did not power the lamp");
            var lines = console(test, computer);
            for (String expected : List.of("denied Access.DENIED", "granted", "two Open Gate", "three")) {
                test.assertTrue(lines.contains(expected), "Missing \"" + expected + "\" in " + lines);
            }
        }).thenWaitUntil(() -> test.assertFalse(runtime.pulses().open(entity(test, door)), "The door did not close"))
                .thenSucceed();
    }

    /** Bridges pair over long distances after owner checks, never across networks, and carry events between areas. */
    @GameTest(maxTicks = 400, setupTicks = 20)
    public void wirelessBridgesPairWithinRangeOnly(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        // Far from the grid of test structures, in chunks this test keeps loaded.
        var homeChunk = chunk(test.absolutePos(new BlockPos(-3000, 1, -1200)));
        BlockPos base = new BlockPos((homeChunk.x() << 4) + 4, test.absolutePos(BlockPos.ZERO).getY() + 1, (homeChunk.z() << 4) + 4);
        var computer = base;
        var near = base.east(2);
        var foreign = base.south(4);
        var foreignBridge = base.south(4).east(2);
        // 70 blocks is beyond ordinary links (32) but within bridge range (256); 330 is beyond both.
        var far = near.north(70);
        var detector = far.east(2);
        var tooFar = near.north(330);
        var chunks = List.of(homeChunk, chunk(far), chunk(tooFar));
        for (var loaded : chunks) {
            level.setChunkForced(loaded.x(), loaded.z(), true);
        }
        var owner = player(test, new BlockPos(1, 1, 3));
        var stranger = player(test, new BlockPos(1, 1, 3));
        var visitor = new AtomicReference<Entity>();
        test.runBeforeTestEnd(() -> {
            for (var loaded : chunks) {
                level.setChunkForced(loaded.x(), loaded.z(), false);
            }
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(stranger, Entity.RemovalReason.DISCARDED);
            if (visitor.get() != null) {
                visitor.get().discard();
            }
        });
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(chunks.stream().allMatch(loaded ->
                        level.getChunkSource().getChunkNow(loaded.x(), loaded.z()) != null), "Chunks loading"))
                .thenExecute(() -> {
                    for (var pos : List.of(computer, foreign)) {
                        level.setBlockAndUpdate(pos, ComputerBlocks.block().defaultBlockState());
                    }
                    for (var pos : List.of(near, far, tooFar, foreignBridge)) {
                        level.setBlockAndUpdate(pos, PeripheralBlocks.block(Kind.WIRELESS_BRIDGE).defaultBlockState());
                    }
                    level.setBlockAndUpdate(detector, PeripheralBlocks.block(Kind.ENTITY_DETECTOR).defaultBlockState());
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    for (var pos : List.of(computer, near, far, detector, tooFar)) {
                        level.getBlockState(pos).getBlock().setPlacedBy(level, pos, level.getBlockState(pos), owner, ItemStack.EMPTY);
                    }
                    for (var pos : List.of(foreign, foreignBridge)) {
                        level.getBlockState(pos).getBlock().setPlacedBy(level, pos, level.getBlockState(pos), stranger, ItemStack.EMPTY);
                    }
                    var root = runtime.requireDevice(level, computer).id();
                    UUID nearId = id(level, near);
                    UUID farId = id(level, far);
                    runtime.link(root, nearId, false);
                    var nearNode = runtime.directory().node(nearId);
                    var candidates = AdvancedApis.candidates(runtime, owner, nearNode);
                    test.assertTrue(candidates.stream().anyMatch(node -> node.id().equals(farId)), "The far bridge was not offered");
                    test.assertFalse(candidates.stream().anyMatch(node -> node.id().equals(id(level, tooFar))),
                            "An out-of-range bridge was offered");
                    try {
                        AdvancedApis.pair(runtime, stranger, nearNode, farId);
                        test.fail("A stranger paired the owner's bridges");
                    } catch (IllegalArgumentException expected) {
                        // Pairing needs the owner of both bridges.
                    }
                    var foreignRoot = runtime.requireDevice(level, foreign).id();
                    runtime.link(foreignRoot, id(level, foreignBridge), false);
                    try {
                        AdvancedApis.pair(runtime, owner, nearNode, id(level, foreignBridge));
                        test.fail("Two networks were merged");
                    } catch (IllegalArgumentException expected) {
                        // Another player's network.
                    }
                    String paired = AdvancedApis.pair(runtime, owner, nearNode, farId);
                    test.assertTrue(paired.contains("70 blocks"), paired);
                    try {
                        AdvancedApis.pair(runtime, owner, runtime.directory().node(farId), id(level, tooFar));
                        test.fail("A bridge paired twice");
                    } catch (IllegalArgumentException expected) {
                        // One partner per bridge.
                    }
                    var detectorEntity = (PeripheralEntity) level.getBlockEntity(detector);
                    detectorEntity.configureRadius(3);
                    runtime.link(farId, detectorEntity.identity(), false);
                    runtime.alias(runtime.directory().node(detectorEntity.identity()), "outpost");
                    var status = AdvancedApis.bridgeStatus(runtime, runtime.directory().node(nearId));
                    test.assertTrue(status.get("connected").equals(new Bool(true)), "Bridge status");
                    var host = (ProgramHost) level.getBlockEntity(computer);
                    run(test, host, """
                            const outpost = detectors.named("outpost")
                            on outpost.entityEnter(entity) {
                                print("outpost " + entity.name)
                            }
                            """);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    var pig = EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    pig.snapTo(Vec3.atBottomCenterOf(detector.east(1)));
                    pig.setNoAi(true);
                    pig.setPersistenceRequired();
                    level.addFreshEntity(pig);
                    visitor.set(pig);
                })
                .thenWaitUntil(() -> {
                    var lines = ComputerLifecycle.console(level, (ProgramHost) level.getBlockEntity(computer), 16);
                    test.assertTrue(lines.contains("outpost Pig"), "The event did not cross the bridge: " + lines);
                })
                .thenExecute(() -> {
                    var nearNode = runtime.directory().node(id(level, near));
                    AdvancedApis.unpair(runtime, owner, nearNode);
                    test.assertTrue(runtime.directory().bridgePeer(nearNode.id()) == null, "Unpairing failed");
                    test.assertFalse(runtime.available(runtime.directory().node(id(level, detector))),
                            "The outpost stayed reachable after unpairing");
                })
                .thenSucceed();
    }

    /**
     * The autonomous scenario: devices cooperate through native and custom events while their computer is out of reach,
     * and the computer later reads what they recorded. The computer stands 100 blocks away behind a bridge pair that is
     * unpaired during the scenario and paired again afterwards.
     */
    @GameTest(maxTicks = 1200, setupTicks = 20)
    public void autonomousSecurityRunsWhileTheComputerIsUnreachable(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var runtime = PeripheralLifecycle.runtime(server);
        // Offsets unlike other tests' far chunks, so no other test keeps these chunks loaded.
        var homeChunk = chunk(test.absolutePos(new BlockPos(-2200, 1, -2700)));
        var siteChunk = chunk(test.absolutePos(new BlockPos(-2200, 1, -2800)));
        BlockPos home = new BlockPos((homeChunk.x() << 4) + 8, test.absolutePos(BlockPos.ZERO).getY() + 1, (homeChunk.z() << 4) + 8);
        BlockPos site = new BlockPos((siteChunk.x() << 4) + 8, home.getY(), (siteChunk.z() << 4) + 8);
        var positions = new java.util.LinkedHashMap<String, BlockPos>();
        positions.put("computer", home);
        positions.put("home_link", home.east(2));
        positions.put("site_link", site);
        positions.put("gate", site.east(3));
        positions.put("siren", site.west(3));
        positions.put("door", site.north(3));
        positions.put("board", site.south(3));
        positions.put("records", site.north(3).east(3));
        positions.put("panel", site.south(3).west(3));
        var owner = player(test, new BlockPos(1, 1, 1));
        var husk = new AtomicReference<Entity>();
        var difficulty = level.getDifficulty();
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        level.setChunkForced(homeChunk.x(), homeChunk.z(), true);
        level.setChunkForced(siteChunk.x(), siteChunk.z(), true);
        test.runBeforeTestEnd(() -> {
            level.setChunkForced(homeChunk.x(), homeChunk.z(), false);
            level.setChunkForced(siteChunk.x(), siteChunk.z(), false);
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            if (husk.get() != null) {
                husk.get().discard();
            }
            server.setDifficulty(difficulty, true);
        });
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(homeChunk.x(), homeChunk.z()) != null
                        && level.getChunkSource().getChunkNow(siteChunk.x(), siteChunk.z()) != null, "Chunks loading"))
                .thenExecute(() -> {
                    var kinds = java.util.Map.of("home_link", Kind.WIRELESS_BRIDGE, "site_link", Kind.WIRELESS_BRIDGE,
                            "gate", Kind.ENTITY_DETECTOR, "siren", Kind.ALARM, "door", Kind.REDSTONE_CONTROLLER,
                            "board", Kind.DISPLAY, "records", Kind.STORAGE_SERVER, "panel", Kind.CONTROL_PANEL);
                    for (var entry : positions.entrySet()) {
                        level.setBlockAndUpdate(entry.getValue(), entry.getKey().equals("computer")
                                ? ComputerBlocks.block().defaultBlockState()
                                : PeripheralBlocks.block(kinds.get(entry.getKey())).defaultBlockState());
                    }
                    level.setBlockAndUpdate(positions.get("door").north(), Blocks.REDSTONE_LAMP.defaultBlockState());
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    for (var at : positions.values()) {
                        level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
                    }
                    var root = runtime.requireDevice(level, home).id();
                    UUID homeLink = id(level, positions.get("home_link"));
                    UUID siteLink = id(level, positions.get("site_link"));
                    runtime.link(root, homeLink, false);
                    AdvancedApis.pair(runtime, owner, runtime.directory().node(homeLink), siteLink);
                    for (String device : List.of("gate", "siren", "door", "board", "records", "panel")) {
                        UUID id = id(level, positions.get(device));
                        runtime.link(siteLink, id, false);
                        runtime.alias(runtime.directory().node(id), device);
                    }
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    // The computer becomes unavailable: its bridge loses its partner, so nothing at the site can reach it.
                    AdvancedApis.unpair(runtime, owner, runtime.directory().node(id(level, positions.get("home_link"))));
                    test.assertFalse(runtime.available(runtime.directory().node(id(level, positions.get("gate")))),
                            "The computer still reaches the site");
                })
                .thenExecute(() -> {
                    var siren = (PeripheralEntity) level.getBlockEntity(positions.get("siren"));
                    var door = (PeripheralEntity) level.getBlockEntity(positions.get("door"));
                    var board = (PeripheralEntity) level.getBlockEntity(positions.get("board"));
                    for (var device : List.of(siren, door, board)) {
                        device.insertDrive(new ItemStack(Drives.DRIVE));
                    }
                    install(test, siren, "autonomous-alarm", owner);
                    // The door program now expects a north-side lamp instead of a south-side door.
                    install(test, door, "autonomous-door", owner);
                    install(test, board, "autonomous-board", owner);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    var spawned = EntityTypes.HUSK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    spawned.snapTo(Vec3.atBottomCenterOf(positions.get("gate").east(2)));
                    spawned.setNoAi(true);
                    spawned.setPersistenceRequired();
                    level.addFreshEntity(spawned);
                    husk.set(spawned);
                })
                .thenWaitUntil(() -> {
                    var siren = (PeripheralEntity) level.getBlockEntity(positions.get("siren"));
                    test.assertTrue(runtime.alarms().active(siren), "The autonomous alarm did not sound");
                    test.assertTrue(level.getBlockState(positions.get("door").north())
                            .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "The door controller did not lock");
                    var board = (PeripheralEntity) level.getBlockEntity(positions.get("board"));
                    test.assertTrue(String.join("\n", board.display()).contains("Last alert: Husk"),
                            "The display did not update: " + board.display());
                    var records = (PeripheralEntity) level.getBlockEntity(positions.get("records"));
                    test.assertTrue(records.served().has("alerts"), "Nothing was recorded on the storage server");
                })
                .thenExecute(() -> {
                    test.assertFalse(runtime.available(runtime.directory().node(id(level, positions.get("records")))),
                            "The site was reachable during the scenario");
                    AdvancedApis.pair(runtime, owner, runtime.directory().node(id(level, positions.get("home_link"))),
                            id(level, positions.get("site_link")));
                })
                .thenWaitUntil(() -> test.assertTrue(runtime.available(runtime.directory().node(id(level, positions.get("records")))),
                        "The computer did not reconnect"))
                .thenExecute(() -> {
                    var computer = (ProgramHost) level.getBlockEntity(home);
                    run(test, computer, example(test, "security-report"));
                })
                .thenWaitUntil(() -> {
                    var lines = ComputerLifecycle.console(level, (ProgramHost) level.getBlockEntity(home), 8);
                    test.assertTrue(lines.stream().anyMatch(line -> line.startsWith("1 alerts while the computer was away")),
                            "The computer did not read the records: " + lines);
                })
                .thenSucceed();
    }

    /** A computer with a running program never keeps its chunk loaded. */
    @GameTest(maxTicks = 900, setupTicks = 20)
    public void computerChunkUnloads(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var chunk = chunk(test.absolutePos(new BlockPos(-2500, 1, -2500)));
        BlockPos at = new BlockPos((chunk.x() << 4) + 8, test.absolutePos(BlockPos.ZERO).getY() + 1, (chunk.z() << 4) + 8);
        var entity = new AtomicReference<net.minecraft.world.level.block.entity.BlockEntity>();
        level.setChunkForced(chunk.x(), chunk.z(), true);
        test.runBeforeTestEnd(() -> level.setChunkForced(chunk.x(), chunk.z(), false));
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null, "loading"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(at, ComputerBlocks.block().defaultBlockState());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    entity.set(level.getBlockEntity(at));
                    test.assertTrue(ComputerLifecycle.active(server, (ProgramHost) entity.get()), "computer not active");
                    server.saveEverything(true, true, true);
                    level.setChunkForced(chunk.x(), chunk.z(), false);
                })
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null
                        && !ComputerLifecycle.active(server, (ProgramHost) entity.get()), "The computer did not unload: active="
                        + ComputerLifecycle.active(server, (ProgramHost) entity.get()) + ", removed=" + entity.get().isRemoved()
                        + ", " + ChunkDiagnostics.describe(level, chunk.x(), chunk.z())))
                .thenSucceed();
    }

    /** A wireless bridge paired with a loaded partner never keeps its own chunk loaded. */
    @GameTest(maxTicks = 900, setupTicks = 20)
    public void pairedBridgeDoesNotForceLoad(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var runtime = PeripheralLifecycle.runtime(server);
        var chunk = chunk(test.absolutePos(new BlockPos(-1700, 1, -3100)));
        var other = chunk(test.absolutePos(new BlockPos(-1700, 1, -3200)));
        BlockPos at = new BlockPos((chunk.x() << 4) + 8, test.absolutePos(BlockPos.ZERO).getY() + 1, (chunk.z() << 4) + 8);
        BlockPos far = new BlockPos((other.x() << 4) + 8, at.getY(), (other.z() << 4) + 8);
        var entity = new AtomicReference<net.minecraft.world.level.block.entity.BlockEntity>();
        var owner = player(test, new BlockPos(1, 1, 1));
        level.setChunkForced(chunk.x(), chunk.z(), true);
        level.setChunkForced(other.x(), other.z(), true);
        test.runBeforeTestEnd(() -> {
            level.setChunkForced(chunk.x(), chunk.z(), false);
            level.setChunkForced(other.x(), other.z(), false);
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        });
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null
                        && level.getChunkSource().getChunkNow(other.x(), other.z()) != null, "loading"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(at, ComputerBlocks.block().defaultBlockState());
                    level.setBlockAndUpdate(at.east(2), PeripheralBlocks.block(Kind.WIRELESS_BRIDGE).defaultBlockState());
                    level.setBlockAndUpdate(far, PeripheralBlocks.block(Kind.WIRELESS_BRIDGE).defaultBlockState());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    for (var pos : List.of(at, at.east(2), far)) {
                        level.getBlockState(pos).getBlock().setPlacedBy(level, pos, level.getBlockState(pos), owner, ItemStack.EMPTY);
                    }
                    var root = runtime.requireDevice(level, at).id();
                    runtime.link(root, id(level, at.east(2)), false);
                    AdvancedApis.pair(runtime, owner, runtime.directory().node(id(level, at.east(2))), id(level, far));
                    entity.set(level.getBlockEntity(at));
                    server.saveEverything(true, true, true);
                    level.setChunkForced(chunk.x(), chunk.z(), false);
                })
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null
                        && !ComputerLifecycle.active(server, (ProgramHost) entity.get()), "A paired bridge kept its chunk loaded "
                        + (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) + " removed " + entity.get().isRemoved()))
                .thenSucceed();
    }

    /** The drive prune command previews without deleting until confirmed. */
    @GameTest(maxTicks = 20, setupTicks = 20)
    public void drivePruningPreviewsBeforeDeleting(GameTestHelper test) {
        var store = dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(test.getLevel().getServer());
        UUID id = UUID.randomUUID();
        var record = store.open(id);
        record.program("on startup { }", false);
        // Only this record is old enough to prune. Pruning everything unused would also delete the drives of tests
        // that run at the same time while their devices' chunks unload, which once made another test fail.
        long now = test.getLevel().getServer().overworld().getGameTime();
        record.touch(now - 10_000_000L);
        long age = 5_000_000L;
        int before = store.count();
        test.assertTrue(store.prune(age, false) == 1 && store.count() == before, "A preview deleted records");
        test.assertTrue(store.find(id) != null, "The preview removed a record");
        test.assertTrue(store.prune(age, true) == 1, "Confirmed pruning deleted other records");
        test.assertTrue(store.find(id) == null, "Confirmed pruning kept an unused record");
        test.succeed();
    }

    // Helpers

    /** Waits until every FilaSignal block placed by the test has a loaded block entity attached to the network. */
    private static void loaded(GameTestHelper test) {
        var runtime = PeripheralLifecycle.runtime(test.getLevel().getServer());
        var level = test.getLevel();
        test.forEveryBlockInStructure(pos -> {
            var entity = level.getBlockEntity(pos);
            if (entity instanceof PeripheralEntity || entity instanceof ProgramHost) {
                try {
                    runtime.requireDevice(level, pos);
                } catch (IllegalArgumentException pending) {
                    if (entity instanceof PeripheralEntity) {
                        throw test.assertionException("A device is not attached yet");
                    }
                }
            }
        });
    }

    private static void install(GameTestHelper test, PeripheralEntity device, String example, ServerPlayer owner) {
        try {
            String source = example(test, example);
            if (example.equals("autonomous-door")) {
                source = source.replace("Direction.SOUTH", "Direction.NORTH");
            }
            ComputerLifecycle.program(test.getLevel(), device, source, false, true);
            ComputerLifecycle.control(test.getLevel(), device, true, "player owner", owner.getUUID());
        } catch (IOException error) {
            throw test.assertionException(example + ": " + error.getMessage());
        }
    }

    private record Chunk(int x, int z) {}

    private static Chunk chunk(BlockPos pos) {
        return new Chunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static UUID id(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PeripheralEntity device ? device.identity() : null;
    }

    static String example(GameTestHelper test, String name) {
        try {
            return java.nio.file.Files.readString(HardwareGameTests.examples(test).resolve(name + ".fsig"));
        } catch (IOException error) {
            throw test.assertionException(name + ": " + error.getMessage());
        }
    }

    private static void run(GameTestHelper test, ProgramHost host, String source) {
        try {
            // A new computer holds a default program whose globals do not fit, so tests install with fresh globals.
            ComputerLifecycle.program(test.getLevel(), host, source, true, true);
            ComputerLifecycle.control(test.getLevel(), host, true, "test");
        } catch (IOException | IllegalArgumentException error) {
            throw test.assertionException("Program rejected: " + error.getMessage());
        }
    }

    private static List<String> console(GameTestHelper test, BlockPos computer) {
        return ComputerLifecycle.console(test.getLevel(), host(test, computer), 32);
    }

    private static ProgramHost host(GameTestHelper test, BlockPos relative) {
        return (ProgramHost) test.getLevel().getBlockEntity(test.absolutePos(relative));
    }

    private static PeripheralEntity entity(GameTestHelper test, BlockPos relative) {
        return (PeripheralEntity) test.getLevel().getBlockEntity(test.absolutePos(relative));
    }

    private static void name(PeripheralRuntime runtime, GameTestHelper test, BlockPos relative, String alias) {
        runtime.alias(runtime.directory().node(entity(test, relative).identity()), alias);
    }

    private static void place(GameTestHelper test, ServerPlayer owner, BlockPos... positions) {
        var level = test.getLevel();
        for (var pos : positions) {
            var at = test.absolutePos(pos);
            level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
        }
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos relative) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(Vec3.atCenterOf(test.absolutePos(relative)).add(0, 1, 0));
        return player;
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
