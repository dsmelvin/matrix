package guru.kumo.operator.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvFileLoaderTest {
    private static final String HOME = System.getProperty("user.home");

    @Test
    void parseExpandsTildeAndHandlesShellSyntax() {
        Map<String, String> env = EnvFileLoader.parse(List.of(
                "# comment",
                "",
                "AGENT_PATH_MEMORY=~/memory",
                "export HOME_ONLY=~",
                "QUOTED=\"~/quoted dir\"",
                "PATH_LIST=~/bin:/usr/bin:~/tools",
                "NOT_A_HOME=a~b",
                "OTHER_USER=~bob/x",
                "EMPTY=",
                "WITH_COMMENT=value # trailing",
                "1BAD=x"));

        assertThat(env)
                .containsEntry("AGENT_PATH_MEMORY", HOME + "/memory")
                .containsEntry("HOME_ONLY", HOME)
                .containsEntry("QUOTED", HOME + "/quoted dir")
                .containsEntry("PATH_LIST", HOME + "/bin:/usr/bin:" + HOME + "/tools")
                .containsEntry("NOT_A_HOME", "a~b")
                .containsEntry("OTHER_USER", "~bob/x")
                .containsEntry("EMPTY", "")
                .containsEntry("WITH_COMMENT", "value")
                .doesNotContainKey("1BAD");
    }
}
