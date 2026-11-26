package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dedicated hardware on the server thread: discovery, typed APIs, authorization, persistence and a full scenario. */
public final class HardwareGameTests {
    private static final HostRegistry.Context CONTEXT = new HostRegistry.Context() {
        @Override public void charge(long operations) { }
        @Override public void log(String line) { }
        @Override public void print(Value value) { }
    };

    /** A computer, a repeater and one of every hardware leaf, owned by a mock player and linked through the repeater. */
    private static final class Rig {
        static final BlockPos COMPUTER = new BlockPos(1, 1, 1);
        static final BlockPos REPEATER = new BlockPos(2, 1, 1);
        static final Map<Kind, BlockPos> LEAVES = Map.of(
                Kind.ENTITY_DETECTOR, new BlockPos(1, 1, 6),
                Kind.WEATHER_STATION, new BlockPos(3, 1, 1),
                Kind.REDSTONE_CONTROLLER, new BlockPos(4, 1, 1),
                Kind.ENVIRONMENT_SENSOR, new BlockPos(5, 1, 1),
                Kind.INVENTORY_MODULE, new BlockPos(6, 1, 4),
                Kind.DISPLAY, new BlockPos(2, 2, 1),
                Kind.ALARM, new BlockPos(3, 2, 1));
        static final Map<Kind, String> NAMES = Map.of(Kind.ENTITY_DETECTOR, "gate", Kind.WEATHER_STATION, "roof",
                Kind.REDSTONE_CONTROLLER, "door", Kind.ENVIRONMENT_SENSOR, "probe", Kind.INVENTORY_MODULE, "store",
                Kind.DISPLAY, "board", Kind.ALARM, "siren");

        final GameTestHelper test;
        final PeripheralRuntime runtime;
        final ServerPlayer owner;
        final Map<Kind, NetworkDirectory.Node> nodes = new EnumMap<>(Kind.class);
        NetworkDirectory.Node computer;
        NetworkDirectory.Node repeater;

        Rig(GameTestHelper test) {
            this.test = test;
            runtime = PeripheralLifecycle.runtime(test.getLevel().getServer());
            test.setBlock(COMPUTER, ComputerBlocks.block());
            test.setBlock(REPEATER, PeripheralBlocks.block(Kind.REPEATER));
            LEAVES.forEach((kind, pos) -> test.setBlock(pos, PeripheralBlocks.block(kind)));
            owner = player(test, new BlockPos(1, 1, 1).offset(0, 0, -30));
        }

        /** Activations are rate-limited server-wide, so wait for the computer and every device to attach. */
        void awaitComputer() {
            try {
                runtime.requireDevice(test.getLevel(), test.absolutePos(COMPUTER));
                runtime.requireDevice(test.getLevel(), test.absolutePos(REPEATER));
                LEAVES.values().forEach(pos -> runtime.requireDevice(test.getLevel(), test.absolutePos(pos)));
            } catch (IllegalArgumentException pending) {
                throw test.assertionException("Devices have not attached yet");
            }
        }

        /** Places ownership, links and names once every block entity has attached. */
        void connect() {
            var level = test.getLevel();
            place(COMPUTER);
            place(REPEATER);
            LEAVES.values().forEach(this::place);
            computer = runtime.requireDevice(level, test.absolutePos(COMPUTER));
            repeater = runtime.requireDevice(level, test.absolutePos(REPEATER));
            var tool = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(PeripheralBlocks.id("linking_tool")));
            // The first link uses the real linking tool and its ACL checks. The rest use the same runtime path.
            NetworkGameTests.click(test, owner, tool, COMPUTER);
            NetworkGameTests.click(test, owner, tool, REPEATER);
            runtime.alias(repeater, "router");
            for (var entry : LEAVES.entrySet()) {
                var node = runtime.requireDevice(level, test.absolutePos(entry.getValue()));
                runtime.link(repeater.id(), node.id(), false);
                runtime.alias(runtime.directory().node(node.id()), NAMES.get(entry.getKey()));
                nodes.put(entry.getKey(), runtime.directory().node(node.id()));
            }
        }

        private void place(BlockPos relative) {
            var pos = test.absolutePos(relative);
            var state = test.getLevel().getBlockState(pos);
            state.getBlock().setPlacedBy(test.getLevel(), pos, state, owner, ItemStack.EMPTY);
        }

        HostRegistry registry() {
            return DeviceHosts.registry(runtime, computer.id());
        }

        PeripheralEntity entity(Kind kind) {
            return (PeripheralEntity) runtime.live(nodes.get(kind).id());
        }
    }

    @GameTest(maxTicks = 80)
    public void hardwareJoinsNetworksAndReportsTypedErrors(GameTestHelper test) {
        var rig = new Rig(test);
        test.runBeforeTestEnd(() -> remove(test, rig.owner));
        test.startSequence().thenWaitUntil(rig::awaitComputer).thenExecute(() -> {
            rig.connect();
            var hosts = rig.registry();
            var members = (ListValue) ok(test, call(hosts, "devices.list"));
            test.assertTrue(members.size() == 8, "Expected the repeater and seven devices, got " + members.size());
            var kinds = new ArrayList<String>();
            for (int i = 0; i < members.size(); i++) {
                var member = (DictValue) members.get(i);
                kinds.add(((Text) member.get("kind")).value());
                test.assertTrue(member.get("connected").equals(new Bool(true)), "Member not connected: " + kinds.getLast());
            }
            test.assertTrue(kinds.containsAll(List.of("repeater", "entity_detector", "weather_station", "redstone_controller",
                    "inventory_module", "environment_sensor", "display", "alarm")), "Missing kinds " + kinds);
            var gate = (HostHandle) ok(test, call(hosts, "detectors.from", new Text("gate")));
            test.assertTrue(property(hosts, gate, "kind").equals(new Text("entity_detector")), "Wrong kind property");
            test.assertTrue(property(hosts, gate, "name").equals(new Text("gate")), "Wrong name property");
            test.assertTrue(property(hosts, gate, "connected").equals(new Bool(true)), "Detector disconnected");
            expectError(test, call(hosts, "displays.from", new Text("gate")), "device.type");
            expectError(test, call(hosts, "detectors.from", new Text("missing")), "device.unknown");
            var device = (HostHandle) ok(test, call(hosts, "devices.from", new Text("siren")));
            test.assertTrue(((ListValue) property(hosts, device, "capabilities")).snapshot()
                    .equals(List.of(new Text("network"), new Text("hardware"), new Text("execution"))), "Alarm capabilities");
            var router = (HostHandle) ok(test, call(hosts, "repeaters.from", new Text("router")));
            test.assertTrue(((ListValue) ok(test, member(hosts, router, "links"))).size() == 8, "Repeater link count");

            // Another computer's network cannot address these devices even with a known name or handle.
            test.setBlock(new BlockPos(7, 1, 7), ComputerBlocks.block());
        }).thenIdle(3).thenExecute(() -> {
            var stranger = rig.runtime.requireDevice(test.getLevel(), test.absolutePos(new BlockPos(7, 1, 7)));
            var foreign = DeviceHosts.registry(rig.runtime, stranger.id());
            expectError(test, call(foreign, "detectors.from", new Text("gate")), "device.unknown");
            var stolen = new HostHandle("EntityDetector", stranger.id(), rig.nodes.get(Kind.ENTITY_DETECTOR).id());
            expectError(test, member(foreign, stolen, "count", new Text("all")), "device.unknown");

            // Unlinking leaves the device known but unavailable. Removing it makes old handles unknown.
            var hosts = rig.registry();
            var roof = (HostHandle) ok(test, call(hosts, "weather.from", new Text("roof")));
            rig.runtime.link(rig.repeater.id(), rig.nodes.get(Kind.WEATHER_STATION).id(), true);
            expectError(test, call(hosts, "weather.from", new Text("roof")), "device.unavailable");
            expectError(test, member(hosts, roof, "raining"), "device.unavailable");
            var board = (HostHandle) ok(test, call(hosts, "displays.from", new Text("board")));
            test.setBlock(Rig.LEAVES.get(Kind.DISPLAY), Blocks.AIR);
            expectError(test, member(hosts, board, "text"), "device.unknown");
            test.assertTrue(((ListValue) ok(test, call(hosts, "devices.list"))).size() == 7, "Removed device still listed");
        }).thenSucceed();
    }

    @GameTest(maxTicks = 160)
    public void hardwareOperationsReadAndControlTheWorld(GameTestHelper test) {
        var rig = new Rig(test);
        var level = test.getLevel();
        var lamp = Rig.LEAVES.get(Kind.REDSTONE_CONTROLLER).south();
        var module = Rig.LEAVES.get(Kind.INVENTORY_MODULE);
        test.setBlock(lamp, Blocks.REDSTONE_LAMP);
        test.setBlock(module.north(), Blocks.BARREL);
        test.setBlock(module.south(), Blocks.BARREL);
        var source = (Container) level.getBlockEntity(test.absolutePos(module.north()));
        var destination = (Container) level.getBlockEntity(test.absolutePos(module.south()));
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 20));
        destination.setItem(0, new ItemStack(Items.IRON_INGOT, 60));
        for (int i = 1; i < destination.getContainerSize(); i++) {
            destination.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        var detector = Rig.LEAVES.get(Kind.ENTITY_DETECTOR);
        var spawned = new ArrayList<Entity>();
        for (int i = 0; i < 3; i++) {
            spawned.add(test.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.PIG, detector.offset(2, 0, -1)));
        }
        for (int i = 0; i < 40; i++) {
            spawned.add(test.spawn(net.minecraft.world.entity.EntityTypes.ARMOR_STAND, detector.offset(-1, 0, 0)));
        }
        test.runBeforeTestEnd(() -> {
            spawned.forEach(Entity::discard);
            remove(test, rig.owner);
            level.getServer().setWeatherParameters(6000, 0, false, false);
        });
        var held = new java.util.concurrent.atomic.AtomicReference<HostRegistry>();
        test.startSequence().thenWaitUntil(rig::awaitComputer).thenExecute(() -> {
            rig.connect();
            level.getServer().setWeatherParameters(0, 6000, true, false);
            rig.runtime.inventory(rig.nodes.get(Kind.INVENTORY_MODULE), Direction.NORTH, "ore");
            rig.runtime.inventory(rig.nodes.get(Kind.INVENTORY_MODULE), Direction.SOUTH, "vault");
            var hosts = rig.registry();
            held.set(hosts);

            // Display: bounded text, line updates and clearing.
            var board = (HostHandle) ok(test, call(hosts, "displays.from", new Text("board")));
            expectError(test, member(hosts, board, "setText", new Text("1\n2\n3\n4\n5\n6\n7\n8\n9")), "argument.range");
            expectError(test, member(hosts, board, "setText", new Text("x".repeat(49))), "argument.range");
            ok(test, member(hosts, board, "setText", new Text("Iron\r\nstock")));
            ok(test, member(hosts, board, "setLine", new Num(3), new Text("low")));
            test.assertTrue(rig.entity(Kind.DISPLAY).display().equals(List.of("Iron", "stock", "", "low")), "Display lines "
                    + rig.entity(Kind.DISPLAY).display());
            test.assertTrue(ok(test, member(hosts, board, "text")).equals(new Text("Iron\nstock\n\nlow")), "Display text");

            // Redstone controller: faces start as inputs and only output faces can be driven.
            var door = (HostHandle) ok(test, call(hosts, "redstone.controller", new Text("door")));
            expectError(test, member(hosts, door, "set", new Text("south"), new Num(15)), "port.mode");
            expectError(test, member(hosts, door, "set", new Text("sideways"), new Num(15)), "argument.invalid");
            ok(test, member(hosts, door, "setMode", new Text("south"), new Text("output")));
            expectError(test, member(hosts, door, "set", new Text("south"), new Num(16)), "argument.range");
            ok(test, member(hosts, door, "set", new Text("south"), new Num(15)));
            test.assertTrue(map(ok(test, member(hosts, door, "outputs"))).equals(Map.of("south", new Num(15))),
                    "Controller outputs");

            // Alarm: validated settings, activation and the visible lit state.
            var siren = (HostHandle) ok(test, call(hosts, "alarms.from", new Text("siren")));
            expectError(test, member(hosts, siren, "configure", new Text("klaxon"), new Num(50), new Num(5)), "argument.range");
            ok(test, member(hosts, siren, "configure", new Text("pling"), new Num(40), new Num(2)));
            ok(test, member(hosts, siren, "activate"));
            test.assertTrue(ok(test, member(hosts, siren, "active")).equals(new Bool(true)), "Alarm inactive");

            // Inventory module: an approved face resolves to the shared Inventory object. Transfers respect capacity.
            var store = (HostHandle) ok(test, call(hosts, "inventory.module", new Text("store")));
            test.assertTrue(((ListValue) ok(test, member(hosts, store, "inventories"))).size() == 2, "Approved faces");
            expectError(test, member(hosts, store, "inventory", new Text("east")), "inventory.unknown");
            var ore = (HostHandle) ok(test, member(hosts, store, "inventory", new Text("north")));
            var vault = (HostHandle) ok(test, member(hosts, store, "inventory", new Text("south")));
            test.assertTrue(ok(test, inventory(hosts, ore, "contains", new Text("minecraft:iron_ingot"))).equals(new Bool(true)), "contains");
            test.assertTrue(ok(test, inventory(hosts, ore, "occupied")).equals(new Num(1)), "occupied");
            test.assertTrue(map(ok(test, inventory(hosts, ore, "items"))).equals(Map.of("minecraft:iron_ingot", new Num(20))), "items");
            var moved = ok(test, inventory(hosts, ore, "moveTo", vault, new Text("minecraft:iron_ingot"), new Num(20)));
            test.assertTrue(moved.equals(new Num(4)), "Partial transfer moved " + moved);
            test.assertTrue(source.getItem(0).getCount() == 16 && destination.getItem(0).getCount() == 64,
                    "Items were lost or duplicated");
            test.assertTrue(((ListValue) ok(test, member(hosts, store, "adjacent"))).size() == 6, "Adjacent faces");

            // Entity detector: categories, type filters, nearest and bounded results.
            var gate = (HostHandle) ok(test, call(hosts, "detectors.from", new Text("gate")));
            test.assertTrue(ok(test, member(hosts, gate, "count", new Text("passive"))).equals(new Num(3)), "Passive count");
            test.assertTrue(ok(test, member(hosts, gate, "count", new Text("minecraft:armor_stand"))).equals(new Num(40)), "Type count");
            test.assertTrue(((ListValue) ok(test, member(hosts, gate, "scan", new Text("other")))).size() == EntityDetection.MAX_RESULTS,
                    "Scan results are not bounded");
            var nearest = (DictValue) ok(test, member(hosts, gate, "nearest", new Text("passive")));
            test.assertTrue(nearest.get("type").equals(new Text("minecraft:pig")) && nearest.get("category").equals(new Text("passive")),
                    "Nearest passive entity");
            test.assertTrue(ok(test, member(hosts, gate, "nearest", new Text("player"))) == Nil.INSTANCE, "Phantom player");
            expectError(test, member(hosts, gate, "scan", new Text("monsters")), "argument.invalid");

            // Environment sensor readings stay within Minecraft's ranges.
            var probe = (HostHandle) ok(test, call(hosts, "environment.from", new Text("probe")));
            var here = (DictValue) ok(test, member(hosts, probe, "read"));
            double light = ((Num) here.get("light")).value();
            test.assertTrue(light >= 0 && light <= 15 && ((Text) here.get("biome")).value().contains(":"), "Environment " + here.keys());
        }).thenIdle(46).thenExecute(() -> {
            var hosts = held.get();
            test.assertTrue(test.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "Controller did not light the lamp");
            test.assertFalse(test.getBlockState(Rig.LEAVES.get(Kind.ALARM)).getValue(BlockStateProperties.LIT),
                    "Alarm did not stop after its two-second duration");
            var roof = (HostHandle) ok(test, call(hosts, "weather.from", new Text("roof")));
            test.assertTrue(ok(test, member(hosts, roof, "raining")).equals(new Bool(true)), "Weather station missed rain");
            var now = (DictValue) ok(test, member(hosts, roof, "read"));
            test.assertTrue(now.get("dimension").equals(new Text("minecraft:overworld"))
                    && now.get("hasWeather").equals(new Bool(true)), "Weather reading " + now.keys());
            double dayTime = ((Num) now.get("dayTime")).value();
            test.assertTrue(dayTime >= 0 && dayTime < 24_000, "Day time out of range");
            var door = (HostHandle) ok(test, call(hosts, "redstone.controller", new Text("door")));
            ok(test, member(hosts, door, "setMode", new Text("south"), new Text("input")));
        }).thenIdle(4).thenExecute(() -> test.assertFalse(test.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                "Leaving output mode did not turn the face off")).thenSucceed();
    }

    @GameTest(maxTicks = 60)
    public void hardwareConfigurationIsAuthorizedAndPersists(GameTestHelper test) {
        var rig = new Rig(test);
        var guest = player(test, Rig.LEAVES.get(Kind.ENTITY_DETECTOR));
        test.runBeforeTestEnd(() -> {
            remove(test, rig.owner);
            remove(test, guest);
        });
        test.startSequence().thenWaitUntil(rig::awaitComputer).thenExecute(() -> {
            rig.connect();
            var detector = rig.nodes.get(Kind.ENTITY_DETECTOR);
            var pos = test.absolutePos(Rig.LEAVES.get(Kind.ENTITY_DETECTOR));
            var denied = DeviceConfigNetwork.respond(guest, new DeviceConfigNetwork.Edit(pos, detector.id(),
                    DeviceConfigNetwork.RADIUS, "3", ""));
            test.assertTrue(denied.title().isEmpty() && denied.readings().isEmpty(), "Untrusted player configured the detector");
            // The owner is far away. Move them close, as the form requires.
            rig.owner.snapTo(Vec3.atCenterOf(pos).add(0, 1, 0));
            var accepted = DeviceConfigNetwork.respond(rig.owner, new DeviceConfigNetwork.Edit(pos, detector.id(),
                    DeviceConfigNetwork.RADIUS, "4", ""));
            test.assertTrue(accepted.title().equals("Entity Detector") && rig.entity(Kind.ENTITY_DETECTOR).range() == 4,
                    "Owner could not set the radius: " + accepted.status());
            var invalid = DeviceConfigNetwork.respond(rig.owner, new DeviceConfigNetwork.Edit(pos, detector.id(),
                    DeviceConfigNetwork.RADIUS, "99", ""));
            test.assertTrue(invalid.title().isEmpty() && rig.entity(Kind.ENTITY_DETECTOR).range() == 4, "Invalid radius accepted");
            var wrongKind = DeviceConfigNetwork.respond(rig.owner, new DeviceConfigNetwork.Edit(pos, detector.id(),
                    DeviceConfigNetwork.DISPLAY_TEXT, "hello", ""));
            test.assertTrue(wrongKind.title().isEmpty(), "A display setting applied to a detector");
            var forged = DeviceConfigNetwork.respond(rig.owner, new DeviceConfigNetwork.Edit(pos, UUID.randomUUID(),
                    DeviceConfigNetwork.RADIUS, "5", ""));
            test.assertTrue(forged.title().isEmpty(), "A forged device identity was accepted");

            var display = rig.entity(Kind.DISPLAY);
            var alarm = rig.entity(Kind.ALARM);
            var controller = rig.entity(Kind.REDSTONE_CONTROLLER);
            display.display(List.of("Persisted", "text"));
            alarm.alarm(new AlarmSettings("chime", 30, 7));
            controller.faceMode(test.getLevel(), Direction.EAST, true);
            for (var entity : List.of(rig.entity(Kind.ENTITY_DETECTOR), display, alarm, controller)) {
                var saved = entity.saveWithFullMetadata(test.getLevel().registryAccess());
                var copy = (PeripheralEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(entity.getBlockPos(),
                        entity.getBlockState(), saved, test.getLevel().registryAccess());
                test.assertTrue(copy != null && copy.identity().equals(entity.identity()) && copy.kind() == entity.kind(),
                        "Identity was not restored for " + entity.kind());
                test.assertTrue(copy.range() == entity.range() && copy.display().equals(entity.display())
                        && copy.alarm().equals(entity.alarm()) && copy.outputFaces().equals(entity.outputFaces()),
                        "Configuration was not restored for " + entity.kind());
            }
        }).thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void securityScenarioDrivesAlarmAndRedstoneFromAScript(GameTestHelper test) {
        var rig = new Rig(test);
        var level = test.getLevel();
        var lamp = Rig.LEAVES.get(Kind.REDSTONE_CONTROLLER).south();
        test.setBlock(lamp, Blocks.REDSTONE_LAMP);
        var difficulty = level.getDifficulty();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        var intruder = new java.util.concurrent.atomic.AtomicReference<Entity>();
        test.runBeforeTestEnd(() -> {
            if (intruder.get() != null) {
                intruder.get().discard();
            }
            remove(test, rig.owner);
            level.getServer().setDifficulty(difficulty, true);
        });
        test.startSequence().thenWaitUntil(rig::awaitComputer).thenExecute(() -> {
            rig.connect();
            try {
                ComputerLifecycle.program(level, test.absolutePos(Rig.COMPUTER), """
                        on startup {
                            door, err = redstone.controller("door")
                            if err is not nil { log.error(err); return }
                            door.setMode("south", "output")
                        }
                        on timer.every(5) {
                            gate, err = detectors.from("gate")
                            siren, alarmErr = alarms.from("siren")
                            door, doorErr = redstone.controller("door")
                            board, boardErr = displays.from("board")
                            if err is not nil or alarmErr is not nil or doorErr is not nil or boardErr is not nil { return }
                            hostiles, err = gate.count("hostile")
                            if err is not nil { log.error(err); return }
                            if hostiles > 0 {
                                siren.activate()
                                door.set("south", 15)
                                board.setText("INTRUDER")
                            } else {
                                siren.deactivate()
                                door.set("south", 0)
                                board.setText("All clear")
                            }
                        }
                        """, true);
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
        }).thenIdle(12).thenExecute(() -> {
            test.assertTrue(rig.entity(Kind.DISPLAY).display().equals(List.of("All clear")), "Script did not run");
            var husk = test.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.HUSK, Rig.LEAVES.get(Kind.ENTITY_DETECTOR).offset(2, 0, 0));
            husk.setPersistenceRequired();
            intruder.set(husk);
        }).thenWaitUntil(() -> {
            test.assertTrue(test.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "Intruder did not light the lamp");
            test.assertTrue(test.getBlockState(Rig.LEAVES.get(Kind.ALARM)).getValue(BlockStateProperties.LIT), "Alarm silent");
            test.assertTrue(rig.entity(Kind.DISPLAY).display().equals(List.of("INTRUDER")), "Display not updated");
        }).thenExecute(() -> {
            intruder.get().discard();
            intruder.set(null);
        }).thenWaitUntil(() -> {
            test.assertFalse(test.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "Lamp stayed on");
            test.assertFalse(rig.runtime.alarms().active(rig.entity(Kind.ALARM)), "Alarm stayed active");
            test.assertTrue(rig.entity(Kind.DISPLAY).display().equals(List.of("All clear")), "Display not reset");
        }).thenSucceed();
    }

    /** Every Minecraft example in the repository must resolve against the computer's actual host API. */
    @GameTest(maxTicks = 20)
    public void examplesResolveAgainstTheComputerApi(GameTestHelper test) {
        test.setBlock(Rig.COMPUTER, ComputerBlocks.block());
        var runtime = PeripheralLifecycle.runtime(test.getLevel().getServer());
        test.startSequence().thenWaitUntil(() -> {
            try {
                runtime.requireDevice(test.getLevel(), test.absolutePos(Rig.COMPUTER));
            } catch (IllegalArgumentException pending) {
                throw test.assertionException("The computer has not activated yet");
            }
        }).thenExecute(() -> {
            var computer = runtime.requireDevice(test.getLevel(), test.absolutePos(Rig.COMPUTER));
            var hosts = DeviceHosts.registry(runtime, computer.id());
            var examples = examples(test);
            var server = test.getLevel().getServer();
            // Device programs resolve against their kind's own registry, exactly as when they are installed.
            var targets = new java.util.LinkedHashMap<String, dev.wiktorfila.filasignal.core.host.HostRegistry>();
            for (String name : List.of("hostile-detector", "security-alarm", "weather-lights", "stock-check",
                    "status-board", "device-discovery", "sensor-inventory", "sensor-door", "central-monitor",
                    "starter-intruder-alarm", "starter-night-lights", "starter-panel-alarm", "starter-weather-board",
                    "starter-low-stock", "security-report", "remote-outpost")) {
                targets.put(name, hosts);
            }
            targets.put("starter-door-log", PeripheralLifecycle.driveHosts(server, "access_controller"));
            targets.put("autonomous-alarm", PeripheralLifecycle.driveHosts(server, "alarm"));
            targets.put("autonomous-door", PeripheralLifecycle.driveHosts(server, "redstone_controller"));
            targets.put("autonomous-board", PeripheralLifecycle.driveHosts(server, "display"));
            targets.put("panel-commands", PeripheralLifecycle.driveHosts(server, "control_panel"));
            targets.put("autonomous-detector", PeripheralLifecycle.driveHosts(server, "entity_detector"));
            targets.put("portable-counter", PeripheralLifecycle.driveHosts(server, "entity_detector"));
            targets.put("weather-history", PeripheralLifecycle.driveHosts(server, "weather_station"));
            targets.put("inventory-watch", PeripheralLifecycle.driveHosts(server, "inventory_module"));
            targets.put("local-redstone", PeripheralLifecycle.driveHosts(server, "redstone_controller"));
            // Every starter runs where its header says: "// Device: KIND" for a drive program, otherwise the computer.
            try (var files = java.nio.file.Files.list(examples)) {
                for (var file : files.filter(path -> path.getFileName().toString().startsWith("starter-")).sorted().toList()) {
                    String name = file.getFileName().toString().replace(".fsig", "");
                    var header = java.nio.file.Files.readAllLines(file).stream().limit(2).toList();
                    test.assertTrue(header.getFirst().startsWith("// Starter: "), name + " needs a // Starter: title");
                    String device = header.size() > 1 && header.get(1).startsWith("// Device: ")
                            ? header.get(1).substring("// Device: ".length()).strip() : "";
                    targets.put(name, device.isEmpty() ? hosts : PeripheralLifecycle.driveHosts(server, device));
                }
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
            for (var target : targets.entrySet()) {
                String name = target.getKey();
                try {
                    String source = java.nio.file.Files.readString(examples.resolve(name + ".fsig"));
                    var resolved = dev.wiktorfila.filasignal.core.analysis.Resolver.resolve(
                            dev.wiktorfila.filasignal.core.parser.Parser.parse(
                                    new dev.wiktorfila.filasignal.core.source.SourceText(name, name + ".fsig", source)), target.getValue());
                    test.assertTrue(resolved.diagnostics().isEmpty(), name + ": " + resolved.diagnostics());
                } catch (IOException error) {
                    throw test.assertionException(name + ": " + error.getMessage());
                }
            }
        }).thenSucceed();
    }

    static java.nio.file.Path examples(GameTestHelper test) {
        for (var dir = java.nio.file.Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (java.nio.file.Files.isRegularFile(dir.resolve("examples").resolve("hello.fsig"))) {
                return dir.resolve("examples");
            }
        }
        throw test.assertionException("The examples directory was not found above " + java.nio.file.Path.of("").toAbsolutePath());
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

    private static List<Value> inventory(HostRegistry hosts, HostHandle handle, String name, Value... arguments) {
        return member(hosts, handle, name, arguments);
    }

    private static Value property(HostRegistry hosts, HostHandle handle, String name) {
        return hosts.object(handle.type()).properties().get(name).handler().invoke(List.of(handle), CONTEXT).getFirst();
    }

    private static Map<String, Value> map(Value value) {
        var dict = (DictValue) value;
        var entries = new java.util.HashMap<String, Value>();
        dict.keys().forEach(key -> entries.put(key, dict.get(key)));
        return entries;
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

    private static ServerPlayer player(GameTestHelper test, BlockPos relative) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(Vec3.atCenterOf(test.absolutePos(relative)).add(0, 1, 0));
        return player;
    }

    private static void remove(GameTestHelper test, ServerPlayer player) {
        if (player != null && !player.isRemoved()) {
            test.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }
}
