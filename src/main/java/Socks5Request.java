import io.netty.buffer.ByteBuf;

record Socks5Request(String host, int port, ByteBuf initialPayload) {
    Socks5Request(String host, int port) {
        this(host, port, null);
    }
}
