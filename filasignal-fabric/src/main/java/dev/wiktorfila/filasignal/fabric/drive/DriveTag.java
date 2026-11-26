package dev.wiktorfila.filasignal.fabric.drive;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * The small item component of a Programmable Data Drive: its identity, a player label and the hardware kind it is
 * written for. The program, globals and stored data live only in the world's drive record for this identity.
 * {@code target} is a device kind ID such as {@code entity_detector}, or empty until the drive is programmed.
 */
public record DriveTag(UUID id, String label, String target) {
    public static final int MAX_LABEL = 32;

    public DriveTag {
        java.util.Objects.requireNonNull(id);
        label = label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label;
        if (target.length() > 32) {
            throw new IllegalArgumentException("Invalid drive target");
        }
    }

    public static final Codec<DriveTag> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(DriveTag::id),
            Codec.string(0, MAX_LABEL).optionalFieldOf("label", "").forGetter(DriveTag::label),
            Codec.string(0, 32).optionalFieldOf("target", "").forGetter(DriveTag::target)
    ).apply(instance, DriveTag::new));

    public static final StreamCodec<ByteBuf, DriveTag> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DriveTag::id, ByteBufCodecs.stringUtf8(MAX_LABEL), DriveTag::label,
            ByteBufCodecs.stringUtf8(32), DriveTag::target, DriveTag::new);

    public DriveTag withLabel(String next) { return new DriveTag(id, next, target); }
    public DriveTag withTarget(String next) { return new DriveTag(id, label, next); }
}
