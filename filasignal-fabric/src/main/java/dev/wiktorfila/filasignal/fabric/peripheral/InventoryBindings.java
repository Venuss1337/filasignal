package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.core.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One server-thread directory of approved interface faces, indexed by identity and network alias. */
final class InventoryBindings {
    record Binding(UUID id, UUID network, UUID interfaceId, Direction face, String alias, UUID inventoryId, UUID owner) {}
    private record Address(UUID network, String alias) {}
    private record Face(UUID interfaceId, Direction direction) {}
    private final Map<UUID, Binding> identities = new LinkedHashMap<>();
    private final Map<Address, Binding> aliases = new LinkedHashMap<>();
    private final Map<Face, Binding> faces = new LinkedHashMap<>();

    Binding byId(UUID id) { return identities.get(id); }
    Binding byAlias(UUID network, String alias) { return aliases.get(new Address(network, alias)); }
    List<Binding> all() { return List.copyOf(identities.values()); }

    Binding at(UUID interfaceId, Direction face) {
        return faces.get(new Face(interfaceId, face));
    }

    void add(Binding binding) {
        NetworkDirectory.name(binding.alias());
        if (identities.containsKey(binding.id()) || byAlias(binding.network(), binding.alias()) != null
                || at(binding.interfaceId(), binding.face()) != null) {
            throw new IllegalArgumentException("Duplicate inventory alias, identity or interface face");
        }
        identities.put(binding.id(), binding);
        aliases.put(new Address(binding.network(), binding.alias()), binding);
        faces.put(new Face(binding.interfaceId(), binding.face()), binding);
    }

    void remove(Binding binding) {
        if (binding != null) {
            identities.remove(binding.id());
            aliases.remove(new Address(binding.network(), binding.alias()));
            faces.remove(new Face(binding.interfaceId(), binding.face()));
        }
    }

    void removeDevice(UUID id) {
        for (Binding binding : all()) {
            if (binding.interfaceId().equals(id) || binding.network().equals(id)) {
                remove(binding);
            }
        }
    }
}
