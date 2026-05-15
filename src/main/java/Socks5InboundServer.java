import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class Socks5InboundServer implements AutoCloseable {
    private final String bindHost;
    private final int port;
    private final TuicOutbound outbound;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;

    Socks5InboundServer(String bindHost, int port, TuicOutbound outbound) {
        this.bindHost = bindHost;
        this.port = port;
        this.outbound = outbound;
    }

    void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new Handler(outbound));
                    }
                });
        serverChannel = bootstrap.bind(new InetSocketAddress(bindHost, port)).sync().channel();
        System.out.println("TUIC SOCKS5 inbound listening on " + bindHost + ":" + port);
    }

    void waitUntilClosed() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    static final class Handler extends ChannelInboundHandlerAdapter {
        private enum State {
            GREETING,
            REQUEST,
            STREAMING
        }

        private static final byte[] GREETING_OK = new byte[]{0x05, 0x00};
        private static final byte[] CONNECT_OK = new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
        private static final byte[] CONNECT_FAILED = new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0};

        private final TuicOutbound outbound;
        private State state = State.GREETING;
        private Channel tuicStream;

        Handler(TuicOutbound outbound) {
            this.outbound = outbound;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            try {
                switch (state) {
                    case GREETING -> handleGreeting(ctx, in);
                    case REQUEST -> handleRequest(ctx, in);
                    case STREAMING -> forwardPayload(ctx, in);
                }
            } finally {
                in.release();
            }
        }

        private void handleGreeting(ChannelHandlerContext ctx, ByteBuf in) {
            if (in.readableBytes() < 2) {
                close(ctx);
                return;
            }
            int version = in.readUnsignedByte();
            int methods = in.readUnsignedByte();
            if (version != 0x05 || in.readableBytes() < methods) {
                close(ctx);
                return;
            }
            boolean noAuth = false;
            for (int i = 0; i < methods; i++) {
                noAuth |= in.readUnsignedByte() == 0x00;
            }
            if (!noAuth) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x05, (byte) 0xff})).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            state = State.REQUEST;
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GREETING_OK));
        }

        private void handleRequest(ChannelHandlerContext ctx, ByteBuf in) {
            Socks5Request request = parseRequest(in);
            if (request == null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(CONNECT_FAILED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            outbound.openTcpStream(ctx.channel(), request).addListener(future -> {
                if (future.isSuccess()) {
                    tuicStream = outbound.streamFor(ctx.channel());
                    state = State.STREAMING;
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(CONNECT_OK));
                } else {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(CONNECT_FAILED)).addListener(ChannelFutureListener.CLOSE);
                }
            });
        }

        private void forwardPayload(ChannelHandlerContext ctx, ByteBuf in) {
            if (tuicStream == null || !tuicStream.isActive()) {
                close(ctx);
                return;
            }
            tuicStream.writeAndFlush(in.retainedDuplicate());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (tuicStream != null && tuicStream.isActive()) {
                tuicStream.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            close(ctx);
        }

        static Socks5Request parseRequest(ByteBuf in) {
            if (in.readableBytes() < 7) {
                return null;
            }
            int version = in.readUnsignedByte();
            int command = in.readUnsignedByte();
            in.skipBytes(1);
            int atyp = in.readUnsignedByte();
            if (version != 0x05 || command != 0x01) {
                return null;
            }

            String host;
            switch (atyp) {
                case 0x01 -> {
                    if (in.readableBytes() < 6) {
                        return null;
                    }
                    host = "%d.%d.%d.%d".formatted(
                            in.readUnsignedByte(),
                            in.readUnsignedByte(),
                            in.readUnsignedByte(),
                            in.readUnsignedByte());
                }
                case 0x03 -> {
                    if (in.readableBytes() < 1) {
                        return null;
                    }
                    int length = in.readUnsignedByte();
                    if (in.readableBytes() < length + 2) {
                        return null;
                    }
                    host = in.readCharSequence(length, StandardCharsets.UTF_8).toString();
                }
                case 0x04 -> {
                    if (in.readableBytes() < 18) {
                        return null;
                    }
                    byte[] bytes = new byte[16];
                    in.readBytes(bytes);
                    try {
                        host = java.net.InetAddress.getByAddress(bytes).getHostAddress();
                    } catch (Exception error) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
            int port = in.readUnsignedShort();
            return new Socks5Request(host, port);
        }

        private static void close(ChannelHandlerContext ctx) {
            ctx.close();
        }
    }
}
