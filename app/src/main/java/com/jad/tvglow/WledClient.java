package com.jad.tvglow;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * WLED realtime UDP (port 21324). DRGB for up to 490 LEDs, DNRGB chunks beyond that.
 * WLED switches into realtime mode on the first packet and returns to its normal
 * effect after the timeout byte (seconds) passes with no packets.
 */
final class WledClient implements Closeable {
    static final int PORT = 21324;
    private static final int TIMEOUT_S = 2;

    private final DatagramSocket socket;
    private final InetAddress address;

    WledClient(String ip) throws IOException {
        socket = new DatagramSocket();
        address = InetAddress.getByName(ip);
    }

    /** One color per LED, in strip order, packed 0xRRGGBB. */
    void send(int[] leds) throws IOException {
        for (byte[] p : WledProtocol.packets(leds, TIMEOUT_S)) {
            socket.send(new DatagramPacket(p, p.length, address, PORT));
        }
    }

    /**
     * All LEDs dark, and kept dark: timeout 255 tells WLED to stay on this data instead of
     * returning to its own effect, so the strip doesn't light back up while the TV is off.
     */
    void blackout(int ledCount) throws IOException {
        for (byte[] p : WledProtocol.packets(new int[Math.max(1, ledCount)], 255)) {
            socket.send(new DatagramPacket(p, p.length, address, PORT));
        }
    }

    /** Release realtime mode right away so WLED returns to its normal effect. */
    void release() throws IOException {
        byte[] p = {2, 1};
        socket.send(new DatagramPacket(p, p.length, address, PORT));
    }

    @Override
    public void close() {
        socket.close();
    }
}
