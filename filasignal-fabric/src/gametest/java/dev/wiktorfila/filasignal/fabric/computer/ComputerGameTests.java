package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.value.Value.Num;
import dev.wiktorfila.filasignal.core.source.SourceText;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.FutureTask;

/** Exercises the registered block, server tick integration and vanilla block-entity NBT. */
public final class ComputerGameTests {
    private static final String RECORD = "filasignal_controller";

    @GameTest(maxTicks = 80)
    public void revisionsFenceConcurrentEditsAndRejectedPrograms(GameTestHelper test) {
        var relative = new BlockPos(1, 1, 1);
        var level = test.getLevel();
        var position = test.absolutePos(relative);
        test.setBlock(relative, ComputerBlocks.computer);
        var owner = test.makeMockServerPlayerInLevel();
        var guest = test.makeMockServerPlayerInLevel();
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(guest, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
        for (var player : List.of(owner, guest)) {
            player.setNoGravity(true);
            player.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 1, 0));
        }
        ComputerBlocks.computer.setPlacedBy(level, position, level.getBlockState(position), owner,
                net.minecraft.world.item.ItemStack.EMPTY);
        test.runAfterDelay(4, () -> {
            try {
                var opened = ProgramEditing.inspect(owner, position);
                denied(test, () -> ProgramEditing.inspect(guest, position));
                denied(test, () -> ProgramEditing.submit(guest, position, opened.revision(), opened.source(), true));
                changeTrust(test, owner, guest, position, "trust");
                var trusted = ProgramEditing.inspect(guest, position);
                changeTrust(test, owner, guest, position, "untrust");
                denied(test, () -> ProgramEditing.submit(guest, position, trusted.revision(), trusted.source(), true));
                denied(test, () -> ProgramEditing.submit(owner, position, opened.revision(), "not valid", true));
                var afterReject = ProgramEditing.inspect(owner, position);
                test.assertTrue(afterReject.equals(opened), "Rejected update changed the running program");
                var draft = opened.source() + "\n// editor update\n";
                ProgramEditing.submit(owner, position, opened.revision(), draft, false);
                test.assertTrue(ProgramEditing.inspect(owner, position).revision().equals(opened.revision()),
                        "Validation published a revision");
                var applied = ProgramEditing.submit(owner, position, opened.revision(), draft, true);
                test.assertTrue(!applied.revision().equals(opened.revision()), "Applying did not rotate the revision");
                denied(test, () -> ProgramEditing.submit(owner, position, opened.revision(), opened.source(), true));
                var stopped = ProgramEditing.control(owner, position, applied.revision(), false);
                var computer = test.getBlockEntity(relative, ComputerBlockEntity.class);
                test.assertTrue(!record(test, computer).enabled() && !stopped.running(), "Stop did not persist");
                ProgramEditing.control(owner, position, stopped.revision(), true);
                test.runAfterDelay(5, () -> {
                    test.assertTrue(ComputerLifecycle.active(level.getServer(), computer), "Start did not activate the controller");
                    test.setBlock(relative, Blocks.AIR);
                    denied(test, () -> ProgramEditing.inspect(owner, position));
                    test.succeed();
                });
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void incompatibleGlobalsRequireOwnerResetFromTheEditor(GameTestHelper test) {
        var relative = new BlockPos(1, 1, 1);
        var level = test.getLevel();
        var position = test.absolutePos(relative);
        test.setBlock(relative, ComputerBlocks.computer);
        var owner = test.makeMockServerPlayerInLevel();
        var guest = test.makeMockServerPlayerInLevel();
        test.runBeforeTestEnd(() -> {
            level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(guest, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
        for (var player : List.of(owner, guest)) {
            player.setNoGravity(true);
            player.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 1, 0));
        }
        // A running program with a saved global that the edit below removes.
        install(test, test.getBlockEntity(relative, ComputerBlockEntity.class), COUNTER);
        ComputerBlocks.computer.setPlacedBy(level, position, level.getBlockState(position), owner,
                net.minecraft.world.item.ItemStack.EMPTY);
        test.runAfterDelay(4, () -> {
            try {
                changeTrust(test, owner, guest, position, "trust");
                var opened = ProgramEditing.inspect(guest, position);
                String renamed = "global boots = 0\non startup { boots++ }\n";
                try {
                    ProgramEditing.submit(guest, position, opened.revision(), renamed, true);
                    test.fail("Removing a saved global was applied without a reset");
                } catch (IllegalArgumentException expected) {
                    test.assertTrue(expected.getMessage().startsWith(ProgramEditing.INCOMPATIBLE_GLOBALS), expected.getMessage());
                }
                denied(test, () -> ProgramEditing.applyWithFreshGlobals(guest, position, opened.revision(), renamed));
                var applied = ProgramEditing.applyWithFreshGlobals(owner, position, opened.revision(), renamed);
                test.assertTrue(applied.source().equals(renamed), "Owner reset did not install the program");
                test.assertTrue(record(test, test.getBlockEntity(relative, ComputerBlockEntity.class)).snapshot() == null,
                        "Saved globals were not discarded");
                test.succeed();
            } catch (IOException error) {
                throw test.assertionException(error.getMessage());
            }
        });
    }

    private static void changeTrust(GameTestHelper test, net.minecraft.server.level.ServerPlayer owner,
            net.minecraft.server.level.ServerPlayer guest, BlockPos pos, String action) {
        try {
            int result = test.getLevel().getServer().getCommands().getDispatcher().execute(
                    "filasignal %s %d %d %d %s".formatted(action, pos.getX(), pos.getY(), pos.getZ(), guest.getUUID()),
                    owner.createCommandSourceStack());
            test.assertTrue(result == 1, "Could not modify trust");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw test.assertionException(error.getMessage());
        }
    }

    private static void denied(GameTestHelper test, java.util.concurrent.Callable<?> action) {
        try {
            action.call();
            test.fail("Rejected editor operation was accepted");
        } catch (IllegalArgumentException expected) {
            // The editor boundary rejects before applying a draft.
        } catch (Exception error) {
            throw test.assertionException(error.getMessage());
        }
    }

    @GameTest(maxTicks = 40)
    public void roundTripAndRemoval(GameTestHelper test) {
        BlockPos position = new BlockPos(1, 1, 1);
        BlockPos otherPosition = new BlockPos(2, 1, 1);
        test.setBlock(position, ComputerBlocks.computer);
        test.setBlock(otherPosition, ComputerBlocks.computer);
        install(test, test.getBlockEntity(position, ComputerBlockEntity.class), COUNTER);
        install(test, test.getBlockEntity(otherPosition, ComputerBlockEntity.class), COUNTER);
        test.runAfterDelay(4, () -> {
            var server = test.getLevel().getServer();
            var first = test.getBlockEntity(position, ComputerBlockEntity.class);
            var second = test.getBlockEntity(otherPosition, ComputerBlockEntity.class);
            test.assertTrue(ComputerLifecycle.active(server, first), "First computer did not activate");
            test.assertTrue(ComputerLifecycle.active(server, second), "Second computer did not activate");
            expectThreadGuard(test, first);
            CompoundTag nbt = first.saveWithFullMetadata(test.getLevel().registryAccess());
            var one = record(test, first);
            var two = record(test, second);
            test.assertTrue(!one.identity().equals(two.identity()), "Controllers share an ID");
            assertStarts(test, one, 1);
            assertStarts(test, two, 1);

            test.setBlock(position, Blocks.AIR);
            test.assertTrue(!ComputerLifecycle.active(server, first), "Removed computer remained attached");
            test.setBlock(position, ComputerBlocks.computer);
            var replacement = test.getBlockEntity(position, ComputerBlockEntity.class);
            replacement.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                    test.getLevel().registryAccess(), nbt));
            test.runAfterDelay(4, () -> {
                var restored = record(test, replacement);
                test.assertTrue(one.identity().equals(restored.identity()), "Restoration changed the ID");
                assertStarts(test, restored, 2);
                assertStarts(test, record(test, second), 1);
                test.succeed();
            });
        });
    }

    @GameTest(maxTicks = 800)
    public void resumesAfterFlushedChunkUnload(GameTestHelper test) {
        var level = test.getLevel();
        var server = level.getServer();
        var far = test.absolutePos(new BlockPos(640, 1, 640));
        int x = far.getX() >> 4;
        int z = far.getZ() >> 4;
        BlockPos at = new BlockPos((x << 4) + 8, far.getY(), (z << 4) + 8);
        var original = new java.util.concurrent.atomic.AtomicReference<ComputerBlockEntity>();
        level.setChunkForced(x, z, true);
        test.runBeforeTestEnd(() -> level.setChunkForced(x, z, false));
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(x, z) != null,
                        "Test chunk is still loading"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(at, ComputerBlocks.computer.defaultBlockState());
                    original.set((ComputerBlockEntity) level.getBlockEntity(at));
                    install(test, original.get(), COUNTER);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    assertStarts(test, record(test, original.get()), 1);
                    server.saveEverything(true, true, true);
                    level.setChunkForced(x, z, false);
                })
                .thenWaitUntil(() -> test.assertTrue(!ComputerLifecycle.active(server, original.get())
                                && level.getChunkSource().getChunkNow(x, z) == null,
                        "The test chunk did not unload its original runtime"))
                .thenExecute(() -> level.setChunkForced(x, z, true))
                .thenWaitUntil(() -> test.assertTrue(level.getChunkSource().getChunkNow(x, z) != null,
                        "Saved chunk is still loading"))
                .thenIdle(4)
                .thenExecute(() -> {
                    var restored = level.getBlockEntity(at);
                    test.assertTrue(restored instanceof ComputerBlockEntity && restored != original.get(),
                            "Save did not load a replacement computer");
                    var state = record(test, (ComputerBlockEntity) restored);
                    test.assertTrue(state.identity().equals(record(test, original.get()).identity()),
                            "Reloaded computer has a different identity");
                    assertStarts(test, state, 2);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 30)
    public void incompatibleSavedRecordIsPreserved(GameTestHelper test) {
        BlockPos position = new BlockPos(1, 1, 1);
        test.setBlock(position, ComputerBlocks.computer);
        var block = test.getBlockEntity(position, ComputerBlockEntity.class);
        var outer = new CompoundTag();
        var invalid = new CompoundTag();
        invalid.putInt("version", 99);
        outer.put(RECORD, invalid);
        block.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                test.getLevel().registryAccess(), outer));
        test.runAfterDelay(4, () -> {
            test.assertTrue(!ComputerLifecycle.active(test.getLevel().getServer(), block), "Bad record activated");
            var output = block.saveWithoutMetadata(test.getLevel().registryAccess());
            test.assertTrue(output.getCompoundOrEmpty(RECORD).getIntOr("version", 0) == 99,
                    "Incompatible data was overwritten");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 30)
    public void rejectsInvalidProgramWithoutReplacingSource(GameTestHelper test) {
        BlockPos position = new BlockPos(1, 1, 1);
        test.setBlock(position, ComputerBlocks.computer);
        var block = test.getBlockEntity(position, ComputerBlockEntity.class);
        String source = "on startup { missing++ }";
        var data = new ControllerData(java.util.UUID.randomUUID(), source, null);
        var outer = new CompoundTag();
        outer.put(RECORD, data.encode().convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue());
        block.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                test.getLevel().registryAccess(), outer));
        test.runAfterDelay(4, () -> {
            test.assertTrue(!ComputerLifecycle.active(test.getLevel().getServer(), block), "Invalid program activated");
            var saved = block.saveWithoutMetadata(test.getLevel().registryAccess()).getCompoundOrEmpty(RECORD);
            test.assertTrue(saved.getStringOr("source", "").equals(source), "Rejected source was overwritten");
            var repaired = new ControllerData(data.identity(), COUNTER, null);
            outer.put(RECORD, repaired.encode().convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue());
            block.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                    test.getLevel().registryAccess(), outer));
            test.runAfterDelay(4, () -> {
                test.assertTrue(ComputerLifecycle.active(test.getLevel().getServer(), block),
                        "Repaired record did not reactivate");
                test.assertTrue(record(test, block).identity().equals(data.identity()), "Repair changed the identity");
                assertStarts(test, record(test, block), 1);
                test.succeed();
            });
        });
    }

    /** A startup counter that tests read back with inspectStarts() to follow saved globals. */
    private static final String COUNTER = """
            global starts = 0
            on startup { starts++ }
            fn inspectStarts() { return starts }
            """;

    /** Replaces a freshly placed computer's program before it activates, as a world edit would. */
    private static void install(GameTestHelper test, ComputerBlockEntity block, String source) {
        var data = new ControllerData(java.util.UUID.randomUUID(), source, null);
        var outer = new CompoundTag();
        outer.put(RECORD, data.encode().convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue());
        block.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                test.getLevel().registryAccess(), outer));
    }

    @GameTest(maxTicks = 30)
    public void isolatesBudgetFaultAndKeepsCompletedWrites(GameTestHelper test) {
        BlockPos position = new BlockPos(1, 1, 1);
        test.setBlock(position, ComputerBlocks.computer);
        var block = test.getBlockEntity(position, ComputerBlockEntity.class);
        install(test, block, """
                global starts = 0
                on startup { starts++; while true {} }
                fn inspectStarts() { return starts }
                """);
        test.runAfterDelay(4, () -> {
            assertStarts(test, record(test, block), 1);
            test.succeed();
        });
    }

    private static ControllerData record(GameTestHelper test, ComputerBlockEntity block) {
        block.saveWithoutMetadata(test.getLevel().registryAccess());
        try {
            return block.activateData();
        } catch (IOException failure) {
            throw test.assertionException(BlockPos.ZERO, failure.getMessage());
        }
    }

    private static void assertStarts(GameTestHelper test, ControllerData data, double expected) {
        test.assertTrue(data.snapshot() != null, "A dispatched computer has no snapshot");
        var source = new SourceText("test", "computer.fsig", data.source());
        var resolved = Resolver.resolve(Parser.parse(source)).program().orElseThrow();
        var runtime = new Interpreter(resolved, ExecutionLimits.DEFAULT, ignored -> {});
        try {
            runtime.restoreGlobals(data.snapshot(), null);
        } catch (IOException failure) {
            throw test.assertionException(BlockPos.ZERO, failure.getMessage());
        }
        var result = runtime.invoke("inspectStarts", List.of());
        test.assertTrue(result.issue().isEmpty() && result.returns().values().equals(List.of(new Num(expected))),
                "Unexpected saved startup count");
    }

    private static void expectThreadGuard(GameTestHelper test, ComputerBlockEntity entity) {
        var server = test.getLevel().getServer();
        var check = new FutureTask<>(() -> {
            try {
                ComputerLifecycle.active(server, entity);
                return false;
            } catch (IllegalStateException expected) {
                return true;
            }
        });
        Thread.startVirtualThread(check);
        try {
            test.assertTrue(check.get(2, java.util.concurrent.TimeUnit.SECONDS), "Wrong-thread access went through");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw test.assertionException(BlockPos.ZERO, "Thread confinement check interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw test.assertionException(BlockPos.ZERO, "Could not check thread confinement: " + failure);
        }
    }
}
