package dev.wiktorfila.filasignal.fabric.peripheral;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Mode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class PeripheralCommands {
    private PeripheralCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = literal("filasignal");
            root.then(literal("status").then(position().executes(context -> modify(context, (runtime, node) ->
                    status(runtime, node)))));
            root.then(literal("alias").then(position().then(argument("name", StringArgumentType.word())
                    .executes(context -> modify(context, (runtime, node) -> {
                        String alias = StringArgumentType.getString(context, "name");
                        runtime.alias(node, alias.equals("-") ? null : alias);
                        return "Device alias updated.";
                    })))));
            var name = argument("name", StringArgumentType.word())
                    .executes(context -> modify(context, (runtime, node) -> configurePort(context, runtime, node)));
            var mode = argument("mode", StringArgumentType.word()).then(name);
            root.then(literal("port").then(position().then(argument("face", StringArgumentType.word()).then(mode))));
            var period = argument("period", IntegerArgumentType.integer(5, 200))
                    .executes(context -> modify(context, (runtime, node) -> configureSensor(context, runtime, node)));
            root.then(literal("sensor").then(position().then(argument("range", IntegerArgumentType.integer(1, 16))
                    .then(period))));
            root.then(literal("inventory").then(position().then(argument("face", StringArgumentType.word())
                    .then(argument("name", StringArgumentType.word()).executes(context -> modify(context, (runtime, node) -> {
                        NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(context.getSource()), true);
                        String alias = StringArgumentType.getString(context, "name");
                        runtime.inventory(node, face(context), alias.equals("-") ? null : alias);
                        return "Inventory face updated.";
                    }))))));
            root.then(accessCommand("claim", true, true));
            root.then(accessCommand("trust", false, true));
            root.then(accessCommand("untrust", false, false));
            root.then(literal("range").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(argument("blocks", IntegerArgumentType.integer(1, 64))
                    .executes(context -> report(context, () -> {
                        runtime(context).range(IntegerArgumentType.getInteger(context, "blocks"));
                        return "Wireless range updated.";
                    }))));
            root.then(literal("drives").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> report(context, () -> dev.wiktorfila.filasignal.fabric.drive.DriveStore
                            .of(context.getSource().getServer()).count() + " drive records in this world"))
                    .then(literal("prune").then(argument("days", IntegerArgumentType.integer(1, 3650))
                            // Without confirm this only reports what would be deleted.
                            .executes(context -> report(context, () -> {
                                int days = IntegerArgumentType.getInteger(context, "days");
                                int matched = dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(context.getSource().getServer())
                                        .prune(days * 24_000L, false);
                                return matched + " drive records are not installed and unused for " + days + " days. Their drives "
                                        + "may still be in chests or offline players' inventories; pruning erases their programs "
                                        + "and data. Nothing was deleted. To delete them: /filasignal drives prune " + days + " confirm";
                            }))
                            .then(literal("confirm").executes(context -> report(context, () -> {
                                int days = IntegerArgumentType.getInteger(context, "days");
                                int removed = dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(context.getSource().getServer())
                                        .prune(days * 24_000L, true);
                                return "Deleted " + removed + " drive records not installed and unused for " + days + " days";
                            }))))));
            root.then(literal("programs").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> report(context, () -> String.join("\n",
                            ComputerLifecycle.busiest(context.getSource().getServer(), 10)))));
            root.then(program("program", false)).then(program("program-reset", true));
            root.then(literal("install").then(position().executes(context -> report(context, () -> {
                authorizeProgram(context, false);
                String source = ProgramBook.read(context.getSource().getPlayer());
                install(context, source, false);
                return "Book program queued with saved globals.";
            }))));
            dispatcher.register(root);
        });
    }

    private static String status(PeripheralRuntime runtime, NetworkDirectory.Node node) {
        var directory = runtime.directory();
        var access = directory.access(node.network() == null ? node.id() : node.network());
        var inventories = new java.util.ArrayList<String>();
        for (Direction side : Direction.values()) {
            var binding = directory.inventories().at(node.id(), side);
            if (binding != null) {
                inventories.add(side.getSerializedName() + "=" + binding.alias());
            }
        }
        return node.kind() + " " + node.id() + ", alias=" + node.alias() + ", network=" + node.network()
                + ", reachable=" + runtime.available(node) + ", owner=" + (access == null ? "unclaimed" : access.owner())
                + ", trusted=" + (access == null ? 0 : access.trusted().size())
                + ", ports=" + node.ports() + ", inventories=" + inventories;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> accessCommand(
            String name, boolean claim, boolean grant) {
        var command = literal(name);
        if (claim) {
            command.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        }
        return command.then(position().then(argument("uuid", StringArgumentType.word())
                .executes(context -> modify(context, (runtime, node) -> {
                    if (node.network() != null && !node.network().equals(node.id())) {
                        throw new IllegalArgumentException("Select the owning computer to configure access");
                    }
                    UUID player = playerId(context);
                    var directory = runtime.directory();
                    NetworkPermission.require(directory, node, NetworkPermission.Actor.from(context.getSource()), true);
                    NetworkAccess access = directory.access(node.id());
                    if (claim ? access != null : access == null) {
                        throw new IllegalArgumentException(claim ? "This device already has an owner" : "An administrator must claim this device first");
                    }
                    runtime.access(node.id(), claim ? new NetworkAccess(player, java.util.Set.of()) : access.trust(player, grant));
                    return "Device access updated.";
                }))));
    }

    private static UUID playerId(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "uuid");
        var player = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player != null) {
            return player.getUUID();
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Enter the name of an online player or a player UUID");
        }
    }

    private static String configureSensor(CommandContext<CommandSourceStack> context, PeripheralRuntime runtime,
                                         NetworkDirectory.Node node) {
        if (!(runtime.live(node.id()) instanceof PeripheralEntity sensor)) {
            throw new IllegalArgumentException("Select a loaded Sensor");
        }
        sensor.configureSensor(IntegerArgumentType.getInteger(context, "range"),
                IntegerArgumentType.getInteger(context, "period"));
        return "Sensor settings updated.";
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> program(String name, boolean reset) {
        return literal(name).then(position().then(argument("source", StringArgumentType.greedyString())
                .executes(context -> report(context, () -> {
                    authorizeProgram(context, reset);
                    install(context, StringArgumentType.getString(context, "source"), reset);
                    return reset ? "Program queued and previous globals discarded." : "Program queued with saved globals.";
                }))));
    }

    private static void authorizeProgram(CommandContext<CommandSourceStack> context, boolean reset)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var runtime = runtime(context);
        var pos = BlockPosArgument.getBlockPos(context, "pos");
        var node = runtime.requireDevice(context.getSource().getLevel(), pos);
        if (!NetworkPermission.allows(runtime.directory(), node, NetworkPermission.Actor.from(context.getSource()), reset)) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("You do not have access to change this computer's program")).create();
        }
    }

    private static void install(CommandContext<CommandSourceStack> context, String source, boolean reset)
            throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException {
        ComputerLifecycle.program(context.getSource().getLevel(), BlockPosArgument.getBlockPos(context, "pos"), source, reset);
    }

    private static String configurePort(CommandContext<CommandSourceStack> context, PeripheralRuntime runtime,
                                        NetworkDirectory.Node node) {
        Direction face = face(context);
        Mode mode = Mode.valueOf(StringArgumentType.getString(context, "mode").toUpperCase(Locale.ROOT));
        String alias = StringArgumentType.getString(context, "name");
        runtime.port(node, face, mode, alias.equals("-") ? null : alias);
        return "Redstone port updated.";
    }

    private static Direction face(CommandContext<CommandSourceStack> context) {
        return Direction.valueOf(StringArgumentType.getString(context, "face").toUpperCase(Locale.ROOT));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.coordinates.Coordinates> position() {
        return argument("pos", BlockPosArgument.blockPos());
    }

    private static PeripheralRuntime runtime(CommandContext<CommandSourceStack> context) {
        return PeripheralLifecycle.runtime(context.getSource().getServer());
    }

    private static int modify(CommandContext<CommandSourceStack> context, Edit edit)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return report(context, () -> {
            var runtime = runtime(context);
            var level = context.getSource().getLevel();
            var pos = BlockPosArgument.getBlockPos(context, "pos");
            if (!LoadedChunks.contains(level, pos)) {
                throw new IllegalArgumentException("Position is unloaded");
            }
            var node = runtime.requireDevice(level, pos);
            NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(context.getSource()), false);
            return edit.apply(runtime, node);
        });
    }

    private static int report(CommandContext<CommandSourceStack> context, Operation action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            String message = action.run();
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return 1;
        } catch (IllegalArgumentException | IOException error) {
            context.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    private interface Edit {
        String apply(PeripheralRuntime runtime, NetworkDirectory.Node node);
    }

    private interface Operation {
        String run() throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException;
    }
}
