package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Request;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayDeque;

/**
 * Paces editor, debugger and device-form packets to the server's per-player request limit (one request per five
 * server ticks). Without pacing, a quick second click was silently dropped by the server and only surfaced as a
 * timeout. Client thread only. The queue is short and keeps requests in order; consecutive breakpoint updates for
 * one computer collapse into the newest set.
 */
final class RequestGate {
    private static final long INTERVAL_NANOS = 300_000_000L;
    private static final int CAPACITY = 8;
    private static final ArrayDeque<CustomPacketPayload> QUEUE = new ArrayDeque<>();
    private static long last = System.nanoTime() - INTERVAL_NANOS;

    private RequestGate() {
    }

    /** Sends immediately when allowed, otherwise queues. Returns false when the queue is full. */
    static boolean send(CustomPacketPayload payload) {
        if (QUEUE.isEmpty() && ready()) {
            transmit(payload);
            return true;
        }
        if (payload instanceof Request request && request.action() == Request.DEBUG_BREAKPOINTS
                && QUEUE.peekLast() instanceof Request previous && previous.action() == Request.DEBUG_BREAKPOINTS
                && previous.pos().equals(request.pos())) {
            QUEUE.removeLast();
        }
        if (QUEUE.size() == CAPACITY) {
            return false;
        }
        QUEUE.addLast(payload);
        return true;
    }

    /** Sends a request that the server accepts outside the rate limit, such as detaching a debugger. */
    static void sendUnpaced(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }

    /**
     * Records a request the server admitted on the player's behalf, such as opening a screen by using a block.
     * It shares the same server limit, so the next queued request waits a full interval.
     */
    static void noteServerAdmission() {
        last = System.nanoTime();
    }

    static void tick() {
        if (!QUEUE.isEmpty() && ready()) {
            transmit(QUEUE.removeFirst());
        }
    }

    /** Drops queued requests, for example after leaving a world. */
    static void clear() {
        QUEUE.clear();
    }

    private static boolean ready() {
        return System.nanoTime() - last >= INTERVAL_NANOS;
    }

    private static void transmit(CustomPacketPayload payload) {
        last = System.nanoTime();
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }
}
