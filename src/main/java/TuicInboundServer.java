import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamFrame;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Date;
import java.util.concurrent.TimeUnit;

final class TuicInboundServer implements AutoCloseable {
    private final TuicConfig config;
    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private Channel udpChannel;
    private SelfSignedCertificate certificate;

    private static void debug(String msg) {
        if (HardcodedConfig.DEBUG) {
            System.out.println(new Date() + " - DEBUG - " + msg);
        }
    }

    TuicInboundServer(TuicConfig config) {
        this.config = config;
    }

    ChannelFuture start() throws Exception {
        Quic.ensureAvailability();
        certificate = new SelfSignedCertificate("localhost");
        QuicSslContext sslContext = QuicSslContextBuilder
                .forServer(certificate.key(), null, certificate.cert())
                .applicationProtocols(config.alpn)
                .build();

        ChannelHandler codec = new QuicServerCodecBuilder()
                .sslContext(sslContext)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ConnectionHandler(config))
                .streamHandler(new StreamHandler(config))
                .maxIdleTimeout(30, TimeUnit.SECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(1_000_000)
                .initialMaxStreamDataBidirectionalRemote(1_000_000)
                .initialMaxStreamsBidirectional(100)
                .congestionControlAlgorithm(config.congestionControl)
                .build();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(codec);

        ChannelFuture future = bootstrap.bind(new InetSocketAddress(config.port));
        future.addListener(bindFuture -> {
            if (bindFuture.isSuccess()) {
                udpChannel = (Channel) bindFuture.getNow();
            }
        });
        return future;
    }

    Channel closeFutureChannel() {
        return udpChannel;
    }

    @Override
    public void close() {
        if (udpChannel != null) {
            udpChannel.close();
        }
        group.shutdownGracefully();
        if (certificate != null) {
            certificate.delete();
        }
    }

    private static final class ConnectionHandler extends ChannelInboundHandlerAdapter {
        private final TuicConfig config;
        private boolean authenticated;

        ConnectionHandler(TuicConfig config) {
            this.config = config;
        }

        boolean authenticate(QuicChannel quic, TuicProtocol.Authenticate auth) {
            if (!config.uuid.equals(auth.uuid())) {
                debug("TUIC auth failed: uuid mismatch from " + quic.remoteAddress());
                return false;
            }
            byte[] expected = TlsExporterTokenProvider.token(quic, config);
            if (MessageDigest.isEqual(expected, auth.token())) {
                authenticated = true;
                debug("TUIC auth ok by TLS exporter from " + quic.remoteAddress());
                return true;
            }
            for (byte[] candidate : TlsExporterTokenProvider.fallbackCandidates(quic, config)) {
                if (MessageDigest.isEqual(candidate, auth.token())) {
                    authenticated = true;
                    debug("TUIC auth ok by fallback token from " + quic.remoteAddress());
                    return true;
                }
            }
            debug("TUIC auth failed: token mismatch from " + quic.remoteAddress());
            return false;
        }

        boolean authenticated() {
            return authenticated;
        }
    }

    private static final class StreamHandler extends ChannelInboundHandlerAdapter {
        private final TuicConfig config;
        private Channel outbound;
        private boolean connected;
        private boolean commandHandled;

        StreamHandler(TuicConfig config) {
            this.config = config;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof QuicStreamFrame frame) {
                handleFrame(ctx, frame.content(), frame.hasFin());
                frame.release();
                return;
            }
            if (msg instanceof ByteBuf buf) {
                handleFrame(ctx, buf, false);
                buf.release();
            }
        }

        private void handleFrame(ChannelHandlerContext ctx, ByteBuf content, boolean fin) {
            if (!commandHandled) {
                commandHandled = true;
                handleCommand(ctx, content);
            } else if (connected && outbound != null && outbound.isActive() && content.isReadable()) {
                outbound.writeAndFlush(content.retain());
            }
            if (fin) {
                ctx.close();
            }
        }

        private void handleCommand(ChannelHandlerContext ctx, ByteBuf content) {
            byte command = TuicProtocol.command(content);
            if (command == TuicProtocol.COMMAND_AUTHENTICATE) {
                TuicProtocol.Authenticate auth = TuicProtocol.authenticate(content);
                ConnectionHandler connection = connectionHandler(ctx);
                if (connection == null || !connection.authenticate((QuicChannel) ctx.channel().parent(), auth)) {
                    debug("TUIC stream auth rejected, closing stream");
                    ctx.close();
                    return;
                }
                ctx.writeAndFlush(new DefaultQuicStreamFrame(Unpooled.EMPTY_BUFFER, true)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (command == TuicProtocol.COMMAND_HEARTBEAT) {
                TuicProtocol.heartbeat(content);
                ctx.writeAndFlush(new DefaultQuicStreamFrame(Unpooled.EMPTY_BUFFER, true)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            ConnectionHandler connection = connectionHandler(ctx);
            if (connection == null || !connection.authenticated()) {
                debug("TUIC command before auth, closing stream");
                ctx.close();
                return;
            }
            if (command != TuicProtocol.COMMAND_CONNECT) {
                debug("TUIC unsupported command " + command + ", closing stream");
                ctx.close();
                return;
            }

            Socks5Request request = TuicProtocol.connect(content);
            if (App.isBlockedDomain(request.host())) {
                debug("TUIC blocked domain " + request.host());
                ctx.close();
                return;
            }
            connectTarget(ctx, request);
        }

        private ConnectionHandler connectionHandler(ChannelHandlerContext ctx) {
            return ctx.channel().parent().pipeline().get(ConnectionHandler.class);
        }

        private void connectTarget(ChannelHandlerContext ctx, Socks5Request request) {
            String resolvedHost = App.resolveHost(request.host());
            Bootstrap bootstrap = new Bootstrap()
                    .group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new RelayTargetHandler((QuicStreamChannel) ctx.channel()));
                        }
                    });

            ChannelFuture future = bootstrap.connect(resolvedHost, request.port());
            outbound = future.channel();
            future.addListener(connectFuture -> {
                if (connectFuture.isSuccess()) {
                    connected = true;
                    debug("TUIC connect ok: " + request.host() + ":" + request.port() + " -> " + resolvedHost + ":" + request.port());
                } else {
                    debug("TUIC connect failed: " + request.host() + ":" + request.port() + " -> " + resolvedHost + ":" + request.port() + ", cause=" + connectFuture.cause());
                    ctx.close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (outbound != null && outbound.isActive()) {
                outbound.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static final class RelayTargetHandler extends ChannelInboundHandlerAdapter {
        private final QuicStreamChannel stream;

        RelayTargetHandler(QuicStreamChannel stream) {
            this.stream = stream;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                if (stream.isActive()) {
                    stream.writeAndFlush(new DefaultQuicStreamFrame(buf.retain(), false));
                }
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (stream.isActive()) {
                stream.writeAndFlush(new DefaultQuicStreamFrame(Unpooled.EMPTY_BUFFER, true)).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            if (stream.isActive()) {
                stream.close();
            }
        }
    }
}
