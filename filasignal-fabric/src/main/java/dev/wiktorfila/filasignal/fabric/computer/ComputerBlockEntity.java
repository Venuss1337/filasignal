package dev.wiktorfila.filasignal.fabric.computer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.fabric.drive.DriveStore;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * The Programmable Computer. It runs its own program and has one drive slot for writing programs onto drives.
 * A drive in the slot is only edited here, never executed. Server-thread mutation only.
 */
final class ComputerBlockEntity extends BlockEntity implements ProgramHost {
    private static final String RECORD = "filasignal_controller";
    private static final String DRIVE = "filasignal_drive";

    // Off-thread NBT consumers can reuse the last published record without touching the runtime.
    private volatile Dynamic<?> saved;
    private ControllerData data;
    private String corruption;
    private long revision;
    private UUID editRevision = UUID.randomUUID();
    private ItemStack drive = ItemStack.EMPTY;

    ComputerBlockEntity(BlockPos position, BlockState state) {
        super(ComputerBlocks.computerType, position, state);
    }

    @Override public BlockEntity block() { return this; }
    @Override public UUID editRevision() { return editRevision; }
    @Override public long revision() { return revision; }
    @Override public boolean runnable() { return true; }
    @Override public String displayName() { return "computer"; }

    @Override
    public String executionId() {
        try {
            return activateData().identity().toString();
        } catch (IOException unreadable) {
            return "unreadable@" + getBlockPos().toShortString();
        }
    }

    /** The computer's network identity. */
    UUID activateIdentity() {
        try {
            return activateData().identity();
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("The computer record is unreadable: " + unreadable.getMessage());
        }
    }

    @Override
    public Program program() throws IOException {
        ControllerData current = activateData();
        return new Program(current.source(), current.snapshot(), current.enabled(), null);
    }

    @Override
    public void install(String source, boolean resetGlobals) throws IOException {
        replaceProgram(activateData().withSource(source, resetGlobals));
    }

    @Override
    public void enable(boolean enabled, String authorizedBy, UUID authority) throws IOException {
        replaceProgram(activateData().withEnabled(enabled));
    }

    @Override
    public HostRegistry hosts(ServerLevel level) {
        try {
            return PeripheralLifecycle.computer(level, this, activateData().identity());
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(unreadable.getMessage());
        }
    }

    @Override public Set<String> capabilities() { return PeripheralLifecycle.capabilities(); }

    @Override
    public void indicate(Indicator indicator) {
        ComputerBlock.show(this, ComputerBlock.Screen.valueOf(indicator.name()), !drive.isEmpty());
    }

    @Override
    public void activated(MinecraftServer server) {
        PeripheralLifecycle.activated(server, data.identity());
    }

    @Override
    public void stopped(MinecraftServer server) {
        if (data != null) {
            PeripheralLifecycle.clearOutputs(server, data.identity());
        }
    }

    // Drive slot

    ItemStack drive() { return drive; }

    /** Puts a drive into the empty slot, assigning a blank drive its identity. */
    void insertDrive(ItemStack stack) {
        if (!drive.isEmpty() || !Drives.isDrive(stack)) {
            throw new IllegalArgumentException("The computer's drive slot is occupied");
        }
        drive = stack.copyWithCount(1);
        Drives.identify(drive);
        setChanged();
        ComputerBlock.show(this, null, true);
    }

    /** Removes and returns the drive, releasing its claim. */
    ItemStack ejectDrive() {
        ItemStack removed = drive;
        drive = ItemStack.EMPTY;
        release(removed);
        setChanged();
        return removed;
    }

    void driveChanged() {
        setChanged();
    }

    /** Releases the drive's mount when this block unloads. */
    void releaseDrive() {
        release(drive);
    }

    private void release(ItemStack stack) {
        var tag = Drives.tag(stack);
        if (tag != null && getLevel() instanceof ServerLevel level) {
            DriveStore.of(level.getServer()).release(tag.id(), this);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (getLevel() != null && !drive.isEmpty()) {
            Containers.dropItemStack(getLevel(), pos.getX(), pos.getY(), pos.getZ(), ejectDrive());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        revision++;
        editRevision = UUID.randomUUID();
        saved = input.read(RECORD, Codec.PASSTHROUGH).orElse(null);
        data = null;
        corruption = null;
        if (saved != null) {
            try {
                data = ControllerData.read(saved);
            } catch (IOException error) {
                corruption = error.getMessage();
            }
        }
        drive = input.read(DRIVE, ItemStack.CODEC).filter(Drives::isDrive).orElse(ItemStack.EMPTY);
        if (getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            // A live record replacement can repair a computer that previously failed activation.
            ComputerLifecycle.schedule(level.getServer(), this);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            ComputerLifecycle.checkpoint(level.getServer(), this);
        }
        Dynamic<?> record = saved;
        if (record != null) {
            output.store(RECORD, Codec.PASSTHROUGH, record);
        }
        if (!drive.isEmpty()) {
            output.store(DRIVE, ItemStack.CODEC, drive);
        }
    }

    ControllerData activateData() throws IOException {
        if (corruption != null) {
            throw new IOException(corruption);
        }
        if (data == null) {
            publish(ControllerData.fresh());
            setChanged();
        }
        return data;
    }

    void replaceProgram(ControllerData next) {
        revision++;
        editRevision = UUID.randomUUID();
        publish(next);
        setChanged();
    }

    @Override
    public void publishSnapshot(byte[] bytes) throws IOException {
        publish(data.withSnapshot(bytes));
    }

    private void publish(ControllerData next) {
        Dynamic<?> encoded = next.encode();
        data = next;
        saved = encoded;
    }
}
