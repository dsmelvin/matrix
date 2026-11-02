package guru.kumo.operator.configuration;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

import java.io.IOException;

/**
 * GraalVM native-image hints for everything Spring AOT cannot discover on its own:
 * <ul>
 *     <li>classpath resources that are looked up by pattern at runtime (Discord slash commands)</li>
 *     <li>tool objects and tool input/output types, which are created by hand (not as beans) and
 *     are inspected reflectively by Spring AI to build the tool callbacks and JSON schemas</li>
 *     <li>enums of libraries that look up their constants reflectively (flexmark's {@code BitFieldSet})</li>
 *     <li>JSON payload models that are (de)serialized reflectively by Jackson
 *     (this application, discord4j and telegrambots)</li>
 *     <li>the OpenAI Java SDK models used by the chat completions API. Jackson calls their private
 *     {@code @JsonAnySetter} / {@code @JsonCreator} members, which the agent-generated
 *     {@code reflect-config.json} shipped in the SDK jar does not cover. Only the packages needed for chat
 *     completions are registered because the SDK has ~26k classes</li>
 * </ul>
 * The registrar runs during AOT processing on a regular JVM, so classpath scanning is fine here.
 */
@Slf4j
public class NativeImageRuntimeHints implements RuntimeHintsRegistrar {
    private static final String[] RESOURCE_PATTERNS = {"commands/*.json"};

    private static final String[] REFLECTION_PACKAGES = {
            "guru.kumo.operator",
            "discord4j.discordjson",
            "discord4j.gateway.json",
            "org.telegram.telegrambots.meta"
    };

    /** Registered including all sub-packages. */
    private static final String[] OPENAI_RECURSIVE_PACKAGES = {
            "com.openai.core",
            "com.openai.errors",
            "com.openai.models.chat.completions",
            "com.openai.models.completions"
    };

    /** Registered without sub-packages (shared types such as CompletionUsage, FunctionDefinition, ...). */
    private static final String[] OPENAI_FLAT_PACKAGES = {
            "com.openai.models"
    };

    private static final String[] ENUM_ONLY_PACKAGES = {
            "com.vladsch.flexmark"
    };

    private static final MemberCategory[] ALL_MEMBERS = {
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS
    };

    @Override
    public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
        for (String pattern : RESOURCE_PATTERNS) {
            hints.resources().registerPattern(pattern);
        }

        ClassLoader loader = classLoader != null ? classLoader : getClass().getClassLoader();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        scan(hints, resolver, loader, REFLECTION_PACKAGES, false, true);
        scan(hints, resolver, loader, OPENAI_RECURSIVE_PACKAGES, false, true);
        scan(hints, resolver, loader, OPENAI_FLAT_PACKAGES, false, false);
        scan(hints, resolver, loader, ENUM_ONLY_PACKAGES, true, true);
    }

    private void scan(RuntimeHints hints, PathMatchingResourcePatternResolver resolver, ClassLoader loader,
                      String[] packageNames, boolean enumsOnly, boolean recursive) {
        for (String packageName : packageNames) {
            String location = "classpath*:" + packageName.replace('.', '/') + (recursive ? "/**/*.class" : "/*.class");
            try {
                for (Resource resource : resolver.getResources(location)) {
                    registerClass(hints, resource, packageName, loader, enumsOnly);
                }
            } catch (IOException e) {
                log.warn("Unable to scan {} for native-image reflection hints: {}", packageName, e.getMessage());
            }
        }
    }

    private void registerClass(RuntimeHints hints, Resource resource, String packageName, ClassLoader loader,
                               boolean enumsOnly) throws IOException {
        String uri = resource.getURI().toString();
        String marker = packageName.replace('.', '/');
        int index = uri.lastIndexOf(marker + "/");
        if (index < 0) return;
        String className = uri.substring(index, uri.length() - ".class".length()).replace('/', '.');
        if (className.endsWith("module-info") || className.endsWith("package-info")) return;
        try {
            Class<?> type = ClassUtils.forName(className, loader);
            if (enumsOnly && !type.isEnum()) return;
            hints.reflection().registerType(type, ALL_MEMBERS);
        } catch (ClassNotFoundException | LinkageError e) {
            log.debug("Skipping {} for native-image reflection hints: {}", className, e.getMessage());
        }
    }
}
