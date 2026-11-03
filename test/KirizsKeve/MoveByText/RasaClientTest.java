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
 * The "spacy:" cases check SpacyClient, which shares all of this (NluServerClient) and talks to the same format.
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
            same("http://127.0.0.1:5005", llm.url, "url");
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

        // spaCy: the same client with its own settings block, address and names

        test("spacy: provider \"spacy\" -> SpacyClient, and the example file's spacy block works", g -> {
            check(LlmBackend.fromConfig(new JSONObject().put("provider", " spaCy ")) instanceof SpacyClient, "provider spaCy");
            JSONObject example = LlmPromptTest.readConfig(Paths.get("data/config/llmdata.json.example"));
            check(example.has("spacy"), "the example file should have a spacy block");
            LlmBackend llm = LlmBackend.fromConfig(example.put("provider", "spacy"));
            check(llm instanceof SpacyClient && llm.isConfigured(), "the example's spacy block should give a ready SpacyClient");
        });

        test("spacy: defaults are localhost:5007, 30 s, \"spaCy NLU\"", g -> {
            SpacyClient llm = new SpacyClient(new JSONObject());
            check(llm.isConfigured(), "a client without settings should still be ready");
            same("http://127.0.0.1:5007", llm.url, "url");
            same(30000, llm.readTimeoutMs, "read timeout");
            same("spaCy NLU", llm.modelName(), "modelName()");
        });

        test("spacy: reads the spacy block, not the rasa one", g -> {
            JSONObject cfg = new JSONObject()
                    .put("rasa", new JSONObject().put("url", closedEndpoint()).put("minConfidence", 0.99))
                    .put("spacy", new JSONObject().put("url", g.endpoint() + "/model/parse"));
            g.reply(200, parse("spawn_fleet", 0.9, ent("size", "small"), ent("faction", "pirate")));
            same("<SPAWN:pirates:40>", new SpacyClient(cfg).askNow("spawn a small pirate fleet"), "reply");
            same("/model/parse", g.only().uri.getPath(), "path");
        });

        test("spacy: errors name spaCy and spacy/start-server.bat", g -> {
            Throwable e = fails(() -> spacyClient(closedEndpoint()).askNow("hi"), "closed port");
            check(e instanceof ConnectException, "expected ConnectException, got " + e);
            has(e.getMessage(), "spacy/start-server.bat", "message");
            has(e.getMessage(), "Can't connect to spaCy", "message");
            g.reply(500, "{}");
            same("spaCy returned HTTP 500: {}", fails(() -> spacyClient(g.endpoint()).askNow("hi"), "500").getMessage(),
                    "500 message");
        });

        test("spacy: loadedModel names the model folder", g -> {
            g.reply(200, "{\"model_file\":\"models/nlu-20261103-120000\"}");
            same("spacy/nlu-20261103-120000", spacyClient(g.endpoint()).loadedModel(), "model");
            same("/status", g.only().uri.getPath(), "path");
        });

        test("spacy rule: a sentence read as cancel without cancel words doesn't wipe the request", g -> {
            g.reply(200, said("Spawn a pirate fleet.", "spawn_fleet", 0.99, ent("faction", "pirate")));
            g.reply(200, said("I beat a small one yesterday.", "cancel", 0.7));
            same("<SPAWN:pirates:100>", spacyClient(g.endpoint()).askNow("Spawn a pirate fleet. I beat a small one yesterday."),
                    "story read as cancel");
            g.reply(200, said("Spawn a pirate fleet.", "spawn_fleet", 0.99, ent("faction", "pirate")));
            g.reply(200, said("Never mind.", "cancel", 0.95));
            same("<NONE>", spacyClient(g.endpoint()).askNow("Spawn a pirate fleet. Never mind."), "real cancel");
        });

        test("spacy rule: Rasa has no rules, the same answers still cancel there", g -> {
            g.reply(200, said("Spawn a pirate fleet.", "spawn_fleet", 0.99, ent("faction", "pirate")));
            g.reply(200, said("I beat a small one yesterday.", "cancel", 0.7));
            same("<NONE>", client(g, g.endpoint(), null).askNow("Spawn a pirate fleet. I beat a small one yesterday."),
                    "RasaClient");
        });

        test("spacy rule: a faction missed in a short command is looked up, not in long sentences", g -> {
            g.reply(200, said("a gigantic tri-tachyon fleet", "spawn_fleet", 0.99, ent("size", "gigantic")));
            same("<SPAWN:tritachyon:300>", spacyClient(g.endpoint()).askNow("a gigantic tri-tachyon fleet"), "short");
            String longer = "please spawn a gigantic fleet of tri-tachyon ships for me right now";
            g.reply(200, said(longer, "spawn_fleet", 0.99, ent("size", "gigantic")));
            same("<NONE>", spacyClient(g.endpoint()).askNow(longer), "12 words");
        });

        test("spacy rule: ordinary words marked as a faction are dropped, made-up names are kept", g -> {
            g.reply(200, said("a nearby faction should help Jangala", "request_help", 0.99,
                    ent("faction", "faction"), ent("target", "Jangala")));
            same("<HELP:nearby:100:Jangala>", spacyClient(g.endpoint()).askNow("a nearby faction should help Jangala"),
                    "\"faction\" dropped");
            g.reply(200, said("spawn a Klingon fleet", "spawn_fleet", 0.99, ent("faction", "Klingon")));
            same("<NONE>", spacyClient(g.endpoint()).askNow("spawn a Klingon fleet"), "Klingon");
            g.reply(200, said("spawn a klingon fleet", "spawn_fleet", 0.99, ent("faction", "klingon")));
            same("<NONE>", spacyClient(g.endpoint()).askNow("spawn a klingon fleet"), "klingon, lowercase");
        });

        test("spacy rule split: \"spawn X and send Y\" goes to the server as two pieces", g -> {
            g.reply(200, said("spawn a small pirate patrol", "spawn_fleet", 0.99, ent("size", "small"), ent("faction", "pirate")));
            g.reply(200, said("send a convoy to Askonia", "trade_convoy", 0.99, ent("destination", "Askonia")));
            same("<SPAWN:pirates:40><TRADE:independent:100:Askonia>",
                    spacyClient(g.endpoint()).askNow("spawn a small pirate patrol and send a convoy to Askonia"), "reply");
            same("spawn a small pirate patrol", new JSONObject(g.request(0).body).getString("text"), "first piece");
            same("send a convoy to Askonia", new JSONObject(g.request(1).body).getString("text"), "second piece");
        });

        test("spacy rule places: ordinary words marked as places are dropped, known places kept", g -> {
            g.reply(200, plain(said("Context: help Jangala", "request_help", 0.99, ent("target", "Context"),
                    ent("target", "Jangala")), "Context", "Jangala"));
            same("<HELP:nearby:100:Jangala>", spacyClient(g.endpoint()).askNow("Context: help Jangala"), "reply");
        });

        test("spacy rule roles: the place's role follows a single intent", g -> {
            g.reply(200, said("backup for Chicomoztoc", "request_help", 0.99, ent("destination", "Chicomoztoc")));
            same("<HELP:nearby:100:Chicomoztoc>", spacyClient(g.endpoint()).askNow("backup for Chicomoztoc"), "help");
            g.reply(200, said("send pirates to Asharu", "spawn_fleet", 0.99, ent("faction", "pirates"), ent("target", "Asharu")));
            same("<SPAWN:pirates:100>", spacyClient(g.endpoint()).askNow("send pirates to Asharu"), "spawn");
            g.reply(200, said("freighters for Jangala", "trade_convoy", 0.99, ent("target", "Jangala")));
            same("<TRADE:independent:100:Jangala>", spacyClient(g.endpoint()).askNow("freighters for Jangala"), "trade");
        });

        test("spacy rule size: a size missed in a short command is looked up", g -> {
            g.reply(200, said("a gigantic tri-tachyon fleet", "spawn_fleet", 0.99, ent("faction", "tri-tachyon")));
            same("<SPAWN:tritachyon:300>", spacyClient(g.endpoint()).askNow("a gigantic tri-tachyon fleet"), "reply");
        });

        test("spacy rule count: a count above 10 is a size", g -> {
            g.reply(200, said("SPAWN PIRATES 200", "spawn_fleet", 0.99, ent("faction", "PIRATES"), ent("count", "200")));
            same("<SPAWN:pirates:200>", spacyClient(g.endpoint()).askNow("SPAWN PIRATES 200"), "reply");
        });

        test("spacy rules: spacy.rules picks them; \"none\" reads the model's answer as it is", g -> {
            same(new java.util.LinkedHashSet<>(SpacyClient.ALL_RULES), SpacyClient.readRules("all"), "all");
            same(new java.util.LinkedHashSet<>(SpacyClient.DEFAULT_RULES), SpacyClient.readRules(null), "default");
            same(new java.util.LinkedHashSet<>(java.util.Arrays.asList("cancel", "size")),
                    SpacyClient.readRules(new JSONArray().put("Cancel").put("size").put("nonsense")), "list");
            same(java.util.Collections.singleton("count"), SpacyClient.readRules(" count "), "text");
            JSONObject cfg = new JSONObject().put("spacy", new JSONObject().put("url", g.endpoint()).put("rules", "none"));
            g.reply(200, said("a gigantic tri-tachyon fleet", "spawn_fleet", 0.99, ent("size", "gigantic")));
            same("<NONE>", new SpacyClient(cfg).askNow("a gigantic tri-tachyon fleet"), "no faction lookup");
        });

        test("spacy: the answer arrives on its own daemon worker thread", g -> {
            g.reply(200, parse("request_help", 0.9, ent("target", "Jangala")));
            Calls calls = new Calls();
            spacyClient(g.endpoint()).ask("Jangala needs help", calls);
            Calls.Call call = calls.next();
            check(call.ok, "expected onResponse, got " + call.text);
            same("<HELP:nearby:100:Jangala>", call.text, "reply");
            same("KirizsKeve-SpacyClient", call.thread.getName(), "thread name");
            check(call.thread.isDaemon(), "worker must be a daemon thread");
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

    private static SpacyClient spacyClient(String url) throws Exception {
        return new SpacyClient(new JSONObject().put("spacy", new JSONObject().put("url", url)));
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

    /** A parse of the given sentence, with an intent ranking: the intent first, then out_of_scope. */
    private static String said(String text, String intent, double confidence, String[]... entities) {
        try {
            return new JSONObject(parse(intent, confidence, entities)).put("text", text)
                    .put("intent_ranking", new JSONArray()
                            .put(new JSONObject().put("name", intent).put("confidence", confidence))
                            .put(new JSONObject().put("name", "out_of_scope").put("confidence", 1 - confidence)))
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The parse with "plain_word": true on the entities with these values, as the server's rules mark them. */
    private static String plain(String parse, String... values) {
        try {
            JSONObject p = new JSONObject(parse);
            JSONArray entities = p.getJSONArray("entities");
            for (int i = 0; i < entities.length(); i++) {
                JSONObject e = entities.getJSONObject(i);
                if (java.util.Arrays.asList(values).contains(e.optString("value"))) e.put("plain_word", true);
            }
            return p.toString();
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
