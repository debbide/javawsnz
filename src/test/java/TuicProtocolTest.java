import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TuicProtocolTest {
    @Test
    void encodesAuthenticateCommand() {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        byte[] token = new byte[32];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) i;
        }

        ByteBuf encoded = TuicProtocol.authenticate(Unpooled.buffer().alloc(), uuid, token);

        assertEquals(50, encoded.readableBytes());
        assertEquals(0x05, encoded.readUnsignedByte());
        assertEquals(0x00, encoded.readUnsignedByte());
        byte[] uuidBytes = new byte[16];
        encoded.readBytes(uuidBytes);
        assertArrayEquals(hex("11111111111111111111111111111111"), uuidBytes);
        byte[] tokenBytes = new byte[32];
        encoded.readBytes(tokenBytes);
        assertArrayEquals(token, tokenBytes);
    }

    @Test
    void parsesAuthenticateCommand() {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        byte[] token = new byte[32];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (31 - i);
        }
        ByteBuf encoded = TuicProtocol.authenticate(Unpooled.buffer().alloc(), uuid, token);

        TuicProtocol.Authenticate parsed = TuicProtocol.authenticate(encoded);

        assertEquals(uuid, parsed.uuid());
        assertArrayEquals(token, parsed.token());
    }

    @Test
    void encodesConnectDomainCommand() {
        ByteBuf encoded = TuicProtocol.connect(Unpooled.buffer().alloc(), new Socks5Request("example.com", 443));

        assertEquals(0x05, encoded.readUnsignedByte());
        assertEquals(0x01, encoded.readUnsignedByte());
        assertEquals(0x03, encoded.readUnsignedByte());
        int len = encoded.readUnsignedByte();
        assertEquals("example.com", encoded.readCharSequence(len, StandardCharsets.UTF_8).toString());
        assertEquals(443, encoded.readUnsignedShort());
    }

    @Test
    void parsesConnectDomainCommand() {
        ByteBuf encoded = TuicProtocol.connect(Unpooled.buffer().alloc(), new Socks5Request("example.com", 443));

        Socks5Request parsed = TuicProtocol.connect(encoded);

        assertEquals("example.com", parsed.host());
        assertEquals(443, parsed.port());
    }

    @Test
    void parsesConnectIpv4Command() {
        ByteBuf encoded = TuicProtocol.connect(Unpooled.buffer().alloc(), new Socks5Request("1.2.3.4", 8443));

        Socks5Request parsed = TuicProtocol.connect(encoded);

        assertEquals("1.2.3.4", parsed.host());
        assertEquals(8443, parsed.port());
    }

    @Test
    void parsesHeartbeatCommand() {
        ByteBuf encoded = TuicProtocol.heartbeat(Unpooled.buffer().alloc());

        TuicProtocol.heartbeat(encoded);

        assertEquals(0, encoded.readableBytes());
    }

    @Test
    void derivesPasswordFromUuid() {
        assertEquals("7bd180e811424387", TuicConfig.derivedPassword("7bd180e8-1142-4387-93f5-03e8d750a896"));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }
}
