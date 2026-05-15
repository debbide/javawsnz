import io.netty.handler.codec.quic.QuicChannel;

import java.util.ArrayList;
import java.util.List;

final class TlsExporterTokenProvider {
    private TlsExporterTokenProvider() {
    }

    static byte[] token(QuicChannel quic, TuicConfig config) {
        byte[] exported = tryExportKeyingMaterial(quic);
        if (exported != null) {
            return exported;
        }
        return TuicProtocol.fallbackToken(config.uuid, config.password, "");
    }

    static List<byte[]> fallbackCandidates(QuicChannel quic, TuicConfig config) {
        List<byte[]> candidates = new ArrayList<>();
        candidates.add(TuicProtocol.fallbackToken(config.uuid, config.password, ""));

        if (quic.remoteAddress() instanceof java.net.InetSocketAddress remote) {
            String authority = remote.getHostString();
            if (authority != null && !authority.isBlank()) {
                candidates.add(TuicProtocol.fallbackToken(config.uuid, config.password, authority));
            }
        }

        String domain = HardcodedConfig.DOMAIN == null ? "" : HardcodedConfig.DOMAIN.trim();
        if (!domain.isBlank()) {
            candidates.add(TuicProtocol.fallbackToken(config.uuid, config.password, domain));
        }
        return candidates;
    }

    private static byte[] tryExportKeyingMaterial(QuicChannel quic) {
        try {
            Object engine = quic.sslEngine();
            for (String methodName : new String[]{"exportKeyingMaterial", "exportKeyingMaterialData"}) {
                for (java.lang.reflect.Method method : engine.getClass().getMethods()) {
                    if (!method.getName().equals(methodName) || method.getReturnType() != byte[].class) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 3 && types[0] == String.class && types[1] == byte[].class && types[2] == int.class) {
                        return (byte[]) method.invoke(engine, TuicProtocol.EXPORTER_LABEL, null, TuicProtocol.EXPORTER_LENGTH);
                    }
                    if (types.length == 2 && types[0] == String.class && types[1] == int.class) {
                        return (byte[]) method.invoke(engine, TuicProtocol.EXPORTER_LABEL, TuicProtocol.EXPORTER_LENGTH);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Netty's QUIC engine currently does not expose TLS exporter publicly on all builds.
        }
        return null;
    }
}
