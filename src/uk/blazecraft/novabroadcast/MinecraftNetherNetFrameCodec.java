package uk.blazecraft.novabroadcast;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/**
 * Bedrock-over-NetherNet application framing. Each application packet is
 * encoded as an unsigned VarInt byte length followed by the packet bytes.
 */
final class MinecraftNetherNetFrameCodec {
    static final class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) return;

            in.markReaderIndex();
            Integer length = readUnsignedVarInt(in);
            if (length == null) {
                in.resetReaderIndex();
                return;
            }
            if (length < 0 || length > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid NetherNet Bedrock frame length: " + length);
            }
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }
            out.add(in.readRetainedSlice(length));
        }
    }

    static final class Encoder extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            int length = msg.readableBytes();
            writeUnsignedVarInt(out, length);
            out.writeBytes(msg, msg.readerIndex(), length);
        }
    }

    static Integer readUnsignedVarInt(ByteBuf in) {
        int value = 0;
        int position = 0;
        while (position < 35) {
            if (!in.isReadable()) return null;
            int current = in.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
        }
        throw new IllegalArgumentException("NetherNet VarInt is too large");
    }

    static void writeUnsignedVarInt(ByteBuf out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarInt cannot be negative");
        do {
            int current = value & 0x7F;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            out.writeByte(current);
        } while (value != 0);
    }

    private MinecraftNetherNetFrameCodec() {}
}
