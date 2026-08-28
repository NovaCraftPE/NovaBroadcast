package uk.blazecraft.novabroadcast;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/** Read-only RakNet unconnected ping used to validate the configured Bedrock transfer target. */
final class BedrockTargetProbe {
    private static final byte[] MAGIC = new byte[] {
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    record Result(String edition, String motd, int protocol, String version,
                  int players, int maxPlayers, String rawAdvertisement) {}

    static Result probe(String host, int port, int timeoutMillis) throws Exception {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("target.host is blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("target.port is invalid");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeout must be positive");

        InetAddress address = InetAddress.getByName(host);
        ByteBuffer request = ByteBuffer.allocate(1 + 8 + MAGIC.length + 8).order(ByteOrder.BIG_ENDIAN);
        request.put((byte) 0x01); // ID_UNCONNECTED_PING
        request.putLong(System.currentTimeMillis());
        request.put(MAGIC);
        request.putLong(ThreadLocalRandom.current().nextLong());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis);
            byte[] outbound = request.array();
            socket.send(new DatagramPacket(outbound, outbound.length, address, port));

            byte[] inbound = new byte[4096];
            DatagramPacket response = new DatagramPacket(inbound, inbound.length);
            socket.receive(response);
            return parsePong(response.getData(), response.getLength());
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException("No RakNet UnconnectedPong from " + host + ":" + port +
                    " within " + timeoutMillis + "ms");
        }
    }

    static Result parsePong(byte[] data, int length) {
        if (data == null || length < 1 + 8 + 8 + MAGIC.length + 2 || length > data.length) {
            throw new IllegalArgumentException("Malformed RakNet UnconnectedPong");
        }
        ByteBuffer in = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);
        if (Byte.toUnsignedInt(in.get()) != 0x1c) {
            throw new IllegalArgumentException("Unexpected RakNet response packet ID");
        }
        in.getLong(); // echoed ping timestamp
        in.getLong(); // server GUID
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(MAGIC, magic)) throw new IllegalArgumentException("Invalid RakNet offline magic");
        int textLength = Short.toUnsignedInt(in.getShort());
        if (textLength > in.remaining()) throw new IllegalArgumentException("Truncated RakNet server advertisement");
        byte[] text = new byte[textLength];
        in.get(text);
        return parseAdvertisement(new String(text, StandardCharsets.UTF_8));
    }

    static Result parseAdvertisement(String advertisement) {
        String[] fields = advertisement == null ? new String[0] : advertisement.split(";", -1);
        if (fields.length < 6) {
            throw new IllegalArgumentException("Bedrock advertisement has fewer than 6 fields");
        }
        int protocol;
        int players;
        int maxPlayers;
        try {
            protocol = Integer.parseInt(fields[2]);
            players = Integer.parseInt(fields[4]);
            maxPlayers = Integer.parseInt(fields[5]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bedrock advertisement contains invalid numeric fields", e);
        }
        return new Result(fields[0], fields[1], protocol, fields[3], players, maxPlayers, advertisement);
    }

    static byte[] testPong(String advertisement) {
        byte[] text = advertisement.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 8 + 8 + MAGIC.length + 2 + text.length).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) 0x1c);
        out.putLong(123L);
        out.putLong(456L);
        out.put(MAGIC);
        out.putShort((short) text.length);
        out.put(text);
        return out.array();
    }

    private BedrockTargetProbe() {}
}
