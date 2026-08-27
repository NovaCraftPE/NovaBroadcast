package uk.blazecraft.novabroadcast;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Implements the public NetherNet one-byte data-channel framing format.
 *
 * Reliable messages may be split into countdown fragments (N..0).
 * Unreliable messages are always a single frame with header 0.
 */
final class NetherNetFraming {
    static List<byte[]> frameReliable(byte[] payload, int maxSctpMessageSize) {
        Objects.requireNonNull(payload, "payload");
        int chunkSize = payloadCapacity(maxSctpMessageSize);
        if (payload.length <= chunkSize) return List.of(frame(0, payload, 0, payload.length));

        int count = (payload.length + chunkSize - 1) / chunkSize;
        if (count > 256) {
            throw new IllegalArgumentException("Payload requires more than 256 NetherNet fragments");
        }

        List<byte[]> frames = new ArrayList<>(count);
        int offset = 0;
        for (int index = 0; index < count; index++) {
            int length = Math.min(chunkSize, payload.length - offset);
            int remaining = count - index - 1;
            frames.add(frame(remaining, payload, offset, length));
            offset += length;
        }
        return List.copyOf(frames);
    }

    static Optional<byte[]> frameUnreliable(byte[] payload, int maxSctpMessageSize) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > payloadCapacity(maxSctpMessageSize)) return Optional.empty();
        return Optional.of(frame(0, payload, 0, payload.length));
    }

    static byte[] stripUnreliable(byte[] frame) {
        requireFrame(frame);
        if (Byte.toUnsignedInt(frame[0]) != 0) {
            throw new IllegalArgumentException("Unreliable NetherNet frame must use header 0");
        }
        return Arrays.copyOfRange(frame, 1, frame.length);
    }

    static final class ReliableReassembler {
        private ByteArrayOutputStream pending;
        private int expectedHeader = -1;

        Optional<byte[]> accept(byte[] frame) {
            requireFrame(frame);
            int header = Byte.toUnsignedInt(frame[0]);

            if (pending == null) {
                if (header == 0) {
                    return Optional.of(Arrays.copyOfRange(frame, 1, frame.length));
                }
                pending = new ByteArrayOutputStream();
                expectedHeader = header;
            }

            if (header != expectedHeader) {
                reset();
                throw new IllegalArgumentException("Out-of-order NetherNet fragment header: expected " +
                        expectedHeader + " but received " + header);
            }

            pending.write(frame, 1, frame.length - 1);
            if (header == 0) {
                byte[] complete = pending.toByteArray();
                reset();
                return Optional.of(complete);
            }

            expectedHeader--;
            return Optional.empty();
        }

        void reset() {
            pending = null;
            expectedHeader = -1;
        }
    }

    private static int payloadCapacity(int maxSctpMessageSize) {
        if (maxSctpMessageSize < 2) {
            throw new IllegalArgumentException("SCTP max message size must be at least 2 bytes");
        }
        return maxSctpMessageSize - 1;
    }

    private static byte[] frame(int header, byte[] payload, int offset, int length) {
        if (header < 0 || header > 255) throw new IllegalArgumentException("Invalid fragment header");
        byte[] out = new byte[length + 1];
        out[0] = (byte) header;
        System.arraycopy(payload, offset, out, 1, length);
        return out;
    }

    private static void requireFrame(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < 1) throw new IllegalArgumentException("Empty NetherNet frame");
    }

    private NetherNetFraming() {}
}
