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
    void parsesSocks5ConnectRequest() {
        ByteBuf request = Unpooled.buffer();
        request.writeByte(0x05);
        request.writeByte(0x01);
        request.writeByte(0x00);
        request.writeByte(0x03);
        request.writeByte(11);
        request.writeCharSequence("example.com", StandardCharsets.UTF_8);
        request.writeShort(443);

        Socks5Request parsed = Socks5InboundServer.Handler.parseRequest(request);

        assertEquals("example.com", parsed.host());
        assertEquals(443, parsed.port());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }
}
