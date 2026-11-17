package KirizsKeve.MoveByText;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What compiled classes call, read from javap -c -p, for CodeRulesTest's rules about the code. Methods are told apart
 * by name only (overloads together), which is enough for "does anything here call X". Needs a JDK, like SandboxCheck.
 */
final class Bytecode {

    /** One class: what it implements and, per method name, the methods it calls ("owner.name"). */
    static final class ClassInfo {
        final String name;
        final Set<String> interfaces = new LinkedHashSet<>();
        final Map<String, Set<String>> calls = new LinkedHashMap<>();

        ClassInfo(String name) {
            this.name = name;
        }

        /** Every call in the class, from any method. */
        Set<String> allCalls() {
            Set<String> all = new LinkedHashSet<>();
            for (Set<String> c : calls.values()) all.addAll(c);
            return all;
        }
    }

    private static final Pattern CLASS = Pattern.compile("^(?:[a-z]+ )*(?:class|interface|enum) ([\\w.$]+)(?: extends [^{]*?)?"
            + "(?: implements ([^{]+))? \\{$");
    private static final Pattern METHOD = Pattern.compile("^  (?:[\\w.$<>\\[\\], ]+ )?([\\w.$]+)\\(.*\\)(?: throws .+)?;$");
    private static final Pattern CALL = Pattern.compile("// (?:Method|InterfaceMethod) (?:([\\w/$]+)\\.)?\"?([\\w$<>]+)\"?:");

    private Bytecode() {
    }

    /** Every class under the folder, by its name ("KirizsKeve.MoveByText.CommandConsole$1"). */
    static Map<String, ClassInfo> read(Path classes) throws Exception {
        List<String> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).map(Path::toString).sorted().collect(Collectors.toList());
        }
        ToolProvider javap = ToolProvider.findFirst("javap").orElseThrow(() ->
                new IllegalStateException("javap not found: run this with a JDK (17 or newer), not a JRE"));
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("-c", "-p"));
        args.addAll(files);
        if (javap.run(new PrintStream(out, true), new PrintStream(err, true), args.toArray(new String[0])) != 0) {
            throw new IllegalStateException("javap failed: " + err.toString(StandardCharsets.UTF_8));
        }

        Map<String, ClassInfo> classesByName = new LinkedHashMap<>();
        ClassInfo current = null;
        String method = null;
        for (String line : out.toString(StandardCharsets.UTF_8).split("\r?\n")) {
            Matcher c = CLASS.matcher(line);
            if (c.matches()) {
                current = new ClassInfo(c.group(1));
                classesByName.put(current.name, current);
                if (c.group(2) != null) {
                    for (String i : c.group(2).split(",")) current.interfaces.add(i.trim().replaceAll("<.*", ""));
                }
                method = null;
                continue;
            }
            if (current == null) continue;
            if (line.equals("  static {};")) {
                method = "<clinit>";
                continue;
            }
            Matcher m = METHOD.matcher(line);
            if (m.matches()) {
                // A constructor is listed by its full class name: call it <init>, as calls to it are
                method = m.group(1).contains(".") ? "<init>" : m.group(1);
                continue;
            }
            Matcher call = CALL.matcher(line);
            if (method != null && call.find()) {
                String owner = call.group(1) == null ? current.name : call.group(1).replace('/', '.');
                current.calls.computeIfAbsent(method, k -> new LinkedHashSet<>()).add(owner + "." + call.group(2));
            }
        }
        return classesByName;
    }
}
