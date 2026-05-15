import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

final class TuicProtocol {
    static final byte VERSION = 0x05;
    static final byte COMMAND_AUTHENTICATE = 0x00;
    static final byte COMMAND_CONNECT = 0x01;
    static final byte COMMAND_HEARTBEAT = 0x04;
    static String exporterLabel(UUID uuid) {
        return uuid.toString();
    }
    static final int EXPORTER_LENGTH = 32;

    private TuicProtocol() {
    }

    static ByteBuf authenticate(ByteBufAllocator alloc, UUID uuid, byte[] token) {
        if (token.length != 32) {
            throw new IllegalArgumentException("TUIC token must be 32 bytes");
        }
        ByteBuf out = alloc.buffer(50);
        out.writeByte(VERSION);
        out.writeByte(COMMAND_AUTHENTICATE);
        writeUuid(out, uuid);
        out.writeBytes(token);
        return out;
    }

    static ByteBuf connect(ByteBufAllocator alloc, Socks5Request request) {
        ByteBuf out = alloc.buffer(260);
        out.writeByte(VERSION);
        out.writeByte(COMMAND_CONNECT);
        writeAddress(out, request.host());
        out.writeShort(request.port());
        return out;
    }

    static ByteBuf heartbeat(ByteBufAllocator alloc) {
        ByteBuf out = alloc.buffer(2);
        out.writeByte(VERSION);
        out.writeByte(COMMAND_HEARTBEAT);
        return out;
    }

    static Authenticate authenticate(ByteBuf in) {
        requireHeader(in, COMMAND_AUTHENTICATE, 48);
        UUID uuid = readUuid(in);
        byte[] token = new byte[EXPORTER_LENGTH];
        in.readBytes(token);
        return new Authenticate(uuid, token);
    }

    static Socks5Request connect(ByteBuf in) {
        requireHeader(in, COMMAND_CONNECT, 1 + 2);
        String host = readAddress(in);
        if (in.readableBytes() < 2) {
            throw new IllegalArgumentException("TUIC connect command missing port");
        }
        int port = in.readUnsignedShort();
        ByteBuf initialPayload = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
        return new Socks5Request(host, port, initialPayload);
    }

    static void heartbeat(ByteBuf in) {
        requireHeader(in, COMMAND_HEARTBEAT, 0);
    }

    static byte command(ByteBuf in) {
        if (in.readableBytes() < 2) {
            throw new IllegalArgumentException("TUIC command is too short");
        }
        if (in.getByte(in.readerIndex()) != VERSION) {
            throw new IllegalArgumentException("unsupported TUIC version");
        }
        return in.getByte(in.readerIndex() + 1);
    }

    static byte[] fallbackToken(UUID uuid, String password, String sni) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(exporterLabel(uuid).getBytes(StandardCharsets.UTF_8));
            mac.update(uuid.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(sni.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (Exception error) {
            throw new IllegalStateException("failed to derive TUIC fallback token", error);
        }
    }

    static void writeAddress(ByteBuf out, String host) {
        InetAddress parsed = parseIp(host);
        if (parsed != null) {
            byte[] bytes = parsed.getAddress();
            if (bytes.length == 4) {
                out.writeByte(0x01);
                out.writeBytes(bytes);
                return;
            }
            if (bytes.length == 16) {
                out.writeByte(0x04);
                out.writeBytes(bytes);
                return;
            }
        }

        byte[] domain = IDN.toASCII(host).getBytes(StandardCharsets.UTF_8);
        if (domain.length > 255) {
            throw new IllegalArgumentException("domain name is too long: " + host);
        }
        out.writeByte(0x03);
        out.writeByte(domain.length);
        out.writeBytes(domain);
    }

    private static void requireHeader(ByteBuf in, byte expectedCommand, int minPayloadLength) {
        if (in.readableBytes() < 2 + minPayloadLength) {
            throw new IllegalArgumentException("TUIC command is too short");
        }
        byte version = in.readByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported TUIC version");
        }
        byte command = in.readByte();
        if (command != expectedCommand) {
            throw new IllegalArgumentException("unexpected TUIC command");
        }
    }

    private static String readAddress(ByteBuf in) {
        int atyp = in.readUnsignedByte();
        try {
            return switch (atyp) {
                case 0x01 -> {
                    byte[] bytes = new byte[4];
                    in.readBytes(bytes);
                    yield Inet4Address.getByAddress(bytes).getHostAddress();
                }
                case 0x03 -> {
                    int len = in.readUnsignedByte();
                    yield IDN.toUnicode(in.readCharSequence(len, StandardCharsets.UTF_8).toString());
                }
                case 0x04 -> {
                    byte[] bytes = new byte[16];
                    in.readBytes(bytes);
                    yield Inet6Address.getByAddress(bytes).getHostAddress();
                }
                default -> throw new IllegalArgumentException("unsupported TUIC address type");
            };
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("invalid TUIC address", error);
        }
    }

    private static InetAddress parseIp(String host) {
        try {
            if (host.indexOf(':') >= 0 || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return InetAddress.getByName(host);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeUuid(ByteBuf out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }

    record Authenticate(UUID uuid, byte[] token) {
    }

    static byte[] bytes(ByteBuf buffer) {
        byte[] out = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), out);
        return out;
    }

    static ByteBuf copied(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
    }
}
