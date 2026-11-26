package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.computer.ProgramHost;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Server performance scenarios. Does nothing unless {@code -Dfilasignal.benchmark.profile} names a profile; run it
 * with {@code tools/benchmarks/Run-Benchmarks.ps1}. Each scenario builds whole networks far from other tests, runs
 * realistic programs (device events, displays, redstone, drive and shared storage, custom events, discovery), warms
 * up, measures every server tick and removes everything again. Results go to {@code -Dfilasignal.benchmark.out}.
 */
public final class ServerBenchmark {
    /**
     * A workload: networks of one computer, one repeater and up to 15 devices, some running drive programs. A
     * saturated scenario's computers run a program that never finishes its work, to measure the per-tick budget cap.
     */
    record Scenario(String name, int devices, int drivePrograms, int perNetwork, boolean saturated) {
        Scenario(String name, int devices, int drivePrograms, int perNetwork) {
            this(name, devices, drivePrograms, perNetwork, false);
        }

        int networks() { return devices == 0 ? 0 : (devices + perNetwork - 1) / perNetwork; }
        int programs() { return networks() + drivePrograms; }
    }

    private static final List<Scenario> CATALOG = List.of(
            new Scenario("idle", 0, 0, 10),
            new Scenario("small", 10, 2, 10),
            new Scenario("advanced", 30, 7, 10),
            new Scenario("large", 100, 20, 10),
            new Scenario("multiplayer", 300, 70, 10),
            new Scenario("stress", 900, 440, 15),
            new Scenario("saturated", 100, 0, 10, true));

    /** Every tick, every computer tries to loop forever; handler and global budgets must stop it. */
    private static final String SATURATED_PROGRAM = """
            on timer.every(1) {
                i = 0
                while true {
                    i++
                }
            }
            """;

    private static final List<Kind> CYCLE = List.of(Kind.ENTITY_DETECTOR, Kind.DISPLAY, Kind.WEATHER_STATION, Kind.REDSTONE_CONTROLLER,
            Kind.STORAGE_SERVER, Kind.ALARM, Kind.ENVIRONMENT_SENSOR, Kind.CONTROL_PANEL, Kind.ENTITY_DETECTOR, Kind.DISPLAY,
            Kind.REDSTONE_CONTROLLER, Kind.ENVIRONMENT_SENSOR, Kind.DISPLAY, Kind.ENTITY_DETECTOR, Kind.CONTROL_PANEL);

    private static final String COMPUTER_PROGRAM = """
            const gate = detectors.named("gate")
            const screen = displays.named("screen")
            const sky = weather.named("sky")
            const lamps = redstoneControllers.named("lamps")
            const records = storageServers.named("records")
            global visits = 0
            global lit = false

            on startup {
                lamps.setMode(Direction.SOUTH, PortMode.OUTPUT)
            }

            on gate.entityEnter(entity) {
                visits++
                screen.write("Visits: " + str(visits) + "\\nLast: " + entity.name)
                records.write("visits", visits)
            }

            on gate.countChanged(count, previous) {
                screen.setLine(2, "Near: " + str(count))
            }

            on timer.every(20) {
                lit = not lit
                if lit {
                    lamps.set(Direction.SOUTH, 15)
                } else {
                    lamps.set(Direction.SOUTH, 0)
                }
                records.write("weather", {raining: sky.raining(), day: sky.time().day})
            }

            on timer.every(100) {
                members = devices.list()
                records.write("members", members.length)
            }

            on events.custom("bench.ping", value, sender) {
                screen.setLine(3, "Ping " + str(value.n))
            }
            """;

    private static String driveProgram(Kind kind) {
        return switch (kind) {
            case ENTITY_DETECTOR -> """
                    global seen = 0
                    on device.entityEnter(entity) {
                        seen++
                        storage.write("seen", seen)
                        events.emit("bench.ping", {n: seen})
                    }
                    on timer.every(40) {
                        storage.write("hostile", device.count(Entity.HOSTILE))
                    }
                    """;
            case DISPLAY -> """
                    on events.custom("bench.ping", value, sender) {
                        device.write("Ping " + str(value.n) + " from " + sender.kind)
                    }
                    """;
            case WEATHER_STATION -> """
                    on timer.every(100) {
                        history, err = storage.read("history")
                        if err is not nil {
                            history = []
                        }
                        history.append(str(device.weather()))
                        while history.length > 32 {
                            history.remove(0)
                        }
                        storage.write("history", history)
                    }
                    """;
            case REDSTONE_CONTROLLER -> """
                    global high = false
                    on startup {
                        device.setMode(Direction.NORTH, PortMode.OUTPUT)
                    }
                    on timer.every(20) {
                        high = not high
                        if high {
                            device.set(Direction.NORTH, 15)
                        } else {
                            device.set(Direction.NORTH, 0)
                        }
                    }
                    """;
            case ENVIRONMENT_SENSOR -> """
                    on timer.every(40) {
                        storage.write("light", device.light())
                    }
                    """;
            case ALARM -> """
                    const gate = detectors.named("gate")
                    on gate.entityLeave(entity) {
                        left, err = storage.read("left")
                        if err is not nil {
                            left = []
                        }
                        left.append(entity.type)
                        while left.length > 16 {
                            left.remove(0)
                        }
                        storage.write("left", left)
                    }
                    """;
            case CONTROL_PANEL -> """
                    on timer.every(60) {
                        storage.write("labels", device.labels())
                    }
                    """;
            default -> null;
        };
    }

    /** Tick samples recorded between the tick start and end events while a measurement is active. */
    private static final class Recorder {
        private static volatile Recorder active;
        private static boolean registered;
        private final MinecraftServer server;
        private final List<long[]> samples = new ArrayList<>();
        private long started;

        private Recorder(MinecraftServer server) {
            this.server = server;
        }

        static void install() {
            if (registered) {
                return;
            }
            registered = true;
            ServerTickEvents.START_SERVER_TICK.register(server -> {
                Recorder recorder = active;
                if (recorder != null && recorder.server == server) {
                    recorder.started = System.nanoTime();
                }
            });
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                Recorder recorder = active;
                if (recorder != null && recorder.server == server && recorder.started != 0) {
                    var metrics = ComputerLifecycle.metrics(server);
                    long scheduler = metrics == null ? 0 : metrics.tickNanos();
                    long devices = PeripheralLifecycle.tickNanos(server);
                    recorder.samples.add(new long[]{System.nanoTime() - recorder.started, scheduler + devices,
                            metrics == null ? 0 : metrics.operations()});
                }
            });
        }
    }

    /** One scenario's results. */
    private record Result(Scenario scenario, int networks, int programsRunning, int activationTicks, int ticks,
                          double meanMs, double medianMs, double p95Ms, double p99Ms, double maxMs, double tps,
                          double filaMeanMs, double filaP95Ms, double filaShare, double operationsMean, long heapUsedMb,
                          long heapMaxMb, long gcCount, long gcMillis, long faults, long dropped, int queuedEvents,
                          int loadedDevices) {
    }

    @GameTest(maxTicks = 200_000, setupTicks = 0)
    public void serverBenchmark(GameTestHelper test) {
        String profile = System.getProperty("filasignal.benchmark.profile", "");
        if (profile.isEmpty()) {
            test.succeed();
            return;
        }
        var server = test.getLevel().getServer();
        Path out = Path.of(System.getProperty("filasignal.benchmark.out", "benchmark-output")).toAbsolutePath();
        int warmup = Integer.getInteger("filasignal.benchmark.warmup", 200);
        int measure = Integer.getInteger("filasignal.benchmark.ticks", 600);
        List<Scenario> scenarios = scenarios(profile);
        Recorder.install();
        var results = new ArrayList<Result>();
        var cleanup = new ArrayList<String>();
        var level = test.getLevel();
        ServerPlayer owner = player(test);
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED));
        // Far from the test grid, high above the ground, so no other test or terrain interferes.
        BlockPos base = new BlockPos(test.absolutePos(BlockPos.ZERO).getX() + 4096, 150, test.absolutePos(BlockPos.ZERO).getZ() + 4096);
        var sequence = test.startSequence();
        for (Scenario scenario : scenarios) {
            var built = new Build(scenario, base);
            var recorder = new Recorder(server);
            var gc = new long[2];
            sequence = sequence
                    .thenExecute(() -> built.place(level))
                    .thenWaitUntil(() -> test.assertTrue(built.attached(level), "Devices are still attaching"))
                    .thenExecute(() -> built.configure(test, level, owner))
                    .thenWaitUntil(() -> test.assertTrue(built.started(server), "Programs are still starting"))
                    .thenIdle(warmup)
                    .thenExecute(() -> {
                        gc[0] = gcCount();
                        gc[1] = gcMillis();
                        Recorder.active = recorder;
                    })
                    .thenExecuteFor(measure, () -> built.stir(level))
                    .thenExecute(() -> {
                        Recorder.active = null;
                        results.add(summarize(scenario, built, recorder, server, gcCount() - gc[0], gcMillis() - gc[1]));
                        built.remove(level);
                    })
                    .thenIdle(40)
                    .thenExecute(() -> cleanup.add(built.leftovers(server)));
        }
        sequence.thenExecute(() -> {
            try {
                write(out, profile, warmup, measure, results, cleanup, server);
            } catch (IOException error) {
                throw test.assertionException("Could not write benchmark results: " + error.getMessage());
            }
        }).thenSucceed();
    }

    private static List<Scenario> scenarios(String profile) {
        List<String> names = switch (profile) {
            case "quick" -> List.of("idle", "small");
            case "normal" -> List.of("idle", "small", "advanced", "large");
            case "large" -> List.of("idle", "large", "multiplayer");
            case "stress" -> List.of("idle", "multiplayer", "stress", "saturated");
            default -> Arrays.asList(profile.split(","));
        };
        return names.stream().map(name -> CATALOG.stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark scenario " + name))).toList();
    }

    /** The blocks, programs and moving entities of one scenario. */
    private static final class Build {
        private final Scenario scenario;
        private final BlockPos base;
        private final List<BlockPos> blocks = new ArrayList<>();
        private final List<List<BlockPos>> networks = new ArrayList<>();
        private int placedAtTick;
        private int startedAtTick = -1;
        private int baselineDevices;
        private int baselinePrograms;
        private int tick;

        Build(Scenario scenario, BlockPos base) {
            this.scenario = scenario;
            this.base = base;
        }

        private BlockPos at(int network, int slot) {
            return base.offset(slot * 2, 0, network * 4);
        }

        void place(ServerLevel level) {
            var server = level.getServer();
            baselineDevices = PeripheralLifecycle.loadedDevices(server);
            var metrics = ComputerLifecycle.metrics(server);
            baselinePrograms = metrics == null ? 0 : metrics.running();
            placedAtTick = server.getTickCount();
            int remaining = scenario.devices();
            for (int n = 0; n < scenario.networks(); n++) {
                var network = new ArrayList<BlockPos>();
                for (int slot = 0; slot < 17; slot++) {
                    BlockPos pos = at(n, slot);
                    level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
                }
                set(level, at(n, 0), ComputerBlocks.block(), network);
                set(level, at(n, 1), PeripheralBlocks.block(Kind.REPEATER), network);
                int count = Math.min(scenario.perNetwork(), remaining);
                remaining -= count;
                for (int i = 0; i < count; i++) {
                    set(level, at(n, 2 + i), PeripheralBlocks.block(CYCLE.get(i)), network);
                }
                networks.add(network);
            }
        }

        private void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, List<BlockPos> network) {
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            blocks.add(pos);
            network.add(pos);
        }

        boolean attached(ServerLevel level) {
            // Computers attach when they activate; devices attach in the topology queue.
            int devices = blocks.size() - networks.size();
            return PeripheralLifecycle.loadedDevices(level.getServer()) - baselineDevices >= devices;
        }

        void configure(GameTestHelper test, ServerLevel level, ServerPlayer owner) {
            var runtime = PeripheralLifecycle.runtime(level.getServer());
            for (BlockPos pos : blocks) {
                level.getBlockState(pos).getBlock().setPlacedBy(level, pos, level.getBlockState(pos), owner, ItemStack.EMPTY);
            }
            int drives = scenario.drivePrograms();
            for (List<BlockPos> network : networks) {
                var root = runtime.requireDevice(level, network.getFirst()).id();
                var relay = runtime.requireDevice(level, network.get(1)).id();
                runtime.link(root, relay, false);
                var named = new java.util.HashSet<Kind>();
                for (BlockPos pos : network.subList(2, network.size())) {
                    var node = runtime.requireDevice(level, pos);
                    runtime.link(relay, node.id(), false);
                    if (named.add(node.kind())) {
                        runtime.alias(node, switch (node.kind()) {
                            case ENTITY_DETECTOR -> "gate";
                            case DISPLAY -> "screen";
                            case WEATHER_STATION -> "sky";
                            case REDSTONE_CONTROLLER -> "lamps";
                            case STORAGE_SERVER -> "records";
                            case ALARM -> "siren";
                            case ENVIRONMENT_SENSOR -> "probe";
                            default -> "panel";
                        });
                    }
                }
                try {
                    var computer = (ProgramHost) level.getBlockEntity(network.getFirst());
                    ComputerLifecycle.program(level, computer, scenario.saturated() ? SATURATED_PROGRAM : COMPUTER_PROGRAM, true, true);
                    ComputerLifecycle.control(level, computer, true, "benchmark");
                    for (BlockPos pos : network.subList(2, network.size())) {
                        if (drives == 0) {
                            break;
                        }
                        var device = (PeripheralEntity) level.getBlockEntity(pos);
                        String source = driveProgram(device.kind());
                        if (source == null) {
                            continue;
                        }
                        device.insertDrive(new ItemStack(Drives.DRIVE));
                        ComputerLifecycle.program(level, device, source, false, true);
                        ComputerLifecycle.control(level, device, true, "benchmark");
                        drives--;
                    }
                } catch (IOException | IllegalArgumentException error) {
                    throw test.assertionException("A benchmark program was rejected: " + error.getMessage());
                }
                // One armor stand per network walks in and out of the first detector's range.
                BlockPos gate = network.get(2);
                command(level.getServer(), "summon armor_stand %d %d %d {NoGravity:1b,Tags:[\"fsbench\",\"fsbench%d\"]}"
                        .formatted(gate.getX() + 3, gate.getY(), gate.getZ() + 2, networks.indexOf(network)));
            }
        }

        boolean started(MinecraftServer server) {
            var metrics = ComputerLifecycle.metrics(server);
            boolean done = metrics != null && metrics.running() - baselinePrograms >= scenario.programs();
            if (done && startedAtTick < 0) {
                startedAtTick = server.getTickCount();
            }
            return done;
        }

        /** Moves the armor stands in and out of range every 40 ticks so detectors raise events. */
        void stir(ServerLevel level) {
            if (++tick % 40 != 0 || networks.isEmpty()) {
                return;
            }
            int offset = (tick / 40) % 2 == 0 ? 3 : 14;
            for (int n = 0; n < networks.size(); n++) {
                BlockPos gate = networks.get(n).get(2);
                command(level.getServer(), "tp @e[tag=fsbench%d] %d %d %d".formatted(n, gate.getX() + offset, gate.getY(), gate.getZ() + 2));
            }
        }

        void remove(ServerLevel level) {
            for (BlockPos pos : blocks) {
                if (level.getBlockEntity(pos) instanceof PeripheralEntity device) {
                    device.ejectDrive();
                }
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            command(level.getServer(), "kill @e[tag=fsbench]");
            for (BlockPos pos : blocks) {
                level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
            }
        }

        /** What is still loaded 40 ticks after removing the scenario, compared with before it was built. */
        String leftovers(MinecraftServer server) {
            var metrics = ComputerLifecycle.metrics(server);
            int programs = (metrics == null ? 0 : metrics.running()) - baselinePrograms;
            int devices = PeripheralLifecycle.loadedDevices(server) - baselineDevices;
            return "%s: %d programs and %d devices remain".formatted(scenario.name(), programs, devices);
        }

        int activationTicks() {
            return startedAtTick < 0 ? -1 : startedAtTick - placedAtTick;
        }
    }

    private static Result summarize(Scenario scenario, Build built, Recorder recorder, MinecraftServer server, long gcCount, long gcMillis) {
        int n = recorder.samples.size();
        double[] total = recorder.samples.stream().mapToDouble(s -> s[0] / 1e6).sorted().toArray();
        double[] fila = recorder.samples.stream().mapToDouble(s -> s[1] / 1e6).sorted().toArray();
        double mean = Arrays.stream(total).average().orElse(0);
        double filaMean = Arrays.stream(fila).average().orElse(0);
        double operations = recorder.samples.stream().mapToLong(s -> s[2]).average().orElse(0);
        var metrics = ComputerLifecycle.metrics(server);
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new Result(scenario, scenario.networks(), metrics == null ? 0 : metrics.running(), built.activationTicks(), n,
                mean, percentile(total, 50), percentile(total, 95), percentile(total, 99), n == 0 ? 0 : total[n - 1],
                Math.min(20.0, mean <= 0 ? 20.0 : 1000.0 / Math.max(50.0, mean)) , filaMean, percentile(fila, 95),
                mean <= 0 ? 0 : filaMean / mean, operations, heap.getUsed() >> 20, heap.getMax() >> 20, gcCount, gcMillis,
                metrics == null ? 0 : metrics.faults(), metrics == null ? 0 : metrics.dropped(),
                metrics == null ? 0 : metrics.queuedEvents(), PeripheralLifecycle.loadedDevices(server));
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    private static void write(Path out, String profile, int warmup, int measure, List<Result> results, List<String> cleanup,
                              MinecraftServer server)
            throws IOException {
        Files.createDirectories(out);
        var json = new StringBuilder("{\n  \"profile\": \"").append(profile).append("\",\n  \"warmupTicks\": ").append(warmup)
                .append(",\n  \"measuredTicks\": ").append(measure)
                .append(",\n  \"world\": \"").append(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace("\\", "/"))
                .append("\",\n  \"jvmArguments\": \"").append(String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())
                        .replace("\\", "/").replace("\"", "'"))
                .append("\",\n  \"scenarios\": [\n");
        var table = new ArrayList<String>();
        table.add("| Scenario | Devices | Programs | Start (ticks) | Mean MSPT | Median | P95 | P99 | Max | TPS (from MSPT) | FilaSignal mean | FilaSignal P95 | FilaSignal share | Ops/tick | Heap used | GC (count / ms) | Faults | Dropped |");
        table.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            json.append(String.format(Locale.ROOT, """
                        {"name": "%s", "devices": %d, "networks": %d, "programs": %d, "activationTicks": %d, "ticks": %d,
                         "meanMs": %.3f, "medianMs": %.3f, "p95Ms": %.3f, "p99Ms": %.3f, "maxMs": %.3f, "tps": %.2f,
                         "filaMeanMs": %.3f, "filaP95Ms": %.3f, "filaShare": %.4f, "operationsPerTick": %.1f,
                         "heapUsedMb": %d, "heapMaxMb": %d, "gcCount": %d, "gcMillis": %d, "faults": %d, "droppedEvents": %d,
                         "queuedEvents": %d, "loadedDevices": %d}%s
                    """, r.scenario().name(), r.scenario().devices(), r.networks(), r.programsRunning(), r.activationTicks(),
                    r.ticks(), r.meanMs(), r.medianMs(), r.p95Ms(), r.p99Ms(), r.maxMs(), r.tps(), r.filaMeanMs(), r.filaP95Ms(),
                    r.filaShare(), r.operationsMean(), r.heapUsedMb(), r.heapMaxMb(), r.gcCount(), r.gcMillis(), r.faults(),
                    r.dropped(), r.queuedEvents(), r.loadedDevices(), i < results.size() - 1 ? "," : ""));
            table.add(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.2f ms | %.2f | %.2f | %.2f | %.2f | %.1f | %.3f ms | %.3f | %.1f%% | %.0f | %d / %d MB | %d / %d | %d | %d |",
                    r.scenario().name(), r.scenario().devices(), r.programsRunning(), r.activationTicks(), r.meanMs(), r.medianMs(),
                    r.p95Ms(), r.p99Ms(), r.maxMs(), r.tps(), r.filaMeanMs(), r.filaP95Ms(), r.filaShare() * 100, r.operationsMean(),
                    r.heapUsedMb(), r.heapMaxMb(), r.gcCount(), r.gcMillis(), r.faults(), r.dropped()));
        }
        json.append("  ],\n  \"afterRemoval\": [")
                .append(String.join(", ", cleanup.stream().map(line -> "\"" + line + "\"").toList())).append("]\n}\n");
        table.add("");
        table.add("After removing each scenario: " + String.join("; ", cleanup) + ".");
        Files.writeString(out.resolve("server-results.json"), json.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("server-results.md"), String.join("\n", table) + "\n", StandardCharsets.UTF_8);
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        return player;
    }
}
