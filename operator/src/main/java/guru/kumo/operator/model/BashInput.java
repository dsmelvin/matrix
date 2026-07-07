package guru.kumo.operator.model;

public record BashInput(String command, Long timeout, String description, Boolean runInBackground) {
}