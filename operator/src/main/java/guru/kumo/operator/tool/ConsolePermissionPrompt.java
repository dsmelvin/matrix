package guru.kumo.operator.tool;

import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.Utils;

import java.io.IOException;
import java.util.Locale;

/**
 * Asks the user a yes/no question on the console and gives up after a timeout. Used by the sandbox helpers that run
 * outside of the JLine line reader (while a shell command is running nobody else reads the terminal).
 */
final class ConsolePermissionPrompt {

    /** How long every sandbox approval prompt waits for an answer before it counts as "no". */
    static final long TIMEOUT_MS = 9_000;
    private static final Object LOCK = new Object();
    private static volatile Responder responder;

    private ConsolePermissionPrompt() {
    }

    enum Answer {
        NO, YES, ALL
    }

    /**
     * Answers the prompts instead of the console (e.g. the {@link ShellToolsPlayground} web UI). Called while the prompt lock is
     * held and expected to give up after {@link #TIMEOUT_MS}; anything but a clear yes/all counts as no.
     */
    interface Responder {
        Answer ask(String question, boolean offerAll);
    }

    /** Installs (or, with null, removes) the responder that replaces the console. */
    static void setResponder(Responder newResponder) {
        responder = newResponder;
    }

    /**
     * Plain yes/no question.
     *
     * @return true only if the user answered yes (y, yes, t, true) in time. No answer, anything else, or an error
     * means no. Prompts are serialized so concurrent questions don't interleave; use {@link #runExclusively} when the
     * answer may already be known once the lock is acquired.
     */
    static boolean ask(String question) {
        synchronized (LOCK) {
            return askLocked(question, false) != Answer.NO;
        }
    }

    /**
     * Runs {@code decision} while holding the prompt lock. Lets callers re-check a grant that another waiting
     * connection may have just received before they prompt again.
     */
    static boolean runExclusively(java.util.function.BooleanSupplier decision) {
        synchronized (LOCK) {
            return decision.getAsBoolean();
        }
    }

    /**
     * Like {@link #ask} but must be called from within {@link #runExclusively}. With {@code offerAll} the user can
     * also answer "all" (same wording as the command permission question of the tool calling manager).
     */
    static Answer askLocked(String question, boolean offerAll) {
        Responder custom = responder;
        if (custom != null) {
            try {
                Answer answer = custom.ask(question, offerAll);
                return answer == null ? Answer.NO : answer;
            } catch (RuntimeException e) {
                return Answer.NO;
            }
        }
        try {
            Utils.discardPendingInput();
            System.out.printf("%n%s%s (%s, %d seconds):%s%n", ColorEnum.BLINK_BOLD_RED, question,
                    offerAll ? "yes, all or no" : "yes/no", TIMEOUT_MS / 1000, ColorEnum.RESET);
            System.out.flush();
            long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
            StringBuilder answer = new StringBuilder();
            while (System.nanoTime() < deadline) {
                if (System.in.available() == 0) {
                    Thread.sleep(50);
                    continue;
                }
                int b = System.in.read();
                if (b < 0 || b == '\n') break;
                if (b != '\r') answer.append((char) b);
            }
            String reply = answer.toString().strip().toLowerCase(Locale.ROOT);
            if (offerAll && reply.equals("all")) return Answer.ALL;
            boolean yes = reply.equals("y") || reply.equals("yes") || reply.equals("t") || reply.equals("true");
            return yes ? Answer.YES : Answer.NO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.NO;
        } catch (IOException e) {
            return Answer.NO;
        }
    }
}
