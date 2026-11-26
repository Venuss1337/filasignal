package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.peripheral.InventoryService.OpenInventory;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/** Publishes inventory member contracts and charges storage work before touching item state. */
final class InventoryHosts {
    static final String READ = "inventory.read";
    static final String TRANSFER = "inventory.transfer";
    private static final String TYPE = "Inventory";
    private static final Slot RECEIVER = handle("inventory", TYPE);
    private static final Slot OUTPUT = handle("destination", TYPE);
    private static final Slot ITEM = new Slot("item", Set.of(Type.STRING));
    private static final Slot INDEX = new Slot("index", Set.of(Type.NUMBER));
    private static final Slot AMOUNT = new Slot("amount", Set.of(Type.NUMBER));
    private static final int MAX_TRANSFER = 256;
    private static final Set<String> ACCESS_ERRORS = Set.of("host.denied", "inventory.unknown", "inventory.unavailable",
            "inventory.stale", "inventory.missing", "inventory.limit", "inventory.unsupported", "device.unavailable", "world.unloaded");

    private final PeripheralRuntime runtime;
    private final UUID controller;
    private final UUID local;
    private final InventoryService inventories;

    InventoryHosts(PeripheralRuntime runtime, UUID controller) {
        this(runtime, controller, null);
    }

    /**
     * Inventory objects for one scope. With a {@code local} device, handles are issued by that device itself and
     * only its own approved faces can be opened, without requiring a route to the computer.
     */
    InventoryHosts(PeripheralRuntime runtime, UUID scope, UUID local) {
        this.runtime = runtime;
        this.controller = scope;
        this.local = local;
        inventories = new InventoryService(runtime);
    }

    Function factory() {
        return HostContracts.factory("inventory.from", TYPE, READ, "Resolve a configured adjacent inventory alias.",
                (args, ctx) -> {
                    String alias = ((Text) args.getFirst()).value();
                    ctx.charge(256L + alias.length());
                    var binding = runtime.directory().inventories().byAlias(controller, alias);
                    try {
                        inventories.open(controller, binding, false);
                        return ok(new HostHandle(TYPE, controller, binding.id()));
                    } catch (HostFailure error) {
                        return error(error.code(), error.getMessage());
                    }
                }).withUsage(ACCESS_ERRORS, "storage, err = inventory.from(\"storage\")");
    }

    ObjectType type() {
        return new ObjectType(TYPE, Map.of(), Map.of(
                "size", read("size", List.of(RECEIVER), Type.NUMBER, "Count sided slots.",
                        (inventory, args, ctx) -> new Num(inventory.slots())),
                "getSlot", read("getSlot", List.of(RECEIVER, INDEX), Type.DICT, "Read an immutable snapshot of a zero-based slot.",
                        (inventory, args, ctx) -> slot(inventory, ((Num) args.get(1)).value())),
                "count", read("count", List.of(RECEIVER, ITEM), Type.NUMBER, "Count the item ID across its component variants.",
                        (inventory, args, ctx) -> count(inventory, item((Text) args.get(1), ctx))),
                "fullness", read("fullness", List.of(RECEIVER), Type.NUMBER, "Read occupied capacity as a fraction between zero and one.",
                        (inventory, args, ctx) -> fullness(inventory)),
                "isEmpty", read("isEmpty", List.of(RECEIVER), Type.BOOL, "Test whether every visible slot is empty.",
                        (inventory, args, ctx) -> new Bool(count(inventory, null).value() == 0)),
                "contains", read("contains", List.of(RECEIVER, ITEM), Type.BOOL, "Test whether at least one of the item is present.",
                        (inventory, args, ctx) -> new Bool(count(inventory, item((Text) args.get(1), ctx)).value() > 0)),
                "occupied", read("occupied", List.of(RECEIVER), Type.NUMBER, "Count non-empty sided slots.",
                        (inventory, args, ctx) -> occupied(inventory)),
                "items", read("items", List.of(RECEIVER), Type.DICT, "Total each item ID present, keyed by ID.",
                        (inventory, args, ctx) -> items(inventory)),
                "moveTo", new Function(TYPE + ".moveTo", List.of(RECEIVER, OUTPUT, ITEM, AMOUNT), result(Type.NUMBER),
                        "Move up to 256 items while preserving each component variant.",
                        Set.of(TRANSFER), 24, this::move).withUsage(errors("inventory.same", "argument.range",
                                "item.identifier", "item.unknown"), "moved, err = storage.moveTo(output, \"minecraft:iron_ingot\", 16)")));
    }

    private Function read(String name, List<Slot> parameters, Type resultType, String documentation, Read operation) {
        return new Function(TYPE + "." + name, parameters, result(resultType), documentation, Set.of(READ), 12, (args, ctx) -> {
            try {
                OpenInventory from = open((HostHandle) args.getFirst(), false, ctx);
                ctx.charge(2L * from.slots());
                return ok(operation.read(from, args, ctx));
            } catch (HostFailure error) {
                return error(error.code(), error.getMessage());
            }
        }).withUsage(switch (name) {
            case "getSlot" -> errors("argument.range");
            case "count", "contains" -> errors("item.identifier", "item.unknown");
            default -> ACCESS_ERRORS;
        }, switch (name) {
            case "getSlot" -> "slot, err = storage.getSlot(0)";
            case "count" -> "count, err = storage.count(\"minecraft:iron_ingot\")";
            case "contains" -> "present, err = storage.contains(\"minecraft:iron_ingot\")";
            default -> "value, err = storage." + name + "()";
        });
    }

    private static Set<String> errors(String... extra) {
        var errors = new java.util.HashSet<>(ACCESS_ERRORS);
        errors.addAll(List.of(extra));
        return errors;
    }

    private OpenInventory open(HostHandle handle, boolean write, Context context) {
        context.charge(256);
        var binding = controller.equals(handle.scope()) ? runtime.directory().inventories().byId(handle.resource()) : null;
        if (local != null) {
            if (binding == null || !binding.interfaceId().equals(local)) {
                throw new HostFailure("inventory.unknown", "This device has no approved inventory with that identity");
            }
            return inventories.open(binding.network(), binding, write, false);
        }
        return inventories.open(controller, binding, write);
    }

    private List<Value> move(List<Value> args, Context context) {
        try {
            int amount = integer((Num) args.get(3), 1, MAX_TRANSFER, "Transfer amount must be an integer from 1 through 256");
            Item item = item((Text) args.get(2), context);
            OpenInventory from = open((HostHandle) args.getFirst(), true, context);
            OpenInventory to = open((HostHandle) args.get(1), true, context);
            if (from.id().equals(to.id())) {
                return error("inventory.same", "Source and destination must be different inventories");
            }
            context.charge(2L * from.slots() * (to.slots() + 1L) + amount);
            long moved = StorageUtil.move(from.boundedStorage(), to.boundedStorage(), variant -> variant.isOf(item), amount, null);
            return ok(new Num(moved));
        } catch (HostFailure error) {
            return error(error.code(), error.getMessage());
        }
    }

    private static Value slot(OpenInventory inventory, double position) {
        int index = integer(new Num(position), 0, inventory.slots() - 1, "Slot index is out of range for this side");
        var slot = inventory.storage().getSlot(index);
        if (slot.isResourceBlank()) {
            return Nil.INSTANCE;
        }
        var variant = slot.getResource();
        var stack = variant.toStack();
        return new DictValue(Map.of(
                "id", new Text(BuiltInRegistries.ITEM.getKey(variant.getItem()).toString()),
                "count", new Num(slot.getAmount()),
                "maxCount", new Num(stack.getMaxStackSize())));
    }

    private static Num count(OpenInventory inventory, Item item) {
        double count = 0;
        for (int i = 0; i < inventory.slots(); i++) {
            var slot = inventory.storage().getSlot(i);
            if (!slot.isResourceBlank() && (item == null || slot.getResource().isOf(item))) {
                count += slot.getAmount();
            }
        }
        return new Num(count);
    }

    private static Num occupied(OpenInventory inventory) {
        int occupied = 0;
        for (int i = 0; i < inventory.slots(); i++) {
            if (!inventory.storage().getSlot(i).isResourceBlank()) {
                occupied++;
            }
        }
        return new Num(occupied);
    }

    // At most 54 slots, so at most 54 distinct entries.
    private static DictValue items(OpenInventory inventory) {
        var totals = new java.util.TreeMap<String, Value>();
        for (int i = 0; i < inventory.slots(); i++) {
            var slot = inventory.storage().getSlot(i);
            if (!slot.isResourceBlank()) {
                String id = BuiltInRegistries.ITEM.getKey(slot.getResource().getItem()).toString();
                double prior = totals.get(id) instanceof Num number ? number.value() : 0;
                totals.put(id, new Num(prior + slot.getAmount()));
            }
        }
        return new DictValue(totals);
    }

    private static Num fullness(OpenInventory inventory) {
        double amount = 0;
        double capacity = 0;
        for (int i = 0; i < inventory.slots(); i++) {
            var slot = inventory.storage().getSlot(i);
            double limit = Math.max(0, slot.getCapacity());
            amount += Math.clamp((double) slot.getAmount(), 0, limit);
            capacity += limit;
        }
        return new Num(capacity == 0 ? 0 : amount / capacity);
    }

    private static Item item(Text text, Context context) {
        String value = text.value();
        context.charge(value.length());
        var id = value.length() <= 256 && value.contains(":") ? Identifier.tryParse(value) : null;
        if (id == null) {
            throw new HostFailure("item.identifier", "Use a namespaced item ID such as minecraft:iron_ingot");
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() ->
                new HostFailure("item.unknown", "No registered item has that identifier"));
    }

    private static int integer(Num number, int min, int max, String message) {
        double value = number.value();
        if (value < min || value > max || value != Math.rint(value)) {
            throw new HostFailure("argument.range", message);
        }
        return (int) value;
    }

    @FunctionalInterface
    private interface Read {
        Value read(OpenInventory inventory, List<Value> args, Context context);
    }
}
