package guru.kumo.operator.tool;

import guru.kumo.operator.service.InterruptController;
import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.Utils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows the questions of the {@link AskUserQuestionTool} on the console and reads the answers with a JLine line reader.
 * <p>
 * Every question is printed with its numbered options plus a final "Other" option. The user answers with:
 * <ul>
 *   <li>the number of an option (single select),</li>
 *   <li>comma / space separated numbers (multi select),</li>
 *   <li>the number of "Other" and then free text, or simply any free text that is not a list of numbers.</li>
 * </ul>
 * Ctrl-C / Ctrl-D cancels the whole turn, like the shell permission question does.
 */
public class ConsoleQuestionHandler implements AskUserQuestionTool.QuestionHandler {
    private static final String OTHER_LABEL = "Other";

    private final Terminal terminal;
    private final LineReader reader;
    private final InterruptController interruptController; // null when Ctrl-C cancellation is not wired in

    public ConsoleQuestionHandler(Terminal terminal, InterruptController interruptController) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
        this.interruptController = interruptController;
    }

    @Override
    public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {
        // One question session at a time: parallel subagents must not interleave their prompts.
        synchronized (this) {
            Map<String, String> answers = new LinkedHashMap<>();
            PrintWriter out = terminal.writer();
            out.printf("%n%sThe agent has %d question(s) for you:%s%n", ColorEnum.BLINK_BOLD_RED, questions.size(), ColorEnum.RESET);
            out.flush();

            for (int i = 0; i < questions.size(); i++) {
                AskUserQuestionTool.Question question = questions.get(i);
                answers.put(question.question(), askQuestion(out, i + 1, questions.size(), question));
            }
            return answers;
        }
    }

    private String askQuestion(PrintWriter out, int index, int total, AskUserQuestionTool.Question question) {
        List<AskUserQuestionTool.Question.Option> options = question.options();
        int otherIndex = options.size() + 1;
        boolean multiSelect = Boolean.TRUE.equals(question.multiSelect());

        out.printf("%n%s[%d/%d] [%s] %s%s%n", ColorEnum.BOLD_HIGH_INTENSITY_BLUE, index, total, question.header(), question.question(), ColorEnum.RESET);
        for (int i = 0; i < options.size(); i++) {
            AskUserQuestionTool.Question.Option option = options.get(i);
            out.printf("  %s%d.%s %s%s%s - %s%n", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, i + 1, ColorEnum.RESET,
                    ColorEnum.BOLD_CYAN, option.label(), ColorEnum.RESET, option.description());
        }
        out.printf("  %s%d.%s %s%s%s - Type your own answer%n", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, otherIndex, ColorEnum.RESET,
                ColorEnum.BOLD_CYAN, OTHER_LABEL, ColorEnum.RESET);
        out.flush();

        String prompt = String.format("%s%s%s ",
                ColorEnum.BOLD_HIGH_INTENSITY_YELLOW,
                multiSelect ? "Choose one or more (e.g. 1,3), or type your own answer:" : "Choose a number, or type your own answer:",
                ColorEnum.RESET);

        while (true) {
            String line = readLine(prompt).strip();
            if (line.isEmpty()) continue;

            List<Integer> numbers = parseNumbers(line);
            if (numbers == null) {
                // Not a list of numbers: free text is the "Other" answer.
                return line;
            }
            if (!multiSelect && numbers.size() > 1) {
                out.printf("%sThis question allows only one choice.%s%n", ColorEnum.BOLD_HIGH_INTENSITY_RED, ColorEnum.RESET);
                out.flush();
                continue;
            }
            if (numbers.stream().anyMatch(n -> n < 1 || n > otherIndex)) {
                out.printf("%sNumbers must be between 1 and %d.%s%n", ColorEnum.BOLD_HIGH_INTENSITY_RED, otherIndex, ColorEnum.RESET);
                out.flush();
                continue;
            }

            List<String> selected = new ArrayList<>();
            boolean other = false;
            for (int number : numbers) {
                if (number == otherIndex) {
                    other = true;
                } else {
                    selected.add(options.get(number - 1).label());
                }
            }
            if (other) {
                String custom = readNonEmpty(out, "Your answer:");
                selected.add(custom);
            }
            return String.join(", ", selected);
        }
    }

    private String readNonEmpty(PrintWriter out, String message) {
        String prompt = String.format("%s%s%s ", ColorEnum.BOLD_HIGH_INTENSITY_YELLOW, message, ColorEnum.RESET);
        while (true) {
            String line = readLine(prompt).strip();
            if (!line.isEmpty()) return line;
        }
    }

    /**
     * @return the distinct numbers if the whole input is numbers separated by commas and/or spaces, otherwise null
     */
    private static List<Integer> parseNumbers(String line) {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (String part : line.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return numbers.isEmpty() ? null : new ArrayList<>(numbers);
    }

    private String readLine(String prompt) {
        Utils.discardPendingInput();
        try {
            String line = reader.readLine(prompt);
            if (line == null) throw new EndOfFileException();
            return line;
        } catch (UserInterruptException | EndOfFileException e) {
            // Ctrl-C / Ctrl-D at a question cancels the whole turn.
            if (interruptController == null) throw e;
            throw interruptController.cancelTurnFromPrompt();
        }
    }
}
