package guru.kumo.operator.tool;

import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.EnvFileLoader;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * OS level sandbox for commands executed by {@link ShellTools}.
 * <p>
 * What the sandbox enforces:
 * <ul>
 *   <li><b>Filesystem writes</b> are only allowed below the configured writable directories
 *       (working directory, temp directories, ...). Everything else is read-only.</li>
 *   <li><b>Sensitive directories</b> (e.g. {@code ~/.ssh}, {@code ~/.aws}) are hidden from reads and writes.</li>
 *   <li><b>Network</b> can be switched off completely or limited to an allow-list of hosts (see {@link #ENV_ALLOWED_HOSTS}; needs
 *       {@code socat} on Linux).</li>
 *   <li><b>Environment</b> is reduced to an allow-list so API keys and other secrets of the parent JVM
 *       are not visible to the command.</li>
 *   <li>The command runs with the configured working directory.</li>
 * </ul>
 * Backends: macOS Seatbelt ({@code sandbox-exec}) and Linux bubblewrap ({@code bwrap}).
 * <p>
 * The {@link Mode} decides what happens when no backend is usable:
 * {@link Mode#REQUIRED} (default) refuses to run commands, {@link Mode#AUTO} runs them unsandboxed with a
 * warning in the output, {@link Mode#OFF} disables the sandbox. It can be set with the
 * {@code AGENT_SHELL_SANDBOX_MODE} variable or the {@code matrix.shell.sandbox} system property.
 * <p>
 * Besides the mode, the sandbox is configured through variables of the env file ({@code --env-file}) or the OS
 * environment; lists are "," separated like {@code AGENT_PATHS_AGENTS}:
 * <pre>
 * AGENT_SHELL_SANDBOX_MODE=required
 * AGENT_SHELL_SANDBOX_WRITABLE_PATHS=~/.m2,~/.npm
 * AGENT_SHELL_SANDBOX_DENY_PATHS=~/Documents,secrets
 * AGENT_SHELL_SANDBOX_DENY_FILE_NAMES=.env,*.pem
 * AGENT_SHELL_SANDBOX_PASS_ENV=GH_TOKEN,NPM_TOKEN
 * AGENT_SHELL_SANDBOX_ALLOW_NETWORK=true
 * AGENT_SHELL_SANDBOX_ALLOWED_HOSTS=github.com,*.githubusercontent.com,https://repo.maven.apache.org
 * </pre>
 * Relative paths are resolved against the working directory. All variables are optional. A denied path always wins:
 * a writable directory that is, or lives inside, a denied path is ignored.
 */
@Slf4j
public final class ShellSandbox {

    /** required | auto | off. */
    public static final String ENV_MODE = "AGENT_SHELL_SANDBOX_MODE";
    private static final String LEGACY_ENV_MODE = "MATRIX_SHELL_SANDBOX";
    /** "," separated directories the command may additionally write to. */
    public static final String ENV_WRITABLE_PATHS = "AGENT_SHELL_SANDBOX_WRITABLE_PATHS";
    /** "," separated files/directories the command can neither read nor write. */
    public static final String ENV_DENY_PATHS = "AGENT_SHELL_SANDBOX_DENY_PATHS";
    /**
     * "," separated file (or directory) names that are denied wherever they are, e.g. {@code .env,*.pem}. Supports the
     * wildcards {@code *} and {@code ?}, is case-insensitive and is added to a built-in list (.netrc, id_rsa, ...).
     */
    public static final String ENV_DENY_FILE_NAMES = "AGENT_SHELL_SANDBOX_DENY_FILE_NAMES";
    /** "," separated names of environment variables handed to the command (from the env file or the OS). */
    public static final String ENV_PASS_ENV = "AGENT_SHELL_SANDBOX_PASS_ENV";
    /** true | false (default true). */
    public static final String ENV_ALLOW_NETWORK = "AGENT_SHELL_SANDBOX_ALLOW_NETWORK";

    /**
     * "," separated hosts (or URLs) the command may reach, e.g. {@code github.com,*.example.com}. When set, the command
     * can only talk to a local filtering proxy ({@code HTTP_PROXY}/{@code HTTPS_PROXY} are set for it), so only
     * HTTP(S) traffic of proxy-aware tools works. Takes precedence over {@link #ENV_ALLOW_NETWORK}=true. Enforced on
     * macOS and on Linux (bubblewrap + {@code socat}); if the proxy cannot be set up the network is blocked completely
     * instead (fail closed) with a warning.
     */
    public static final String ENV_ALLOWED_HOSTS = "AGENT_SHELL_SANDBOX_ALLOWED_HOSTS";

    public enum Mode {
        REQUIRED, AUTO, OFF;

        static Mode fromEnvironment() {
            String value = System.getProperty("matrix.shell.sandbox", EnvFileLoader.getenv(ENV_MODE));
            if (value == null || value.isBlank()) {
                value = EnvFileLoader.getenv(LEGACY_ENV_MODE);
            }
            if (value == null || value.isBlank()) {
                // The container already is the sandbox, so don't nest another one inside it.
                return isRunningInContainer() ? OFF : REQUIRED;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false", "disabled", "none" -> OFF;
                case "auto", "best-effort" -> AUTO;
                default -> REQUIRED;
            };
        }
    }

    public enum Backend {
        MACOS_SEATBELT, LINUX_BWRAP, NONE
    }

    /**
     * Thrown when a command must not run because no sandbox could be set up.
     */
    public static class SandboxUnavailableException extends RuntimeException {
        public SandboxUnavailableException(String message) {
            super(message);
        }
    }

    private static final Set<String> DEFAULT_ENV_ALLOWLIST = Set.of(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TZ",
            "TMPDIR", "JAVA_HOME");

    private static final List<String> DEFAULT_DENY_ACCESS_RELATIVE_TO_HOME = List.of(
            ".ssh", ".aws", ".gnupg", ".kube", ".azure", ".config/gcloud", ".netrc");

    private static final List<String> DEFAULT_DENY_FILE_NAMES = List.of(
            "secrets.json", ".env", ".netrc", ".git-credentials", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    /** Directories that are not descended into when looking for denied file names on Linux. */
    private static final Set<String> SCAN_SKIP_DIRECTORIES = Set.of(".git", "node_modules");
    private static final int SCAN_MAX_DEPTH = 12;
    private static final int SCAN_MAX_ENTRIES = 200_000;

    private static final int LINUX_PROXY_PORT = 3128;

    private static final String SEATBELT_EXECUTABLE = "/usr/bin/sandbox-exec";

    private final Mode mode;
    private final Backend backend;
    private final Path workingDirectory;
    private final List<Path> writableDirectories;
    private final List<Path> denyAccessPaths;
    private final List<String> denyFileNames;
    private final List<Pattern> denyFileNamePatterns;
    private final List<String> denyFileNameSeatbeltRegexes;
    private final boolean allowNetwork;
    private final List<String> allowedHosts;
    private final HostAllowListProxy proxy;
    private final Set<String> envAllowlist;
    private final String bwrapExecutable;

    private ShellSandbox(Builder builder) {
        this.mode = builder.mode;
        this.workingDirectory = realPath(builder.workingDirectory != null ? builder.workingDirectory
                : Path.of(System.getProperty("user.dir")));
        this.allowNetwork = builder.allowNetwork;
        this.allowedHosts = builder.allowedHosts.stream().map(HostAllowListProxy::normalize)
                .filter(h -> !h.isEmpty()).distinct().toList();

        Set<String> env = new LinkedHashSet<>(DEFAULT_ENV_ALLOWLIST);
        env.addAll(builder.extraEnv);
        this.envAllowlist = Set.copyOf(env);

        Set<Path> deny = new LinkedHashSet<>();
        String homeProperty = System.getProperty("user.home");
        if (homeProperty != null && !homeProperty.isBlank()) {
            Path home = Path.of(homeProperty);
            DEFAULT_DENY_ACCESS_RELATIVE_TO_HOME.forEach(p -> deny.add(home.resolve(p)));
        }
        builder.denyAccessPaths.stream().map(this::resolveAgainstWorkingDirectory).forEach(deny::add);
        this.denyAccessPaths = deny.stream().map(ShellSandbox::realPath).filter(Files::exists).distinct().toList();

        Set<String> names = new LinkedHashSet<>(DEFAULT_DENY_FILE_NAMES);
        names.addAll(builder.denyFileNames);
        List<String> validNames = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        List<String> seatbeltRegexes = new ArrayList<>();
        for (String name : names) {
            if (!isValidFileNamePattern(name)) {
                log.warn("Shell sandbox: ignoring file name '{}', only letters, digits, '.', '_', '-', '*' and '?' are supported", name);
                continue;
            }
            validNames.add(name);
            patterns.add(Pattern.compile(globToJavaRegex(name), Pattern.CASE_INSENSITIVE));
            seatbeltRegexes.add("(^|/)" + globToSeatbeltRegex(name) + "(/|$)");
        }
        this.denyFileNames = List.copyOf(validNames);
        this.denyFileNamePatterns = List.copyOf(patterns);
        this.denyFileNameSeatbeltRegexes = List.copyOf(seatbeltRegexes);

        // Deny is strict: a denied path stays denied, a writable directory that is the denied path or lives inside it
        // is ignored (a denied path nested inside a writable directory is simply carved out of it).
        Set<Path> writableCandidates = new LinkedHashSet<>();
        writableCandidates.add(this.workingDirectory);
        builder.writableDirectories.stream().map(this::resolveAgainstWorkingDirectory).map(ShellSandbox::realPath)
                .filter(Files::isDirectory).forEach(writableCandidates::add);
        Set<Path> writable = new LinkedHashSet<>();
        for (Path candidate : writableCandidates) {
            Path blockedBy = denyAccessPaths.stream().filter(candidate::startsWith).findFirst().orElse(null);
            if (blockedBy != null) {
                log.warn("Shell sandbox: {} is not writable because it is inside the denied path {}", candidate, blockedBy);
            } else {
                writable.add(candidate);
            }
        }
        this.writableDirectories = List.copyOf(writable);

        String bwrap = null;
        Backend detected = Backend.NONE;
        if (mode != Mode.OFF) {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("mac") && Files.isExecutable(Path.of(SEATBELT_EXECUTABLE))
                    && probe(SEATBELT_EXECUTABLE, "-p", "(version 1)(allow default)", "/usr/bin/true")) {
                detected = Backend.MACOS_SEATBELT;
            } else if (os.contains("linux")) {
                bwrap = findOnPath("bwrap");
                if (bwrap != null && probe(bwrap, "--ro-bind", "/", "/", "--unshare-all", "--die-with-parent",
                        "/bin/sh", "-c", "true")) {
                    detected = Backend.LINUX_BWRAP;
                }
            }
        }
        this.backend = detected;
        this.bwrapExecutable = bwrap;

        HostAllowListProxy started = null;
        if (!allowedHosts.isEmpty() && allowNetwork && backend != Backend.NONE) {
            if (backend == Backend.MACOS_SEATBELT) {
                try {
                    started = new HostAllowListProxy(allowedHosts);
                } catch (IOException e) {
                    log.warn("Shell sandbox: could not start the host allow-list proxy, network is blocked: {}", e.getMessage());
                }
            } else if (bwrap != null && findOnPath("socat") != null) {
                try {
                    Path dir = Files.createTempDirectory("matrix-sbx");
                    Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                    started = new HostAllowListProxy(allowedHosts, dir.resolve("proxy.sock"));
                    dir.toFile().deleteOnExit();
                    started.socketPath().toFile().deleteOnExit();
                } catch (IOException | RuntimeException e) {
                    log.warn("Shell sandbox: could not start the host allow-list proxy, network is blocked: {}", e.getMessage());
                }
            } else {
                log.warn("{}Shell sandbox: the host allow-list needs 'socat' on Linux, network is blocked. Install socat.{}", ColorEnum.BOLD_HIGH_INTENSITY_RED, ColorEnum.RESET);
            }
        }
        this.proxy = started;
    }

    private boolean networkOpen() {
        return allowNetwork && allowedHosts.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mode mode() {
        return mode;
    }

    public Backend backend() {
        return backend;
    }

    /**
     * @return true if commands run inside an OS level sandbox.
     */
    public boolean isActive() {
        return backend != Backend.NONE;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public String describe() {
        return "mode=" + mode + ", backend=" + backend + ", network=" + (allowedHosts.isEmpty() ? String.valueOf(allowNetwork) : "allowed-hosts" + allowedHosts) + ", workingDirectory="
                + workingDirectory + ", writable=" + writableDirectories + ", denied=" + denyAccessPaths + ", deniedFileNames=" + denyFileNames
                + ", passEnv=" + envAllowlist;
    }

    /**
     * Builds the argument vector to start {@code command} through a shell, wrapped in the sandbox.
     *
     * @throws SandboxUnavailableException if the mode is {@link Mode#REQUIRED} and no backend is usable
     */
    public String[] wrap(String command) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String[] plainShell = os.contains("win") ? new String[]{"cmd.exe", "/c", command}
                : new String[]{"/bin/sh", "-c", command};

        return switch (backend) {
            case MACOS_SEATBELT -> seatbeltCommand(command);
            case LINUX_BWRAP -> bwrapCommand(command);
            case NONE -> {
                if (mode == Mode.REQUIRED) {
                    throw new SandboxUnavailableException(
                            "Shell sandbox is required but no usable sandbox backend was found on this system "
                                    + "(needs macOS sandbox-exec or Linux bubblewrap with user namespaces). "
                                    + "Install bubblewrap, run the container with the required privileges, or set "
                                    + "AGENT_SHELL_SANDBOX_MODE=auto|off to run commands without a sandbox.");
                }
                yield plainShell;
            }
        };
    }

    /**
     * Removes everything that is not allow-listed from the child's environment.
     */
    public void applyEnvironment(Map<String, String> environment) {
        if (!isActive()) {
            return;
        }
        environment.keySet().removeIf(key -> !envAllowlist.contains(key));
        if (proxy != null) {
            String url = "http://127.0.0.1:" + (proxy.socketPath() != null ? LINUX_PROXY_PORT : proxy.port());
            for (String key : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
                environment.put(key, url);
            }
            environment.remove("NO_PROXY");
            environment.remove("no_proxy");
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // macOS Seatbelt
    // ------------------------------------------------------------------------------------------------------------

    private String[] seatbeltCommand(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n(allow default)\n(deny file-write*)\n");

        // Paths are passed as parameters (-D) so they never have to be escaped inside the profile.
        profile.append("(allow file-write*\n");
        for (int i = 0; i < writableDirectories.size(); i++) {
            args.add("-D");
            args.add("W" + i + "=" + writableDirectories.get(i));
            profile.append("  (subpath (param \"W").append(i).append("\"))\n");
        }
        profile.append("  (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\")")
                .append(" (literal \"/dev/dtracehelper\") (regex #\"^/dev/ttys[0-9]+$\"))\n");

        // Evaluated last, so these win over any allow above.
        for (int i = 0; i < denyAccessPaths.size(); i++) {
            args.add("-D");
            args.add("D" + i + "=" + denyAccessPaths.get(i));
            profile.append("(deny file-read* file-write* (subpath (param \"D").append(i).append("\")))\n");
        }
        // Denied file names, wherever they are. The patterns only contain [a-zA-Z0-9._-*?] translated to
        // bracket expressions, so nothing can break out of the regex literal.
        for (String regex : denyFileNameSeatbeltRegexes) {
            profile.append("(deny file-read* file-write* (regex #\"").append(regex).append("\"))\n");
        }
        // Public CA bundles live in system locations and match patterns like "*.pem"; tools (aws, pip, curl, ...)
        // need to read them, so they are re-allowed. Evaluated after the file name denies, limited to system paths.
        if (!denyFileNameSeatbeltRegexes.isEmpty()) {
            profile.append("(allow file-read* (regex #\"^(/opt|/usr|/etc|/private/etc|/System|/Library)/.*")
                    .append("(cacert|cert|ca-bundle|ca-certificates|tls-ca-bundle)[.](pem|crt)$\"))\n");
        }
        if (!networkOpen()) {
            profile.append("(deny network*)\n");
            if (proxy != null) {
                profile.append("(allow network-outbound (remote ip \"localhost:").append(proxy.port()).append("\"))\n");
            }
        }

        List<String> result = new ArrayList<>();
        result.add(SEATBELT_EXECUTABLE);
        result.addAll(args);
        result.add("-p");
        result.add(profile.toString());
        result.add("/bin/sh");
        result.add("-c");
        result.add(command);
        return result.toArray(new String[0]);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Linux bubblewrap
    // ------------------------------------------------------------------------------------------------------------

    private String[] bwrapCommand(String command) {
        List<String> result = new ArrayList<>();
        result.add(bwrapExecutable);
        result.addAll(List.of("--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc"));
        result.addAll(List.of("--unshare-all", "--die-with-parent", "--new-session"));
        if (networkOpen()) {
            result.add("--share-net");
        }
        // Private /tmp unless /tmp itself was listed as writable.
        if (writableDirectories.stream().noneMatch(w -> w.equals(Path.of("/tmp")) || w.startsWith("/tmp"))) {
            result.addAll(List.of("--tmpfs", "/tmp"));
        }
        if (proxy != null) {
            Path dir = proxy.socketPath().getParent();
            result.addAll(List.of("--ro-bind", dir.toString(), dir.toString()));
        }
        for (Path writable : writableDirectories) {
            result.addAll(List.of("--bind", writable.toString(), writable.toString()));
        }
        for (Path denied : denyAccessPaths) {
            if (Files.isDirectory(denied)) {
                result.addAll(List.of("--tmpfs", denied.toString()));
            } else {
                result.addAll(List.of("--ro-bind", "/dev/null", denied.toString()));
            }
        }
        // bubblewrap has no patterns: mask matching files below the directories the command works in. This is
        // evaluated for every command, so files created since the last command are covered as well.
        for (Path masked : findDeniedFileNames()) {
            if (Files.isDirectory(masked, LinkOption.NOFOLLOW_LINKS)) {
                result.addAll(List.of("--tmpfs", masked.toString()));
            } else {
                result.addAll(List.of("--ro-bind", "/dev/null", masked.toString()));
            }
        }
        result.addAll(List.of("--chdir", workingDirectory.toString()));
        if (proxy != null) {
            // The network namespace only has a loopback interface: bridge a local port to the proxy's Unix socket.
            String bridge = "socat TCP-LISTEN:" + LINUX_PROXY_PORT + ",bind=127.0.0.1,fork,reuseaddr UNIX-CONNECT:"
                    + proxy.socketPath() + " >/dev/null 2>&1 & sleep 0.2; exec /bin/sh -c \"$1\"";
            result.addAll(List.of("--", "/bin/sh", "-c", bridge, "sh", command));
        } else {
            result.addAll(List.of("--", "/bin/sh", "-c", command));
        }
        return result.toArray(new String[0]);
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Best-effort detection of Docker / Podman / Kubernetes containers.
     */
    public static boolean isRunningInContainer() {
        if (Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))
                || System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return true;
        }
        try {
            Path cgroup = Path.of("/proc/1/cgroup");
            if (Files.isReadable(cgroup)) {
                String content = Files.readString(cgroup);
                return content.contains("docker") || content.contains("kubepods") || content.contains("containerd")
                        || content.contains("libpod");
            }
        } catch (IOException | RuntimeException e) {
            // ignore, assume not in a container
        }
        return false;
    }

    private static boolean isValidFileNamePattern(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '*' || c == '?';
            if (!ok) return false;
        }
        return true;
    }

    private static String globToJavaRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    /** Case-insensitive on purpose: macOS file systems usually are, so ".ENV" must not bypass ".env". */
    private static String globToSeatbeltRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (Character.isLetter(c)) {
                regex.append('[').append(Character.toLowerCase(c)).append(Character.toUpperCase(c)).append(']');
            } else if (c == '.') {
                regex.append("[.]");
            } else {
                regex.append(c); // digits, '_' and '-' are literal outside of brackets
            }
        }
        return regex.toString();
    }

    /**
     * Finds files and directories below the working/writable directories whose name matches a denied file name.
     */
    private List<Path> findDeniedFileNames() {
        if (denyFileNamePatterns.isEmpty()) {
            return List.of();
        }
        // Only scan top level roots, writable directories nested in another one are covered by their parent.
        List<Path> roots = writableDirectories.stream()
                .filter(w -> writableDirectories.stream().noneMatch(o -> !o.equals(w) && w.startsWith(o))).toList();
        List<Path> found = new ArrayList<>();
        int[] visited = {0};
        for (Path root : roots) {
            if (root.equals(Path.of("/tmp")) || root.getParent() == null) {
                continue; // never walk the whole file system or the shared temp directory
            }
            try {
                Files.walkFileTree(root, Set.of(), SCAN_MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (++visited[0] > SCAN_MAX_ENTRIES) return FileVisitResult.TERMINATE;
                        if (!dir.equals(root)) {
                            if (matchesDeniedName(dir)) {
                                found.add(dir);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (SCAN_SKIP_DIRECTORIES.contains(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (++visited[0] > SCAN_MAX_ENTRIES) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile() && matchesDeniedName(file)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException e) {
                log.warn("Shell sandbox: could not scan {} for denied file names: {}", root, e.getMessage());
            }
        }
        return found;
    }

    private boolean matchesDeniedName(Path path) {
        String name = path.getFileName().toString();
        return denyFileNamePatterns.stream().anyMatch(p -> p.matcher(name).matches());
    }

    /** Splits a "," separated value (like AGENT_PATHS_AGENTS) into trimmed, non-empty entries. */
    private static List<String> envList(String name) {
        String value = EnvFileLoader.getenv(name);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<Path> envPathList(String name) {
        List<Path> paths = new ArrayList<>();
        for (String value : envList(name)) {
            try {
                paths.add(Path.of(expandHome(value)));
            } catch (InvalidPathException e) {
                log.warn("Ignoring invalid path '{}' in {}", value, name);
            }
        }
        return paths;
    }

    private static boolean envBoolean(String name, boolean defaultValue) {
        String value = EnvFileLoader.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> defaultValue;
        };
    }

    /** Values from the env file are already expanded by EnvFileLoader, plain OS environment values are not. */
    private static String expandHome(String value) {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) return value;
        if (value.equals("~")) return home;
        if (value.startsWith("~/")) return home + value.substring(1);
        return value;
    }

    private Path resolveAgainstWorkingDirectory(Path path) {
        return path.isAbsolute() ? path : workingDirectory.resolve(path);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, executable);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static boolean probe(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static class Builder {
        // Defaults come from the env file / environment (see the ENV_* constants); the setters below add to them
        // (lists) or override them (mode, network).
        private Mode mode = Mode.fromEnvironment();
        private Path workingDirectory;
        private final List<Path> writableDirectories = new ArrayList<>(envPathList(ENV_WRITABLE_PATHS));
        private final List<Path> denyAccessPaths = new ArrayList<>(envPathList(ENV_DENY_PATHS));
        private final List<String> denyFileNames = new ArrayList<>(envList(ENV_DENY_FILE_NAMES));
        private final Set<String> extraEnv = new LinkedHashSet<>(envList(ENV_PASS_ENV));
        private boolean allowNetwork = envBoolean(ENV_ALLOW_NETWORK, true);
        private final List<String> allowedHosts = new ArrayList<>(envList(ENV_ALLOWED_HOSTS));

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Directory the command starts in. It is always writable.
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Additional directories (recursively) the command may write to.
         */
        public Builder writableDirectories(List<Path> directories) {
            this.writableDirectories.addAll(directories);
            return this;
        }

        /**
         * Additional files/directories the command may neither read nor write.
         */
        public Builder denyAccess(Path... paths) {
            this.denyAccessPaths.addAll(List.of(paths));
            return this;
        }

        /**
         * Additional file names (wildcards {@code *} and {@code ?} allowed) that are denied wherever they are.
         */
        public Builder denyFileNames(String... names) {
            this.denyFileNames.addAll(List.of(names));
            return this;
        }

        /**
         * Additional environment variables that are passed through to the command.
         */
        public Builder passEnvironment(String... names) {
            this.extraEnv.addAll(List.of(names));
            return this;
        }

        public Builder allowNetwork(boolean allowNetwork) {
            this.allowNetwork = allowNetwork;
            return this;
        }

        /**
         * Additional hosts (or URLs, {@code *.example.com} for sub domains) the command may reach. Setting any
         * restricts the network to these hosts.
         */
        public Builder allowedHosts(String... hosts) {
            this.allowedHosts.addAll(List.of(hosts));
            return this;
        }

        public ShellSandbox build() {
            return new ShellSandbox(this);
        }
    }
}
