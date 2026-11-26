package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ProgramEditing;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork.*;

/**
 * Release-candidate security checks of the client request boundary: what strangers and visitors can do and learn
 * through device forms and remote editing, and how forged or distant requests are refused.
 */
public final class SecurityGameTests {
    private static final BlockPos COMPUTER = new BlockPos(1, 1, 1);
    private static final BlockPos REPEATER = new BlockPos(2, 1, 1);
    private static final BlockPos DETECTOR = new BlockPos(3, 1, 1);
    private static final BlockPos PANEL = new BlockPos(4, 1, 1);
    private static final BlockPos ACCESS = new BlockPos(5, 1, 1);

    @GameTest(maxTicks = 60)
    public void strangersAndVisitorsCannotChangeOrInspectDevices(GameTestHelper test) {
        var level = test.getLevel();
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        test.setBlock(COMPUTER, ComputerBlocks.block());
        test.setBlock(REPEATER, PeripheralBlocks.block(Kind.REPEATER));
        test.setBlock(DETECTOR, PeripheralBlocks.block(Kind.ENTITY_DETECTOR));
        test.setBlock(PANEL, PeripheralBlocks.block(Kind.CONTROL_PANEL));
        test.setBlock(ACCESS, PeripheralBlocks.block(Kind.ACCESS_CONTROLLER));
        ServerPlayer owner = player(test, new BlockPos(3, 1, 3));
        ServerPlayer stranger = player(test, new BlockPos(3, 1, 3));
        ServerPlayer distant = player(test, new BlockPos(3, 1, 3));
        test.runBeforeTestEnd(() -> List.of(owner, stranger, distant)
                .forEach(player -> level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED)));
        test.startSequence().thenIdle(5).thenExecute(() -> {
            for (BlockPos pos : List.of(COMPUTER, REPEATER, DETECTOR, PANEL, ACCESS)) {
                var at = test.absolutePos(pos);
                level.getBlockState(at).getBlock().setPlacedBy(level, at, level.getBlockState(at), owner, ItemStack.EMPTY);
            }
        }).thenIdle(5).thenExecute(() -> {
            var root = runtime.requireDevice(level, test.absolutePos(COMPUTER)).id();
            var relay = id(test, REPEATER);
            runtime.link(root, relay, false);
            for (BlockPos pos : List.of(DETECTOR, PANEL, ACCESS)) {
                runtime.link(relay, id(test, pos), false);
            }
            runtime.alias(runtime.directory().node(id(test, DETECTOR)), "gate");
            entity(test, PANEL).publicUse(true);

            // A stranger's every change is refused and changes nothing.
            for (int action : List.of(ALIAS, RADIUS, DETECT_FILTER, TRUST, STORAGE_VIEW, STORAGE_CLEAR, PROGRAM_START,
                    PROGRAM_EDIT, DRIVE_EJECT, DRIVE_LABEL)) {
                var form = respond(stranger, new Edit(test.absolutePos(DETECTOR), id(test, DETECTOR), action, "evil", "trust"));
                test.assertTrue(refused(form), "A stranger's request " + action + " was accepted");
            }
            for (int action : List.of(PUBLIC_USE, ACCESS_ALLOW, ACCESS_OUTPUT, ACCESS_TEST)) {
                var form = respond(stranger, new Edit(test.absolutePos(ACCESS), id(test, ACCESS), action, "public", "add"));
                test.assertTrue(refused(form), "A stranger's Access Controller request " + action + " was accepted");
            }
            test.assertTrue("gate".equals(runtime.directory().node(id(test, DETECTOR)).alias()), "A stranger renamed a device");
            test.assertFalse(entity(test, ACCESS).publicUse(), "A stranger made the Access Controller public");
            test.assertTrue(entity(test, ACCESS).allowed().isEmpty(), "A stranger joined the access list");

            // A visitor to a public panel may press and refresh, and learns only the buttons.
            var visit = respond(stranger, new Edit(test.absolutePos(PANEL), id(test, PANEL), REFRESH, "", ""));
            test.assertFalse(refused(visit), "A visitor could not use a public panel: " + visit.status());
            test.assertTrue(visit.settings().endsWith("\tvisitor"), "The visitor form was not marked as a visitor's");
            test.assertTrue(visit.readings().isEmpty() && visit.program().isEmpty(),
                    "A visitor received the access summary or program state: " + visit.readings() + " / " + visit.program());
            var rename = respond(stranger, new Edit(test.absolutePos(PANEL), id(test, PANEL), PANEL_LABEL, "Hacked", "1"));
            test.assertTrue(refused(rename) && entity(test, PANEL).labels().getFirst().equals("Button 1"),
                    "A visitor renamed a button");

            // Forged identities and distant requests are refused even for the owner.
            test.assertTrue(refused(respond(owner, new Edit(test.absolutePos(DETECTOR), UUID.randomUUID(), ALIAS, "x", ""))),
                    "A request naming another device's identity was accepted");
            distant.snapTo(Vec3.atCenterOf(test.absolutePos(DETECTOR)).add(40, 0, 0));
            test.assertTrue(refused(respond(distant, new Edit(test.absolutePos(DETECTOR), id(test, DETECTOR), REFRESH, "", ""))),
                    "A distant request was accepted");

            // Remote drive editing and the computer program stay closed to strangers.
            for (String target : List.of(ProgramEditing.COMPUTER, "remote:gate")) {
                try {
                    ProgramEditing.inspect(stranger, test.absolutePos(COMPUTER), target);
                    test.fail("A stranger opened the editing target '" + target + "'");
                } catch (IllegalArgumentException | IOException expected) {
                    // Refused at the editor boundary.
                }
            }
        }).thenSucceed();
    }

    /** A failure form carries no device data: its kind is empty. */
    private static boolean refused(Form form) {
        return form.kind().isEmpty();
    }

    private static UUID id(GameTestHelper test, BlockPos pos) {
        return entity(test, pos).identity();
    }

    private static PeripheralEntity entity(GameTestHelper test, BlockPos pos) {
        return (PeripheralEntity) test.getLevel().getBlockEntity(test.absolutePos(pos));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper test, BlockPos relative) {
        var player = test.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.snapTo(Vec3.atCenterOf(test.absolutePos(relative)).add(0, 1, 0));
        return player;
    }
}
