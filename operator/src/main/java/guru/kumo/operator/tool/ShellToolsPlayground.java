package guru.kumo.operator.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import guru.kumo.operator.util.EnvFileLoader;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A small web UI to send shell commands to {@link ShellTools} (the {@code Bash} tool of the agent) so the permissions of
 * the sandbox can be verified by hand on macOS (Seatbelt) and Linux (bubblewrap).
 * <p>
 * It uses the very same code path as the agent: the env file ({@code --env-file/-e}) and the workspace
 * ({@code --workspace/-w}) are loaded by {@link EnvFileLoader}, the sandbox is built by {@link ShellSandbox} and the command
 * is executed by {@link ShellTools#bash}. The approval questions of the sandbox (a host that is not allow-listed, the Docker
 * socket) normally appear on the console; here they appear in the page ({@link ConsolePermissionPrompt#setResponder}).
 * <p>
 * It does not start the Spring context. Run it with {@code scripts/sandbox-ui.sh [-e env-file] [-w workspace] [--port 8765]}.
 * <p>
 * Because this executes shell commands for whoever can reach it, it only listens on the loopback interface, requires a random
 * token (part of the printed URL) on every request and rejects requests whose Host header is not a loopback name (DNS rebinding).
 */
@Slf4j
public final class ShellToolsPlayground {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int DEFAULT_PORT = 8765;
    private static final String TOKEN_HEADER = "X-Playground-Token";
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final int MAX_HISTORY = 50;

    private final String token = newToken();
    private final String initialUserDir = System.getProperty("user.dir");
    private final AtomicReference<Session> session = new AtomicReference<>();
    private final Map<Long, PendingPrompt> pendingPrompts = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> promptHistory = new CopyOnWriteArrayList<>();
    private final AtomicLong promptIds = new AtomicLong();

    /** What the user asked for in the form, so the page can show it again. */
    private record Settings(String envFile, String workspace, String mode, String network) {
    }

    private record Session(Settings settings, ShellSandbox sandbox, ShellTools tools) {
    }

    private static final class PendingPrompt {
        final long id;
        final String question;
        final boolean offerAll;
        final long deadlineNanos;
        final CompletableFuture<ConsolePermissionPrompt.Answer> answer = new CompletableFuture<>();

        PendingPrompt(long id, String question, boolean offerAll) {
            this.id = id;
            this.question = question;
            this.offerAll = offerAll;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ConsolePermissionPrompt.TIMEOUT_MS);
        }
    }

    private ShellToolsPlayground() {
    }

    public static void main(String[] args) throws Exception {
        String portArg = option(args, "port", null);
        int port = portArg == null ? DEFAULT_PORT : Integer.parseInt(portArg.strip());
        String envFile = option(args, EnvFileLoader.ENV_FILE_LONG_OPTION, EnvFileLoader.ENV_FILE_SHORT_OPTION);
        String workspace = option(args, EnvFileLoader.WORKSPACE_LONG_OPTION, EnvFileLoader.WORKSPACE_SHORT_OPTION);

        ShellToolsPlayground playground = new ShellToolsPlayground();
        playground.configure(new Settings(blankToEmpty(envFile), blankToEmpty(workspace), "", ""));
        ConsolePermissionPrompt.setResponder(playground::askInBrowser);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        playground.register(server);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "playground-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            ConsolePermissionPrompt.setResponder(null);
            Session current = playground.session.get();
            if (current != null) current.sandbox().close();
        }));

        String url = "http://localhost:" + server.getAddress().getPort() + "/?token=" + playground.token;
        System.out.printf("%nShell sandbox playground: %s%nPress Ctrl-C to stop.%n", url);
        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Configuration: the same steps the agent goes through at startup
    // ------------------------------------------------------------------------------------------------------------

    /**
     * (Re)loads the env file and workspace and builds a fresh sandbox + tool, like {@code OperatorApplication.main} and
     * {@code AgentOperator.loadTools} do. A sandbox reads its settings when it is built, so every change needs a new one.
     */
    private synchronized Session configure(Settings settings) {
        // prepare() only ever sets user.dir when a workspace is given, so start from the original directory.
        System.setProperty("user.dir", initialUserDir);
        if (settings.mode().isBlank()) {
            System.clearProperty("matrix.shell.sandbox");
        } else {
            System.setProperty("matrix.shell.sandbox", settings.mode());
        }

        List<String> launchArguments = new ArrayList<>();
        if (!settings.envFile().isBlank()) launchArguments.addAll(List.of("--" + EnvFileLoader.ENV_FILE_LONG_OPTION, settings.envFile()));
        if (!settings.workspace().isBlank()) launchArguments.addAll(List.of("--" + EnvFileLoader.WORKSPACE_LONG_OPTION, settings.workspace()));
        EnvFileLoader.prepare(launchArguments.toArray(String[]::new));

        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        List<Path> writable = List.of(Path.of("/tmp"), Path.of(System.getProperty("java.io.tmpdir")), workingDirectory);
        ShellSandbox.Builder sandboxBuilder = ShellSandbox.builder().workingDirectory(workingDirectory).writableDirectories(writable);
        switch (settings.network()) {
            case "allow" -> sandboxBuilder.allowNetwork(true);
            case "block" -> sandboxBuilder.allowNetwork(false);
            default -> {
            }
        }
        ShellSandbox sandbox = sandboxBuilder.build();
        Session next = new Session(settings, sandbox, ShellTools.builder().sandbox(sandbox).build());
        Session previous = session.getAndSet(next);
        if (previous != null) previous.sandbox().close();
        return next;
    }

    private Map<String, Object> state() {
        Session current = session.get();
        ShellSandbox sandbox = current.sandbox();
        Settings settings = current.settings();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        Map<String, Object> host = new LinkedHashMap<>();
        host.put("os", os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : os.contains("win") ? "windows" : "other");
        host.put("osName", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        host.put("java", System.getProperty("java.version"));
        host.put("user", System.getProperty("user.name"));
        host.put("home", System.getProperty("user.home"));
        host.put("inContainer", ShellSandbox.isRunningInContainer());

        Map<String, Object> envFile = new LinkedHashMap<>();
        Path resolvedEnvFile = resolveEnvFile(settings.envFile());
        envFile.put("resolved", resolvedEnvFile.toString());
        envFile.put("exists", Files.isRegularFile(resolvedEnvFile));
        // Names only: the values can be secrets.
        envFile.put("variableNames", new ArrayList<>(EnvFileLoader.variables().keySet()));

        Map<String, Object> sandboxInfo = new LinkedHashMap<>();
        sandboxInfo.put("mode", sandbox.mode().name());
        sandboxInfo.put("backend", sandbox.backend().name());
        sandboxInfo.put("active", sandbox.isActive());
        sandboxInfo.put("networkBlocked", sandbox.isNetworkBlocked());
        sandboxInfo.put("workingDirectory", sandbox.workingDirectory().toString());
        sandboxInfo.put("description", sandbox.describe());

        Map<String, Object> binaries = new LinkedHashMap<>();
        for (String name : List.of("bwrap", "socat", "curl", "docker", "git")) binaries.put(name, onPath(name));
        binaries.put("sandbox-exec", Files.isExecutable(Path.of("/usr/bin/sandbox-exec")));

        List<String> warnings = new ArrayList<>();
        if (!sandbox.isActive() && sandbox.mode() == ShellSandbox.Mode.REQUIRED) {
            warnings.add("No sandbox backend is usable and the mode is REQUIRED: every command will be refused.");
        } else if (!sandbox.isActive() && sandbox.mode() == ShellSandbox.Mode.AUTO) {
            warnings.add("No sandbox backend is usable: commands run WITHOUT a sandbox.");
        } else if (sandbox.mode() == ShellSandbox.Mode.OFF) {
            warnings.add("The sandbox is OFF: commands run with the full permissions of this process.");
        }
        if (!settings.envFile().isBlank() && !Files.isRegularFile(resolvedEnvFile)) {
            warnings.add("Env file does not exist: " + resolvedEnvFile);
        }
        if (!settings.workspace().isBlank() && !Files.isDirectory(resolveWorkspace(settings.workspace()))) {
            warnings.add("Workspace is not a directory, it was ignored: " + resolveWorkspace(settings.workspace()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("settings", Map.of("envFile", settings.envFile(), "workspace", settings.workspace(), "mode", settings.mode(), "network", settings.network()));
        result.put("workspace", System.getProperty("user.dir"));
        result.put("envFile", envFile);
        result.put("sandbox", sandboxInfo);
        result.put("binaries", binaries);
        result.put("warnings", warnings);
        result.put("promptTimeoutMs", ConsolePermissionPrompt.TIMEOUT_MS);
        return result;
    }

    /** The workspace as {@link EnvFileLoader} resolves it, for the "was it ignored" check. */
    private Path resolveWorkspace(String workspace) {
        Path launch = Path.of(firstNonBlank(System.getenv("PWD"), initialUserDir)).toAbsolutePath().normalize();
        return launch.resolve(expandTilde(workspace)).normalize();
    }

    private Path resolveEnvFile(String envFile) {
        Path launch = Path.of(firstNonBlank(System.getenv("PWD"), initialUserDir)).toAbsolutePath().normalize();
        return envFile.isBlank() ? launch.resolve(".env") : launch.resolve(expandTilde(envFile)).normalize();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Approval prompts of the sandbox
    // ------------------------------------------------------------------------------------------------------------

    private ConsolePermissionPrompt.Answer askInBrowser(String question, boolean offerAll) {
        PendingPrompt prompt = new PendingPrompt(promptIds.incrementAndGet(), question, offerAll);
        pendingPrompts.put(prompt.id, prompt);
        ConsolePermissionPrompt.Answer answer = ConsolePermissionPrompt.Answer.NO;
        String outcome = "no answer in time";
        try {
            answer = prompt.answer.get(ConsolePermissionPrompt.TIMEOUT_MS, TimeUnit.MILLISECONDS);
            outcome = answer.name().toLowerCase(Locale.ROOT);
        } catch (TimeoutException e) {
            // no answer counts as no, like on the console
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
        } catch (Exception e) {
            outcome = "error";
        } finally {
            pendingPrompts.remove(prompt.id);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", java.time.LocalTime.now().withNano(0).toString());
        entry.put("question", question);
        entry.put("outcome", outcome);
        promptHistory.add(0, entry);
        while (promptHistory.size() > MAX_HISTORY) promptHistory.remove(promptHistory.size() - 1);
        return answer;
    }

    private Map<String, Object> promptState() {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (PendingPrompt prompt : pendingPrompts.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", prompt.id);
            entry.put("question", prompt.question);
            entry.put("offerAll", prompt.offerAll);
            entry.put("remainingMs", Math.max(0, TimeUnit.NANOSECONDS.toMillis(prompt.deadlineNanos - System.nanoTime())));
            pending.add(entry);
        }
        pending.sort((a, b) -> Long.compare((Long) a.get("id"), (Long) b.get("id")));
        return Map.of("pending", pending, "history", promptHistory);
    }

    // ------------------------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------------------------

    private void register(HttpServer server) {
        server.createContext("/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            if (!hostAllowed(exchange) || !token.equals(queryParameter(exchange.getRequestURI(), "token"))) {
                send(exchange, 403, "text/plain; charset=utf-8", "Open the URL that was printed in the terminal (it contains the token).");
                return;
            }
            try (InputStream page = ShellToolsPlayground.class.getResourceAsStream("/shell-playground/index.html")) {
                if (page == null) {
                    send(exchange, 500, "text/plain; charset=utf-8", "shell-playground/index.html is missing from the classpath");
                    return;
                }
                String html = new String(page.readAllBytes(), StandardCharsets.UTF_8).replace("__TOKEN__", token);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                send(exchange, 200, "text/html; charset=utf-8", html);
            }
        });
        api(server, "/api/state", "GET", body -> state());
        api(server, "/api/configure", "POST", body -> {
            configure(new Settings(text(body, "envFile"), text(body, "workspace"), text(body, "mode").toLowerCase(Locale.ROOT), text(body, "network").toLowerCase(Locale.ROOT)));
            return state();
        });
        api(server, "/api/run", "POST", body -> {
            String command = text(body, "command");
            if (command.isBlank()) throw new IllegalArgumentException("command is empty");
            Long timeout = body.get("timeoutMs") instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
            boolean background = Boolean.TRUE.equals(body.get("background"));
            long started = System.nanoTime();
            String output = session.get().tools().bash(command, timeout, background, "Sandbox playground command");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("output", output);
            result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return result;
        });
        api(server, "/api/output", "POST", body -> Map.of("output",
                session.get().tools().bashOutput(text(body, "id"), blankToNull(text(body, "filter")))));
        api(server, "/api/kill", "POST", body -> Map.of("output", session.get().tools().killShell(text(body, "id"))));
        api(server, "/api/prompts", "GET", body -> promptState());
        api(server, "/api/prompts/answer", "POST", body -> {
            PendingPrompt prompt = pendingPrompts.get(body.get("id") instanceof Number n ? n.longValue() : -1L);
            if (prompt == null) return Map.of("ok", false, "message", "The question is gone (answered or timed out).");
            ConsolePermissionPrompt.Answer answer = switch (text(body, "answer").toLowerCase(Locale.ROOT)) {
                case "yes" -> ConsolePermissionPrompt.Answer.YES;
                case "all" -> prompt.offerAll ? ConsolePermissionPrompt.Answer.ALL : ConsolePermissionPrompt.Answer.YES;
                default -> ConsolePermissionPrompt.Answer.NO;
            };
            prompt.answer.complete(answer);
            return Map.of("ok", true);
        });
    }

    private interface ApiHandler {
        Object handle(Map<String, Object> body) throws Exception;
    }

    private void api(HttpServer server, String path, String method, ApiHandler handler) {
        HttpHandler wrapped = exchange -> {
            try {
                if (!exchange.getRequestMethod().equals(method)) {
                    send(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
                    return;
                }
                if (!hostAllowed(exchange) || !tokenValid(exchange)) {
                    send(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
                    return;
                }
                Map<String, Object> body = Map.of();
                if (method.equals("POST")) {
                    String raw = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
                    if (!raw.isBlank()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = JSON.readValue(raw, Map.class);
                        body = parsed;
                    }
                }
                send(exchange, 200, "application/json", JSON.writeValueAsString(handler.handle(body)));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", errorJson(e));
            } catch (Exception e) {
                log.warn("Request {} failed", path, e);
                send(exchange, 500, "application/json", errorJson(e));
            }
        };
        server.createContext(path, wrapped);
    }

    private static String errorJson(Exception e) {
        return JSON.writeValueAsString(Map.of("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
    }

    private boolean tokenValid(HttpExchange exchange) {
        String given = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** Only loopback host names: a web page on another origin can't reach this server by rebinding its DNS name. */
    private static boolean hostAllowed(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.split(":", 2)[0];
        return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String option(String[] args, String longName, Character shortName) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--" + longName) || (shortName != null && arg.equals("-" + shortName))) {
                return i + 1 < args.length ? args[i + 1] : null;
            }
            if (arg.startsWith("--" + longName + "=")) return arg.substring(longName.length() + 3);
        }
        return null;
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, executable))) return true;
        }
        return false;
    }

    private static String expandTilde(String value) {
        String home = System.getProperty("user.home");
        if (value.equals("~")) return home;
        return value.startsWith("~/") ? home + value.substring(1) : value;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
