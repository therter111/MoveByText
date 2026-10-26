package KirizsKeve.MoveByText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-readable log file of one LlmPromptTest run, named after the date and time it started.
 * Starts with the summary and a table per group, then every problem, then every case in order, then the
 * system prompt that was used, so runs before and after a prompt change can be compared.
 * The whole file is rewritten after every case, so a run that is stopped halfway still leaves a full report
 * of the cases it got through.
 */
final class LlmTestReport {

    /** Long lines are wrapped at about this many characters. */
    private static final int WIDTH = 100;
    /** Lines up with the text after "PASS   ". */
    private static final String PAD = "       ";
    /** Lines up with the text after "Expected:  ". */
    private static final String ROW_PAD = PAD + "           ";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Indexes into the count arrays. */
    private static final int CASES = 0, PASSED = 1, FLAKY = 2, FAILED = 3, ERRORS = 4;

    private final Path file;
    private final String header;
    private final String systemPrompt;
    private final LocalDateTime started = LocalDateTime.now();
    private LocalDateTime finished;
    private final Map<String, int[]> groups = new LinkedHashMap<String, int[]>();
    private final int[] total = new int[5];
    private final StringBuilder problems = new StringBuilder();
    private final StringBuilder cases = new StringBuilder();
    private String lastGroup;
    private boolean writeFailed;

    /**
     * @param provider        put in the file name so each provider's runs are easy to tell apart (e.g. "koboldcpp"),
     *                        or null for the plain name Gemini runs have always used
     * @param groupOfEachCase the group of every case that will run, in order
     * @param filter          the word the cases were picked by, or null when all of them run
     * @param allCases        how many cases there are without the filter
     */
    LlmTestReport(Path dir, String provider, String model, String systemPrompt, String filter, List<String> groupOfEachCase,
                  int allCases) throws IOException {
        Files.createDirectories(dir);
        String name = "llm-prompt-test_" + (provider == null ? "" : provider + "_") + started.format(FILE_TIME)
                + (filter == null ? "" : "_" + filter.replaceAll("[^a-z0-9]+", "-")) + ".log";
        file = dir.resolve(name);
        this.systemPrompt = systemPrompt;

        for (String group : groupOfEachCase) {
            groups.computeIfAbsent(group, g -> new int[5])[CASES]++;
        }
        total[CASES] = groupOfEachCase.size();

        String selection = filter == null ? "all " + total[CASES]
                : total[CASES] + " of " + allCases + ", the ones with \"" + filter + "\" in their group or input";
        header = "Model:     " + model + "\n"
                + "Cases:     " + selection + "\n"
                + "Prompt:    " + systemPrompt.length() + " characters, fingerprint " + fingerprint(systemPrompt)
                + " (changes whenever the prompt does; the full text is at the end)\n";
        write();
    }

    Path file() {
        return file;
    }

    void pass(String group, String input, String reply) {
        add(group, PASSED, "PASS ", input, row("Reply:", reply(reply)));
    }

    /** A case whose first reply was wrong: FLAKY when the second one was right, FAIL when it was wrong too. */
    void retried(String group, String input, boolean secondPassed, String first, String second, String[] expected) {
        String details = row("Expected:", String.join("\nor ", expected))
                + row("1st reply:", reply(first))
                + row("2nd reply:", reply(second));
        add(group, secondPassed ? FLAKY : FAILED, secondPassed ? "FLAKY" : "FAIL ", input, details);
    }

    void error(String group, String input, Exception e) {
        // Gemini's error bodies are indented JSON; one line reads better here
        add(group, ERRORS, "ERROR", input, row("Error:", e.toString().replaceAll("\\s+", " ")));
    }

    void finish() {
        finished = LocalDateTime.now();
        write();
    }

    private void add(String group, int result, String label, String input, String details) {
        groups.get(group)[result]++;
        total[result]++;

        if (!group.equals(lastGroup)) {
            lastGroup = group;
            cases.append('\n').append(group).append('\n').append("-".repeat(group.length())).append("\n\n");
        }
        cases.append(label).append("  ").append(quote(input)).append('\n').append(details).append('\n');
        if (result != PASSED) {
            problems.append(label).append("  ").append(group).append('\n')
                    .append(PAD).append(quote(input)).append('\n').append(details).append('\n');
        }
        write();
    }

    private void write() {
        int done = total[PASSED] + total[FLAKY] + total[FAILED] + total[ERRORS];
        StringBuilder out = new StringBuilder();
        out.append("MoveByText LLM prompt test\n");
        out.append("==========================\n\n");
        out.append("Started:   ").append(started.format(TIME)).append('\n');
        if (finished != null) {
            out.append("Finished:  ").append(finished.format(TIME)).append(" (took ").append(duration(started, finished)).append(")\n");
        } else {
            out.append("Finished:  not yet, ").append(done).append(" of ").append(total[CASES]).append(" cases done at ")
                    .append(LocalDateTime.now().format(TIME)).append(" (still running, or stopped early)\n");
        }
        out.append(header);
        out.append("Requests:  ").append(LlmPromptTest.requestsSent).append(" sent, ")
                .append(LlmPromptTest.rateLimited
                        ? "never more than " + LlmPromptTest.MAX_REQUESTS_PER_MINUTE + " a minute (retries included)"
                        : "no rate limit (local server)")
                .append("\n\n");
        out.append("Result:    ").append(summary(total)).append("\n\n");

        out.append("By group\n");
        out.append("--------\n");
        out.append(" passed  flaky  failed  error  not run   group\n");
        for (Map.Entry<String, int[]> entry : groups.entrySet()) {
            int[] c = entry.getValue();
            out.append(String.format("%7s %6s %7s %6s %8s   %s\n", c[PASSED] + "/" + c[CASES], dash(c[FLAKY]),
                    dash(c[FAILED]), dash(c[ERRORS]), dash(notRun(c)), entry.getKey()));
        }
        out.append('\n');

        out.append("Problems\n");
        out.append("--------\n\n");
        out.append(problems.length() == 0 ? (finished != null ? "None.\n" : "None so far.\n") : problems);
        out.append('\n');

        out.append("All cases in order\n");
        out.append("------------------\n");
        out.append(cases.length() == 0 ? (finished != null ? "\nNone.\n" : "\nNone yet.\n") : cases);
        out.append('\n');

        out.append("System prompt used\n");
        out.append("------------------\n\n");
        out.append(wrap(systemPrompt, "")).append('\n');

        try {
            Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The console still has the results, so don't stop the run over it
            if (!writeFailed) System.out.println("Could not write the log file " + file.toAbsolutePath() + ": " + e);
            writeFailed = true;
        }
    }

    /** For example "161 of 184 passed (88%), 1 flaky, 21 failed, 1 error, 16 not run". */
    private static String summary(int[] c) {
        int done = c[CASES] - notRun(c);
        if (done == 0) return c[CASES] == 0 ? "no cases matched" : "no cases done yet";
        if (c[PASSED] == c[CASES]) return "all " + c[CASES] + " passed";

        StringBuilder s = new StringBuilder();
        s.append(c[PASSED]).append(" of ").append(done).append(" passed (").append(Math.round(c[PASSED] * 100.0 / done)).append("%)");
        if (c[FLAKY] > 0) s.append(", ").append(c[FLAKY]).append(" flaky");
        if (c[FAILED] > 0) s.append(", ").append(c[FAILED]).append(" failed");
        if (c[ERRORS] > 0) s.append(", ").append(c[ERRORS]).append(c[ERRORS] == 1 ? " error" : " errors");
        if (notRun(c) > 0) s.append(", ").append(notRun(c)).append(" not run");
        return s.toString();
    }

    private static int notRun(int[] c) {
        return c[CASES] - c[PASSED] - c[FLAKY] - c[FAILED] - c[ERRORS];
    }

    private static String dash(int count) {
        return count == 0 ? "-" : String.valueOf(count);
    }

    /** One labelled line under a case, such as "Expected:  <SPAWN:pirates:100>". */
    private static String row(String label, String text) {
        return PAD + String.format("%-11s", label) + wrap(text, ROW_PAD) + "\n";
    }

    private static String reply(String reply) {
        if (reply.trim().isEmpty()) return "(empty reply)";
        return reply + (LlmPromptTest.canonical(reply) == null ? "   (not only command tags)" : "");
    }

    /** The input in quotes, so spaces at its start or end can be seen. */
    private static String quote(String input) {
        return "\"" + wrap(input, PAD) + "\"";
    }

    /**
     * Breaks text into lines of about WIDTH characters, starting each new line with indent.
     * Keeps the text's own line breaks and runs of spaces, since some cases test exactly those.
     */
    private static String wrap(String text, String indent) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n').append(indent);
            int column = indent.length();
            boolean lineStart = true;
            for (String word : lines[i].split(" ", -1)) {
                if (!lineStart) {
                    if (!word.isEmpty() && column + 1 + word.length() > WIDTH) {
                        out.append('\n').append(indent);
                        column = indent.length();
                    } else {
                        out.append(' ');
                        column++;
                    }
                }
                out.append(word);
                column += word.length();
                lineStart = false;
            }
        }
        return out.toString();
    }

    /** Short hash of the prompt, to tell at a glance whether two logs used the same one. */
    private static String fingerprint(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private static String duration(LocalDateTime from, LocalDateTime to) {
        long seconds = Duration.between(from, to).getSeconds();
        return seconds >= 60 ? seconds / 60 + " min " + seconds % 60 + " s" : seconds + " s";
    }
}
