package guru.kumo.operator.service;

import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single owner of Ctrl-C (SIGINT) handling.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>Ctrl-C while a console turn is running: the turn is cancelled and the operator goes back to
 *       "waiting for input". The "exit window" is armed.</li>
 *   <li>Ctrl-C at the prompt (idle, or with a half-typed message): the typed input is dropped and the exit window is armed.</li>
 *   <li>Ctrl-C again while the exit window is armed: graceful exit through the Spring context, which runs
 *       {@link ShutdownHandler}.</li>
 *   <li>Ctrl-C once more while the exit is in progress (or after {@link #FORCE_EXIT_AFTER}): the JVM is halted.</li>
 * </ul>
 * SIGTERM / SIGHUP are intentionally left to the JVM defaults: they run the Spring shutdown hook, hence
 * {@link ShutdownHandler}, without any help from here.
 * <p>
 * Only console-originated work ({@link #runTurn(Runnable)}) is cancellable. Discord / Telegram requests and background
 * tasks are not touched by a terminal Ctrl-C; they are closed at exit by their channels.
 */
@Slf4j
@Component
@Profile("operator")
public class InterruptController {
    public static final Duration EXIT_WINDOW = Duration.ofSeconds(3);
    public static final Duration FORCE_EXIT_AFTER = Duration.ofSeconds(10);
    public static final int EXIT_CODE_SIGINT = 130;

    private static final Duration TURN_UNWIND_TIMEOUT = Duration.ofSeconds(3);
    private static final ThreadLocal<Turn> CURRENT_TURN = new ThreadLocal<>();

    private final Terminal terminal;
    private final Runnable exitAction;
    private final Object pressLock = new Object();
    private final AtomicBoolean exiting = new AtomicBoolean(false);

    private volatile Turn activeTurn;
    private volatile boolean shuttingDown;
    private volatile boolean lastTurnCancelled;
    private boolean armed;          // guarded by pressLock
    private long armedAtNanos;      // guarded by pressLock

    @Autowired
    InterruptController(Terminal terminal, ConfigurableApplicationContext applicationContext) {
        this(terminal, () -> exitProcess(applicationContext));
    }

    /**
     * @param exitAction what a confirmed exit does; it must not block (it runs on the signal dispatcher or a prompt thread)
     */
    InterruptController(Terminal terminal, Runnable exitAction) {
        this.terminal = terminal;
        this.exitAction = exitAction;
        // JLine's LineReader swaps in its own INT handler while it is reading and restores this one afterwards, so
        // this handler only sees Ctrl-C while no prompt is being read, i.e. while the agent is working.
        terminal.handle(Terminal.Signal.INT, signal -> onSignal());
        routeProcessSigintToTerminal(terminal);
    }

    /**
     * JLine only takes over the process-level SIGINT for some terminal types (a real tty); for a dumb or piped
     * terminal the JVM default would still kill the process on the first Ctrl-C. Taking the process-level handler here
     * and forwarding it to {@link Terminal#raise(Terminal.Signal)} gives every terminal type the same behaviour: the
     * signal reaches the handler JLine currently has registered, i.e. the line reader's while a prompt is being read,
     * and {@link #onSignal()} otherwise.
     */
    private static void routeProcessSigintToTerminal(Terminal terminal) {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> terminal.raise(Terminal.Signal.INT));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            log.warn("Could not register the process-level SIGINT handler: {}", e.getMessage());
        }
    }

    /**
     * Runs one console-originated unit of agent work (the init prompt or one user message) so that Ctrl-C can cancel it.
     *
     * @return {@code true} if the work completed, {@code false} if it was cancelled by the user
     */
    public boolean runTurn(Runnable work) {
        Turn turn = new Turn(Thread.currentThread());
        CURRENT_TURN.set(turn);
        activeTurn = turn;
        lastTurnCancelled = false;
        try {
            work.run();
            return true;
        } catch (CancellationException e) {
            return cancelled();
        } catch (RuntimeException e) {
            // An interrupt can surface as an arbitrary exception from deep inside an HTTP client or a tool.
            if (turn.cancelled) return cancelled();
            throw e;
        } finally {
            activeTurn = null;
            CURRENT_TURN.remove();
            Thread.interrupted(); // never leak the interrupt flag into the prompt
            turn.done.countDown();
        }
    }

    private boolean cancelled() {
        Thread.interrupted();
        lastTurnCancelled = true;
        if (!shuttingDown) {
            print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + "Cancelled. Back to waiting for input." + ColorEnum.RESET);
        }
        return false;
    }

    /**
     * Cooperative cancellation point; a no-op on threads that are not running a console turn.
     */
    public void checkCancelled() {
        if (isCancelled()) throw new CancellationException("Interrupted by user");
    }

    public boolean isCancelled() {
        Turn turn = CURRENT_TURN.get();
        return turn != null && turn.cancelled;
    }

    /**
     * Whether the most recent {@link #runTurn(Runnable)} ended because the user cancelled it.
     */
    public boolean lastTurnCancelled() {
        return lastTurnCancelled;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Called by a prompt reader that caught {@code UserInterruptException} (Ctrl-C while reading a line) at the main prompt.
     *
     * @param hadInput whether a message was being typed, which is then dropped by the caller
     */
    public void onPromptInterrupted(boolean hadInput) {
        if (exiting.get()) return;
        if (registerPress()) {
            requestExit();
            return;
        }
        print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + (hadInput ? "Input cleared. " : "") + exitHint() + ColorEnum.RESET);
    }

    /**
     * Called when Ctrl-C (or Ctrl-D) is pressed inside a prompt that is part of a running turn, e.g. the permission
     * question for a shell command. The caller throws the returned exception to unwind the turn.
     */
    public CancellationException cancelTurnFromPrompt() {
        Turn turn = CURRENT_TURN.get();
        if (turn != null) turn.cancelled = true;
        if (registerPress()) {
            requestExit();
        } else {
            print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + exitHint() + ColorEnum.RESET);
        }
        return new CancellationException("Interrupted by user");
    }

    /**
     * Graceful exit through the Spring context, which runs {@link ShutdownHandler}. Does not block the caller.
     */
    public void requestExit() {
        shuttingDown = true;
        if (!exiting.compareAndSet(false, true)) return;
        print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + "Shutting down... (Ctrl-C again to force)" + ColorEnum.RESET);

        exitAction.run();
    }

    /**
     * Closes the Spring context (which runs {@link ShutdownHandler}) and ends the JVM, on its own threads so that a
     * further Ctrl-C can still be handled. A watchdog halts the JVM if the shutdown hangs.
     */
    private static void exitProcess(ConfigurableApplicationContext applicationContext) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(FORCE_EXIT_AFTER);
                log.warn("Shutdown did not finish in {}, forcing exit.", FORCE_EXIT_AFTER);
                halt();
            } catch (InterruptedException ignored) {
            }
        }, "operator-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Thread exitThread = new Thread(() -> {
            int exitCode = EXIT_CODE_SIGINT;
            try {
                exitCode = SpringApplication.exit(applicationContext, () -> EXIT_CODE_SIGINT);
            } catch (Exception e) {
                log.error("Error while closing the application context", e);
            } finally {
                System.exit(exitCode);
            }
        }, "operator-exit");
        exitThread.start();
    }

    /**
     * Called by {@link ShutdownHandler} before anything is closed: cancels the running console turn and gives it a
     * moment to unwind, so that chat memory is in a consistent state when it is saved.
     */
    public void beginShutdown() {
        shuttingDown = true;
        Turn turn = activeTurn;
        if (turn == null) return;
        turn.cancel();
        if (turn.thread == Thread.currentThread()) return;
        try {
            if (!turn.done.await(TURN_UNWIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("The running turn did not stop within {}.", TURN_UNWIND_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Runs on the JVM's signal dispatcher thread: keep it short and never block.
    private void onSignal() {
        if (exiting.get()) {
            halt();
            return;
        }
        if (registerPress()) {
            requestExit();
            return;
        }
        Turn turn = activeTurn;
        if (turn != null) {
            print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + "Interrupting the running task... " + exitHint() + ColorEnum.RESET);
            turn.cancel();
        } else {
            print(ColorEnum.BOLD_HIGH_INTENSITY_YELLOW + exitHint() + ColorEnum.RESET);
        }
    }

    /**
     * Records a Ctrl-C.
     *
     * @return {@code true} if it is the second press inside the exit window, i.e. the user wants to exit
     */
    private boolean registerPress() {
        synchronized (pressLock) {
            long now = System.nanoTime();
            if (armed && now - armedAtNanos < EXIT_WINDOW.toNanos()) {
                armed = false;
                return true;
            }
            armed = true;
            armedAtNanos = now;
            return false;
        }
    }

    private static String exitHint() {
        return "Press Ctrl-C again within " + EXIT_WINDOW.toSeconds() + "s to exit.";
    }

    private static void halt() {
        Runtime.getRuntime().halt(EXIT_CODE_SIGINT);
    }

    private void print(String message) {
        try {
            terminal.writer().println(System.lineSeparator() + message);
            terminal.writer().flush();
        } catch (RuntimeException ignored) {
            // the terminal may already be closed during shutdown
        }
    }

    private static final class Turn {
        final Thread thread;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean cancelled;

        Turn(Thread thread) {
            this.thread = thread;
        }

        void cancel() {
            cancelled = true;
            thread.interrupt();
        }
    }
}
