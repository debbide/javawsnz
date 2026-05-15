import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;

import java.util.Locale;
import java.util.UUID;

final class TuicConfig {
    final String server;
    final int port;
    final UUID uuid;
    final String password;
    final String sni;
    final boolean insecure;
    final String alpn;
    final String bindHost;
    final int socksPort;
    final QuicCongestionControlAlgorithm congestionControl;

    private TuicConfig(
            String server,
            int port,
            UUID uuid,
            String password,
            String sni,
            boolean insecure,
            String alpn,
            String bindHost,
            int socksPort,
            QuicCongestionControlAlgorithm congestionControl) {
        this.server = server;
        this.port = port;
        this.uuid = uuid;
        this.password = password;
        this.sni = sni;
        this.insecure = insecure;
        this.alpn = alpn;
        this.bindHost = bindHost;
        this.socksPort = socksPort;
        this.congestionControl = congestionControl;
    }

    static TuicConfig load() {
        String server = HardcodedConfig.TUIC_SERVER;
        int port = HardcodedConfig.TUIC_PORT;
        String uuidText = HardcodedConfig.TUIC_UUID.isBlank() ? HardcodedConfig.UUID : HardcodedConfig.TUIC_UUID;
        String password = HardcodedConfig.TUIC_PASSWORD;
        String sni = HardcodedConfig.TUIC_SNI.isBlank() ? server : HardcodedConfig.TUIC_SNI;
        boolean insecure = HardcodedConfig.TUIC_INSECURE;
        String alpn = HardcodedConfig.TUIC_ALPN;
        String bindHost = HardcodedConfig.SOCKS_BIND;
        int socksPort = HardcodedConfig.SOCKS_PORT;
        QuicCongestionControlAlgorithm congestion = congestion(HardcodedConfig.TUIC_CONGESTION_CONTROL);

        if (server.isBlank()) {
            throw new IllegalArgumentException("HardcodedConfig.TUIC_SERVER must be set");
        }
        if (uuidText.isBlank()) {
            throw new IllegalArgumentException("HardcodedConfig.TUIC_UUID or HardcodedConfig.UUID must be set");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("HardcodedConfig.TUIC_PASSWORD must be set");
        }
        return new TuicConfig(server, port, UUID.fromString(uuidText), password, sni, insecure, alpn, bindHost, socksPort, congestion);
    }

    private static QuicCongestionControlAlgorithm congestion(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "reno" -> QuicCongestionControlAlgorithm.RENO;
            case "cubic" -> QuicCongestionControlAlgorithm.CUBIC;
            default -> QuicCongestionControlAlgorithm.BBR;
        };
    }
}
