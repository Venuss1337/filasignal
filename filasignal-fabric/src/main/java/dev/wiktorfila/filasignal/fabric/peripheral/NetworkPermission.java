package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Central server-side rules shared by commands, the linking tool and world callbacks. */
final class NetworkPermission {
    record Actor(UUID identity, boolean administrator) {
        static Actor from(ServerPlayer player) {
            return new Actor(player.getUUID(), Commands.LEVEL_GAMEMASTERS.check(player.permissions()));
        }

        static Actor from(CommandSourceStack source) {
            ServerPlayer player = source.getPlayer();
            return new Actor(player == null || player.isSpectator() ? null : player.getUUID(),
                    Commands.LEVEL_GAMEMASTERS.check(source.permissions()));
        }
    }

    private NetworkPermission() {}

    static boolean allows(NetworkDirectory directory, Node node, Actor actor, boolean ownerOnly) {
        if (actor.administrator()) {
            return true;
        }
        if (node == null || actor.identity() == null) {
            return false;
        }
        UUID authority = node.network() == null ? node.id() : node.network();
        NetworkAccess access = directory.access(authority);
        return access != null && (access.owner().equals(actor.identity())
                || (!ownerOnly && access.trusted().contains(actor.identity())));
    }

    static void require(NetworkDirectory directory, Node node, Actor actor, boolean ownerOnly) {
        if (!allows(directory, node, actor, ownerOnly)) {
            throw new IllegalArgumentException(ownerOnly ? "Only the owner or an administrator can do that"
                    : "Only the owner, a trusted player or an administrator can configure this device");
        }
    }
}
