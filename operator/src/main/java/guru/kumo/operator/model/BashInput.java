package guru.kumo.operator.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Arguments of the {@code Bash} tool call as sent by the model. Must accept the same names as the tool schema
 * (see {@code ShellTools#bash}); unknown properties are ignored so that a stray argument can never break the
 * permission prompt or the console output of a tool call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BashInput(String command, Long timeout, String description,
                        @JsonProperty("run_in_background") @JsonAlias("runInBackground") Boolean runInBackground) {
}
