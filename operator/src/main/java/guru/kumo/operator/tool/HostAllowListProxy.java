package guru.kumo.operator.tool;

import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal local HTTP/HTTPS forward proxy that only lets requests through to allow-listed hosts.
 * <p>
 * The OS sandbox can only block the network as a whole (or allow a single local port), so the sandboxed command is
 * limited to reaching this proxy and the proxy decides per host. Supports {@code CONNECT host:port} (HTTPS) and plain
 * HTTP requests with an absolute URI. Entries are host names or URLs; {@code example.com} matches exactly,
 * {@code *.example.com} matches any sub domain. Hosts resolving to loopback / link-local addresses are always refused
 * (and private addresses unless the host is listed by name), so an allowed name cannot be used to reach local
 * services or cloud metadata endpoints.
 */
@Slf4j
final class HostAllowListProxy implements AutoCloseable {

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final List<String> allowed;
    private final ServerSocket server;
    private final ServerSocketChannel unixServer;
    private final Path socketPath;

    /** Listens on a loopback TCP port. */
    HostAllowListProxy(List<String> allowList) throws IOException {
        this.allowed = normalizeAll(allowList);
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.unixServer = null;
        this.socketPath = null;
        start();
    }

    /** Listens on a Unix domain socket (reachable from a network-less sandbox through a bind mount + socat). */
    HostAllowListProxy(List<String> allowList, Path socket) throws IOException {
        this.allowed = normalizeAll(allowList);
        this.server = null;
        this.socketPath = socket;
        this.unixServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.unixServer.bind(UnixDomainSocketAddress.of(socket));
        start();
    }

    private static List<String> normalizeAll(List<String> list) {
        return list.stream().map(HostAllowListProxy::normalize).filter(s -> !s.isEmpty()).toList();
    }

    private void start() {
        Thread acceptor = new Thread(this::acceptLoop, "shell-sandbox-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    Path socketPath() {
        return socketPath;
    }

    /** Accepts "https://api.example.com/path", "api.example.com:443" or "*.example.com". */
    static String normalize(String entry) {
        String value = entry.strip().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) value = value.substring(0, colon);
        return value;
    }

    boolean isAllowed(String host) {
        return allowed.stream().anyMatch(p -> p.equals("*") || new Matcher(p).matches(host));
    }

    record Matcher(String pattern) {
        boolean matches(String host) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            return pattern.startsWith("*.") ? h.endsWith(pattern.substring(1)) : pattern.equals(h);
        }
    }

    /** True if the host matches an entry other than the catch-all "*". */
    private boolean isListedByName(String host) {
        return allowed.stream().anyMatch(p -> !p.equals("*") && new HostAllowListProxy.Matcher(p).matches(host));
    }

    private void acceptLoop() {
        while (isOpen()) {
            try {
                if (server != null) {
                    Socket client = server.accept();
                    Thread.ofVirtual().start(() -> handle(client, client::getInputStream, client::getOutputStream));
                } else {
                    SocketChannel client = unixServer.accept();
                    Thread.ofVirtual().start(() -> handle(client, () -> Channels.newInputStream(client),
                            () -> Channels.newOutputStream(client)));
                }
            } catch (IOException e) {
                if (isOpen()) log.debug("Sandbox proxy accept failed: {}", e.getMessage());
            }
        }
    }

    private boolean isOpen() {
        return server != null ? !server.isClosed() : unixServer.isOpen();
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private void handle(Closeable client, IoSupplier<InputStream> inSupplier, IoSupplier<OutputStream> outSupplier) {
        try (client) {
            if (client instanceof Socket socket) socket.setSoTimeout(30_000);
            InputStream in = inSupplier.get();
            OutputStream out = outSupplier.get();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 3) {
                reply(out, 400, "Bad Request");
                return;
            }
            String method = request[0];
            String host;
            int port;
            byte[] initial = null;
            if (method.equalsIgnoreCase("CONNECT")) {
                String authority = request[1];
                int colon = authority.lastIndexOf(':');
                host = colon > 0 ? authority.substring(0, colon) : authority;
                port = colon > 0 ? Integer.parseInt(authority.substring(colon + 1)) : 443;
            } else {
                URI uri = URI.create(request[1]);
                if (uri.getHost() == null || !"http".equalsIgnoreCase(uri.getScheme())) {
                    reply(out, 400, "Only absolute http:// URIs and CONNECT are supported");
                    return;
                }
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : 80;
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                StringBuilder rewritten = new StringBuilder(method).append(' ').append(path).append(' ')
                        .append(request[2]).append("\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String lower = lines[i].toLowerCase(Locale.ROOT);
                    if (lower.startsWith("proxy-") || lower.startsWith("connection:")) continue;
                    rewritten.append(lines[i]).append("\r\n");
                }
                // One request per connection, so every request is checked against the allow list.
                rewritten.append("Connection: close\r\n\r\n");
                initial = rewritten.toString().getBytes(StandardCharsets.ISO_8859_1);
            }

            if (!isAllowed(host)) {
                log.info("{}Shell sandbox: blocked network access to {}:{}{}", ColorEnum.BOLD_HIGH_INTENSITY_RED, host, port, ColorEnum.RESET);
                reply(out, 403, "Host " + host + " is not in AGENT_SHELL_SANDBOX_ALLOWED_HOSTS");
                return;
            }
            InetAddress address = InetAddress.getByName(host);
            // Private (RFC 1918) addresses are fine for hosts that are listed by name (internal servers such as
            // bitbucket.example.com); loopback and link-local (cloud metadata) never are.
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                    || (address.isSiteLocalAddress() && !isListedByName(host))) {
                reply(out, 403, "Host " + host + " resolves to a local address");
                return;
            }
            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress(address, port), 15_000);
                if (initial == null) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } else {
                    upstream.getOutputStream().write(initial);
                }
                if (client instanceof Socket socket) socket.setSoTimeout(0);
                Thread.ofVirtual().start(() -> {
                    try {
                        in.transferTo(upstream.getOutputStream());
                        upstream.shutdownOutput();
                    } catch (IOException ignored) {
                        // connection closed
                    }
                });
                upstream.getInputStream().transferTo(out);
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Sandbox proxy connection failed: {}", e.getMessage());
        }
    }

    /** Reads up to and including the blank line that ends the request head. */
    private static String readHead(InputStream in) throws IOException {
        List<Byte> bytes = new ArrayList<>();
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            bytes.add((byte) b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                byte[] array = new byte[bytes.size() - 4];
                for (int i = 0; i < array.length; i++) array[i] = bytes.get(i);
                return new String(array, StandardCharsets.ISO_8859_1);
            }
            if (bytes.size() > MAX_HEADER_BYTES) return null;
        }
        return null;
    }

    private static void reply(OutputStream out, int status, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " " + message.split("\n")[0].replace('\r', ' ') + "\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() {
        try {
            if (server != null) server.close();
            if (unixServer != null) unixServer.close();
            if (socketPath != null) Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // closing
        }
    }
}
