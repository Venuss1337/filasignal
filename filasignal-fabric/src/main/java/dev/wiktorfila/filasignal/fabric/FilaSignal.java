package dev.wiktorfila.filasignal.fabric;

import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralBlocks;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle;
import net.fabricmc.api.ModInitializer;
import org.slf4j.LoggerFactory;

public final class FilaSignal implements ModInitializer {
    @Override
    public void onInitialize() {
        dev.wiktorfila.filasignal.fabric.drive.Drives.register();
        ComputerBlocks.register();
        PeripheralBlocks.register();
        ComputerLifecycle.register();
        dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.register();
        // Sample new observations after dispatch so handlers see them on a later server tick.
        PeripheralLifecycle.register();
        dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork.register();
        LoggerFactory.getLogger("filasignal").info("Programmable Computer, network devices, dedicated hardware, data drives and Linking Tool registered");
    }
}
