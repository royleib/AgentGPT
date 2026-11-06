import com.royleib.fasthotspot.proxy.ProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/**
 * Plain-JVM test for the proxy engine. Run with:
 *   ./proxy-test/run.sh
 */
public class ProxyServerTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // Upstream "origin" server: echoes raw bytes, or answers HTTP requests
        // with the request line it received.
        ServerSocket origin = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = origin.accept();
                    new Thread(() -> serveOrigin(s)).start();
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        int op = origin.getLocalPort();

        ProxyServer proxy = new ProxyServer(0, "192.168.49.1");
        proxy.start();
        int pp = proxy.getPort();

        // 1. Plain HTTP with absolute URI
        try (Socket s = new Socket("127.0.0.1", pp)) {
            s.getOutputStream().write(("GET http://127.0.0.1:" + op + "/hello?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String resp = readAll(s.getInputStream());
            check("http forward", resp.contains("REQ=GET /hello?x=1 HTTP/1.1") && resp.contains("NOPROXYHDR"));
        }

        // 2. HTTPS CONNECT tunnel carrying 2 MB of random data
        byte[] payload = new byte[2 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        try (Socket s = new Socket("127.0.0.1", pp)) {
            OutputStream o = s.getOutputStream();
            InputStream i = s.getInputStream();
            o.write(("CONNECT 127.0.0.1:" + op + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String status = readLine(i);
            readLine(i); // blank line
            check("connect status", status.startsWith("HTTP/1.1 200"));
            check("connect echo", echoRoundTrip(s, payload));
        }

        // 3. SOCKS5 with domain name
        try (Socket s = new Socket("127.0.0.1", pp)) {
            OutputStream o = s.getOutputStream();
            DataInputStream i = new DataInputStream(s.getInputStream());
            o.write(new byte[]{5, 1, 0});
            byte[] g = new byte[2];
            i.readFully(g);
            check("socks5 greeting", g[0] == 5 && g[1] == 0);
            byte[] host = "localhost".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0, 3, (byte) host.length});
            req.write(host);
            req.write(op >> 8);
            req.write(op & 0xFF);
            o.write(req.toByteArray());
            byte[] r = new byte[10];
            i.readFully(r);
            check("socks5 reply", r[1] == 0);
            check("socks5 echo", echoRoundTrip(s, payload));
        }

        // 4. SOCKS4a
        try (Socket s = new Socket("127.0.0.1", pp)) {
            OutputStream o = s.getOutputStream();
            DataInputStream i = new DataInputStream(s.getInputStream());
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{4, 1, (byte) (op >> 8), (byte) op, 0, 0, 0, 1, 0});
            req.write("localhost".getBytes(StandardCharsets.US_ASCII));
            req.write(0);
            o.write(req.toByteArray());
            byte[] r = new byte[8];
            i.readFully(r);
            check("socks4a reply", (r[1] & 0xFF) == 0x5A);
            check("socks4a echo", echoRoundTrip(s, "ping".getBytes()));
        }

        // 5. PAC file
        try (Socket s = new Socket("127.0.0.1", pp)) {
            s.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            String resp = readAll(s.getInputStream());
            check("pac file", resp.contains("FindProxyForURL") && resp.contains("PROXY 192.168.49.1:" + pp));
        }

        // 6. Unreachable upstream -> 502
        try (Socket s = new Socket("127.0.0.1", pp)) {
            s.getOutputStream().write("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            check("bad gateway", readAll(s.getInputStream()).startsWith("HTTP/1.1 502"));
        }

        check("byte counters", proxy.getBytesUp() > 4_000_000 && proxy.getBytesDown() > 4_000_000);
        proxy.stop();
        origin.close();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void serveOrigin(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] peek = new byte[4];
            int n = in.read(peek);
            if (n == 4 && new String(peek, StandardCharsets.US_ASCII).equals("GET ")) {
                String line = "GET " + readLine(in);
                StringBuilder headers = new StringBuilder();
                String h;
                while (!(h = readLine(in)).isEmpty()) headers.append(h).append('\n');
                String flag = headers.toString().toLowerCase().contains("proxy-connection") ? "HASPROXYHDR" : "NOPROXYHDR";
                String body = "REQ=" + line + "\n" + flag + "\n";
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                s.close();
                return;
            }
            if (n > 0) out.write(peek, 0, n);
            byte[] buf = new byte[8192];
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    static boolean echoRoundTrip(Socket s, byte[] payload) throws Exception {
        Thread w = new Thread(() -> {
            try {
                s.getOutputStream().write(payload);
                s.getOutputStream().flush();
            } catch (Exception ignored) {
            }
        });
        w.start();
        byte[] got = new byte[payload.length];
        new DataInputStream(s.getInputStream()).readFully(got);
        w.join();
        return Arrays.equals(got, payload);
    }

    static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
        return sb.toString();
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("ISO-8859-1");
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
}
