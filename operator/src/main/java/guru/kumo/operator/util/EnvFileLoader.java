package guru.kumo.operator.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java replacement of what {@code scripts/run.sh} used to do in bash: load an env file, export its variables and
 * switch to the requested working directory.
 * <p>
 * A JVM can neither change its own process environment nor its real working directory, so the equivalent is:
 * <ul>
 *   <li>the variables are registered as the highest priority Spring property source (placeholders such as
 *   {@code ${INFERENCE_BASE_URL}} resolve against them) and are readable through {@link #getenv(String)};</li>
 *   <li>they are also handed to child processes started by the shell tool ({@link #applyTo(Map)});</li>
 *   <li>the working directory is published through the {@code user.dir} system property and {@code PWD}.</li>
 * </ul>
 * This has to happen before the Spring context starts, because beans read these values while being created.
 * That is why {@link #prepare(String[])} is called from {@code OperatorApplication.main} and the options are only
 * declared (for parsing and {@code help}) on {@code OperatorShellCommand}.
 */
@Slf4j
public final class EnvFileLoader {
    public static final String ENV_FILE_LONG_OPTION = "env-file";
    public static final char ENV_FILE_SHORT_OPTION = 'e';
    public static final String WORKSPACE_LONG_OPTION = "workspace";
    public static final char WORKSPACE_SHORT_OPTION = 'w';

    private static final String DEFAULT_ENV_FILE_NAME = ".env";
    private static final String PROPERTY_SOURCE_NAME = "operatorEnvFile";

    private static volatile Map<String, String> variables = Collections.emptyMap();

    private EnvFileLoader() {
    }

    /**
     * Reads {@code --env-file/-e} and {@code --workspace/-w} from the launch arguments, loads the env file and
     * applies the workspace. Safe to call without any of the options.
     */
    public static void prepare(String[] args) {
        String envFileArg = optionValue(args, ENV_FILE_LONG_OPTION, ENV_FILE_SHORT_OPTION);
        String workspaceArg = optionValue(args, WORKSPACE_LONG_OPTION, WORKSPACE_SHORT_OPTION);

        // The directory the user launched from; run.sh left it in PWD (mvn's forked JVM inherits it).
        Path launchDirectory = Path.of(firstNonBlank(System.getenv("PWD"), System.getProperty("user.dir")))
                .toAbsolutePath().normalize();

        Path workspace = workspaceArg == null ? null : resolve(workspaceArg, launchDirectory);
        if (workspace != null) {
            if (Files.isDirectory(workspace)) {
                System.setProperty("user.dir", workspace.toString());
            } else {
                log.error("Workspace directory doesn't exist. {}", workspace);
                workspace = null;
            }
        }

        // An explicit env file wins; otherwise fall back to ".env" in the launch directory, like run.sh did.
        Path envFile = envFileArg != null
                ? resolve(envFileArg, launchDirectory)
                : launchDirectory.resolve(DEFAULT_ENV_FILE_NAME);

        Map<String, String> loaded = new LinkedHashMap<>();
        if (Files.isRegularFile(envFile)) {
            try {
                loaded.putAll(parse(Files.readAllLines(envFile, StandardCharsets.UTF_8)));
                log.info("Env file loaded successfully. {} ({} variables)", envFile, loaded.size());
            } catch (IOException e) {
                throw new IllegalStateException("Error reading env file " + envFile, e);
            }
        } else if (envFileArg != null) {
            log.error("Env file doesn't exist. {}", envFile);
        }
        if (workspace != null) {
            loaded.put("PWD", workspace.toString());
        }
        variables = Collections.unmodifiableMap(loaded);
    }

    /** Registers the loaded variables with the highest priority, i.e. above the real OS environment. */
    public static void registerWith(ConfigurableEnvironment environment) {
        if (variables.isEmpty()) return;
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, new LinkedHashMap<String, Object>(variables)));
    }

    /** Like {@link System#getenv(String)}, but values from the env file take precedence. */
    public static String getenv(String name) {
        String value = variables.get(name);
        return value != null ? value : System.getenv(name);
    }

    /** Adds the loaded variables to the environment of a child process. */
    public static void applyTo(Map<String, String> processEnvironment) {
        processEnvironment.putAll(variables);
    }

    public static Map<String, String> variables() {
        return variables;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------------------------------------------

    /** Parses shell style assignments: {@code KEY=value}, {@code export KEY="value"}, comments and blank lines. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).stripLeading();
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).strip();
            if (!isValidName(key)) continue;
            result.put(key, expandTilde(parseValue(line.substring(equals + 1).strip())));
        }
        return result;
    }

    private static String parseValue(String value) {
        if (value.isEmpty()) return value;
        char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            int end = value.indexOf(first, 1);
            return end < 0 ? value.substring(1) : value.substring(1, end);
        }
        int comment = value.indexOf(" #");
        return (comment < 0 ? value : value.substring(0, comment)).stripTrailing();
    }

    /**
     * Replaces {@code ~} with the user's home directory the way a shell does in assignments: at the start of the
     * value or right after a {@code :} (so path lists work), and only when followed by {@code /}, {@code :} or the
     * end of the value.
     */
    static String expandTilde(String value) {
        String home = System.getProperty("user.home");
        if (value.startsWith("$HOME"))  return value.replace("$HOME", home);
        if (home == null || home.isEmpty() || value.indexOf('~') < 0) return value;
        StringBuilder out = new StringBuilder(value.length() + home.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean wordStart = i == 0 || value.charAt(i - 1) == ':';
            boolean wordEnd = i + 1 == value.length() || value.charAt(i + 1) == '/' || value.charAt(i + 1) == ':';
            if (c == '~' && wordStart && wordEnd) {
                out.append(home);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isValidName(String key) {
        if (!(Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')) return false;
        for (int i = 1; i < key.length(); i++) {
            if (!(Character.isLetterOrDigit(key.charAt(i)) || key.charAt(i) == '_')) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Launch argument handling
    // ------------------------------------------------------------------------------------------------------------

    private static String optionValue(String[] args, String longName, char shortName) {
        if (args == null) return null;
        String longFlag = "--" + longName;
        String shortFlag = "-" + shortName;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals(longFlag) || arg.equals(shortFlag)) {
                return i + 1 < args.length ? args[i + 1] : null;
            }
            if (arg.startsWith(longFlag + "=")) {
                return arg.substring(longFlag.length() + 1);
            }
        }
        return null;
    }

    private static Path resolve(String pathName, Path base) {
        String expanded = expandTilde(pathName);
        return base.resolve(expanded).normalize();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
