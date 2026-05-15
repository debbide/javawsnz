import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamFrame;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class TuicOutbound implements AutoCloseable {
    private final TuicConfig config;
    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final AtomicReference<QuicChannel> connection = new AtomicReference<>();
    private final Map<Channel, Channel> streamByInbound = new ConcurrentHashMap<>();
    private volatile Channel udpChannel;

    TuicOutbound(TuicConfig config) {
        this.config = config;
    }

    ChannelFuture openTcpStream(Channel inbound, Socks5Request request) {
        ChannelPromise promise = inbound.newPromise();
        ensureConnected().addListener(connectFuture -> {
            if (!connectFuture.isSuccess()) {
                promise.setFailure(connectFuture.cause());
                return;
            }
            QuicChannel quic = (QuicChannel) connectFuture.getNow();
            quic.createStream(QuicStreamType.BIDIRECTIONAL, new TuicStreamHandler(inbound)).addListener(streamFuture -> {
                if (!streamFuture.isSuccess()) {
                    promise.setFailure(streamFuture.cause());
                    return;
                }
                QuicStreamChannel stream = (QuicStreamChannel) streamFuture.getNow();
                streamByInbound.put(inbound, stream);
                stream.closeFuture().addListener(closeFuture -> streamByInbound.remove(inbound, stream));
                ByteBuf connect = TuicProtocol.connect(stream.alloc(), request);
                stream.writeAndFlush(new DefaultQuicStreamFrame(connect, false)).addListener(writeFuture -> {
                    if (writeFuture.isSuccess()) {
                        promise.setSuccess();
                    } else {
                        promise.setFailure(writeFuture.cause());
                    }
                });
            });
        });
        return promise;
    }

    Channel streamFor(Channel inbound) {
        return streamByInbound.get(inbound);
    }

    private Future<QuicChannel> ensureConnected() {
        QuicChannel current = connection.get();
        if (current != null && current.isActive()) {
            return current.eventLoop().newSucceededFuture(current);
        }
        return connect();
    }

    private Future<QuicChannel> connect() {
        Quic.ensureAvailability();
        Promise<QuicChannel> promise = group.next().newPromise();
        try {
            QuicSslContextBuilder sslBuilder = QuicSslContextBuilder.forClient()
                    .applicationProtocols(config.alpn);
            if (config.insecure) {
                sslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                sslBuilder.endpointIdentificationAlgorithm(null);
            }
            QuicSslContext sslContext = sslBuilder.build();

            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
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

            bootstrap.bind(0).addListener(bindFuture -> {
                if (!bindFuture.isSuccess()) {
                    promise.setFailure(bindFuture.cause());
                    return;
                }
                udpChannel = (Channel) bindFuture.getNow();
                QuicChannel.newBootstrap(udpChannel)
                        .handler(new ChannelInboundHandlerAdapter())
                        .streamHandler(new ChannelInboundHandlerAdapter())
                        .remoteAddress(new InetSocketAddress(config.server, config.port))
                        .connect()
                        .addListener(quicFuture -> {
                            if (!quicFuture.isSuccess()) {
                                promise.setFailure(quicFuture.cause());
                                return;
                            }
                            QuicChannel quic = (QuicChannel) quicFuture.getNow();
                            connection.set(quic);
                            authenticate(quic, promise);
                        });
            });
        } catch (Exception error) {
            promise.setFailure(error);
        }
        return promise;
    }

    private void authenticate(QuicChannel quic, Promise<QuicChannel> promise) {
        quic.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).addListener(streamFuture -> {
            if (!streamFuture.isSuccess()) {
                promise.setFailure(streamFuture.cause());
                return;
            }
            QuicStreamChannel stream = (QuicStreamChannel) streamFuture.getNow();
            byte[] token = TlsExporterTokenProvider.token(quic, config);
            ByteBuf auth = TuicProtocol.authenticate(stream.alloc(), config.uuid, token);
            stream.writeAndFlush(new DefaultQuicStreamFrame(auth, false)).addListener(writeFuture -> {
                stream.close();
                if (writeFuture.isSuccess()) {
                    scheduleHeartbeat(quic);
                    promise.setSuccess(quic);
                } else {
                    promise.setFailure(writeFuture.cause());
                }
            });
        });
    }

    private void scheduleHeartbeat(QuicChannel quic) {
        quic.eventLoop().scheduleAtFixedRate(() -> {
            if (!quic.isActive()) {
                return;
            }
            quic.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).addListener(streamFuture -> {
                if (streamFuture.isSuccess()) {
                    QuicStreamChannel stream = (QuicStreamChannel) streamFuture.getNow();
                    stream.writeAndFlush(new DefaultQuicStreamFrame(TuicProtocol.heartbeat(stream.alloc()), true));
                }
            });
        }, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        QuicChannel current = connection.getAndSet(null);
        if (current != null) {
            current.close(true, 0, Unpooled.EMPTY_BUFFER);
        }
        if (udpChannel != null) {
            udpChannel.close();
        }
        group.shutdownGracefully();
    }

    private static final class TuicStreamHandler extends ChannelInboundHandlerAdapter {
        private final Channel inbound;

        TuicStreamHandler(Channel inbound) {
            this.inbound = inbound;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof QuicStreamFrame frame) {
                if (frame.content().isReadable() && inbound.isActive()) {
                    inbound.writeAndFlush(frame.content().retain());
                }
                if (frame.hasFin()) {
                    ctx.close();
                    inbound.close();
                }
                frame.release();
                return;
            }
            if (msg instanceof ByteBuf buf) {
                if (inbound.isActive()) {
                    inbound.writeAndFlush(buf.retain());
                }
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (inbound.isActive()) {
                inbound.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            if (inbound.isActive()) {
                inbound.close();
            }
        }
    }
}
