# Inventory automation

Connect a single-block inventory to a face of a reachable Network Interface or [Inventory Module](HARDWARE.md#inventory-module). As the computer owner, run `/filasignal inventory X Y Z north storage` at the device, or approve the face in its form. Configure another face as `output`. Directions describe the interface in world coordinates. FilaSignal accesses the opposite face of the inventory, so furnace and other sided rules apply.

| Inventory method | Value on success |
| --- | --- |
| `size()` | Number of sided slots. |
| `getSlot(index)` | Read-only snapshot with `id`, `count`, and `maxCount`. A valid empty slot returns `nil, nil`. Slot indices start at zero. |
| `count(itemId)` | Total visible count of the specified registered item, across component variants. |
| `fullness()` | Occupied capacity divided by summed slot capacity, between 0 and 1. Zero capacity returns 0. |
| `isEmpty()` | Whether all visible slots are empty. |
| `contains(itemId)` | Whether at least one of the item is present. |
| `occupied()` | Number of non-empty sided slots. |
| `items()` | Dictionary of item ID to total count over the visible slots. |
| `moveTo(destination, itemId, amount)` | Quantity moved, from zero up to a requested whole number of 1–256. Component variants are preserved. |

Every call returns two results. Success returns `value, nil`. Expected failure returns `nil, error`. Use namespaced runtime registry IDs for both vanilla and modded items. An absent registry entry returns `item.unknown`. Slot snapshots omit arbitrary components, while transfers preserve the original components. The basic item filter matches all variants of the requested item.

Inventory aliases are case-sensitive and unique within the network. They have a namespace independent from device and port aliases. Use `-` to revoke a face. Renaming the same face and block preserves its handle identity. Replacement invalidates its grant even at the same coordinates. The owner must configure the face again. Disconnection or unload leaves the grant saved while the inventory is unavailable. Each call revalidates identity, owner, scope, route, loaded chunk, and protection policy.

## Compatibility and limits

The prototype accepts single-block Fabric sided slotted storage with up to 54 visible slots. Double chests, entity inventories, unslotted providers, locked containers and unopened loot containers are outside the supported subset. Operations execute on the server thread and charge for slots and transfer size before mutating storage. The service holds no content cache and issues no chunk tickets.

Transfers use the [Fabric Transfer API](https://github.com/FabricMC/fabric-api/blob/26.3/_autodocs/api-reference/14-transfer-storage.md). Zero and partial transfers are normal successes. Third-party compatibility depends on the storage provider following the sided and transaction contracts. A later script fault does not roll back a completed move. There is no cross-world-save transaction.

World border and dedicated-server spawn protection apply to automation. Protection mods can register `InventoryProtection.CHECK` to veto configuration or calls by owner UUID, including while the owner is offline. Test claim integration with the server's installed mods before deployment. See [permissions](PERMISSIONS-AND-THREADING.md) and the [sensor inventory example](../../examples/sensor-inventory.fsig).
