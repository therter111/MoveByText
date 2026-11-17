package KirizsKeve.MoveByText.nlp;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * What every backend shares: one background worker thread, and ask() running a request on it and handing the
 * answer to the callback. A subclass only says how one request is made (request()) and which settings it needs
 * (require()).
 *
 * The callback runs on the worker thread, never on the game thread, so it must not touch Starsector objects.
 * Requests run one after another, in the order they were asked.
 */
public abstract class AbstractBackend implements Backend {

    private final Logger log = Global.getLogger(getClass());

    /** Names of required settings that are missing, in the order they were checked. */
    private final List<String> missing = new ArrayList<>();

    /** How long to wait for an answer. */
    private volatile int readTimeoutMs;

    private final ExecutorService worker;

    protected AbstractBackend(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        final String threadName = "KirizsKeve-" + getClass().getSimpleName();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, threadName);
            t.setDaemon(true); // never keep the game process alive
            return t;
        });
    }

    /** Who answers, for the log: "Gemini", "KoboldCpp", "Rasa"... */
    protected abstract String displayName();

    /** One request, on the calling thread. ask() runs it on the worker, askNow() right away. */
    protected abstract String request(String userText) throws Exception;

    /** Called on the worker when ask() got a reply, before the callback. Chat backends remember the exchange. */
    protected void answered(String userText, String reply) {
    }

    /** Records a required setting; a null value makes the backend not configured. */
    protected final void require(String name, Object value) {
        if (value == null) missing.add(name);
    }

    /** The missing settings, each with a space in front (" apiKey model"); "" when nothing is missing. */
    protected final String missingSettings() {
        StringBuilder sb = new StringBuilder();
        for (String name : missing) sb.append(' ').append(name);
        return sb.toString();
    }

    /** "GeminiClient ready, model = x", or which settings are missing. Call at the end of the constructor. */
    protected final void logReady(String details) {
        String name = getClass().getSimpleName();
        if (isConfigured()) {
            log.info(name + " ready" + details);
        } else {
            log.warn(name + " NOT ready. Missing in " + Settings.FILE + ":" + missingSettings());
        }
    }

    protected final Logger log() {
        return log;
    }

    /** Runs a task on the worker, after the requests asked before it; nothing after close(). */
    protected final void onWorker(Runnable task) {
        try {
            worker.submit(task);
        } catch (RejectedExecutionException e) {
            // closed: nothing left to do for this backend
        }
    }

    /** Lets the running and queued requests finish, then ends the worker thread; ask() fails from now on. */
    @Override
    public void close() {
        worker.shutdown();
    }

    @Override
    public boolean isConfigured() {
        return missing.isEmpty();
    }

    @Override
    public final void ask(final String userText, final Callback callback) {
        if (!isConfigured()) {
            callback.onError(notConfigured());
            return;
        }

        try {
            worker.submit(() -> {
                String reply = null;
                String error = null;
                try {
                    reply = request(userText);
                    answered(userText, reply);
                } catch (Exception e) {
                    log.error(displayName() + " request failed", e);
                    error = (e.getMessage() != null) ? e.getMessage() : e.toString();
                }

                if (error != null) {
                    callback.onError(error);
                } else {
                    callback.onResponse(reply);
                }
            });
        } catch (RejectedExecutionException e) {
            callback.onError(displayName() + " was closed (the settings were reloaded). Send the message again.");
        }
    }

    /** Blocking request that records no history. For tests only - never call this from the game thread. */
    @Override
    public final String askNow(String userText) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(notConfigured());
        }
        return request(userText);
    }

    /** Nothing to forget, unless the backend keeps a conversation. */
    @Override
    public void clearHistory() {
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    /** The game keeps the default or the configured timeout; tests may change it. */
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    private String notConfigured() {
        return "LLM not configured. Missing in " + Settings.FILE + ":" + missingSettings();
    }
}
