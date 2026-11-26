package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.DebugState;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Request;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Debugger behavior on the Minecraft server thread with real computers, ACLs and the shared scheduler. */
public final class DebuggerGameTests {
    private static final String PROGRAM = """
            global hits = 0
            on startup {
                hits += 1
                hits += 10
            }
            on timer.every(5) {
                hits += 0
            }
            fn inspect() { return hits }
            """;

    @GameTest(maxTicks = 200)
    public void breakpointsPauseOneComputerWithoutBlockingOthers(GameTestHelper test) {
        var level = test.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var otherRelative = new BlockPos(3, 1, 1);
        var position = test.absolutePos(relative);
        test.setBlock(relative, ComputerBlocks.computer);
        test.setBlock(otherRelative, ComputerBlocks.computer);
        // An unrelated computer runs a timer on every tick through the same scheduler.
        var busy = new ControllerData(UUID.randomUUID(), "global n = 0\non timer.every(1) { n++ }", null);
        var record = new net.minecraft.nbt.CompoundTag();
        record.put("filasignal_controller", busy.encode().convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue());
        computer(test, otherRelative).loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), record));
        var owner = player(test, position);
        var guest = player(test, position);
        ComputerBlocks.computer.setPlacedBy(level, position, level.getBlockState(position), owner,
                net.minecraft.world.item.ItemStack.EMPTY);
        var otherHandlers = new AtomicLong();
        test.startSequence()
                .thenIdle(4)
                .thenExecute(() -> {
                    var program = inspect(test, owner, position);
                    submit(test, owner, position, program.revision(), PROGRAM);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    // A guest without access learns nothing and cannot control execution.
                    var denied = debug(guest, position, Request.DEBUG_ATTACH, "");
                    test.assertFalse(denied.success(), "Unauthorized player attached a debugger");
                    test.assertTrue(denied.console().isEmpty() && denied.pause().line() == 0, "Denied reply leaked state");
                    test.assertFalse(debug(guest, position, Request.DEBUG_PAUSE, "").success(), "Unauthorized pause");

                    var attached = debug(owner, position, Request.DEBUG_ATTACH, "");
                    test.assertTrue(attached.success(), attached.message());
                    test.assertTrue(attached.status().state() == DebugState.RUNNING, attached.status().headline());
                    test.assertTrue(debug(owner, position, Request.DEBUG_BREAKPOINTS, "4").breakpoints().equals(List.of(4)),
                            "Breakpoint was not stored");
                    test.assertFalse(debug(owner, position, Request.DEBUG_BREAKPOINTS, "1,x").success(),
                            "Malformed breakpoint list was accepted");
                    restart(test, owner, position);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    var paused = debug(owner, position, Request.DEBUG_PAUSE, "");
                    test.assertTrue(paused.status().state() == DebugState.PAUSED, paused.status().headline());
                    test.assertTrue(paused.pause().line() == 4 && paused.pause().reason().equals("breakpoint"),
                            "Paused at the wrong place: " + paused.pause());
                    test.assertTrue(paused.pause().frames().equals(List.of(new EditorNetwork.Frame("startup", 4))),
                            "Unexpected call stack " + paused.pause().frames());
                    test.assertTrue(paused.pause().variables().contains(new EditorNetwork.Variable("global", "hits", "12")),
                            "Missing global value " + paused.pause().variables());
                    test.assertTrue(paused.console().stream().anyMatch(line -> line.text().equals("Paused at line 4 (breakpoint)")),
                            "Pause was not recorded in the console");
                    // Saving the world while paused keeps the earlier record instead of failing.
                    computer(test, relative).saveWithoutMetadata(level.registryAccess());
                    otherHandlers.set(ComputerLifecycle.statistics(level.getServer(), computer(test, otherRelative)).handlers());
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    var other = ComputerLifecycle.statistics(level.getServer(), computer(test, otherRelative));
                    test.assertTrue(other.handlers() > otherHandlers.get(), "Pausing one computer stopped another");
                    test.assertTrue(state(test, owner, position).status().state() == DebugState.PAUSED,
                            "Paused handler continued without a request");
                    test.assertTrue(debug(owner, position, Request.DEBUG_RESUME, "").success(), "Resume failed");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    var resumed = state(test, owner, position);
                    test.assertTrue(resumed.status().state() == DebugState.RUNNING, resumed.status().headline());
                    assertHits(test, owner, position, 22);
                    restart(test, owner, position);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    test.assertTrue(state(test, owner, position).status().state() == DebugState.PAUSED, "Restart did not pause");
                    try {
                        var program = inspect(test, owner, position);
                        ProgramEditing.control(owner, position, program.revision(), false);
                    } catch (IOException error) {
                        throw test.assertionException(error.getMessage());
                    }
                    var stopped = state(test, owner, position);
                    test.assertTrue(stopped.status().state() == DebugState.STOPPED, stopped.status().headline());
                    test.assertTrue(stopped.console().stream().anyMatch(line -> line.text().startsWith("Paused handler cancelled")),
                            "Stop did not report the cancelled handler");
                    // The cancelled handler kept its earlier write and globals were saved.
                    assertHits(test, owner, position, 23);
                    restart(test, owner, position);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    test.assertTrue(state(test, owner, position).status().state() == DebugState.PAUSED, "Restart did not pause");
                    owner.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(position).add(30, 1, 0));
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    // Leaving range ends the session: breakpoints clear and the paused handler continues.
                    owner.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 1, 0));
                    var released = debug(owner, position, Request.DEBUG_ATTACH, "");
                    test.assertTrue(released.status().state() == DebugState.RUNNING, released.status().headline());
                    test.assertTrue(released.breakpoints().isEmpty(), "Breakpoints outlived the session");
                    assertHits(test, owner, position, 34);
                    test.assertTrue(debug(owner, position, Request.DEBUG_DETACH, "").success(), "Detach request failed");
                })
                .thenSucceed();
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(guest, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
    }

    @GameTest(maxTicks = 60)
    public void runtimeErrorsReportLocationAndCallStack(GameTestHelper test) {
        var level = test.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var position = test.absolutePos(relative);
        test.setBlock(relative, ComputerBlocks.computer);
        var owner = player(test, position);
        ComputerBlocks.computer.setPlacedBy(level, position, level.getBlockState(position), owner,
                net.minecraft.world.item.ItemStack.EMPTY);
        test.startSequence()
                .thenIdle(4)
                .thenExecute(() -> submit(test, owner, position, inspect(test, owner, position).revision(), """
                        fn divide(value) {
                            return value / 0
                        }
                        on startup {
                            print("before")
                            result = divide(4)
                        }
                        """))
                .thenIdle(4)
                .thenExecute(() -> {
                    var view = state(test, owner, position);
                    test.assertTrue(view.status().faultLine() == 2, "Fault line " + view.status().faultLine());
                    test.assertTrue(view.status().fault().contains("Division"), view.status().fault());
                    test.assertTrue(view.status().faultFrames().equals(List.of(new EditorNetwork.Frame("divide", 2),
                            new EditorNetwork.Frame("startup", 6))), "Call stack " + view.status().faultFrames());
                    test.assertTrue(view.console().stream().anyMatch(line -> line.text().equals("before")), "Output missing");
                    test.assertTrue(inspect(test, owner, position).fault().startsWith("2:"), "Editor fault summary missing");
                    submit(test, owner, position, inspect(test, owner, position).revision(), "on startup {\n  print(\"ok\")\n}");
                })
                .thenExecute(() -> {
                    test.assertTrue(debug(owner, position, Request.DEBUG_ATTACH, "").status().fault().isEmpty(),
                            "Stale fault survived a new program");
                })
                .thenSucceed();
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
    }

    /** A debugged handler pauses before a hardware call and steps over it. The call still runs on the server thread. */
    @GameTest(maxTicks = 60)
    public void steppingOverAHardwareCallKeepsItOnTheServerThread(GameTestHelper test) {
        var level = test.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var position = test.absolutePos(relative);
        test.setBlock(relative, ComputerBlocks.computer);
        var owner = player(test, position);
        ComputerBlocks.computer.setPlacedBy(level, position, level.getBlockState(position), owner,
                net.minecraft.world.item.ItemStack.EMPTY);
        test.runBeforeTestEnd(() -> level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        test.startSequence()
                .thenIdle(4)
                .thenExecute(() -> {
                    submit(test, owner, position, inspect(test, owner, position).revision(), """
                            on startup {
                                members, err = devices.list()
                                if err is nil { print("listed") }
                            }
                            """);
                    test.assertTrue(debug(owner, position, Request.DEBUG_ATTACH, "").success(), "Attach failed");
                    debug(owner, position, Request.DEBUG_BREAKPOINTS, "2");
                    restart(test, owner, position);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    test.assertTrue(state(test, owner, position).pause().line() == 2, "Did not pause before the hardware call");
                    debug(owner, position, Request.DEBUG_STEP, "");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    var stepped = state(test, owner, position);
                    test.assertTrue(stepped.pause().line() == 3, "Step did not reach the next statement: " + stepped.pause());
                    debug(owner, position, Request.DEBUG_RESUME, "");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    var finished = state(test, owner, position);
                    test.assertTrue(finished.status().fault().isEmpty(), "Hardware call faulted: " + finished.status().fault());
                    test.assertTrue(finished.console().stream().anyMatch(line -> line.text().equals("listed")),
                            "The hardware call did not return normally");
                })
                .thenSucceed();
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos position) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 1, 0));
        return player;
    }

    private static DebugState debug(ServerPlayer player, BlockPos position, int action, String argument) {
        var request = new Request(position, UUID.randomUUID(), action, UUID.randomUUID(), argument);
        if (action == Request.DEBUG_DETACH) {
            ComputerLifecycle.detachDebugger(player, position);
            return new DebugState(position, request.requestId(), true, "", new EditorNetwork.Status(0, "", "", 0, 0,
                    List.of(), ""), EditorNetwork.Pause.NONE, List.of(), List.of());
        }
        return ProgramDebugging.respond(player, request);
    }

    private static DebugState state(GameTestHelper test, ServerPlayer player, BlockPos position) {
        return ComputerLifecycle.debugView(test.getLevel(), (ComputerBlockEntity) test.getLevel().getBlockEntity(position),
                position, EditorNetwork.EMPTY_REQUEST, "");
    }

    private static ComputerBlockEntity computer(GameTestHelper test, BlockPos relative) {
        return test.getBlockEntity(relative, ComputerBlockEntity.class);
    }

    private static ProgramEditing.Snapshot inspect(GameTestHelper test, ServerPlayer player, BlockPos position) {
        try {
            return ProgramEditing.inspect(player, position);
        } catch (IOException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    // Installs with fresh globals, as the operator command does, because these programs replace the default globals.
    private static void submit(GameTestHelper test, ServerPlayer player, BlockPos position, UUID revision, String source) {
        try {
            ComputerLifecycle.program(test.getLevel(), position, source, true);
        } catch (IOException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    private static void restart(GameTestHelper test, ServerPlayer player, BlockPos position) {
        try {
            ProgramEditing.control(player, position, inspect(test, player, position).revision(), true);
        } catch (IOException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    private static void assertHits(GameTestHelper test, ServerPlayer player, BlockPos position, double expected) {
        var computer = (ComputerBlockEntity) test.getLevel().getBlockEntity(position);
        computer.saveWithoutMetadata(test.getLevel().registryAccess());
        try {
            var data = computer.activateData();
            var source = new dev.wiktorfila.filasignal.core.source.SourceText("test", "computer.fsig", data.source());
            var resolved = dev.wiktorfila.filasignal.core.analysis.Resolver.resolve(
                    dev.wiktorfila.filasignal.core.parser.Parser.parse(source)).program().orElseThrow();
            var runtime = new dev.wiktorfila.filasignal.core.runtime.execution.Interpreter(resolved,
                    dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits.DEFAULT, ignored -> {});
            runtime.restoreGlobals(data.snapshot(), null);
            var result = runtime.invoke("inspect", List.of());
            test.assertTrue(result.returns().values().equals(List.of(
                    new dev.wiktorfila.filasignal.core.runtime.value.Value.Num(expected))),
                    "Saved hits " + result.returns().values() + " expected " + expected);
        } catch (IOException error) {
            throw test.assertionException(error.getMessage());
        }
    }
}
