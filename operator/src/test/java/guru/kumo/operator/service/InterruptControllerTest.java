package guru.kumo.operator.service;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InterruptControllerTest {
    private Terminal terminal;
    private AtomicInteger exits;
    private InterruptController controller;

    @BeforeEach
    void setUp() throws Exception {
        terminal = TerminalBuilder.builder().system(false).type("dumb")
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build();
        exits = new AtomicInteger();
        controller = new InterruptController(terminal, exits::incrementAndGet);
    }

    @AfterEach
    void tearDown() throws Exception {
        terminal.close();
    }

    @Test
    void firstSigintCancelsTheRunningTurnAndDoesNotExit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        boolean[] completed = new boolean[1];
        Thread turnThread = new Thread(() -> completed[0] = controller.runTurn(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                // what a blocking HTTP client or tool does when the console thread is interrupted
                throw new IllegalStateException(e);
            }
        }));
        turnThread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        terminal.raise(Terminal.Signal.INT);
        turnThread.join(5_000);

        assertThat(turnThread.isAlive()).isFalse();
        assertThat(completed[0]).isFalse();
        assertThat(controller.lastTurnCancelled()).isTrue();
        assertThat(exits.get()).isZero();
        assertThat(turnThread.isInterrupted()).isFalse();
    }

    @Test
    void cooperativeCancellationPointsOnlyFireOnTheCancelledTurnThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Throwable[] thrown = new Throwable[1];
        Thread turnThread = new Thread(() -> controller.runTurn(() -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // swallowed on purpose: only the flag tells the turn it was cancelled
                }
            }
            try {
                controller.checkCancelled();
            } catch (CancellationException e) {
                thrown[0] = e;
                throw e;
            }
        }));
        turnThread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        terminal.raise(Terminal.Signal.INT);
        // a thread that is not running a console turn (Discord, Telegram, background task) is never cancelled
        assertThatCode(controller::checkCancelled).doesNotThrowAnyException();
        release.countDown();
        turnThread.join(5_000);

        assertThat(thrown[0]).isInstanceOf(CancellationException.class);
    }

    @Test
    void secondSigintInsideTheWindowExits() {
        terminal.raise(Terminal.Signal.INT);
        assertThat(exits.get()).isZero();

        terminal.raise(Terminal.Signal.INT);
        assertThat(exits.get()).isEqualTo(1);
        assertThat(controller.isShuttingDown()).isTrue();
    }

    @Test
    void promptInterruptsFollowTheSameTwoStepExit() {
        controller.onPromptInterrupted(true);
        assertThat(exits.get()).isZero();

        controller.onPromptInterrupted(false);
        assertThat(exits.get()).isEqualTo(1);
    }

    @Test
    void pressAfterSigintAtThePromptExits() throws Exception {
        controller.runTurn(() -> terminal.raise(Terminal.Signal.INT));
        assertThat(controller.lastTurnCancelled()).isFalse(); // the turn finished by itself; Ctrl-C only armed the window

        controller.onPromptInterrupted(false);
        assertThat(exits.get()).isEqualTo(1);
    }

    @Test
    void ctrlCAtThePermissionPromptCancelsTheTurn() {
        boolean completed = controller.runTurn(() -> {
            throw controller.cancelTurnFromPrompt();
        });

        assertThat(completed).isFalse();
        assertThat(controller.lastTurnCancelled()).isTrue();
        assertThat(exits.get()).isZero();
    }

    @Test
    void shutdownCancelsAndWaitsForTheRunningTurn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        boolean[] unwound = new boolean[1];
        Thread turnThread = new Thread(() -> controller.runTurn(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                unwound[0] = true;
                throw new CancellationException();
            }
        }));
        turnThread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        controller.beginShutdown();

        assertThat(unwound[0]).isTrue();
        assertThat(controller.isShuttingDown()).isTrue();
        turnThread.join(5_000);
    }
}
