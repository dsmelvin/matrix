package guru.kumo.operator.tool;

import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gives a sandboxed command controlled access to one host Unix socket (typically the Docker daemon socket).
 * <p>
 * The sandbox is only allowed to reach the broker's own socket. For every connection the broker asks the user
 * (see {@link ConsolePermissionPrompt#TIMEOUT_MS} for how long it waits) and only forwards the bytes to the real socket if the answer is
 * yes or all. "yes" is valid for the command that is running at that moment: {@link #newCommand()} (called whenever a
 * new command is wrapped) revokes it, so the next command asks again. "all" is valid for the rest of the session.
 * No answer means the connection is closed.
 */
@Slf4j
final class UnixSocketBroker implements AutoCloseable {

    private final Path target;
    private final Path socketPath;
    private final ServerSocketChannel server;
    private final AtomicLong commandGeneration = new AtomicLong();
    private volatile long grantedGeneration = -1;
    /** Set by answering "all": no more questions until the application exits. */
    private volatile boolean alwaysAllow;

    UnixSocketBroker(Path target) throws IOException {
        this.target = target;
        Path dir = Files.createTempDirectory("matrix-sbx").toRealPath();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        dir.toFile().deleteOnExit();
        this.socketPath = dir.resolve("s.sock");
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.server.bind(UnixDomainSocketAddress.of(socketPath));
        socketPath.toFile().deleteOnExit();
        Thread acceptor = new Thread(this::acceptLoop, "shell-sandbox-socket-broker");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The socket the sandboxed command connects to. */
    Path socketPath() {
        return socketPath;
    }

    /** Path of the real socket this broker forwards to. */
    Path target() {
        return target;
    }

    /** Marks the start of a new command, which revokes an earlier approval. */
    void newCommand() {
        commandGeneration.incrementAndGet();
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                SocketChannel client = server.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException e) {
                if (server.isOpen()) log.debug("Socket broker accept failed: {}", e.getMessage());
            }
        }
    }

    private void handle(SocketChannel client) {
        try (client) {
            if (!approved()) {
                log.warn("{}Shell sandbox: blocked access to {}{}", ColorEnum.BOLD_HIGH_INTENSITY_RED, target, ColorEnum.RESET);
                return;
            }
            try (SocketChannel upstream = SocketChannel.open(UnixDomainSocketAddress.of(target))) {
                Thread toUpstream = Thread.ofVirtual().start(() -> pump(client, upstream));
                pump(upstream, client);
                try {
                    toUpstream.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Socket broker connection failed: {}", e.getMessage());
        }
    }

    private boolean approved() {
        if (alwaysAllow) return true;
        long generation = commandGeneration.get();
        if (grantedGeneration == generation) return true;
        return ConsolePermissionPrompt.runExclusively(() -> {
            // Another connection may have been approved while this one was waiting for the prompt.
            if (alwaysAllow || grantedGeneration == generation) return true;
            ConsolePermissionPrompt.Answer answer = ConsolePermissionPrompt.askLocked(
                    "Sandbox blocked access to " + target + ". Allow it for this command?", true);
            switch (answer) {
                case ALL -> {
                    alwaysAllow = true;
                    log.info("{}Shell sandbox: access to {} granted for all commands of this session{}", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, target, ColorEnum.RESET);
                }
                case YES -> {
                    grantedGeneration = generation;
                    log.info("{}Shell sandbox: access to {} granted for the running command{}", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, target, ColorEnum.RESET);
                }
                case NO -> {
                    return false;
                }
            }
            return true;
        });
    }

    /** Copies until EOF, then half-closes the destination so the peer sees the end of the stream. */
    private static void pump(SocketChannel from, SocketChannel to) {
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) to.write(buffer);
                buffer.clear();
            }
            to.shutdownOutput();
        } catch (IOException ignored) {
            // connection closed
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // closing
        }
    }
}
