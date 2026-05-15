final class TuicClientApp {
    private TuicClientApp() {
    }

    static void runForeground() {
        TuicConfig config = TuicConfig.load();
        try (TuicOutbound outbound = new TuicOutbound(config);
             Socks5InboundServer inbound = new Socks5InboundServer(config.bindHost, config.socksPort, outbound)) {
            inbound.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                inbound.close();
                outbound.close();
            }, "tuic-client-shutdown"));
            inbound.waitUntilClosed();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            throw new RuntimeException("TUIC client failed", error);
        }
    }

    static void startBackground() {
        Thread thread = new Thread(TuicClientApp::runForeground, "tuic-client");
        thread.setDaemon(false);
        thread.start();
    }
}
