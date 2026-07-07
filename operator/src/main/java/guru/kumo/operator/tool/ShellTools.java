package guru.kumo.operator.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ShellTools {
    public static final String TOOL_NAME = "Bash";
    private static final Map<String, BackgroundProcess> backgroundProcesses = new ConcurrentHashMap<>();

    /**
     * Destroys a process AND every descendant it spawned (e.g. a backgrounded
     * grandchild started with "cmd &" that inherited the shell's stdout/stderr
     * pipes). Killing only the direct child leaves those pipes open, which is
     * what causes the stdout/stderr reader threads to block on read() forever.
     */
    private static void destroyProcessTree(Process process) {
        // Kill descendants first so they can't re-parent or keep pipes open
        // after the shell itself is gone.
        process.descendants().forEach(ph -> {
            ph.destroy();
        });
        process.destroy();

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ph -> ph.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ph -> ph.destroyForcibly());
            process.destroyForcibly();
        }
    }

    private static class BackgroundProcess {

        final Process process;

        final StringBuilder stdout;

        final StringBuilder stderr;

        final Thread stdoutReader;

        final Thread stderrReader;

        int lastStdoutPosition = 0;

        int lastStderrPosition = 0;

        BackgroundProcess(Process process) {
            this.process = process;
            this.stdout = new StringBuilder();
            this.stderr = new StringBuilder();

            // Start thread to read stdout
            this.stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stdout) {
                            stdout.append(line).append("\n");
                        }
                    }
                } catch (IOException e) {
                    // Process terminated or stream closed
                }
            });
            this.stdoutReader.setDaemon(true);
            this.stdoutReader.start();

            // Start thread to read stderr
            this.stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderr) {
                            stderr.append(line).append("\n");
                        }
                    }
                } catch (IOException e) {
                    // Process terminated or stream closed
                }
            });
            this.stderrReader.setDaemon(true);
            this.stderrReader.start();
        }

        String getNewOutput(String filter) {
            StringBuilder result = new StringBuilder();

            synchronized (stdout) {
                String newStdout = stdout.substring(lastStdoutPosition);
                if (filter != null && !filter.isEmpty()) {
                    Pattern pattern = Pattern.compile(filter);
                    newStdout = filterOutput(newStdout, pattern);
                }
                if (!newStdout.isEmpty()) {
                    result.append("STDOUT:\n").append(newStdout);
                }
                lastStdoutPosition = stdout.length();
            }

            synchronized (stderr) {
                String newStderr = stderr.substring(lastStderrPosition);
                if (filter != null && !filter.isEmpty()) {
                    Pattern pattern = Pattern.compile(filter);
                    newStderr = filterOutput(newStderr, pattern);
                }
                if (!newStderr.isEmpty()) {
                    if (result.length() > 0)
                        result.append("\n");
                    result.append("STDERR:\n").append(newStderr);
                }
                lastStderrPosition = stderr.length();
            }

            return result.toString();
        }

        private String filterOutput(String output, Pattern pattern) {
            String[] lines = output.split("\n");
            StringBuilder filtered = new StringBuilder();
            for (String line : lines) {
                if (pattern.matcher(line).find()) {
                    filtered.append(line).append("\n");
                }
            }
            return filtered.toString();
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void destroy() {
            destroyProcessTree(process);
        }

        int getExitCode() {
            return process.exitValue();
        }

    }

    // @formatter:off
    @Tool(name = TOOL_NAME, description = """
		Execute a bash command for terminal operations like npm, docker, make, mvn, python.
		DO NOT use for file operations — use specialized tools instead:
		- File search: Use Glob (NOT find or ls)
		- Content search: Use Grep (NOT grep or rg)
		- Read files: Use Read (NOT cat/head/tail)
		- Edit files: Use Edit (NOT sed/awk)
		- Write files: Use Write (NOT echo >/cat <<EOF)

		Usage notes:
		- The command argument is required.
		- Optional timeout in milliseconds (max 600000ms / 10 minutes). Default: 120000ms (2 minutes).
		- Output truncated at 30000 characters.
		- Use run_in_background for long-running commands.
		- Quote file paths with spaces in double quotes.
		- Chain dependent commands with &&. Use ; if earlier failures are acceptable.
		- Prefer absolute paths over cd.

		Important notes:
		- NEVER run additional commands to read or explore code, besides git bash commands
		- NEVER use the TodoWrite or Task tools
		- DO NOT push to the remote repository unless the user explicitly asks you to do so
		- IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
		- If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
		- In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:
		<example>
		git commit -m "$(cat <<'EOF'
		Commit message here.

		EOF
		)"
		</example>

		# Creating pull requests
		Use the gh command via the Bash tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.

		IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:

		1. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following bash commands in parallel using the Bash tool, in order to understand the current state of the branch since it diverged from the main branch:
		- Run a git status command to see all untracked files
		- Run a git diff command to see both staged and unstaged changes that will be committed
		- Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
		- Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
		2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request summary
		3. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following commands in parallel:
		- Create new branch if needed
		- Push to remote with -u flag if needed
		- Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
		<example>
		gh pr create --title "the pr title" --body "$(cat <<'EOF'

		## Summary
		<1-3 bullet points>

		## Test plan
		[Bulleted markdown checklist of TODOs for testing the pull request...]

		EOF
		)"
		</example>

		Important:
		- DO NOT use the TodoWrite or Task tools
		- Return the PR URL when you're done, so the user can see it

		# Other common operations
		- View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
		""")
    // @formatter:on
    public String bash(
            @ToolParam(description = "The command to execute") String command,
            @ToolParam(description = "Optional timeout in milliseconds (max 600000)", required = false) Long timeout,
            @ToolParam(description = "Set to true to run this command in the background. Use BashOutput to read the output later.", required = false) Boolean runInBackground,
            @ToolParam(description = """
                    Clear, concise description of what this command does in 5-10 words, in active voice. Examples:
                    Input: ls
                    Output: List files in current directory
                    
                    Input: git status
                    Output: Show working tree status
                    
                    Input: npm install
                    Output: Install package dependencies
                    
                    Input: mkdir foo
                    Output: Create directory 'foo'
                    """, required = false) String description) {

        // Generate unique shell ID for all executions
        String shellId = "shell_" + System.currentTimeMillis();

        try {
            // Determine the shell to use based on OS
            String[] shellCommand;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                shellCommand = new String[]{"cmd.exe", "/c", command};
            } else {
                shellCommand = new String[]{"/bin/sh", "-c", command};
            }

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
            processBuilder.redirectErrorStream(false);

            // Set working directory if available in tool context
            // processBuilder.directory(new File(workingDirectory));

            Process process = processBuilder.start();

            if (Boolean.TRUE.equals(runInBackground)) {
                // Run in background
                BackgroundProcess bgProcess = new BackgroundProcess(process);
                backgroundProcesses.put(shellId, bgProcess);

                return String.format(
                        "bash_id: %s\n\nBackground shell started with ID: %s\nUse BashOutput tool with bash_id='%s' to retrieve output.",
                        shellId, shellId, shellId);
            } else {
                // Run synchronously with timeout
                long timeoutMs = timeout != null ? Math.min(timeout, 600000) : 120000;

                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();

                // Read stdout
                Thread stdoutThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stdout.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                });

                // Read stderr
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stderr.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                });

                // CRITICAL: these must be daemons. If the invoked command backgrounds
                // a grandchild that inherits stdout/stderr (`server &`, `nohup ... &`),
                // the pipe never reaches EOF and readLine() blocks forever. Without
                // setDaemon(true) that leaked thread keeps the whole JVM alive at exit.
                stdoutThread.setDaemon(true);
                stderrThread.setDaemon(true);

                stdoutThread.start();
                stderrThread.start();

                boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                if (!completed) {
                    destroyProcessTree(process);
                    return String.format("bash_id: %s\n\nCommand timed out after %dms", shellId, timeoutMs);
                }

                // The shell itself has exited, but reader threads can still be
                // blocked if a backgrounded grandchild kept the pipe open. Kill
                // any surviving descendants so the readers actually hit EOF,
                // then give the join a bounded wait regardless.
                destroyProcessTree(process);

                stdoutThread.join(2000);
                stderrThread.join(2000);

                int exitCode = process.exitValue();
                StringBuilder result = new StringBuilder();

                // Add bash_id at the beginning
                result.append("bash_id: ").append(shellId).append("\n\n");

                if (stdout.length() > 0) {
                    result.append(stdout.toString());
                }

                if (stderr.length() > 0) {
                    if (result.length() > result.indexOf("\n\n") + 2)
                        result.append("\n");
                    result.append("STDERR:\n").append(stderr.toString());
                }

                if (exitCode != 0) {
                    if (result.length() > result.indexOf("\n\n") + 2)
                        result.append("\n");
                    result.append("Exit code: ").append(exitCode);
                }

                // Truncate if too long
                String output = result.toString();
                if (output.length() > 30000) {
                    // Keep the bash_id header
                    String header = output.substring(0, output.indexOf("\n\n") + 2);
                    String content = output.substring(output.indexOf("\n\n") + 2);
                    output = header + content.substring(0, Math.min(content.length(), 30000 - header.length()))
                            + "\n... (output truncated)";
                }

                return output;
            }

        } catch (IOException e) {
            return "Error executing command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Command execution interrupted: " + e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = "BashOutput", description = """
		- Retrieves output from a running or completed background bash shell
		- Takes a shell_id parameter identifying the shell
		- Always returns only new output since the last check
		- Returns stdout and stderr output along with shell status
		- Supports optional regex filtering to show only lines matching a pattern
		- Use this tool when you need to monitor or check the output of a long-running shell
		- Shell IDs can be found using the /bashes command
		""")
    // @formatter:on
    public String bashOutput(
            @ToolParam(description = "The ID of the background shell to retrieve output from") String bash_id,
            @ToolParam(description = "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read.", required = false) String filter) {

        BackgroundProcess bgProcess = backgroundProcesses.get(bash_id);

        if (bgProcess == null) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        String newOutput = bgProcess.getNewOutput(filter);

        StringBuilder result = new StringBuilder();
        result.append("Shell ID: ").append(bash_id).append("\n");
        result.append("Status: ").append(bgProcess.isAlive() ? "Running" : "Completed").append("\n");

        if (!bgProcess.isAlive()) {
            try {
                result.append("Exit code: ").append(bgProcess.getExitCode()).append("\n");
            } catch (IllegalThreadStateException e) {
                // Process not yet terminated
            }
        }

        if (!newOutput.isEmpty()) {
            result.append("\nNew output:\n").append(newOutput);
        } else {
            result.append("\nNo new output since last check.");
        }

        return result.toString();
    }

    // @formatter:off
    @Tool(name = "KillShell", description = """
		- Kills a running background bash shell by its ID
		- Takes a shell_id parameter identifying the shell to kill
		- Returns a success or failure status
		- Use this tool when you need to terminate a long-running shell
		- Shell IDs can be found using the /bashes command
		""")
    // @formatter:on
    public String killShell(@ToolParam(description = "The ID of the background shell to kill") String bash_id) {

        BackgroundProcess bgProcess = backgroundProcesses.get(bash_id);

        if (bgProcess == null) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        if (!bgProcess.isAlive()) {
            backgroundProcesses.remove(bash_id);
            return "Shell " + bash_id + " was already terminated. Removed from active shells.";
        }

        bgProcess.destroy();

        // Wait a bit to confirm termination
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        backgroundProcesses.remove(bash_id);

        return "Successfully killed shell: " + bash_id;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public ShellTools build() {
            return new ShellTools();
        }
    }
}