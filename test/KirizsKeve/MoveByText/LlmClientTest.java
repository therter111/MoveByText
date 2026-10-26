package KirizsKeve.MoveByText;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.varia.NullAppender;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline tests for LlmClient: reading the settings, the HTTP request it builds, how it reads Gemini's
 * replies and errors, the async ask() path, the conversation history and clearHistory().
 *
 * A fake Gemini server runs on localhost, so no API key or network is needed and nothing in the game
 * is touched. For the real model with the real system prompt, see LlmPromptTest.
 *
 * Run main() from IntelliJ (working directory = the mod folder). Pass a word as the first argument to run
 * only the tests whose name contains it, e.g. "history". Exits with code 1 if any test fails.
 */
public class LlmClientTest {

    private static final String KEY = "test-key-123";
    private static final String MODEL = "gemini-test";
    private static final String PROMPT = "You are a test prompt.";
    private static final String CONFIG_FILE = "data/config/llmdata.json";
    private static final String EXAMPLE_CONFIG = "data/config/llmdata.json.example";

    // Non-ASCII test text, written as escapes so this file compiles the same under any source encoding
    private static final String HUNGARIAN = "\u00E1rv\u00EDzt\u0171r\u0151 t\u00FCk\u00F6rf\u00FAr\u00F3g\u00E9p \u0150\u0170";
    private static final String EMOJI = "\uD83D\uDE80\uD83D\uDC7E"; // rocket, alien - each outside the BMP, 4 bytes in UTF-8
    private static final String CJK = "\u65E5\u672C\u8A9E"; // "Japanese"
    private static final String THREE_BYTE_CHAR = "\u65E5"; // 3 bytes in UTF-8

    /** How long to wait for a request or callback that should come, before calling it a failure. */
    private static final long WAIT_MS = 10000;

    private interface Test {
        void run(FakeGemini gemini) throws Exception;
    }

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();

    private static void test(String name, Test body) {
        TESTS.put(name, body);
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    static {
        test("config: key, model and prompt present -> configured", g -> {
            check(client(g).isConfigured(), "a client with all three settings should be configured");
        });

        test("config: empty settings -> all three reported missing, in order, nothing sent", g -> {
            LlmClient llm = new LlmClient(new JSONObject(), g.endpoint());
            check(!llm.isConfigured(), "a client without settings must not be configured");

            Throwable e = fails(() -> llm.askNow("hi"), "askNow on an unconfigured client");
            same(IllegalStateException.class, e.getClass(), "exception type");
            same("LLM not configured. Missing in " + CONFIG_FILE + ": apiKey model systemPrompt", e.getMessage(), "message");
            same(0, g.count(), "requests that reached the server");
        });

        test("config: each missing setting is named on its own", g -> {
            String[][] cases = {
                    {null, MODEL, PROMPT, "apiKey"},
                    {KEY, null, PROMPT, "model"},
                    {KEY, MODEL, null, "systemPrompt"},
                    {null, null, PROMPT, "apiKey model"},
                    {KEY, null, null, "model systemPrompt"},
            };
            for (String[] c : cases) {
                LlmClient llm = new LlmClient(config(c[0], c[1], c[2]), g.endpoint());
                check(!llm.isConfigured(), "missing '" + c[3] + "' must leave the client unconfigured");
                Throwable e = fails(() -> llm.askNow("hi"), "askNow with '" + c[3] + "' missing");
                same("LLM not configured. Missing in " + CONFIG_FILE + ": " + c[3], e.getMessage(), "message for missing " + c[3]);
            }
            same(0, g.count(), "requests that reached the server");
        });

        test("config: blank values count as missing", g -> {
            for (String blank : new String[]{"", " ", "\t", "\n  \r\n"}) {
                LlmClient llm = new LlmClient(config(blank, blank, blank), g.endpoint());
                check(!llm.isConfigured(), "blank value " + show(blank) + " must count as missing");
                has(fails(() -> llm.askNow("hi"), "askNow with blank settings").getMessage(),
                        ": apiKey model systemPrompt", "message for blank " + show(blank));
            }
        });

        test("config: the PASTE_ template placeholder key counts as missing", g -> {
            for (String key : new String[]{"PASTE_YOUR_KEY_HERE", "PASTE_", "  PASTE_KEY  "}) {
                LlmClient llm = new LlmClient(config(key, MODEL, PROMPT), g.endpoint());
                check(!llm.isConfigured(), "placeholder key " + show(key) + " must count as missing");
                same("LLM not configured. Missing in " + CONFIG_FILE + ": apiKey",
                        fails(() -> llm.askNow("hi"), "askNow with placeholder key").getMessage(), "message for " + show(key));
            }
        });

        test("config: a key that only contains PASTE_ further in is accepted", g -> {
            check(new LlmClient(config("abc-PASTE_-xyz", MODEL, PROMPT), g.endpoint()).isConfigured(),
                    "only a key that STARTS with PASTE_ is the placeholder");
        });

        test("config: the shipped llmdata.json.example counts as not configured", g -> {
            // Someone who copies the example without pasting a key should get "missing apiKey",
            // not a request to Google with a fake key
            JSONObject example = LlmPromptTest.readConfig(Paths.get(EXAMPLE_CONFIG));
            LlmClient llm = new LlmClient(example, g.endpoint());
            check(!llm.isConfigured(), "the example's apiKey " + show(example.optString("apiKey"))
                    + " is accepted as a real key - it should be a PASTE_ placeholder");
            same("LLM not configured. Missing in " + CONFIG_FILE + ": apiKey",
                    fails(() -> llm.askNow("hi"), "askNow with the example settings").getMessage(),
                    "message (model and systemPrompt in the example must be valid)");
        });

        test("config: values are trimmed before use", g -> {
            LlmClient llm = new LlmClient(config("  " + KEY + " \n", "\t" + MODEL + " ", "\n  " + PROMPT + "  \n"), g.endpoint());
            llm.askNow("hi");
            Request r = g.only();
            same("/v1beta/models/" + MODEL + ":generateContent", r.uri.getPath(), "request path (model trimmed)");
            same(KEY, r.headers.getFirst("x-goog-api-key"), "key header (key trimmed)");
            same(PROMPT, systemPrompt(r), "system prompt (trimmed)");
        });

        test("config: maxOutputTokens missing, 0 or negative -> no generationConfig", g -> {
            Object[] values = {null, 0, -1, -500};
            for (int i = 0; i < values.length; i++) {
                JSONObject cfg = config(KEY, MODEL, PROMPT);
                if (values[i] != null) cfg.put("maxOutputTokens", values[i]);
                new LlmClient(cfg, g.endpoint()).askNow("hi");
                check(!g.request(i).json().has("generationConfig"),
                        "maxOutputTokens=" + values[i] + " must leave generationConfig out, body: " + g.request(i).body);
            }
        });

        test("config: maxOutputTokens > 0 -> sent as generationConfig.maxOutputTokens", g -> {
            int[] values = {1, 256, 1024, 65536};
            for (int i = 0; i < values.length; i++) {
                new LlmClient(config(KEY, MODEL, PROMPT).put("maxOutputTokens", values[i]), g.endpoint()).askNow("hi");
                JSONObject gen = g.request(i).json().getJSONObject("generationConfig");
                same(values[i], gen.getInt("maxOutputTokens"), "generationConfig.maxOutputTokens");
                same(Collections.singleton("maxOutputTokens"), keys(gen), "generationConfig fields");
            }
        });

        test("config: maxOutputTokens written as text", g -> {
            // A number in quotes still works; words are ignored like a missing setting
            new LlmClient(config(KEY, MODEL, PROMPT).put("maxOutputTokens", "512"), g.endpoint()).askNow("hi");
            same(512, g.request(0).json().getJSONObject("generationConfig").getInt("maxOutputTokens"), "\"512\" as text");

            new LlmClient(config(KEY, MODEL, PROMPT).put("maxOutputTokens", "lots"), g.endpoint()).askNow("hi");
            check(!g.request(1).json().has("generationConfig"), "\"lots\" must be ignored, body: " + g.request(1).body);
        });
    }

    // ------------------------------------------------------------------------------------------
    // The request sent to Gemini
    // ------------------------------------------------------------------------------------------

    static {
        test("request: POST to <endpoint><model>:generateContent", g -> {
            client(g).askNow("hi");
            Request r = g.only();
            same("POST", r.method, "HTTP method");
            same("/v1beta/models/" + MODEL + ":generateContent", r.uri.getPath(), "path");
            same(null, r.uri.getRawQuery(), "query string");
        });

        test("request: the key is only in the x-goog-api-key header", g -> {
            client(g).askNow("hi");
            Request r = g.only();
            same(KEY, r.headers.getFirst("x-goog-api-key"), "x-goog-api-key header");
            check(!r.uri.toString().contains(KEY), "key leaked into the URL: " + r.uri);
            check(!r.body.contains(KEY), "key leaked into the body: " + r.body);
        });

        test("request: JSON body with a UTF-8 content type", g -> {
            client(g).askNow("hi");
            same("application/json; charset=utf-8", g.only().headers.getFirst("Content-Type"), "Content-Type header");
        });

        test("request: body is the system prompt plus exactly one user turn", g -> {
            client(g).askNow("spawn a small pirate fleet");
            JSONObject body = g.only().json();
            same(new TreeSet<>(Arrays.asList("contents", "systemInstruction")), keys(body), "top-level fields");

            JSONArray sysParts = body.getJSONObject("systemInstruction").getJSONArray("parts");
            same(1, sysParts.length(), "systemInstruction parts");
            same(PROMPT, sysParts.getJSONObject(0).getString("text"), "system prompt text");

            same(Collections.singletonList("user: spawn a small pirate fleet"), turns(g.only()), "contents");
            same(1, body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").length(), "parts in the user turn");
        });

        test("request: tricky user text reaches the server unchanged, as valid UTF-8", g -> {
            String[] inputs = {
                    "",
                    "quote \" and backslash \\ and slash /",
                    "line1\nline2\r\n\ttabbed",
                    "<SPAWN:pirates:40> & </script> {\"json\": [1]}",
                    HUNGARIAN,
                    "emoji " + EMOJI + " and CJK " + CJK,
                    "control \u0000 \u0001 \u001f and \u2028 separators",
            };
            LlmClient llm = client(g);
            for (int i = 0; i < inputs.length; i++) {
                llm.askNow(inputs[i]);
                Request r = g.request(i);
                check(r.validUtf8, "request body for " + show(inputs[i]) + " is not valid UTF-8");
                same(inputs[i], r.userText(), "user text");
            }
        });

        test("request: tricky system prompt reaches the server unchanged", g -> {
            String prompt = "Rules:\n1. \"quotes\" \\ backslashes\n2. <TAGS:like:this>\n3. " + HUNGARIAN + EMOJI + CJK;
            new LlmClient(config(KEY, MODEL, prompt), g.endpoint()).askNow("hi");
            same(prompt, systemPrompt(g.only()), "system prompt");
        });

        test("request: a very long message is sent whole", g -> {
            String text = "fleet ".repeat(40000) + HUNGARIAN;
            client(g).askNow(text);
            same(text, g.only().userText(), "200 KB user text");
        });

        test("request: askNow keeps no history between calls", g -> {
            LlmClient llm = client(g);
            llm.askNow("one");
            llm.askNow("two");
            same(Collections.singletonList("user: two"), turns(g.request(1)), "contents of the second request");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Reading Gemini's reply
    // ------------------------------------------------------------------------------------------

    static {
        test("reply: a single text part is returned", g -> {
            g.reply(answer("<SPAWN:pirates:40>"));
            same("<SPAWN:pirates:40>", client(g).askNow("hi"), "reply");
        });

        test("reply: several parts are joined in order", g -> {
            g.reply(answer("<SPAWN:", "pirates", ":40>", "<NONE>"));
            same("<SPAWN:pirates:40><NONE>", client(g).askNow("hi"), "reply");
        });

        test("reply: outer whitespace is trimmed, inner whitespace kept", g -> {
            g.reply(answer("\n  <SPAWN:pirates:40>\n", "  <NONE>  \n\t"));
            same("<SPAWN:pirates:40>\n  <NONE>", client(g).askNow("hi"), "reply");
        });

        test("reply: parts without text (thought signatures, function calls) are skipped", g -> {
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("thoughtSignature", "abc123"))
                    .put(new JSONObject().put("text", "<NONE>"))
                    .put(new JSONObject().put("functionCall", new JSONObject().put("name", "spawn")));
            g.reply(candidates(new JSONObject().put("content", new JSONObject().put("parts", parts)).put("finishReason", "STOP")));
            same("<NONE>", client(g).askNow("hi"), "reply");
        });

        test("reply: only the first candidate is used", g -> {
            g.reply(candidates(candidate("first"), candidate("second")));
            same("first", client(g).askNow("hi"), "reply");
        });

        test("reply: extra fields (usageMetadata, modelVersion) are ignored", g -> {
            JSONObject body = new JSONObject(answer("<NONE>"))
                    .put("usageMetadata", new JSONObject().put("promptTokenCount", 812).put("totalTokenCount", 815))
                    .put("modelVersion", MODEL)
                    .put("responseId", "xyz");
            g.reply(body.toString());
            same("<NONE>", client(g).askNow("hi"), "reply");
        });

        test("reply: non-ASCII text is decoded correctly", g -> {
            String text = "Jangala \u2013 " + HUNGARIAN + " " + EMOJI + " " + CJK; // \u2013 is a dash org.json sends escaped
            g.reply(answer(text));
            same(text, client(g).askNow("hi"), "reply");
        });

        test("reply: bodies around the 8 KB read buffer are read whole", g -> {
            LlmClient llm = client(g);
            int[] sizes = {200, 8191, 8192, 8193, 16383, 16384, 16385, 65536, 1000000};
            for (int size : sizes) {
                int filler = size - answer("").getBytes(StandardCharsets.UTF_8).length;
                String text = "x".repeat(filler);
                String body = answer(text);
                same(size, body.getBytes(StandardCharsets.UTF_8).length, "test setup: body size");
                g.reply(body);
                same(text, llm.askNow("hi"), "reply text for a " + size + " byte body");
            }
        });

        test("reply: multi-byte characters split by the read buffer survive", g -> {
            // Put a 3-byte character where the 8192 / 16384 byte buffer edges cut through it
            LlmClient llm = client(g);
            byte[] ch = THREE_BYTE_CHAR.getBytes(StandardCharsets.UTF_8);
            for (int start : new int[]{8190, 8191, 16382, 16383}) {
                int base = indexOf(answer(THREE_BYTE_CHAR + " tail").getBytes(StandardCharsets.UTF_8), ch);
                String text = "x".repeat(start - base) + THREE_BYTE_CHAR + " tail";
                String body = answer(text);
                same(start, indexOf(body.getBytes(StandardCharsets.UTF_8), ch), "test setup: byte position of the character");
                g.reply(body);
                same(text, llm.askNow("hi"), "reply with the character at byte " + start);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Replies that carry no answer
    // ------------------------------------------------------------------------------------------

    static {
        test("reply error: no candidates (blocked prompt) -> explained, body included", g -> {
            String body = "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}";
            g.reply(body);
            Throwable e = fails(() -> client(g).askNow("hi"), "askNow on a blocked prompt");
            same(RuntimeException.class, e.getClass(), "exception type");
            same("Gemini returned no answer (the prompt may have been blocked): " + body, e.getMessage(), "message");
        });

        test("reply error: empty candidates list", g -> {
            g.reply("{\"candidates\":[]}");
            has(fails(() -> client(g).askNow("hi"), "askNow with no candidates").getMessage(),
                    "Gemini returned no answer", "message");
        });

        test("reply error: candidate without content -> finishReason in the message", g -> {
            g.reply("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
            has(fails(() -> client(g).askNow("hi"), "askNow with a SAFETY stop").getMessage(),
                    "Gemini returned an empty reply (finishReason=SAFETY)", "message");
        });

        test("reply error: content without parts", g -> {
            g.reply("{\"candidates\":[{\"content\":{\"role\":\"model\"},\"finishReason\":\"STOP\"}]}");
            has(fails(() -> client(g).askNow("hi"), "askNow with no parts").getMessage(),
                    "empty reply (finishReason=STOP)", "message");
        });

        test("reply error: only whitespace after MAX_TOKENS -> says to raise maxOutputTokens", g -> {
            g.reply(candidates(candidate(" \n ").put("finishReason", "MAX_TOKENS")));
            String message = fails(() -> client(g).askNow("hi"), "askNow cut off by MAX_TOKENS").getMessage();
            has(message, "finishReason=MAX_TOKENS", "message");
            has(message, "raise maxOutputTokens", "message");
        });

        test("reply error: missing finishReason -> 'unknown'", g -> {
            g.reply("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}");
            has(fails(() -> client(g).askNow("hi"), "askNow with an empty text").getMessage(),
                    "finishReason=unknown", "message");
        });

        test("reply error: HTTP 200 with a body that isn't JSON", g -> {
            g.reply("<html><body>502 Bad Gateway</body></html>");
            Throwable e = fails(() -> client(g).askNow("hi"), "askNow on an HTML body");
            check(!String.valueOf(e.getMessage()).startsWith("Gemini API returned HTTP"), "should be a parse error, got: " + e);
        });

        test("reply error: HTTP 200 with an empty body", g -> {
            g.reply("");
            fails(() -> client(g).askNow("hi"), "askNow on an empty body");
        });
    }

    // ------------------------------------------------------------------------------------------
    // HTTP and network failures
    // ------------------------------------------------------------------------------------------

    static {
        test("http: error statuses -> 'Gemini API returned HTTP <code>: <body>'", g -> {
            // LlmPromptTest retries when the message contains "HTTP 429" or "HTTP 503", so the format matters
            LlmClient llm = client(g);
            for (int code : new int[]{400, 401, 403, 404, 429, 500, 503}) {
                String body = "{\"error\":{\"code\":" + code + ",\"message\":\"failure " + code + "\",\"status\":\"X\"}}";
                g.reply(code, body);
                Throwable e = fails(() -> llm.askNow("hi"), "askNow on HTTP " + code);
                same(RuntimeException.class, e.getClass(), "exception type for HTTP " + code);
                same("Gemini API returned HTTP " + code + ": " + body, e.getMessage(), "message for HTTP " + code);
            }
        });

        test("http: an error without a body still reports the status", g -> {
            g.reply(500, "");
            same("Gemini API returned HTTP 500: ",
                    fails(() -> client(g).askNow("hi"), "askNow on an empty HTTP 500").getMessage(), "message");
        });

        test("http: nothing listening -> connection error", g -> {
            LlmClient llm = new LlmClient(config(KEY, MODEL, PROMPT), closedEndpoint());
            Throwable e = fails(() -> llm.askNow("hi"), "askNow to a closed port");
            check(e instanceof ConnectException, "expected a ConnectException, got " + e);
        });

        test("http: server slower than readTimeoutMs -> read timeout", g -> {
            same(60000, client(g).readTimeoutMs, "default read timeout the game uses");

            g.replyAfter(2000, 200, answer("too late"));
            LlmClient llm = client(g);
            llm.readTimeoutMs = 300;
            long start = System.nanoTime();
            Throwable e = fails(() -> llm.askNow("hi"), "askNow to a server slower than the timeout");
            long tookMs = (System.nanoTime() - start) / 1000000;
            check(e instanceof SocketTimeoutException, "expected a SocketTimeoutException, got " + e);
            check(tookMs < 1500, "gave up after " + tookMs + " ms, the 300 ms timeout wasn't used");
        });
    }

    // ------------------------------------------------------------------------------------------
    // ask(): background thread and callbacks
    // ------------------------------------------------------------------------------------------

    static {
        test("ask: not configured -> onError at once on the caller's thread, nothing sent", g -> {
            Calls calls = new Calls();
            new LlmClient(config(null, MODEL, PROMPT), g.endpoint()).ask("hi", calls);

            Calls.Call call = calls.calls.poll(); // no waiting: it must already be there
            check(call != null, "onError should be called before ask() returns");
            check(!call.ok, "expected onError, got onResponse: " + call.text);
            same(Thread.currentThread(), call.thread, "thread of the callback");
            same("LLM not configured. Missing in " + CONFIG_FILE + ": apiKey", call.text, "message");
            calls.expectNoMore(200);
            same(0, g.count(), "requests that reached the server");
        });

        test("ask: the answer arrives through onResponse on the daemon worker thread", g -> {
            g.reply(answer("<SPAWN:pirates:40>"));
            Calls calls = new Calls();
            client(g).ask("hi", calls);

            Calls.Call call = calls.next();
            check(call.ok, "expected onResponse, got onError: " + call.text);
            same("<SPAWN:pirates:40>", call.text, "reply");
            same("KirizsKeve-LlmClient", call.thread.getName(), "callback thread name");
            check(call.thread.isDaemon(), "the worker thread must be a daemon so it never keeps the game running");
            check(call.thread != Thread.currentThread(), "the callback must not run on the caller's thread");
        });

        test("ask: returns at once while the request is still running", g -> {
            g.replyAfter(1500, 200, answer("<NONE>"));
            Calls calls = new Calls();
            long start = System.nanoTime();
            client(g).ask("hi", calls);
            long tookMs = (System.nanoTime() - start) / 1000000;
            check(tookMs < 500, "ask() blocked for " + tookMs + " ms - it must not wait for the server");
            same("<NONE>", calls.response(), "reply");
        });

        test("ask: exactly one callback per call", g -> {
            Calls calls = new Calls();
            client(g).ask("hi", calls);
            calls.response();
            calls.expectNoMore(300);
        });

        test("ask: HTTP error -> onError with the status, never onResponse", g -> {
            g.reply(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
            Calls calls = new Calls();
            client(g).ask("hi", calls);
            has(calls.error(), "Gemini API returned HTTP 503", "error message");
            calls.expectNoMore(300);
        });

        test("ask: unusable reply -> onError", g -> {
            g.reply("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
            Calls calls = new Calls();
            client(g).ask("hi", calls);
            has(calls.error(), "Gemini returned no answer", "error message");
        });

        test("ask: connection failure -> onError with a message", g -> {
            Calls calls = new Calls();
            new LlmClient(config(KEY, MODEL, PROMPT), closedEndpoint()).ask("hi", calls);
            String message = calls.error();
            check(message != null && !message.trim().isEmpty(), "error message is empty");
        });

        test("ask: requests run one at a time, in the order asked", g -> {
            for (int i = 1; i <= 5; i++) g.replyAfter(150, 200, answer("r" + i));
            LlmClient llm = client(g);
            Calls calls = new Calls();
            for (int i = 1; i <= 5; i++) llm.ask("m" + i, calls);

            for (int i = 1; i <= 5; i++) same("r" + i, calls.response(), "reply #" + i);
            for (int i = 0; i < 5; i++) same("m" + (i + 1), g.request(i).userText(), "user text of request #" + (i + 1));
            same(1, g.maxInFlight.get(), "requests at the server at the same time");
        });

        test("ask: a callback that throws doesn't stop later requests", g -> {
            LlmClient llm = client(g);
            llm.ask("first", new LlmClient.Callback() {
                @Override
                public void onResponse(String text) {
                    throw new RuntimeException("callback blew up");
                }

                @Override
                public void onError(String message) {
                    throw new RuntimeException("callback blew up");
                }
            });
            Calls calls = new Calls();
            llm.ask("second", calls);
            same("<NONE>", calls.response(), "reply to the request after the throwing callback");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Conversation history
    // ------------------------------------------------------------------------------------------

    static {
        test("history: the previous exchange is sent with the next request", g -> {
            g.reply(answer("  R1 \n"));
            LlmClient llm = client(g);
            exchange(llm, "first");
            exchange(llm, "second");
            same(Collections.singletonList("user: first"), turns(g.request(0)), "contents of request 1");
            same(Arrays.asList("user: first", "model: R1", "user: second"), turns(g.request(1)),
                    "contents of request 2 (the stored reply is the trimmed one)");
        });

        test("history: a failed request is not remembered", g -> {
            g.reply(500, "{}");
            LlmClient llm = client(g);
            Calls calls = new Calls();
            llm.ask("lost", calls);
            calls.error();
            exchange(llm, "kept");
            same(Collections.singletonList("user: kept"), turns(g.request(1)), "contents after a failed request");
        });

        test("history: a reply without an answer is not remembered", g -> {
            g.reply("{\"candidates\":[]}");
            LlmClient llm = client(g);
            Calls calls = new Calls();
            llm.ask("lost", calls);
            calls.error();
            exchange(llm, "kept");
            same(Collections.singletonList("user: kept"), turns(g.request(1)), "contents after an empty answer");
        });

        test("history: up to 20 messages are kept whole", g -> {
            LlmClient llm = client(g);
            conversation(g, llm, 10);
            exchange(llm, "q11");
            same(expectedTurns(1, 11), turns(g.last()), "contents of request 11");
        });

        test("history: past 20 messages the oldest exchange is dropped as a pair", g -> {
            LlmClient llm = client(g);
            conversation(g, llm, 11);
            exchange(llm, "q12");
            same(expectedTurns(2, 12), turns(g.last()), "contents of request 12");
        });

        test("history: a long conversation keeps the last 10 exchanges, starting with a user turn", g -> {
            LlmClient llm = client(g);
            conversation(g, llm, 25);
            exchange(llm, "q26");
            same(expectedTurns(16, 26), turns(g.last()), "contents of request 26");
        });

        test("history: clearHistory forgets the conversation", g -> {
            LlmClient llm = client(g);
            exchange(llm, "a");
            exchange(llm, "b");
            llm.clearHistory();
            exchange(llm, "c");
            same(Collections.singletonList("user: c"), turns(g.request(2)), "contents after clearHistory");
        });

        test("history: clearHistory waits for asks queued before it", g -> {
            // Called while "a" is still at the server: "a" must still be answered and then be forgotten
            g.replyAfter(300, 200, answer("A"));
            LlmClient llm = client(g);
            Calls first = new Calls(), second = new Calls();
            llm.ask("a", first);
            llm.clearHistory();
            llm.ask("b", second);
            same("A", first.response(), "reply to the ask made before clearHistory");
            second.response();
            same(Collections.singletonList("user: b"), turns(g.request(1)), "contents of the ask made after clearHistory");
        });

        test("history: clearHistory on a fresh client is harmless", g -> {
            LlmClient llm = client(g);
            llm.clearHistory();
            same("<NONE>", exchange(llm, "hi"), "reply");
            same(Collections.singletonList("user: hi"), turns(g.only()), "contents");
        });

        test("history: separate clients keep separate histories", g -> {
            LlmClient a = client(g), b = client(g);
            exchange(a, "for a");
            exchange(b, "for b");
            same(Collections.singletonList("user: for b"), turns(g.request(1)), "contents of client b's request");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        BasicConfigurator.configure(new NullAppender()); // LlmClient logs every request; keep the output to results
        String filter = args.length > 0 ? args[0] : null;

        int run = 0, failed = 0;
        long allStart = System.nanoTime();
        for (Map.Entry<String, Test> t : TESTS.entrySet()) {
            if (filter != null && !t.getKey().contains(filter)) continue;
            run++;
            long start = System.nanoTime();
            FakeGemini gemini = null;
            try {
                gemini = new FakeGemini();
                t.getValue().run(gemini);
                System.out.println("PASS   " + t.getKey() + "  (" + (System.nanoTime() - start) / 1000000 + " ms)");
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL   " + t.getKey());
                String detail = e instanceof AssertionError ? e.getMessage() : "unexpected " + e;
                System.out.println("       " + String.valueOf(detail).replace("\n", "\n       "));
                if (!(e instanceof AssertionError)) {
                    for (StackTraceElement frame : e.getStackTrace()) {
                        if (frame.getClassName().startsWith("KirizsKeve")) System.out.println("         at " + frame);
                    }
                }
            } finally {
                if (gemini != null) gemini.stop();
            }
        }

        System.out.println();
        System.out.println((run - failed) + "/" + run + " passed in " + (System.nanoTime() - allStart) / 1000000 + " ms");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------
    // Fake Gemini server
    // ------------------------------------------------------------------------------------------

    /** Stands in for Gemini on localhost: records every request and answers from a queue of replies. */
    private static class FakeGemini {
        /** Used when no reply was queued. */
        static final Reply DEFAULT = new Reply(0, 200,
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"<NONE>\"}]},\"finishReason\":\"STOP\"}]}");

        final HttpServer server;
        final ExecutorService threads = Executors.newCachedThreadPool(); // several threads, so overlapping requests would show
        final List<Request> received = Collections.synchronizedList(new ArrayList<Request>());
        final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        FakeGemini() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext("/", this::handle);
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/";
        }

        void reply(String body) {
            replyAfter(0, 200, body);
        }

        void reply(int status, String body) {
            replyAfter(0, status, body);
        }

        void replyAfter(long delayMs, int status, String body) {
            replies.add(new Reply(delayMs, status, body));
        }

        int count() {
            return received.size();
        }

        /** The index-th request (0-based), waiting for it to arrive. */
        Request request(int index) throws InterruptedException {
            long end = System.currentTimeMillis() + WAIT_MS;
            while (received.size() <= index) {
                if (System.currentTimeMillis() > end) {
                    throw new AssertionError("request #" + (index + 1) + " never reached the fake server (got " + received.size() + ")");
                }
                Thread.sleep(5);
            }
            return received.get(index);
        }

        Request only() throws InterruptedException {
            Request r = request(0);
            same(1, count(), "requests that reached the server");
            return r;
        }

        Request last() {
            check(!received.isEmpty(), "no request reached the fake server");
            return received.get(received.size() - 1);
        }

        void stop() {
            server.stop(0);
            threads.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                Reply reply;
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    byte[] raw = exchange.getRequestBody().readAllBytes();
                    received.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders(), raw));
                    reply = replies.poll();
                    if (reply == null) reply = DEFAULT;
                    if (reply.delayMs > 0) Thread.sleep(reply.delayMs);
                } finally {
                    // Before answering, so the client's next request can't overlap with this one by accident
                    inFlight.decrementAndGet();
                }

                byte[] out = reply.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(reply.status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) exchange.getResponseBody().write(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }
    }

    private static class Reply {
        final long delayMs;
        final int status;
        final String body;

        Reply(long delayMs, int status, String body) {
            this.delayMs = delayMs;
            this.status = status;
            this.body = body;
        }
    }

    /** One request as the fake server saw it. */
    private static class Request {
        final String method;
        final URI uri;
        final Headers headers;
        final String body;
        final boolean validUtf8;

        Request(String method, URI uri, Headers headers, byte[] raw) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = new String(raw, StandardCharsets.UTF_8);
            this.validUtf8 = isValidUtf8(raw);
        }

        JSONObject json() throws Exception {
            return new JSONObject(body);
        }

        /** Text of the newest user turn, i.e. what was just asked. */
        String userText() throws Exception {
            JSONArray contents = json().getJSONArray("contents");
            return contents.getJSONObject(contents.length() - 1).getJSONArray("parts").getJSONObject(0).getString("text");
        }
    }

    /** Collects callbacks with the thread each one ran on. */
    private static class Calls implements LlmClient.Callback {
        static class Call {
            final boolean ok;
            final String text;
            final Thread thread;

            Call(boolean ok, String text) {
                this.ok = ok;
                this.text = text;
                this.thread = Thread.currentThread();
            }
        }

        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();

        @Override
        public void onResponse(String text) {
            calls.add(new Call(true, text));
        }

        @Override
        public void onError(String message) {
            calls.add(new Call(false, message));
        }

        Call next() throws InterruptedException {
            Call call = calls.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            if (call == null) throw new AssertionError("no callback within " + WAIT_MS / 1000 + " s");
            return call;
        }

        String response() throws InterruptedException {
            Call call = next();
            if (!call.ok) throw new AssertionError("expected onResponse, got onError: " + call.text);
            return call.text;
        }

        String error() throws InterruptedException {
            Call call = next();
            if (call.ok) throw new AssertionError("expected onError, got onResponse: " + call.text);
            return call.text;
        }

        void expectNoMore(long waitMs) throws InterruptedException {
            Call extra = calls.poll(waitMs, TimeUnit.MILLISECONDS);
            if (extra != null) {
                throw new AssertionError("unexpected extra " + (extra.ok ? "onResponse" : "onError") + ": " + show(extra.text));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static JSONObject config(String key, String model, String prompt) throws Exception {
        JSONObject cfg = new JSONObject();
        if (key != null) cfg.put("apiKey", key);
        if (model != null) cfg.put("model", model);
        if (prompt != null) cfg.put("systemPrompt", prompt);
        return cfg;
    }

    private static LlmClient client(FakeGemini gemini) throws Exception {
        return new LlmClient(config(KEY, MODEL, PROMPT), gemini.endpoint());
    }

    /** An endpoint on a port nothing listens on. */
    private static String closedEndpoint() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/v1beta/models/";
    }

    /** A normal Gemini answer: one candidate with these text parts. */
    private static String answer(String... texts) throws Exception {
        return candidates(candidate(texts));
    }

    private static JSONObject candidate(String... texts) throws Exception {
        JSONArray parts = new JSONArray();
        for (String text : texts) parts.put(new JSONObject().put("text", text));
        return new JSONObject()
                .put("content", new JSONObject().put("role", "model").put("parts", parts))
                .put("finishReason", "STOP");
    }

    private static String candidates(JSONObject... candidates) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject c : candidates) list.put(c);
        return new JSONObject().put("candidates", list).toString();
    }

    /** ask() and wait for the answer. */
    private static String exchange(LlmClient llm, String text) throws InterruptedException {
        Calls calls = new Calls();
        llm.ask(text, calls);
        return calls.response();
    }

    /** n exchanges "q1"/"r1" ... "qn"/"rn". */
    private static void conversation(FakeGemini gemini, LlmClient llm, int n) throws Exception {
        for (int i = 1; i <= n; i++) {
            gemini.reply(answer("r" + i));
            same("r" + i, exchange(llm, "q" + i), "reply #" + i);
        }
    }

    /** "user: q<first>", "model: r<first>", ... up to the new question "user: q<next>". */
    private static List<String> expectedTurns(int first, int next) {
        List<String> turns = new ArrayList<>();
        for (int i = first; i < next; i++) {
            turns.add("user: q" + i);
            turns.add("model: r" + i);
        }
        turns.add("user: q" + next);
        return turns;
    }

    /** The request's contents as "role: text" lines. */
    private static List<String> turns(Request r) throws Exception {
        JSONArray contents = r.json().getJSONArray("contents");
        List<String> turns = new ArrayList<>();
        for (int i = 0; i < contents.length(); i++) {
            JSONObject turn = contents.getJSONObject(i);
            turns.add(turn.getString("role") + ": " + turn.getJSONArray("parts").getJSONObject(0).getString("text"));
        }
        return turns;
    }

    private static String systemPrompt(Request r) throws Exception {
        return r.json().getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    private static TreeSet<String> keys(JSONObject json) {
        TreeSet<String> keys = new TreeSet<>();
        for (Iterator<?> it = json.keys(); it.hasNext(); ) keys.add(String.valueOf(it.next()));
        return keys;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------------------------------

    private interface Code {
        void run() throws Exception;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\nexpected: " + show(expected) + "\nactual:   " + show(actual));
        }
    }

    private static void has(String text, String part, String what) {
        if (text == null || !text.contains(part)) {
            throw new AssertionError(what + " should contain " + show(part) + "\nactual: " + show(text));
        }
    }

    /** Runs code that must throw, and returns what it threw. */
    private static Throwable fails(Code code, String what) {
        try {
            code.run();
        } catch (Throwable e) {
            return e;
        }
        throw new AssertionError(what + ": expected an exception, but it returned normally");
    }

    /** Readable form for failure messages: strings quoted with escapes visible, long ones cut short. */
    private static String show(Object value) {
        if (!(value instanceof String)) return String.valueOf(value);
        String s = ((String) value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        if (s.length() > 300) s = s.substring(0, 300) + "... (" + ((String) value).length() + " chars)";
        return "\"" + s + "\"";
    }
}
