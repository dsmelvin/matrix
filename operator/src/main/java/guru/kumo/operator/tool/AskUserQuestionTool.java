package guru.kumo.operator.tool;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Slf4j
public class AskUserQuestionTool {
    private static final JsonMapper jsonMapper = new JsonMapper();

    private final QuestionHandler questionHandler;
    private final boolean answersValidation;

    @FunctionalInterface
    public interface QuestionHandler {
        Map<String, String> handle(List<Question> questions);
    }

    protected AskUserQuestionTool(QuestionHandler questionHandler, boolean answersValidation) {
        this.questionHandler = questionHandler;
        this.answersValidation = answersValidation;
    }

    public record Question(
            @JsonPropertyDescription("The complete question to ask the user. Should be clear, specific, and end with a question mark. Example: \"Which library should we use for date formatting?\"") String question,
            @JsonPropertyDescription("Very short label displayed as a chip/tag (max 12 chars). Examples: \"Auth method\", \"Library\", \"Approach\"") String header,
            @JsonPropertyDescription("The available choices for this question. Must have 2-4 options. Each option should be a distinct, mutually exclusive choice (unless multiSelect is enabled).") List<Option> options,
            @JsonPropertyDescription("Set to true to allow the user to select multiple options instead of just one. Use when choices are not mutually exclusive. Defaults to false if null.") Boolean multiSelect) {
        public Question {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("Question text cannot be null or blank");
            }
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("Header cannot be null or blank");
            }
            if (header.length() > 12) {
                log.warn("Header length is greater than 12 characters: {}", header);
            }
            if (options == null || options.size() < 2 || options.size() > 4) {
                log.warn("Options size must be between 2 and 4, got: {}", options == null ? 0 : options.size());
            }
            if (multiSelect == null) {
                multiSelect = false;
            }
            options = List.copyOf(options);
        }

        public record Option(
                @JsonPropertyDescription("The display text for this option that the user will see and select. Should be concise (1-5 words) and clearly describe the choice.") String label,
                @JsonPropertyDescription("Explanation of what this option means or what will happen if chosen. Useful for providing context about trade-offs or implications.") String description) {
            public Option {
                if (label == null || label.isBlank()) {
                    throw new IllegalArgumentException("Option label cannot be null or blank");
                }
                if (description == null || description.isBlank()) {
                    throw new IllegalArgumentException("Option description cannot be null or blank");
                }
            }
        }
    }

    @Tool(name = "AskUserQuestionTool",
            description = """
                    Instead of chasing your tail, asking the user for some guideline can help speed up the process.
                    Use this tool when you need to ask the user questions during execution. This allows you to:
                    1. Gather user preferences or requirements
                    2. Clarify ambiguous instructions
                    3. Get decisions on implementation choices as you work
                    4. Offer choices to the user about what direction to take.
                    
                    Usage notes:
                    - Users will always be able to select "Other" to provide custom text input
                    - Use multiSelect: true to allow multiple answers to be selected for a question
                    - If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label
                    """)
    public String askUserQuestion(
            @ToolParam(description = "Questions to ask the user (1-4 questions)") List<Question> questions,
            @ToolParam(description = "User answers collected by the permission component", required = false) Map<String, String> answers) {

        this.validateQuestions(questions);

        log.debug("Asking user {} question(s)", questions.size());
        if (log.isTraceEnabled()) {
            questions.forEach(q -> log.trace("Question: {}", q.question()));
        }

        Map<String, String> result = this.questionHandler.handle(questions);

        if (this.answersValidation) {
            this.validateAnswers(questions, result);
        }

        if (log.isDebugEnabled() && result != null) {
            log.debug("Received {} answer(s) from user", result.size());
        }

        return "User has answered your questions: " + jsonMapper.writeValueAsString(result);
    }

    private void validateQuestions(List<Question> questions) {
        if (questions == null) {
            throw new IllegalArgumentException("Questions list cannot be null");
        }

        if (questions.isEmpty() || questions.size() > 4) {
            throw new IllegalArgumentException("Questions list must contain 1-4 questions, got: " + questions.size());
        }

        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            if (question == null) {
                throw new IllegalArgumentException("Question at index " + i + " is null");
            }
        }
    }

    private void validateAnswers(List<Question> questions, Map<String, String> answers) {
        if (answers == null) {
            throw new InvalidUserAnswerException("questionHandler returned null. Must return a non-null Map<String, String>");
        }

        for (Question question : questions) {
            String questionText = question.question();

            if (!answers.containsKey(questionText)) {
                throw new InvalidUserAnswerException("Missing answer for question: \"" + questionText + "\". All questions must have answers.");
            }

            String answerValue = answers.get(questionText);
            if (answerValue == null) {
                throw new InvalidUserAnswerException("Answer for question \"" + questionText
                        + "\" is null. Answer values should not be null (empty strings are acceptable).");
            }
        }

        if (answers.size() > questions.size()) {
            for (String answerKey : answers.keySet()) {
                boolean foundMatch = questions.stream().anyMatch(q -> q.question().equals(answerKey));
                if (!foundMatch) {
                    log.warn("Answer map contains unexpected key that does not match any question: \"{}\"", answerKey);
                }
            }
        }
    }

    public static class InvalidUserAnswerException extends RuntimeException {
        public InvalidUserAnswerException(String message) {
            super(message);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private QuestionHandler questionHandler;
        private boolean answersValidation = true;

        public Builder answersValidation(boolean answersValidation) {
            this.answersValidation = answersValidation;
            return this;
        }

        public Builder questionHandler(QuestionHandler questionHandler) {
            Assert.notNull(questionHandler, "questionHandler must not be null");
            this.questionHandler = questionHandler;
            return this;
        }

        public AskUserQuestionTool build() {
            Assert.notNull(questionHandler, "questionHandler must be provided");
            return new AskUserQuestionTool(questionHandler, answersValidation);
        }
    }
}