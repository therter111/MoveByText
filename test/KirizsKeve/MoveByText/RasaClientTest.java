package KirizsKeve.MoveByText;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Offline tests for RasaClient: settings, the /model/parse request, turning the answer into tags, errors, /status
 * and the background thread. A fake Rasa server runs on localhost, so Rasa doesn't need to be installed.
 * RasaCommandBuilderTest covers the entity rules in detail.
 *
 * Run main() from IntelliJ (working directory = the mod folder). Pass a word to run only matching tests.
 */
public class RasaClientTest {

    private static final long WAIT_MS = 10000;

    private interface Test {
        void run(FakeRasa rasa) throws Exception;
    }

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();

    private static void test(String name, Test body) {
        TESTS.put(name, body);
    }

    static {
        test("provider: \"rasa\" -> RasaClient, and the example file's rasa block works", g -> {
            check(LlmBackend.fromConfig(new JSONObject().put("provider", " Rasa ")) instanceof RasaClient, "provider Rasa");
            JSONObject example = LlmPromptTest.readConfig(Paths.get("data/config/llmdata.json.example"));
            check(example.has("rasa"), "the example file should have a rasa block");
            LlmBackend llm = LlmBackend.fromConfig(example.put("provider", "rasa"));
            check(llm instanceof RasaClient && llm.isConfigured(), "the example's rasa block should give a ready RasaClient");
        });

        test("config: everything optional; defaults are localhost:5005, 0.5 and 30 s", g -> {
            RasaClient llm = new RasaClient(new JSONObject());
            check(llm.isConfigured(), "a client without settings should still be ready");
            same(30000, llm.readTimeoutMs, "read timeout");
            same("Rasa NLU", llm.modelName(), "modelName()");
        });

        test("config: url forms are normalized", g -> {
            String e = g.endpoint();
            String[] urls = {e.substring("http://".length()), e + "/", e + "/model/parse", e + "/model", "  " + e + " "};
            for (int i = 0; i < urls.length; i++) {
                client(g, urls[i], null).askNow("hi");
                same("/model/parse", g.request(i).uri.getPath(), "path for " + urls[i]);
            }
        });

        test("request: POST /model/parse with {\"text\": ...}, JSON, no token by default", g -> {
            client(g, g.endpoint(), null).askNow("spawn a \"small\" pirate fleet á");
            Request r = g.only();
            same("POST", r.method, "method");
            same("/model/parse", r.uri.getPath(), "path");
            same(null, r.uri.getRawQuery(), "query");
            same("application/json; charset=utf-8", r.contentType, "Content-Type");
            same("spawn a \"small\" pirate fleet á", new JSONObject(r.body).getString("text"), "text");
            same(Collections.singletonList("text"), keys(new JSONObject(r.body)), "body fields");
        });

        test("request: the token goes in the query string, URL-encoded", g -> {
            client(g, g.endpoint(), "s3cret&x=1").askNow("hi");
            same("token=s3cret%26x%3D1", g.only().uri.getRawQuery(), "query");
        });

        test("request: a long message is read one sentence at a time, then put back together", g -> {
            g.reply(200, parse("out_of_scope", 0.99));
            g.reply(200, parse("spawn_fleet", 0.97, ent("size", "tiny"), ent("faction", "tri-tachyon")));
            g.reply(200, parse("out_of_scope", 0.99));
            String reply = client(g, g.endpoint(), null).askNow(
                    "Long day at Corvus, the Hegemony fined me again. So spawn a tiny tri-tachyon fleet! Then I'll rest.");
            same("<SPAWN:tritachyon:15>", reply, "reply");
            same(3, g.received.size(), "requests (one per sentence)");
            same("So spawn a tiny tri-tachyon fleet!", new JSONObject(g.request(1).body).getString("text"), "second sentence");
        });

        test("reply: intent and entities become tags", g -> {
            g.reply(200, parse("spawn_fleet", 0.95, ent("size", "small"), ent("faction", "pirate")));
            same("<SPAWN:pirates:40>", client(g, g.endpoint(), null).askNow("spawn a small pirate fleet"), "reply");
        });

        test("reply: low confidence is NONE, the threshold comes from minConfidence", g -> {
            JSONObject cfg = new JSONObject().put("rasa", new JSONObject().put("url", g.endpoint()).put("minConfidence", 0.9));
            g.reply(200, parse("spawn_fleet", 0.8, ent("faction", "pirate")));
            same("<NONE>", new RasaClient(cfg).askNow("pirates?"), "reply at 0.8 with minConfidence 0.9");
        });

        test("reply: a body that isn't JSON -> an exception", g -> {
            g.reply(200, "<html>oops</html>");
            fails(() -> client(g, g.endpoint(), null).askNow("hi"), "askNow on HTML");
        });

        test("http: 401 and 409 get hints, other codes the plain message", g -> {
            RasaClient llm = client(g, g.endpoint(), null);
            g.reply(401, "{\"message\":\"User is not authenticated.\"}");
            has(fails(() -> llm.askNow("hi"), "401").getMessage(), "rasa.token", "401 message");
            g.reply(409, "{\"message\":\"No model loaded\"}");
            has(fails(() -> llm.askNow("hi"), "409").getMessage(), "rasa/train.bat", "409 message");
            g.reply(500, "{}");
            same("Rasa returned HTTP 500: {}", fails(() -> llm.askNow("hi"), "500").getMessage(), "500 message");
        });

        test("http: nothing listening -> ConnectException telling to start the server", g -> {
            Throwable e = fails(() -> client(g, closedEndpoint(), null).askNow("hi"), "closed port");
            check(e instanceof ConnectException, "expected ConnectException, got " + e);
            has(e.getMessage(), "rasa/start-server.bat", "message");
        });

        test("http: slower than the timeout -> SocketTimeoutException", g -> {
            g.replyAfter(2000, 200, parse("out_of_scope", 0.9));
            RasaClient llm = client(g, g.endpoint(), null);
            llm.readTimeoutMs = 300;
            Throwable e = fails(() -> llm.askNow("hi"), "slow server");
            check(e instanceof SocketTimeoutException, "expected SocketTimeoutException, got " + e);
        });

        test("loadedModel: GET /status, file name only", g -> {
            g.reply(200, "{\"model_file\":\"models/nlu-20261026-120000.tar.gz\",\"num_active_training_jobs\":0}");
            same("rasa/nlu-20261026-120000.tar.gz", client(g, g.endpoint(), "tok").loadedModel(), "model");
            same("GET", g.only().method, "method");
            same("/status", g.only().uri.getPath(), "path");
            same("token=tok", g.only().uri.getRawQuery(), "token");
        });

        test("ask: the answer arrives on the daemon worker thread; errors go to onError", g -> {
            g.reply(200, parse("request_help", 0.9, ent("target", "Jangala")));
            Calls calls = new Calls();
            RasaClient llm = client(g, g.endpoint(), null);
            llm.ask("Jangala needs help", calls);
            Calls.Call call = calls.next();
            check(call.ok, "expected onResponse, got " + call.text);
            same("<HELP:nearby:100:Jangala>", call.text, "reply");
            same("KirizsKeve-RasaClient", call.thread.getName(), "thread name");
            check(call.thread.isDaemon(), "worker must be a daemon thread");

            g.reply(500, "{}");
            llm.ask("hi", calls);
            Calls.Call error = calls.next();
            check(!error.ok, "expected onError");
            has(error.text, "HTTP 500", "error message");
        });

        test("describe: a readable summary for the game log", g -> {
            JSONObject p = new JSONObject(parse("spawn_fleet", 0.97, ent("faction", "pirate"), ent("size", "small", "2")));
            same("spawn_fleet 0.97: faction=pirate size=small#2", RasaClient.describe(p), "summary");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        BasicConfigurator.configure(new NullAppender());
        String filter = args.length > 0 ? args[0] : null;
        int run = 0, failed = 0;
        for (Map.Entry<String, Test> t : TESTS.entrySet()) {
            if (filter != null && !t.getKey().contains(filter)) continue;
            run++;
            FakeRasa rasa = null;
            try {
                rasa = new FakeRasa();
                t.getValue().run(rasa);
                System.out.println("PASS   " + t.getKey());
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL   " + t.getKey());
                String detail = e instanceof AssertionError ? e.getMessage() : "unexpected " + e;
                System.out.println("       " + String.valueOf(detail).replace("\n", "\n       "));
            } finally {
                if (rasa != null) rasa.stop();
            }
        }
        System.out.println();
        System.out.println((run - failed) + "/" + run + " passed");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------
    // Fake Rasa server
    // ------------------------------------------------------------------------------------------

    private static class FakeRasa {
        final HttpServer server;
        final ExecutorService threads = Executors.newCachedThreadPool();
        final List<Request> received = Collections.synchronizedList(new ArrayList<Request>());
        final Queue<Object[]> replies = new ConcurrentLinkedQueue<>();

        FakeRasa() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext("/", this::handle);
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reply(int status, String body) {
            replyAfter(0, status, body);
        }

        void replyAfter(long delayMs, int status, String body) {
            replies.add(new Object[]{delayMs, status, body});
        }

        Request request(int index) throws InterruptedException {
            long end = System.currentTimeMillis() + WAIT_MS;
            while (received.size() <= index) {
                if (System.currentTimeMillis() > end) throw new AssertionError("request #" + (index + 1) + " never arrived");
                Thread.sleep(5);
            }
            return received.get(index);
        }

        Request only() throws InterruptedException {
            Request r = request(0);
            same(1, received.size(), "requests that reached the server");
            return r;
        }

        void stop() {
            server.stop(0);
            threads.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                received.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), new String(raw, StandardCharsets.UTF_8)));
                Object[] reply = replies.poll();
                if (reply == null) reply = new Object[]{0L, 200, parse("out_of_scope", 0.9)};
                if ((Long) reply[0] > 0) Thread.sleep((Long) reply[0]);
                byte[] out = ((String) reply[2]).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders((Integer) reply[1], out.length == 0 ? -1 : out.length);
                if (out.length > 0) exchange.getResponseBody().write(out);
            } catch (Exception e) {
                // the client gave up (timeout test)
            } finally {
                exchange.close();
            }
        }
    }

    private static class Request {
        final String method;
        final URI uri;
        final String contentType;
        final String body;

        Request(String method, URI uri, String contentType, String body) {
            this.method = method;
            this.uri = uri;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static class Calls implements LlmClient.Callback {
        static class Call {
            final boolean ok;
            final String text;
            final Thread thread = Thread.currentThread();

            Call(boolean ok, String text) {
                this.ok = ok;
                this.text = text;
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
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static RasaClient client(FakeRasa rasa, String url, String token) throws Exception {
        JSONObject r = new JSONObject().put("url", url);
        if (token != null) r.put("token", token);
        return new RasaClient(new JSONObject().put("rasa", r));
    }

    private static String[] ent(String... fields) {
        return fields;
    }

    private static String parse(String intent, double confidence, String[]... entities) {
        try {
            JSONArray list = new JSONArray();
            int start = 0;
            for (String[] e : entities) {
                JSONObject o = new JSONObject().put("entity", e[0]).put("value", e[1]).put("start", start).put("end", start + e[1].length());
                if (e.length > 2) o.put("group", e[2]);
                list.put(o);
                start += e[1].length() + 1;
            }
            return new JSONObject().put("text", "t")
                    .put("intent", new JSONObject().put("name", intent).put("confidence", confidence))
                    .put("entities", list).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String closedEndpoint() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port;
    }

    private static List<String> keys(JSONObject json) {
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<?> it = json.keys(); it.hasNext(); ) keys.add(String.valueOf(it.next()));
        return keys;
    }

    private interface Code {
        void run() throws Exception;
    }

    private static Throwable fails(Code code, String what) {
        try {
            code.run();
        } catch (Throwable e) {
            return e;
        }
        throw new AssertionError(what + ": expected an exception");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\nexpected: " + expected + "\nactual:   " + actual);
        }
    }

    private static void has(String text, String part, String what) {
        if (text == null || !text.contains(part)) throw new AssertionError(what + " should contain " + part + "\nactual: " + text);
    }
}
