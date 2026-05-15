import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;

import java.util.Locale;
import java.util.UUID;

final class TuicConfig {
    final int port;
    final UUID uuid;
    final String password;
    final boolean insecure;
    final String alpn;
    final String congestionControlName;
    final QuicCongestionControlAlgorithm congestionControl;

    private TuicConfig(
            int port,
            UUID uuid,
            String password,
            boolean insecure,
            String alpn,
            String congestionControlName,
            QuicCongestionControlAlgorithm congestionControl) {
        this.port = port;
        this.uuid = uuid;
        this.password = password;
        this.insecure = insecure;
        this.alpn = alpn;
        this.congestionControlName = congestionControlName;
        this.congestionControl = congestionControl;
    }

    static TuicConfig load() {
        String uuidText = HardcodedConfig.TUIC_UUID.isBlank() ? HardcodedConfig.UUID : HardcodedConfig.TUIC_UUID;
        if (uuidText.isBlank()) {
            throw new IllegalArgumentException("HardcodedConfig.TUIC_UUID or HardcodedConfig.UUID must be set");
        }
        String password = HardcodedConfig.TUIC_PASSWORD.isBlank() ? derivedPassword(uuidText) : HardcodedConfig.TUIC_PASSWORD;
        String alpn = HardcodedConfig.TUIC_ALPN.isBlank() ? "h3" : HardcodedConfig.TUIC_ALPN;
        String congestionName = HardcodedConfig.TUIC_CONGESTION_CONTROL.isBlank()
                ? "bbr"
                : HardcodedConfig.TUIC_CONGESTION_CONTROL.toLowerCase(Locale.ROOT);
        return new TuicConfig(
                HardcodedConfig.PORT,
                UUID.fromString(uuidText),
                password,
                HardcodedConfig.TUIC_INSECURE,
                alpn,
                congestionName,
                congestion(congestionName));
    }

    static String derivedPassword(String uuidText) {
        String compact = uuidText.replace("-", "");
        return compact.substring(0, Math.min(16, compact.length()));
    }

    private static QuicCongestionControlAlgorithm congestion(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "reno" -> QuicCongestionControlAlgorithm.RENO;
            case "cubic" -> QuicCongestionControlAlgorithm.CUBIC;
            default -> QuicCongestionControlAlgorithm.BBR;
        };
    }
}
