package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.EnumMap;
import java.util.Map;

/** Common registrations for network devices, dedicated hardware and the linking tool. */
public final class PeripheralBlocks {
    static Block networkInterface;
    static Block sensor;
    static BlockEntityType<PeripheralEntity> interfaceType;
    static BlockEntityType<PeripheralEntity> sensorType;
    /** One block entity type serves every dedicated hardware block. Its kind comes from the block. */
    static BlockEntityType<PeripheralEntity> hardwareType;
    private static final Map<Kind, Block> BLOCKS = new EnumMap<>(Kind.class);

    /** Registry names of the dedicated hardware blocks. */
    static final Map<Kind, String> HARDWARE = Map.ofEntries(
            Map.entry(Kind.REPEATER, "network_repeater"),
            Map.entry(Kind.ENTITY_DETECTOR, "entity_detector"),
            Map.entry(Kind.WEATHER_STATION, "weather_station"),
            Map.entry(Kind.REDSTONE_CONTROLLER, "redstone_controller"),
            Map.entry(Kind.INVENTORY_MODULE, "inventory_module"),
            Map.entry(Kind.ENVIRONMENT_SENSOR, "environment_sensor"),
            Map.entry(Kind.DISPLAY, "display_module"),
            Map.entry(Kind.ALARM, "alarm_module"),
            Map.entry(Kind.STORAGE_SERVER, "storage_server"),
            Map.entry(Kind.ACCESS_CONTROLLER, "access_controller"),
            Map.entry(Kind.CONTROL_PANEL, "control_panel"),
            Map.entry(Kind.WIRELESS_BRIDGE, "wireless_bridge"));

    private PeripheralBlocks() {}

    /** Registers the devices before vanilla freezes registries. */
    public static void register() {
        networkInterface = registerBlock("network_interface", Kind.INTERFACE, Blocks.IRON_BLOCK);
        sensor = registerBlock("sensor", Kind.SENSOR, Blocks.OBSERVER);
        interfaceType = registerEntity("network_interface", networkInterface);
        sensorType = registerEntity("sensor", sensor);
        for (Kind kind : Kind.values()) {
            String name = HARDWARE.get(kind);
            if (name != null) {
                registerBlock(name, kind, Blocks.IRON_BLOCK);
            }
        }
        hardwareType = registerEntity("hardware", HARDWARE.keySet().stream().map(BLOCKS::get).toArray(Block[]::new));
        var key = ResourceKey.create(Registries.ITEM, id("linking_tool"));
        Registry.register(BuiltInRegistries.ITEM, key, new LinkingTool(new Item.Properties().setId(key).stacksTo(1)));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("devices"), FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.filasignal.devices"))
                .icon(() -> new ItemStack(BLOCKS.get(Kind.REPEATER)))
                .displayItems((parameters, output) -> {
                    output.accept(BuiltInRegistries.ITEM.getValue(id("programmable_computer")));
                    output.accept(BuiltInRegistries.ITEM.getValue(id("linking_tool")));
                    output.accept(dev.wiktorfila.filasignal.fabric.drive.Drives.DRIVE);
                    for (Kind kind : Kind.values()) {
                        if (BLOCKS.containsKey(kind)) {
                            output.accept(BLOCKS.get(kind));
                        }
                    }
                }).build());
    }

    /** The block entity type shared by dedicated hardware, used to register the Display Module's renderer. */
    public static BlockEntityType<PeripheralEntity> hardwareType() { return hardwareType; }

    /** The registered block for a peripheral kind, or null for the computer. */
    static Block block(Kind kind) {
        return BLOCKS.get(kind);
    }

    static boolean isPeripheral(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof PeripheralBlock;
    }

    private static Block registerBlock(String name, Kind kind, Block base) {
        var key = ResourceKey.create(Registries.BLOCK, id(name));
        var item = ResourceKey.create(Registries.ITEM, id(name));
        var properties = BlockBehaviour.Properties.ofFullCopy(base)
                .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE).setId(key);
        Block block = Registry.register(BuiltInRegistries.BLOCK, key, switch (kind) {
            case ALARM -> new AlarmBlock(properties.lightLevel(state -> state.getValue(BlockStateProperties.LIT) ? 10 : 0));
            case ACCESS_CONTROLLER -> new AccessControllerBlock(properties);
            case ENTITY_DETECTOR, WEATHER_STATION, DISPLAY, STORAGE_SERVER, CONTROL_PANEL, WIRELESS_BRIDGE ->
                    new OrientedBlock(properties, kind);
            default -> new PeripheralBlock(properties, kind);
        });
        Registry.register(BuiltInRegistries.ITEM, item, new DeviceItem(block, new Item.Properties().setId(item)));
        BLOCKS.put(kind, block);
        return block;
    }

    private static BlockEntityType<PeripheralEntity> registerEntity(String name, Block... blocks) {
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(name),
                FabricBlockEntityTypeBuilder.create((position, state) ->
                        new PeripheralEntity(position, state, ((PeripheralBlock) state.getBlock()).kind()), blocks).build());
    }

    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("filasignal", name);
    }
}
