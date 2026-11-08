package com.royleib.fasthotspot.proxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.SocketFactory;

/**
 * A small, fast, dependency-free proxy server that speaks HTTP, HTTPS (CONNECT),
 * SOCKS4/4a and SOCKS5 on a single port.
 *
 * Connections opened by this proxy originate from the phone itself, so the
 * carrier sees ordinary phone traffic instead of tethered traffic.
 *
 * Pure Java (no Android APIs) so it can be unit-tested on a desktop JVM.
 */
public final class ProxyServer {

    /** Resolves host names; lets Android route DNS over a specific network. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int IDLE_CHECK_MS = 30_000;
    private static final int IDLE_TIMEOUT_MS = 5 * 60_000;

    private final int port;
    private final String pacHost;
    private volatile SocketFactory upstreamFactory = SocketFactory.getDefault();
    private volatile Resolver resolver = InetAddress::getAllByName;

    private final AtomicLong bytesUp = new AtomicLong();
    private final AtomicLong bytesDown = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * @param port    TCP port to listen on (all interfaces).
     * @param pacHost host/IP clients should use in the auto-config (PAC) file.
     */
    public ProxyServer(int port, String pacHost) {
        this.port = port;
        this.pacHost = pacHost;
    }

    public void setUpstream(SocketFactory factory, Resolver resolver) {
        this.upstreamFactory = factory != null ? factory : SocketFactory.getDefault();
        this.resolver = resolver != null ? resolver : InetAddress::getAllByName;
    }

    public long getBytesUp() { return bytesUp.get(); }
    public long getBytesDown() { return bytesDown.get(); }
    public int getActiveConnections() { return activeConnections.get(); }
    public int getPort() { return serverSocket != null ? serverSocket.getLocalPort() : port; }
    public boolean isRunning() { return running; }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.setReceiveBufferSize(BUFFER_SIZE * 4);
        serverSocket.bind(new InetSocketAddress(port), 256);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxy-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptThread = new Thread(this::acceptLoop, "proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(serverSocket);
        if (pool != null) pool.shutdownNow();
        serverSocket = null;
        pool = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                // Pool rejected (shutting down).
                if (!running) return;
            }
        }
    }

    private void handle(Socket client) {
        activeConnections.incrementAndGet();
        Socket upstream = null;
        try {
            tune(client);
            client.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(client.getInputStream(), BUFFER_SIZE);
            OutputStream out = client.getOutputStream();
            in.mark(1);
            int first = in.read();
            if (first < 0) return;
            in.reset();
            if (first == 0x05) {
                upstream = handleSocks5(in, out);
            } else if (first == 0x04) {
                upstream = handleSocks4(in, out);
            } else {
                upstream = handleHttp(in, out);
            }
            if (upstream != null) {
                client.setSoTimeout(0);
                relay(client, in, upstream);
            }
        } catch (IOException ignored) {
            // Client went away or upstream failed; nothing else to do.
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
            activeConnections.decrementAndGet();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private Socket handleHttp(InputStream in, OutputStream out) throws IOException {
        byte[] headerBytes = readHeaders(in);
        if (headerBytes == null) return null;
        String head = new String(headerBytes, StandardCharsets.ISO_8859_1);
        int lineEnd = head.indexOf("\r\n");
        String requestLine = lineEnd >= 0 ? head.substring(0, lineEnd) : head;
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            writeStatus(out, 400, "Bad Request");
            return null;
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];

        if ("CONNECT".equalsIgnoreCase(method)) {
            HostPort hp = HostPort.parse(target, 443);
            Socket up = connect(hp.host, hp.port);
            if (up == null) {
                writeStatus(out, 502, "Bad Gateway");
                return null;
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return up;
        }

        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://")) {
            // Direct request to the proxy itself (e.g. PAC file).
            serveLocal(target, out);
            return null;
        }

        // Absolute-form: http://host[:port]/path
        String rest = target.substring("http://".length());
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "/";
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        HostPort hp = HostPort.parse(authority, 80);

        Socket up = connect(hp.host, hp.port);
        if (up == null) {
            writeStatus(out, 502, "Bad Gateway");
            return null;
        }

        StringBuilder sb = new StringBuilder(head.length());
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
        String[] lines = head.substring(lineEnd + 2).split("\r\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("proxy-connection:") || l.startsWith("proxy-authorization:")
                    || l.startsWith("connection:") || l.startsWith("keep-alive:")) {
                continue;
            }
            sb.append(line).append("\r\n");
        }
        // One request per upstream connection keeps the relay simple and correct
        // when a browser reuses its proxy connection for different hosts.
        sb.append("Connection: close\r\n\r\n");
        byte[] rewritten = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        OutputStream upOut = up.getOutputStream();
        upOut.write(rewritten);
        upOut.flush();
        bytesUp.addAndGet(rewritten.length);
        return up;
    }

    private void serveLocal(String target, OutputStream out) throws IOException {
        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/proxy.pac") || path.equals("/wpad.dat") || path.equals("/")) {
            String body = pacFile();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            String resp = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/x-ns-proxy-autoconfig\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.write(b);
            out.flush();
        } else {
            writeStatus(out, 404, "Not Found");
        }
    }

    public String pacFile() {
        String hp = pacHost + ":" + getPort();
        // No dnsResolve() here: the hotspot network has no DNS server of its own, so
        // resolving on the client would stall every page load. The proxy resolves names.
        return "function FindProxyForURL(url, host) {\n"
                + "  if (isPlainHostName(host) || host == \"localhost\" || shExpMatch(host, \"*.local\") ||\n"
                + "      shExpMatch(host, \"192.168.49.*\") || shExpMatch(host, \"127.*\")) {\n"
                + "    return \"DIRECT\";\n"
                + "  }\n"
                + "  return \"PROXY " + hp + "; SOCKS5 " + hp + "\";\n"
                + "}\n";
    }

    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int last4 = 0; // rolling window of the last four bytes
        while (buf.size() < MAX_HEADER_BYTES) {
            int c = in.read();
            if (c < 0) return null;
            buf.write(c);
            last4 = (last4 << 8) | c;
            if (last4 == 0x0D0A0D0A) return buf.toByteArray();
        }
        return null;
    }

    private static void writeStatus(OutputStream out, int code, String text) throws IOException {
        String body = code + " " + text + "\n";
        String resp = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length() + "\r\nConnection: close\r\n\r\n" + body;
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    // -------------------------------------------------------------- SOCKS5

    private Socket handleSocks5(InputStream in, OutputStream out) throws IOException {
        // Greeting: VER NMETHODS METHODS...
        readByte(in); // version 5
        int nMethods = readByte(in);
        boolean noAuth = false;
        for (int i = 0; i < nMethods; i++) {
            if (readByte(in) == 0x00) noAuth = true;
        }
        if (!noAuth) {
            out.write(new byte[]{0x05, (byte) 0xFF});
            out.flush();
            return null;
        }
        out.write(new byte[]{0x05, 0x00});
        out.flush();

        // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        readByte(in);
        int cmd = readByte(in);
        readByte(in);
        int atyp = readByte(in);
        String host;
        switch (atyp) {
            case 0x01: {
                byte[] a = readFully(in, 4);
                host = InetAddress.getByAddress(a).getHostAddress();
                break;
            }
            case 0x03: {
                int len = readByte(in);
                host = new String(readFully(in, len), StandardCharsets.US_ASCII);
                break;
            }
            case 0x04: {
                byte[] a = readFully(in, 16);
                host = InetAddress.getByAddress(a).getHostAddress();
                break;
            }
            default:
                socks5Reply(out, 0x08);
                return null;
        }
        int dstPort = (readByte(in) << 8) | readByte(in);
        if (cmd != 0x01) { // only CONNECT
            socks5Reply(out, 0x07);
            return null;
        }
        Socket up = connect(host, dstPort);
        if (up == null) {
            socks5Reply(out, 0x05);
            return null;
        }
        socks5Reply(out, 0x00);
        return up;
    }

    private static void socks5Reply(OutputStream out, int status) throws IOException {
        out.write(new byte[]{0x05, (byte) status, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    // -------------------------------------------------------------- SOCKS4

    private Socket handleSocks4(InputStream in, OutputStream out) throws IOException {
        readByte(in); // version 4
        int cmd = readByte(in);
        int dstPort = (readByte(in) << 8) | readByte(in);
        byte[] ip = readFully(in, 4);
        readNulTerminated(in); // user id
        String host;
        if (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) {
            host = readNulTerminated(in); // SOCKS4a
        } else {
            host = InetAddress.getByAddress(ip).getHostAddress();
        }
        Socket up = cmd == 0x01 ? connect(host, dstPort) : null;
        out.write(new byte[]{0x00, (byte) (up != null ? 0x5A : 0x5B), 0, 0, 0, 0, 0, 0});
        out.flush();
        return up;
    }

    private static String readNulTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = readByte(in)) != 0) {
            b.write(c);
            if (b.size() > 255) throw new IOException("field too long");
        }
        return b.toString("US-ASCII");
    }

    // -------------------------------------------------------------- common

    private Socket connect(String host, int port) {
        InetAddress[] addrs;
        try {
            addrs = resolver.resolve(host);
        } catch (IOException e) {
            return null;
        }
        for (InetAddress addr : addrs) {
            Socket s = null;
            try {
                s = upstreamFactory.createSocket();
                tune(s);
                s.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                closeQuietly(s);
            }
        }
        return null;
    }

    private static void tune(Socket s) throws SocketException {
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setReceiveBufferSize(BUFFER_SIZE * 4);
        s.setSendBufferSize(BUFFER_SIZE * 4);
    }

    /** Pumps bytes in both directions until either side closes or both go quiet. */
    private void relay(Socket client, InputStream clientIn, Socket upstream) throws IOException {
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        OutputStream clientOut = client.getOutputStream();
        // Reads wake up periodically so dead connections (e.g. a laptop that went to
        // sleep or switched networks) are closed instead of piling up forever.
        client.setSoTimeout(IDLE_CHECK_MS);
        upstream.setSoTimeout(IDLE_CHECK_MS);
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

        Thread upThread = new Thread(() -> {
            if (pump(clientIn, upOut, bytesUp, lastActivity)) {
                closeQuietly(upstream);
                closeQuietly(client);
            } else {
                try { upstream.shutdownOutput(); } catch (IOException ignored) { }
            }
        }, "proxy-up");
        upThread.setDaemon(true);
        upThread.start();

        if (pump(upIn, clientOut, bytesDown, lastActivity)) {
            closeQuietly(upstream);
            closeQuietly(client);
        } else {
            try { client.shutdownOutput(); } catch (IOException ignored) { }
        }
        try {
            upThread.join(IDLE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Copies until EOF or error. Returns true if it stopped because the connection sat idle. */
    private static boolean pump(InputStream in, OutputStream out, AtomicLong counter, AtomicLong lastActivity) {
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            while (true) {
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException e) {
                    // Only give up when neither direction has moved data for a while,
                    // so a long download with a quiet upload side keeps going.
                    if (System.currentTimeMillis() - lastActivity.get() >= IDLE_TIMEOUT_MS) return true;
                    continue;
                }
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
                counter.addAndGet(n);
                lastActivity.set(System.currentTimeMillis());
            }
            out.flush();
        } catch (IOException ignored) {
            // Connection closed.
        }
        return false;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("unexpected end of stream");
        return b;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("unexpected end of stream");
            off += n;
        }
        return b;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort parse(String s, int defaultPort) {
            if (s.startsWith("[")) { // IPv6 literal
                int end = s.indexOf(']');
                String h = s.substring(1, end);
                int p = defaultPort;
                if (end + 1 < s.length() && s.charAt(end + 1) == ':') {
                    p = Integer.parseInt(s.substring(end + 2));
                }
                return new HostPort(h, p);
            }
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(':') == colon) {
                return new HostPort(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
            }
            return new HostPort(s, defaultPort);
        }
    }
}
