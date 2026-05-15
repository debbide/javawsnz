import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NezhaAgentBridgeTest {
    @Test
    void resolvesServerTargetWithExplicitPort() {
        assertEquals("nz.example.com:5555", NezhaAgentBridge.resolveServerTarget("nz.example.com", "5555"));
    }

    @Test
    void infersPlaintextForNonTlsPorts() {
        assertFalse(NezhaAgentBridge.inferTls("127.0.0.1:5555"));
    }

    @Test
    void buildsInMemoryConfig() {
        Map<String, Object> config = NezhaAgentBridge.config(
                "nz.example.com",
                "443",
                "secret",
                "11111111-1111-1111-1111-111111111111",
                false);

        assertEquals("nz.example.com:443", config.get("server"));
        assertEquals("secret", config.get("client_secret"));
        assertEquals("11111111-1111-1111-1111-111111111111", config.get("uuid"));
        assertTrue((Boolean) config.get("tls"));
        assertFalse((Boolean) config.get("disable_command_execute"));
    }
}
