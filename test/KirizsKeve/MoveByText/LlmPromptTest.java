package KirizsKeve.MoveByText;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Live test of the LLM part only: sends player sentences to Gemini with the real system prompt
 * from data/config/llmdata.json and checks the command tags that come back. Nothing in the game is touched.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or pass the path to llmdata.json as
 * the first argument. Uses real API calls, so it needs the key in llmdata.json and a network connection.
 * Exits with code 1 if any case fails.
 */
public class LlmPromptTest {

    /** Pause between requests so the free tier's requests-per-minute limit isn't hit. */
    private static final long DELAY_MS = 4000;
    private static final long RETRY_DELAY_MS = 20000;

    /** Any reply must be made of these tags and nothing else. */
    private static final String VALID_REPLY =
            "(<SPAWN:[a-z_]+:\\d+>|<TRADE:[a-z_]+:\\d+:[^:>]+(:[^:>]+)?>)+|<NONE>";

    private static final List<String[]> CASES = new ArrayList<String[]>();

    private static void expect(String input, String... acceptedReplies) {
        String[] c = new String[acceptedReplies.length + 1];
        c[0] = input;
        System.arraycopy(acceptedReplies, 0, c, 1, acceptedReplies.length);
        CASES.add(c);
    }

    static {
        // SPAWN
        expect("summon a massive hegemony armada", "<SPAWN:hegemony:300>");
        expect("I want to fight a few tri-tachyon scouts", "<SPAWN:tritachyon:50>");
        expect("bring a standard luddic path task force", "<SPAWN:luddic_path:150>");

        // TRADE, destination only
        expect("send a medium trade convoy to Corvus", "<TRADE:independent:50:Corvus>");
        expect("a single hegemony trader should go to Chicomoztoc", "<TRADE:hegemony:20:Chicomoztoc>");
        expect("huge sindrian diktat supply run to the Askonia system", "<TRADE:sindrian_diktat:100:Askonia>");

        // TRADE with a start point - destination comes first
        expect("send a persean league convoy from Kazeron to Jangala", "<TRADE:persean:50:Jangala:Kazeron>");
        expect("a small independent trader out of Asharu heading to Sindria", "<TRADE:independent:20:Sindria:Asharu>");

        // Several commands in one message
        expect("spawn a small pirate patrol and send a convoy to Askonia",
                "<SPAWN:pirates:50><TRADE:independent:50:Askonia>");

        // Not a command
        expect("what's the best weapon for a frigate?", "<NONE>");
        expect("tell me about the hegemony", "<NONE>");
    }

    public static void main(String[] args) throws Exception {
        Path config = Paths.get(args.length > 0 ? args[0] : "data/config/llmdata.json");
        LlmClient llm = new LlmClient(readConfig(config));
        if (!llm.isConfigured()) {
            System.out.println("LLM not configured, check " + config.toAbsolutePath());
            System.exit(1);
        }

        int failed = 0;
        for (int i = 0; i < CASES.size(); i++) {
            String[] c = CASES.get(i);
            String input = c[0];
            if (i > 0) Thread.sleep(DELAY_MS);

            String reply;
            try {
                reply = ask(llm, input);
            } catch (Exception e) {
                failed++;
                System.out.println("ERROR  \"" + input + "\"\n       " + e.getMessage());
                continue;
            }

            // The model sometimes puts whitespace or newlines between tags; the game's parser doesn't care
            String normalized = reply.replaceAll(">\\s+<", "><").trim();
            boolean valid = normalized.matches(VALID_REPLY);
            boolean expected = false;
            for (int a = 1; a < c.length; a++) {
                if (c[a].equals(normalized)) expected = true;
            }

            if (valid && expected) {
                System.out.println("PASS   \"" + input + "\" -> " + normalized);
            } else {
                failed++;
                System.out.println("FAIL   \"" + input + "\"");
                System.out.println("       got:      " + reply);
                System.out.println("       expected: " + join(c));
                if (!valid) System.out.println("       (reply isn't only command tags - the game would still try to parse it)");
            }
        }

        System.out.println();
        System.out.println((CASES.size() - failed) + "/" + CASES.size() + " passed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** One retry on rate limits / overload, which the free tier hits often. */
    private static String ask(LlmClient llm, String input) throws Exception {
        try {
            return llm.askNow(input);
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            if (!msg.contains("HTTP 429") && !msg.contains("HTTP 503")) throw e;
            System.out.println("       (rate limited or overloaded, retrying in " + RETRY_DELAY_MS / 1000 + "s)");
            Thread.sleep(RETRY_DELAY_MS);
            return llm.askNow(input);
        }
    }

    /** llmdata.json has '#' comment lines, which plain org.json may not accept, so drop them first. */
    private static JSONObject readConfig(Path path) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.trim().startsWith("#")) sb.append(line).append('\n');
        }
        return new JSONObject(sb.toString());
    }

    private static String join(String[] c) {
        StringBuilder sb = new StringBuilder();
        for (int a = 1; a < c.length; a++) {
            if (a > 1) sb.append("  or  ");
            sb.append(c[a]);
        }
        return sb.toString();
    }
}
