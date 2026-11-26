package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.Function;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Slot;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Spec;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Target;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.method;
import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.slot;
import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/** Network discovery, the network repeater and the inventory module. */
final class NetworkApis {
    static final int MAX_LISTED = 256;

    private NetworkApis() {}

    /** {@code devices.list()} describes every member of the computer's network, connected or not. */
    static List<Function> functions(HardwareHosts hosts) {
        return List.of(new Function("devices.list", List.of(), result(Type.LIST),
                "List this computer's network members with name, id, kind, capabilities and connected.",
                Set.of("devices.read"), 16, (args, ctx) -> {
                    var listed = new ArrayList<Value>();
                    for (Node node : hosts.runtime().directory().nodes()) {
                        if (listed.size() < MAX_LISTED && hosts.controller().equals(node.network())
                                && !node.id().equals(hosts.controller())) {
                            ctx.charge(32);
                            listed.add(hosts.describe(node));
                        }
                    }
                    return ok(new ListValue(listed));
                }).withUsage(Set.of("host.denied"), "members, err = devices.list()"));
    }

    static List<Spec> specs(HardwareHosts hosts) {
        return List.of(repeater(hosts), inventoryModule(hosts));
    }

    private static Spec repeater(HardwareHosts hosts) {
        return new Spec(Kind.REPEATER, "Repeater", "repeaters.from", "devices.read",
                "Resolve a network repeater by name.", "router, err = repeaters.from(\"router\")", List.of(
                method("links", Type.LIST, "List directly linked devices with name, id, kind, capabilities and connected.",
                        "devices.read", 16, "linked, err = router.links()", (target, args, ctx) -> {
                            var linked = new ArrayList<Value>();
                            for (Node node : neighbors(hosts, target)) {
                                ctx.charge(64);
                                linked.add(hosts.describe(node));
                            }
                            return new ListValue(linked);
                        }),
                method("status", Type.DICT, "Summarize links: total, connected and the computer's network ID.",
                        "devices.read", 16, "info, err = router.status()", (target, args, ctx) -> {
                            var nodes = neighbors(hosts, target);
                            ctx.charge(16L * nodes.size());
                            long connected = nodes.stream().filter(hosts.runtime()::available).count();
                            var values = new LinkedHashMap<String, Value>();
                            values.put("links", new Num(nodes.size()));
                            values.put("connectedLinks", new Num(connected));
                            values.put("network", new Text(hosts.controller().toString()));
                            return new DictValue(values);
                        })));
    }

    private static List<Node> neighbors(HardwareHosts hosts, Target target) {
        var directory = hosts.runtime().directory();
        return directory.neighbors(target.node().id()).stream().map(directory::node)
                .filter(node -> node != null).sorted(java.util.Comparator.comparing(node -> node.id().toString())).toList();
    }

    private static Spec inventoryModule(HardwareHosts hosts) {
        var inventories = new InventoryService(hosts.runtime());
        return new Spec(Kind.INVENTORY_MODULE, "InventoryModule", "inventory.module", InventoryHosts.READ,
                "Resolve an inventory module by name.", "module, err = inventory.module(\"warehouse\")", List.of(
                method("inventories", Type.LIST, "List approved faces with name, face and available.", InventoryHosts.READ, 16,
                        "faces, err = module.inventories()", (target, args, ctx) -> {
                            var listed = new ArrayList<Value>();
                            for (Direction face : Direction.values()) {
                                var binding = hosts.runtime().directory().inventories().at(target.node().id(), face);
                                if (binding != null) {
                                    ctx.charge(128);
                                    boolean available;
                                    try {
                                        hosts.openInventory(inventories, target, binding);
                                        available = true;
                                    } catch (HostFailure unavailable) {
                                        available = false;
                                    }
                                    var values = new LinkedHashMap<String, Value>();
                                    values.put("name", new Text(binding.alias()));
                                    values.put("face", new Text(face.getSerializedName()));
                                    values.put("available", new Bool(available));
                                    listed.add(new DictValue(values));
                                }
                            }
                            return new ListValue(listed);
                        }),
                method("inventory", Type.HANDLE, "Resolve the approved inventory on a face as an Inventory.", InventoryHosts.READ, 16,
                        "storage, err = module.inventory(\"north\")", Set.of("argument.invalid", "inventory.unknown",
                                "inventory.unavailable", "inventory.stale", "inventory.missing", "inventory.unsupported",
                                "inventory.limit", "world.unloaded"), (target, args, ctx) -> {
                            Direction face = HardwareHosts.face(args.getFirst());
                            var binding = hosts.runtime().directory().inventories().at(target.node().id(), face);
                            if (binding == null) {
                                throw new HostFailure("inventory.unknown", "No inventory is approved on the " + face.getSerializedName() + " face");
                            }
                            ctx.charge(256);
                            hosts.openInventory(inventories, target, binding);
                            return new HostHandle("Inventory", hosts.controller(), binding.id());
                        }, HardwareHosts.choice("face")),
                method("adjacent", Type.LIST, "List neighboring blocks with face, block ID and whether they expose items.",
                        InventoryHosts.READ, 16, "around, err = module.adjacent()", Set.of("world.unloaded"),
                        (target, args, ctx) -> {
                            var listed = new ArrayList<Value>();
                            for (Direction face : Direction.values()) {
                                ctx.charge(64);
                                var pos = target.device().getBlockPos().relative(face);
                                if (!LoadedChunks.contains(target.level(), pos)) {
                                    throw new HostFailure("world.unloaded", "A neighboring chunk is unloaded");
                                }
                                var state = target.level().getBlockState(pos);
                                var values = new LinkedHashMap<String, Value>();
                                values.put("face", new Text(face.getSerializedName()));
                                values.put("block", new Text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
                                values.put("container", new Bool(ItemStorage.SIDED.find(target.level(), pos, face.getOpposite()) != null));
                                values.put("approved", new Bool(hosts.runtime().directory().inventories().at(target.node().id(), face) != null));
                                listed.add(new DictValue(values));
                            }
                            return new ListValue(listed);
                        })));
    }

}
