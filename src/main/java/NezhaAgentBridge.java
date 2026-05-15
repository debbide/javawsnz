import com.nezhahq.agent.NezhaJavaAgent;

import java.util.LinkedHashMap;
import java.util.Map;

final class NezhaAgentBridge {
    private static final String[] TLS_PORTS = {"443", "8443", "2096", "2087", "2083", "2053"};

    private NezhaAgentBridge() {
    }

    static NezhaJavaAgent.RunningAgent start(String server, String port, String clientSecret, String uuid, boolean debug) {
        return NezhaJavaAgent.start(NezhaJavaAgent.AgentConfig.of(config(server, port, clientSecret, uuid, debug)));
    }

    static Map<String, Object> config(String server, String port, String clientSecret, String uuid, boolean debug) {
        String target = resolveServerTarget(server, port);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("server", target);
        config.put("client_secret", safeTrim(clientSecret));
        config.put("uuid", safeTrim(uuid));
        config.put("tls", inferTls(target));
        config.put("insecure_tls", false);
        config.put("debug", debug);
        config.put("report_delay", 3);
        config.put("ip_report_period", 1800);
        config.put("gpu", false);
        config.put("temperature", false);
        config.put("skip_connection_count", false);
        config.put("skip_procs_count", false);
        config.put("disable_auto_update", true);
        config.put("disable_force_update", true);
        config.put("disable_command_execute", false);
        config.put("disable_send_query", false);
        config.put("disable_nat", true);
        return config;
    }

    static String resolveServerTarget(String server, String port) {
        String host = safeTrim(server);
        if (host.isEmpty()) {
            return host;
        }
        if (hasExplicitPort(host)) {
            return host;
        }

        String resolvedPort = safeTrim(port);
        if (resolvedPort.isEmpty()) {
            return host;
        }

        if (looksLikeIpv6(host)) {
            if (!host.startsWith("[")) {
                host = "[" + host + "]";
            }
            return host + ":" + resolvedPort;
        }
        return host + ":" + resolvedPort;
    }

    static boolean inferTls(String target) {
        String port = extractPort(target);
        if (port.isEmpty()) {
            return false;
        }
        for (String tlsPort : TLS_PORTS) {
            if (tlsPort.equals(port)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExplicitPort(String value) {
        return !extractPort(value).isEmpty();
    }

    private static String extractPort(String value) {
        String text = safeTrim(value);
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("[")) {
            int closing = text.indexOf(']');
            if (closing >= 0 && closing < text.length() - 1 && text.charAt(closing + 1) == ':') {
                return text.substring(closing + 2);
            }
            return "";
        }
        int firstColon = text.indexOf(':');
        int lastColon = text.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon && lastColon < text.length() - 1) {
            return text.substring(lastColon + 1);
        }
        return "";
    }

    private static boolean looksLikeIpv6(String value) {
        return value.indexOf(':') != -1 && value.indexOf(':') != value.lastIndexOf(':');
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
