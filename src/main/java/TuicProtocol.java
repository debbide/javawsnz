import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

final class TuicProtocol {
    static final byte VERSION = 0x05;
    static final byte COMMAND_AUTHENTICATE = 0x00;
    static final byte COMMAND_CONNECT = 0x01;
    static final byte COMMAND_HEARTBEAT = 0x04;
    static final String EXPORTER_LABEL = "EXPORTER-tuic token";
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

    static byte[] fallbackToken(UUID uuid, String password, String sni) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(EXPORTER_LABEL.getBytes(StandardCharsets.UTF_8));
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

    static byte[] bytes(ByteBuf buffer) {
        byte[] out = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), out);
        return out;
    }

    static ByteBuf copied(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
    }
}
