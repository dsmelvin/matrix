package guru.kumo.operator.tool;

import guru.kumo.operator.util.EnvFileLoader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code git} against local repositories, without a shell and without the OS sandbox of {@link ShellTools},
 * but with its own access control:
 * <ul>
 *   <li>Only an allow-list of git sub commands runs, no shell is involved and global options cannot be injected.</li>
 *   <li>Only repositories whose work tree lives inside the allowed directories are touched.</li>
 *   <li>Repository config can not run programs: hooks, fsmonitor, credential helpers, editors and pagers are
 *       neutralised, and option such as {@code --upload-pack}, {@code --exec} or {@code --output} are rejected.</li>
 *   <li><b>Remote access</b> ({@code fetch, pull, push, ls-remote}) is limited to configured remotes whose
 *       host is in {@link #ENV_ALLOWED_REMOTE_HOSTS}. Pushing is off unless {@link #ENV_ALLOW_PUSH} is true, and never
 *       forced, deleting, or to a protected branch.</li>
 *   <li>Credentials are not inherited from the user's environment (no ssh-agent, no keychain, no {@code ~/.ssh/config});
 *       only the optional key / token below are used.</li>
 * </ul>
 * Settings come from the env file or the OS environment, lists are "," separated:
 * <pre>
 * AGENT_GIT_ALLOWED_REMOTE_HOSTS=bitbucket.rks-cloud.com,github.com
 * AGENT_GIT_ALLOW_PUSH=false
 * AGENT_GIT_PROTECTED_BRANCHES=main,master,release/*
 * AGENT_GIT_SSH_KEY=~/.ssh/matrix_deploy_key
 * AGENT_GIT_SSH_KNOWN_HOSTS=~/.ssh/known_hosts
 * AGENT_GIT_HTTPS_TOKEN=
 * </pre>
 * The configuration of a repository is trusted to be what git itself needs (remotes, user, diff drivers ...), so only
 * point the tool at repositories you would run git in yourself.
 */
@Slf4j
public class GitTool {
    public static final String TOOL_NAME = "Git";

    public static final String ENV_ALLOWED_REMOTE_HOSTS = "AGENT_GIT_ALLOWED_REMOTE_HOSTS";
    public static final String ENV_ALLOW_PUSH = "AGENT_GIT_ALLOW_PUSH";
    public static final String ENV_PROTECTED_BRANCHES = "AGENT_GIT_PROTECTED_BRANCHES";
    public static final String ENV_SSH_KEY = "AGENT_GIT_SSH_KEY";
    public static final String ENV_SSH_KNOWN_HOSTS = "AGENT_GIT_SSH_KNOWN_HOSTS";
    public static final String ENV_HTTPS_TOKEN = "AGENT_GIT_HTTPS_TOKEN";

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int MAX_CAPTURE_BYTES = 4 * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;

    private static final Set<String> LOCAL_COMMANDS = Set.of(
            "status", "diff", "log", "show", "blame", "rev-parse", "rev-list", "ls-files", "ls-tree", "cat-file",
            "describe", "shortlog", "grep", "show-ref", "diff-tree", "branch", "tag", "add", "rm", "mv", "commit",
            "restore", "switch", "checkout", "reset", "stash", "merge", "rebase", "cherry-pick", "revert");
    private static final Set<String> NETWORK_COMMANDS = Set.of("fetch", "pull", "push", "ls-remote");
    private static final Set<String> ALLOWED_REMOTE_SUBCOMMANDS = Set.of("add", "set-url", "remove", "rm", "rename",
            "get-url", "-v", "--verbose");

    /** Options that run programs, write outside the repository or read outside of it. Matched as prefixes. */
    private static final List<String> DENIED_OPTIONS = List.of(
            "--upload-pack", "--receive-pack", "--exec", "--git-dir", "--work-tree", "--output", "--no-index",
            "--open-files-in-pager", "--template", "--config", "--recurse-submodules", "--ext-diff", "--textconv");

    /** Options of fetch/pull/push/ls-remote that take a separate value (so the value is not mistaken for the remote). */
    private static final Set<String> VALUE_OPTIONS = Set.of("--depth", "--deepen", "--shallow-since",
            "--shallow-exclude", "--jobs", "-j", "--filter", "--negotiation-tip", "--refmap", "--server-option", "-o",
            "--push-option", "--repo", "--strategy", "-s", "--strategy-option", "-X", "--upload-pack");

    private static final Pattern SCP_LIKE = Pattern.compile("^(?:[^@/\\s]+@)?([^:/\\s]+):(?!//).*$");
    private static final Pattern SHORT_CLUSTER = Pattern.compile("^-[A-Za-z]+$");

    private final List<Path> allowedDirectories;
    private final Path workingDirectory;
    private final List<String> allowedRemoteHosts;
    private final boolean allowPush;
    private final List<String> protectedBranches;
    private final Path sshKey;
    private final Path sshKnownHosts;
    private final String httpsToken;

    private GitTool(Builder builder) {
        this.workingDirectory = realPath(builder.workingDirectory != null ? builder.workingDirectory
                : Path.of(System.getProperty("user.dir")));
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(workingDirectory);
        builder.allowedDirectories.stream().map(GitTool::realPath).filter(Files::isDirectory).forEach(dirs::add);
        this.allowedDirectories = List.copyOf(dirs);

        this.allowedRemoteHosts = envList(ENV_ALLOWED_REMOTE_HOSTS).stream().map(HostAllowListProxy::normalize)
                .filter(h -> !h.isEmpty()).toList();
        this.allowPush = envBoolean(ENV_ALLOW_PUSH, false);
        List<String> branches = envList(ENV_PROTECTED_BRANCHES);
        this.protectedBranches = branches.isEmpty() ? List.of("main", "master") : branches;
        this.sshKey = envPath(ENV_SSH_KEY);
        this.sshKnownHosts = envPath(ENV_SSH_KNOWN_HOSTS);
        String token = EnvFileLoader.getenv(ENV_HTTPS_TOKEN);
        this.httpsToken = token == null || token.isBlank() ? null : token.strip();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String describe() {
        return "workingDirectory=" + workingDirectory + ", allowedDirectories=" + allowedDirectories
                + ", remoteHosts=" + allowedRemoteHosts + ", push=" + allowPush + ", protectedBranches="
                + protectedBranches + ", sshKey=" + (sshKey != null) + ", httpsToken=" + (httpsToken != null);
    }

    // @formatter:off
    @Tool(name = TOOL_NAME, description = """
		Run a git command in a local repository. Prefer this over Bash for git operations.
		Pass the arguments WITHOUT the leading "git", one element per argument, e.g.
		["status", "--short"], ["commit", "-m", "Fix NPE in parser"], ["log", "--oneline", "-20"].
		No shell is used, so do not quote, pipe or chain commands.

		Supported: status, diff, log, show, blame, rev-parse, rev-list, ls-files, ls-tree, cat-file, describe,
		shortlog, grep, show-ref, diff-tree, branch, tag, add, rm, mv, commit, restore, switch, checkout, reset, stash,
		merge, rebase, cherry-pick, revert, remote (add, set-url, remove, rename, get-url), fetch, pull, push, ls-remote.
		Not supported: clone, init, config, submodule, worktree, archive, bundle, format-patch, clean and interactive modes (-i, -p).

		Remote access:
		- fetch, pull, push and ls-remote only work with a configured remote NAME (e.g. origin) whose host is allowed by
		  the user. Raw URLs are rejected, add the remote first with ["remote", "add", "name", "url"].
		- push is only possible if the user enabled it. Force pushes, branch deletions, --all/--mirror and pushes to
		  protected branches are always rejected. DO NOT push unless the user explicitly asks you to.
		- No other credentials than the ones configured for this tool are available.

		Other notes:
		- Optional repository: directory of the repository, defaults to the working directory.
		- Optional timeout in milliseconds (max 600000). Default: 120000.
		- Output is truncated at 30000 characters.
		- Never pass -i to rebase/add, and do not skip hooks or signing unless the user asked for it.
		- If there are no changes to commit, do not create an empty commit.
		""")
    // @formatter:on
    public String git(
            @ToolParam(description = "git arguments without the leading 'git', one element per argument") List<String> args,
            @ToolParam(description = "Optional repository directory, defaults to the working directory", required = false) String repository,
            @ToolParam(description = "Optional timeout in milliseconds (max 600000)", required = false) Long timeout) {
        if (args == null || args.isEmpty() || args.stream().anyMatch(a -> a == null)) {
            return "Error: args must contain the git sub command and its arguments, e.g. [\"status\"].";
        }
        try {
            Path repo = resolveRepository(repository);
            List<String> arguments = List.copyOf(args);
            validate(arguments, repo);

            long timeoutMs = timeout != null ? Math.max(1, Math.min(timeout, MAX_TIMEOUT_MS)) : DEFAULT_TIMEOUT_MS;
            Result result = run(repo, arguments, timeoutMs);
            return format(result);
        } catch (GitDeniedException e) {
            return "Error: not allowed. " + e.getMessage();
        } catch (IOException e) {
            return "Error executing git: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Git execution interrupted";
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------------------------------------------------

    private static final class GitDeniedException extends RuntimeException {
        GitDeniedException(String message) {
            super(message);
        }
    }

    private Path resolveRepository(String repository) throws IOException, InterruptedException {
        Path dir = repository == null || repository.isBlank() ? workingDirectory
                : (Path.of(repository).isAbsolute() ? Path.of(repository) : workingDirectory.resolve(repository));
        dir = realPath(dir);
        if (!Files.isDirectory(dir)) {
            throw new GitDeniedException("Repository directory does not exist: " + dir);
        }
        if (!isInsideAllowedDirectory(dir)) {
            throw new GitDeniedException(dir + " is outside of the allowed directories " + allowedDirectories);
        }
        Result top = run(dir, List.of("rev-parse", "--show-toplevel"), 15_000);
        if (top.exitCode != 0 || top.stdout.isBlank()) {
            throw new GitDeniedException(dir + " is not inside a git repository.");
        }
        Path toplevel = realPath(Path.of(top.stdout.strip()));
        if (!isInsideAllowedDirectory(toplevel)) {
            throw new GitDeniedException("The repository " + toplevel + " is outside of the allowed directories "
                    + allowedDirectories);
        }
        return dir;
    }

    private boolean isInsideAllowedDirectory(Path path) {
        return allowedDirectories.stream().anyMatch(path::startsWith);
    }

    private void validate(List<String> args, Path repo) throws IOException, InterruptedException {
        String sub = args.get(0);
        boolean network = NETWORK_COMMANDS.contains(sub);
        if (!LOCAL_COMMANDS.contains(sub) && !network && !sub.equals("remote")) {
            throw new GitDeniedException("The git sub command '" + sub + "' is not supported by this tool.");
        }

        boolean optionsEnded = false;
        for (String arg : args.subList(1, args.size())) {
            if (arg.equals("--")) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded || !arg.startsWith("-")) {
                continue;
            }
            if (isDeniedOption(arg)) {
                throw new GitDeniedException("The option " + arg + " is not allowed.");
            }
            if (arg.equals("--interactive") || arg.equals("--patch")) {
                throw new GitDeniedException("Interactive options are not supported.");
            }
            if (sub.equals("grep") && arg.startsWith("-O")) {
                throw new GitDeniedException("The option " + arg + " is not allowed.");
            }
        }

        if (sub.equals("remote")) {
            validateRemoteCommand(args);
        } else if (network) {
            validateNetworkCommand(sub, args, repo);
        }
    }

    /** git accepts unambiguous abbreviations of long options, so every prefix of a denied option is denied too. */
    private static boolean isDeniedOption(String arg) {
        if (!arg.startsWith("--")) return false;
        int eq = arg.indexOf('=');
        String name = eq >= 0 ? arg.substring(0, eq) : arg;
        return name.length() > 2 && DENIED_OPTIONS.stream().anyMatch(d -> d.startsWith(name));
    }

    private void validateRemoteCommand(List<String> args) {
        if (args.size() > 1) {
            String action = args.get(1);
            if (!ALLOWED_REMOTE_SUBCOMMANDS.contains(action)) {
                throw new GitDeniedException("'git remote " + action + "' is not supported, use add, set-url, remove, "
                        + "rename, get-url or -v.");
            }
            if (action.equals("add") || action.equals("set-url")) {
                List<String> positional = positionals(args.subList(2, args.size()), Set.of("-t", "-m", "--track", "--master"));
                // add <name> <url>, set-url [--push|--add|--delete] <name> <newurl> [<oldurl>]
                if (positional.size() >= 2) {
                    for (String url : positional.subList(1, positional.size())) {
                        requireAllowedHost(url, "remote");
                    }
                }
            }
        }
    }

    private void validateNetworkCommand(String sub, List<String> args, Path repo) throws IOException, InterruptedException {
        List<String> rest = args.subList(1, args.size());
        List<String> positional = positionals(rest, VALUE_OPTIONS);
        boolean push = sub.equals("push");
        boolean all = rest.contains("--all") && !push;

        if (allowedRemoteHosts.isEmpty()) {
            throw new GitDeniedException("Remote access is disabled, no hosts are listed in " + ENV_ALLOWED_REMOTE_HOSTS + ".");
        }
        if (push) {
            if (!allowPush) {
                throw new GitDeniedException("Pushing is disabled, set " + ENV_ALLOW_PUSH + "=true to enable it.");
            }
            validatePush(rest, positional, repo);
        }

        Set<String> remotes = new LinkedHashSet<>();
        List<String> configured = lines(run(repo, List.of("remote"), 15_000));
        if (all) {
            remotes.addAll(configured);
        } else if (positional.isEmpty()) {
            remotes.add(defaultRemote(repo, push));
        } else {
            String token = positional.get(0);
            if (configured.contains(token)) {
                remotes.add(token);
            } else if (sub.equals("fetch") || sub.equals("pull")) {
                String group = run(repo, List.of("config", "--get", "remotes." + token), 15_000).stdout.strip();
                if (group.isEmpty()) {
                    throw unknownRemote(token);
                }
                for (String member : group.split("\\s+")) {
                    if (!configured.contains(member)) throw unknownRemote(member);
                    remotes.add(member);
                }
            } else {
                throw unknownRemote(token);
            }
        }

        for (String remote : remotes) {
            List<String> urlArgs = new ArrayList<>(List.of("remote", "get-url"));
            if (push) urlArgs.add("--push");
            urlArgs.addAll(List.of("--all", remote));
            Result urls = run(repo, urlArgs, 15_000);
            if (urls.exitCode != 0 || urls.stdout.isBlank()) {
                throw new GitDeniedException("The remote '" + remote + "' has no URL.");
            }
            for (String url : lines(urls)) {
                requireAllowedHost(url, "remote '" + remote + "'");
            }
        }
    }

    private GitDeniedException unknownRemote(String token) {
        return new GitDeniedException("'" + token + "' is not a configured remote. Raw URLs and paths are not accepted, "
                + "add the remote first with [\"remote\", \"add\", \"<name>\", \"<url>\"].");
    }

    private void validatePush(List<String> rest, List<String> positional, Path repo) throws IOException, InterruptedException {
        boolean optionsEnded = false;
        for (String arg : rest) {
            if (arg.equals("--")) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded || !arg.startsWith("-")) continue;
            if (arg.startsWith("--force") || arg.equals("--delete") || arg.equals("--mirror") || arg.equals("--all")
                    || arg.equals("--prune")) {
                throw new GitDeniedException("The push option " + arg + " is not allowed (no force, delete or bulk pushes).");
            }
            if (!arg.startsWith("--") && SHORT_CLUSTER.matcher(arg).matches() && (arg.contains("f") || arg.contains("d"))) {
                throw new GitDeniedException("The push option " + arg + " is not allowed (no force or delete).");
            }
        }

        List<String> refspecs = positional.size() > 1 ? positional.subList(1, positional.size()) : List.of();
        List<String> destinations = new ArrayList<>();
        for (String refspec : refspecs) {
            if (refspec.startsWith("+")) {
                throw new GitDeniedException("Forced refspecs (" + refspec + ") are not allowed.");
            }
            if (refspec.startsWith(":")) {
                throw new GitDeniedException("Deleting remote refs (" + refspec + ") is not allowed.");
            }
            String destination = refspec.contains(":") ? refspec.substring(refspec.lastIndexOf(':') + 1) : refspec;
            if (destination.equals("HEAD") || destination.isEmpty()) {
                destination = currentBranch(repo);
            }
            destinations.add(destination);
        }
        if (refspecs.isEmpty()) {
            destinations.add(currentBranch(repo)); // push.default=simple pushes the current branch only
        }
        for (String destination : destinations) {
            String branch = destination.startsWith("refs/heads/") ? destination.substring("refs/heads/".length()) : destination;
            if (destination.startsWith("refs/") && !destination.startsWith("refs/heads/") && !destination.startsWith("refs/tags/")) {
                throw new GitDeniedException("Pushing to " + destination + " is not allowed.");
            }
            if (!destination.startsWith("refs/tags/") && isProtected(branch)) {
                throw new GitDeniedException("The branch '" + branch + "' is protected (" + ENV_PROTECTED_BRANCHES + "), push another branch.");
            }
        }
    }

    private boolean isProtected(String branch) {
        for (String pattern : protectedBranches) {
            if (pattern.endsWith("*") ? branch.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(branch)) {
                return true;
            }
        }
        return false;
    }

    private String currentBranch(Path repo) throws IOException, InterruptedException {
        Result r = run(repo, List.of("symbolic-ref", "--short", "-q", "HEAD"), 15_000);
        String branch = r.stdout.strip();
        if (r.exitCode != 0 || branch.isEmpty()) {
            throw new GitDeniedException("HEAD is detached, name the branch to push explicitly.");
        }
        return branch;
    }

    private String defaultRemote(Path repo, boolean push) throws IOException, InterruptedException {
        String branch = null;
        Result b = run(repo, List.of("symbolic-ref", "--short", "-q", "HEAD"), 15_000);
        if (b.exitCode == 0 && !b.stdout.isBlank()) branch = b.stdout.strip();
        List<String> keys = new ArrayList<>();
        if (branch != null && push) keys.add("branch." + branch + ".pushRemote");
        if (push) keys.add("remote.pushDefault");
        if (branch != null) keys.add("branch." + branch + ".remote");
        for (String key : keys) {
            String value = run(repo, List.of("config", "--get", key), 15_000).stdout.strip();
            if (!value.isEmpty()) return value;
        }
        return "origin";
    }

    private void requireAllowedHost(String url, String what) {
        String host = hostOf(url);
        if (host == null) {
            throw new GitDeniedException("The " + what + " URL '" + url + "' is not a https:// or ssh:// URL with a host.");
        }
        if (allowedRemoteHosts.stream().noneMatch(p -> p.equals("*") || new HostAllowListProxy.Matcher(p).matches(host))) {
            throw new GitDeniedException("The host '" + host + "' of the " + what + " is not in " + ENV_ALLOWED_REMOTE_HOSTS + ".");
        }
    }

    /** Host of an https:// or ssh:// URL or an scp-like "user@host:path"; null for anything else (paths, ext::, ...). */
    static String hostOf(String url) {
        String value = url.strip();
        if (value.contains("://")) {
            try {
                URI uri = URI.create(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                return (scheme.equals("https") || scheme.equals("ssh")) ? uri.getHost() : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (value.startsWith("/") || value.startsWith(".") || value.startsWith("-") || value.contains("::")) {
            return null;
        }
        Matcher m = SCP_LIKE.matcher(value);
        return m.matches() ? m.group(1) : null;
    }

    /** Arguments that are neither options nor the value of a known value option; stops at "--". */
    private static List<String> positionals(List<String> args, Set<String> valueOptions) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) {
                result.addAll(args.subList(i + 1, args.size()));
                break;
            }
            if (arg.startsWith("-")) {
                if (valueOptions.contains(arg)) i++;
                continue;
            }
            result.add(arg);
        }
        return result;
    }

    private static List<String> lines(Result result) {
        return Arrays.stream(result.stdout.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ------------------------------------------------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------------------------------------------------

    private record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    private Result run(Path repo, List<String> args, long timeoutMs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of(isWindows() ? "NUL" : "/dev/null").toFile()));
        configureEnvironment(builder.environment());
        Process process = builder.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = capture(process.getInputStream(), out);
        Thread errThread = capture(process.getErrorStream(), err);

        boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
        outThread.join(2000);
        errThread.join(2000);
        return new Result(completed ? process.exitValue() : -1, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8), !completed);
    }

    private static Thread capture(InputStream stream, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (stream) {
                int n;
                while ((n = stream.read(buffer)) >= 0) {
                    synchronized (sink) {
                        if (sink.size() < MAX_CAPTURE_BYTES) sink.write(buffer, 0, n);
                    }
                }
            } catch (IOException e) {
                // stream closed
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void configureEnvironment(Map<String, String> environment) {
        Map<String, String> original = new LinkedHashMap<>(environment);
        environment.clear();
        for (String key : List.of("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TZ",
                "SystemRoot", "USERPROFILE", "TEMP", "TMP", "APPDATA")) {
            if (original.containsKey(key)) environment.put(key, original.get(key));
        }
        environment.put("TERM", "dumb");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_EDITOR", "true");
        environment.put("GIT_SEQUENCE_EDITOR", "true");
        environment.put("GIT_MERGE_AUTOEDIT", "no");
        environment.put("GIT_PAGER", "cat");
        environment.put("GIT_ASKPASS", "echo");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_ALLOW_PROTOCOL", "https:ssh");
        environment.put("GIT_SSH_COMMAND", sshCommand());

        // Settings through the environment (not argv) so the token never shows up in the process list.
        List<String[]> config = new ArrayList<>(List.of(
                new String[]{"core.hooksPath", isWindows() ? "NUL" : "/dev/null"},
                new String[]{"core.fsmonitor", "false"},
                new String[]{"core.editor", "true"},
                new String[]{"core.askPass", "echo"},
                new String[]{"credential.helper", ""},
                new String[]{"protocol.ext.allow", "never"},
                new String[]{"push.default", "simple"},
                new String[]{"fetch.recurseSubmodules", "false"},
                new String[]{"submodule.recurse", "false"},
                new String[]{"gc.auto", "0"}));
        if (httpsToken != null) {
            for (String host : allowedRemoteHosts) {
                if (!host.contains("*")) {
                    config.add(new String[]{"http.https://" + host + "/.extraHeader", "Authorization: Bearer " + httpsToken});
                }
            }
        }
        environment.put("GIT_CONFIG_COUNT", String.valueOf(config.size()));
        for (int i = 0; i < config.size(); i++) {
            environment.put("GIT_CONFIG_KEY_" + i, config.get(i)[0]);
            environment.put("GIT_CONFIG_VALUE_" + i, config.get(i)[1]);
        }
    }

    /** ssh without agent, without ~/.ssh/config and prompts; only the configured key is offered. */
    private String sshCommand() {
        StringBuilder ssh = new StringBuilder("ssh -F /dev/null -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityAgent=none"
                + " -o StrictHostKeyChecking=yes -o ForwardAgent=no -o ClearAllForwardings=yes");
        if (sshKey != null) ssh.append(" -i ").append(shellQuote(sshKey.toString()));
        if (sshKnownHosts != null) ssh.append(" -o UserKnownHostsFile=").append(shellQuote(sshKnownHosts.toString()));
        return ssh.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String format(Result result) {
        StringBuilder text = new StringBuilder();
        if (result.timedOut) {
            return "Git command timed out";
        }
        if (!result.stdout.isEmpty()) text.append(result.stdout);
        if (!result.stderr.isEmpty()) {
            if (text.length() > 0 && !text.toString().endsWith("\n")) text.append("\n");
            text.append("STDERR:\n").append(result.stderr);
        }
        if (result.exitCode != 0) {
            if (text.length() > 0 && !text.toString().endsWith("\n")) text.append("\n");
            text.append("Exit code: ").append(result.exitCode);
        }
        if (text.length() == 0) return "(no output)";
        String output = text.toString();
        return output.length() > MAX_OUTPUT_CHARS ? output.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)" : output;
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static List<String> envList(String name) {
        String value = EnvFileLoader.getenv(name);
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean envBoolean(String name, boolean defaultValue) {
        String value = EnvFileLoader.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> defaultValue;
        };
    }

    private static Path envPath(String name) {
        String value = EnvFileLoader.getenv(name);
        if (value == null || value.isBlank()) return null;
        String home = System.getProperty("user.home");
        String expanded = value.strip();
        if (home != null && (expanded.equals("~") || expanded.startsWith("~/"))) {
            expanded = home + expanded.substring(1);
        }
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    public static class Builder {
        private Path workingDirectory;
        private final List<Path> allowedDirectories = new ArrayList<>();

        /** Default repository, and always an allowed directory. */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory != null ? Path.of(workingDirectory) : null;
            return this;
        }

        /** Repositories whose work tree is inside one of these directories can be used. */
        public Builder allowedDirectories(Collection<Path> directories) {
            this.allowedDirectories.addAll(directories);
            return this;
        }

        public GitTool build() {
            GitTool tool = new GitTool(this);
            log.info("Git tool: {}", tool.describe());
            return tool;
        }
    }
}
