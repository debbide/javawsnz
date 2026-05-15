import io.netty.handler.codec.quic.QuicChannel;

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
