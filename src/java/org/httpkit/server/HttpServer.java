package org.httpkit.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static org.httpkit.ws.CloseFrame.CLOSE_AWAY;
import static org.httpkit.ws.CloseFrame.CLOSE_MESG_BIG;
import static org.httpkit.ws.CloseFrame.CLOSE_NORMAL;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.httpkit.*;
import org.httpkit.ws.*;

public class HttpServer implements Runnable {

    static final String THREAD_NAME = "server-loop";

    private final IHandler handler;
    private final int maxBody;
    private final int maxLine;

    private final Selector selector;
    private final ServerSocketChannel serverChannel;

    private Thread serverThread;

    private final ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();
    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine)
            throws IOException {
        this.handler = handler;
        this.maxLine = maxLine;
        this.maxBody = maxBody;
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
    }

    void accept(SelectionKey key) {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        try {
            while ((s = ch.accept()) != null) {
                s.configureBlocking(false);
                HttpServerAtta atta = new HttpServerAtta(maxBody, maxLine);
                SelectionKey k = s.register(selector, OP_READ, atta);
                atta.channel = new AsyncChannel(k, this);
            }
        } catch (Exception e) {
            // too many open files. do not quit
            HttpUtils.printError("accept incoming request", e);
        }
    }

    private void closeKey(final SelectionKey key, int status) {
        try {
            key.channel().close();
        } catch (Exception ignore) {
        }

        ServerAtta att = (ServerAtta) key.attachment();
        if (att instanceof HttpServerAtta) {
            handler.clientClose(att.channel, -1);
        } else {
            handler.clientClose(att.channel, status);
        }
    }

    private void decodeHttp(HttpServerAtta atta, SelectionKey key, SocketChannel ch) {
        try {
            do {
                atta.channel.reset(); // reuse for performance
                HttpRequest request = atta.decoder.decode(buffer);
                if (request != null) {
                    if (request.isWebSocket) {
                        key.attach(new WsServerAtta(atta.channel));
                    } else {
                        atta.keepalive = request.isKeepAlive;
                    }
                    request.channel = atta.channel;
                    request.remoteAddr = (InetSocketAddress) ch.socket()
                            .getRemoteSocketAddress();
                    handler.handle(request, new ResponseCallback(key, this));
                    // pipelining not supported : need queue to ensure order
                    // AsyncChannel can't be reseted here
                    atta.decoder.reset();
                }
            } while (buffer.hasRemaining()); // consume all
        } catch (ProtocolException e) {
            closeKey(key, -1);
        } catch (RequestTooLargeException e) {
            atta.keepalive = false;
            tryWrite(key, ClojureRing.encode(413, null, e.getMessage()));
        } catch (LineTooLargeException e) {
            atta.keepalive = false; // close after write
            tryWrite(key, ClojureRing.encode(414, null, e.getMessage()));
        }
    }

    private void decodeWs(WsServerAtta atta, SelectionKey key) {
        try {
            do {
                WSFrame frame = atta.decoder.decode(buffer);
                if (frame instanceof TextFrame || frame instanceof BinaryFrame) {
                    handler.handle(atta.channel, frame);
                    atta.decoder.reset();
                } else if (frame instanceof PingFrame) {
                    atta.decoder.reset();
                    tryWrite(key, WSEncoder.encode(WSDecoder.OPCODE_PONG, frame.data));
                } else if (frame instanceof CloseFrame) {
                    // even though the logic connection is closed. the socket
                    // did not, if client willing to reuse it, http-kit is more
                    // than happy
                    handler.clientClose(atta.channel, ((CloseFrame) frame).getStatus());
                    tryWrite(key, WSEncoder.encode(WSDecoder.OPCODE_CLOSE, frame.data));
                }
            } while (buffer.hasRemaining()); // consume all
        } catch (ProtocolException e) {
            System.err.printf("%s [%s] WARN - %s\n", new Date(), THREAD_NAME, e.getMessage());
            closeKey(key, CLOSE_MESG_BIG); // TODO more specific error
        }
    }

    private void doRead(final SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeKey(key, CLOSE_AWAY);
            } else if (read > 0) {
                final ServerAtta atta = (ServerAtta) key.attachment();
                buffer.flip(); // flip for read
                if (atta instanceof HttpServerAtta) {
                    decodeHttp((HttpServerAtta) atta, key, ch);
                } else {
                    decodeWs((WsServerAtta) atta, key);
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    private void doWrite(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            // the sync is per socket (per client). virtually, no contention
            // 1. keep byte data order, 2. ensure visibility
            synchronized (atta) {
                LinkedList<ByteBuffer> toWrites = atta.toWrites;
                int size = toWrites.size();
                if (size == 1) {
                    ch.write(toWrites.get(0));
                    // TODO investigate why needed.
                    // ws request for write, but has no data?
                } else if (size > 0) {
                    ByteBuffer buffers[] = new ByteBuffer[size];
                    toWrites.toArray(buffers);
                    ch.write(buffers, 0, buffers.length);
                }
                Iterator<ByteBuffer> ite = toWrites.iterator();
                while (ite.hasNext()) {
                    if (!ite.next().hasRemaining()) {
                        ite.remove();
                    }
                }
                // all done
                if (toWrites.size() == 0) {
                    if (atta.isKeepAlive()) {
                        key.interestOps(OP_READ);
                    } else {
                        closeKey(key, CLOSE_NORMAL);
                    }
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        synchronized (atta) {
            if (atta.toWrites.isEmpty()) {
                SocketChannel ch = (SocketChannel) key.channel();
                try {
                    // TCP buffer most of time is empty, writable(8K ~ 256k)
                    // One IO thread => One thread reading + Many thread writing
                    // Save 2 system call
                    ch.write(buffers, 0, buffers.length);
                    if (buffers[buffers.length - 1].hasRemaining()) {
                        for (ByteBuffer b : buffers) {
                            if (b.hasRemaining()) {
                                atta.toWrites.add(b);
                            }
                        }
                        pendings.add(key);
                        selector.wakeup();
                    } else if (!atta.isKeepAlive()) {
                        closeKey(key, CLOSE_NORMAL);
                    }
                } catch (IOException e) {
                    closeKey(key, CLOSE_AWAY);
                }
            } else {
                // If has pending write, order should be maintained. (WebSocket)
                for (ByteBuffer b : buffers) {
                    atta.toWrites.add(b);
                }
                pendings.add(key);
                selector.wakeup();
            }
        }
    }

    public void run() {
        while (true) {
            try {
                SelectionKey k = null;
                while ((k = pendings.poll()) != null) {
                    if (k.isValid()) {
                        k.interestOps(OP_WRITE);
                    }
                }
                if (selector.select() <= 0) {
                    continue;
                }
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                for (SelectionKey key : selectedKeys) {
                    // TODO I do not know if this is needed
                    // if !valid, isAcceptable, isReadable.. will Exception
                    // run hours happily after commented, but not sure.
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        doRead(key);
                    } else if (key.isWritable()) {
                        doWrite(key);
                    }
                }
                selectedKeys.clear();
            } catch (ClosedSelectorException ignore) {
                return;
            } catch (Exception e) { // catch any exception, print it
                HttpUtils.printError("http server loop error, should not happen", e);
            }
        }
    }

    public void start() throws IOException {
        serverThread = new Thread(this, THREAD_NAME);
        serverThread.start();
    }

    public void stop() {
        if (selector.isOpen()) {
            try {
                serverChannel.close();
                Set<SelectionKey> keys = selector.keys();
                for (SelectionKey k : keys) {
                    k.channel().close();
                }
                selector.close();
                handler.close();
            } catch (IOException ignore) {
            }
            serverThread.interrupt();
        }
    }
}
