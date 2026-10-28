"""Runs the hand-written LlmPromptTest cases against every model of a cross-validation run, and the full model.

Template cross-validation (cross_validate.py) only shows how well the model learns the template data. This shows how
it does on the 629 hand-written messages instead. For each part of the run, and then for the newest model in models/,
it starts a Rasa server on port 5006, runs LlmPromptTest against it, saves the outputs and stops the server. The test
gets its own config file, so data/config/llmdata.json is never touched. Port 5006 keeps clear of start-server.bat (5005).

Outputs, in the run folder:
    part-XX/handwritten/            report.log (same format as test/logs), console.txt, server.log, result.json
    handwritten-full-model/         the same for the newest model in models/
    handwritten-summary.md, .json   pass rates per model, per set (basic, advanced, extensive) and per group,
                                    and how many cases pass with all, none or some of the part models

Needs a JDK 17 or newer (JAVA_HOME, or the newest one in ~/.jdks) and the Starsector and mod jars the IntelliJ
project uses. Takes about 1.5 minutes per model. Run in the Rasa environment, in the rasa folder:
    python test_handwritten.py                                   newest results/cv-* run
    python test_handwritten.py results/cv-20261027-152032        a given run
    python test_handwritten.py --summary-only results/cv-...     rebuild the summary from the saved results
"""

import argparse
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
PORT = 5006
EXE = ".exe" if os.name == "nt" else ""
CORE = os.path.normpath(os.path.join(MOD, "..", "..", "starsector-core"))
MOD_LIBS = [os.path.normpath(os.path.join(MOD, "..", p)) for p in (
    "LazyLib/jars/LazyLib.jar", "MagicLib/jars/MagicLib.jar", "GraphicsLib/jars/Graphics.jar",
    "RolflectionLib-SS-main/jars/RolflectionLibJAR.jar")]
CLASSPATH = os.pathsep.join(sorted(glob.glob(os.path.join(CORE, "*.jar"))) + [p for p in MOD_LIBS if os.path.exists(p)])
TEST_FILES = ["LlmPromptTest", "LlmPromptAdvancedCases", "LlmPromptExtensiveCases", "LlmTestReport"]
SETS = ["basic", "advanced", "extensive"]
ENV = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8", TF_CPP_MIN_LOG_LEVEL="2",
           SQLALCHEMY_SILENCE_UBER_WARNING="1")


def say(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def find_jdk():
    """The bin folder of a JDK: JAVA_HOME, else the newest in ~/.jdks, else javac on the PATH."""
    homes = [os.environ["JAVA_HOME"]] if os.environ.get("JAVA_HOME") else []
    homes += sorted(glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "*")), reverse=True)
    for home in homes:
        if os.path.exists(os.path.join(home, "bin", "javac" + EXE)):
            return os.path.join(home, "bin")
    javac = shutil.which("javac")
    if javac:
        return os.path.dirname(javac)
    sys.exit("No JDK found. Set JAVA_HOME to a JDK 17 or newer.")


def compile_tests(jdk, classes):
    sources = glob.glob(os.path.join(MOD, "src", "**", "*.java"), recursive=True)
    sources += [os.path.join(MOD, "test", "KirizsKeve", "MoveByText", f + ".java") for f in TEST_FILES]
    argfile = os.path.join(classes, "..", "sources.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        # Forward slashes: a backslash inside quotes is an escape character in javac argument files
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in sources))
    subprocess.run([os.path.join(jdk, "javac" + EXE), "-encoding", "UTF-8", "-nowarn", "-d", classes,
                    "-cp", CLASSPATH, "@" + argfile], check=True)
    say("compiled %d source files" % len(sources))


def write_config(path):
    """The example config with the provider set to a Rasa server on PORT."""
    example = os.path.join(MOD, "data", "config", "llmdata.json.example")
    cfg = json.loads("".join(l for l in open(example, encoding="utf-8") if not l.strip().startswith("#")))
    cfg["provider"] = "rasa"
    cfg["rasa"] = {"url": "http://127.0.0.1:%d" % PORT, "token": "", "minConfidence": 0.5, "timeoutSeconds": 30}
    json.dump(cfg, open(path, "w", encoding="utf-8"), indent=2)


def status():
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/status" % PORT, timeout=3) as r:
            return json.load(r)
    except Exception:
        return None


def stop(server):
    # /T: the venv's python.exe starts the real interpreter as a child process, which has to go too
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(server.pid), "/T", "/F"], capture_output=True)
    else:
        server.kill()
    server.wait()
    for _ in range(30):
        if status() is None:
            return
        time.sleep(1)


def parse_console(text):
    """(set, group, PASS/FAIL/FLAKY/ERROR) for each case, in order."""
    cases, group = [], None
    for line in text.splitlines():
        m = re.match(r"^== (.+) ==$", line)
        if m:
            group = m.group(1)
            continue
        m = re.match(r"^(PASS|FAIL|FLAKY|ERROR)\s+\"", line)
        if m and group and group != "summary":
            s = "advanced" if group.startswith("advanced") else "extensive" if group.startswith("extensive") else "basic"
            cases.append((s, group, m.group(1)))
    return cases


def evaluate(label, model, out, jdk, classes, config):
    """Runs LlmPromptTest against one model; saves everything in out and returns the result."""
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    if status() is not None:
        raise RuntimeError("something is already listening on port %d" % PORT)
    server_log = open(os.path.join(out, "server.log"), "w", encoding="utf-8")
    start = time.time()
    server = subprocess.Popen([sys.executable, "-m", "rasa", "run", "--enable-api", "--interface", "127.0.0.1",
                               "--port", str(PORT), "--model", model], cwd=HERE, env=ENV,
                              stdout=server_log, stderr=subprocess.STDOUT)
    try:
        while True:
            s = status()
            if s and s.get("model_file"):
                break
            if server.poll() is not None or time.time() - start > 300:
                raise RuntimeError("the Rasa server for %s did not start, see %s" % (label, server_log.name))
            time.sleep(2)
        say("%s: server up in %.0f s" % (label, time.time() - start))
        started = time.time()
        proc = subprocess.run([os.path.join(jdk, "java" + EXE), "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
                               "-cp", classes + os.pathsep + CLASSPATH, "KirizsKeve.MoveByText.LlmPromptTest", config],
                              cwd=MOD, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=3600)
    finally:
        stop(server)
        server_log.close()

    console = proc.stdout + proc.stderr
    open(os.path.join(out, "console.txt"), "w", encoding="utf-8").write(console)
    m = re.search(r"Writing the results to (.+\.log)", console)
    if m and os.path.exists(m.group(1).strip()):
        shutil.move(m.group(1).strip(), os.path.join(out, "report.log"))  # out of test/logs, next to the rest
    cases = parse_console(console)
    m = re.search(r"^(\d+)/(\d+) passed", console, re.M)
    if not m or int(m.group(2)) != len(cases):
        raise RuntimeError("%s: could not read the test result, see %s" % (label, os.path.join(out, "console.txt")))
    result = dict(label=label, model=model, passed=int(m.group(1)), total=int(m.group(2)),
                  minutes=round((time.time() - started) / 60, 1), cases=cases)
    for s in SETS:
        selected = [c for c in cases if c[0] == s]
        result[s] = [sum(c[2] == "PASS" for c in selected), len(selected)]
    json.dump(result, open(os.path.join(out, "result.json"), "w", encoding="utf-8"), indent=1)
    say("%s: %d/%d passed (%.1f%%) - basic %d/%d, advanced %d/%d, extensive %d/%d" % (
        label, result["passed"], result["total"], pct(result["passed"], result["total"]),
        *result["basic"], *result["advanced"], *result["extensive"]))
    return result


def pct(a, b):
    return 100.0 * a / b if b else 0.0


def spread(values):
    return statistics.stdev(values) if len(values) > 1 else 0.0


def summarize(run, parts, full):
    def row(r):
        return "| %s | %s | %s | %s | %d/%d (%.1f%%) |" % (
            r["label"], *["%d/%d (%.0f%%)" % (r[s][0], r[s][1], pct(*r[s])) for s in SETS],
            r["passed"], r["total"], pct(r["passed"], r["total"]))

    md = ["# Cross-validation models on the hand-written test cases", "",
          "Each part's model (trained on all parts but its own) was run through `LlmPromptTest`: %d hand-written "
          "cases (basic, advanced, and long \"extensive\" ones). The full model, trained on all the template data, "
          "is shown for comparison. Rasa answers the same way every time, so a case either passes or fails."
          % parts[0]["total"],
          "", "## Pass rate per model", "", "| Model | Basic | Advanced | Extensive | Total |", "|---|---|---|---|---|"]
    md += [row(r) for r in parts]
    totals = [pct(r["passed"], r["total"]) for r in parts]
    md.append("| **Mean of the %d parts** | %s | **%.1f%% ± %.1f** |" % (len(parts), " | ".join(
        "%.1f%% ± %.1f" % (statistics.mean(pct(*r[s]) for r in parts), spread([pct(*r[s]) for r in parts]))
        for s in SETS), statistics.mean(totals), spread(totals)))
    if full:
        md.append(row(full))

    groups = []
    for _, g, _ in parts[0]["cases"]:
        if g not in groups:
            groups.append(g)
    md += ["", "## Pass rate per group", "",
           "| Group | Cases | Parts, mean | Parts, range | Full model |", "|---|---|---|---|---|"]
    for g in groups:
        n = sum(c[1] == g for c in parts[0]["cases"])
        rates = [pct(sum(c[1] == g and c[2] == "PASS" for c in r["cases"]), n) for r in parts]
        f = "%.0f%%" % pct(sum(c[1] == g and c[2] == "PASS" for c in full["cases"]), n) if full else "-"
        md.append("| %s | %d | %.0f%% | %.0f-%.0f%% | %s |" % (g, n, statistics.mean(rates), min(rates), max(rates), f))

    per_case = [sum(r["cases"][i][2] == "PASS" for r in parts) for i in range(len(parts[0]["cases"]))]
    hist = [per_case.count(k) for k in range(len(parts) + 1)]
    md += ["", "## How consistent the parts are", "",
           "Out of %d cases: %d pass with all %d models, %d fail with all of them, %d pass with some."
           % (len(per_case), hist[-1], len(parts), hist[0], len(per_case) - hist[-1] - hist[0]), "",
           "| Models that pass the case | " + " | ".join(str(k) for k in range(len(parts) + 1)) + " |",
           "|---|" + "---|" * (len(parts) + 1), "| Cases | " + " | ".join(str(h) for h in hist) + " |"]

    with open(os.path.join(run, "handwritten-summary.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md) + "\n")
    strip = lambda r: {k: v for k, v in r.items() if k != "cases"}
    json.dump(dict(parts=[strip(r) for r in parts], full_model=strip(full) if full else None,
                   passes_per_case=per_case),
              open(os.path.join(run, "handwritten-summary.json"), "w", encoding="utf-8"), indent=1)
    say("summary: " + os.path.relpath(os.path.join(run, "handwritten-summary.md"), HERE))


def main():
    parser = argparse.ArgumentParser(description="Runs the hand-written test cases against a cross-validation run.")
    parser.add_argument("run", nargs="?", help="cross-validation run folder (default: the newest results/cv-*)")
    parser.add_argument("--summary-only", action="store_true", help="rebuild the summary from the saved results")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    runs = sorted(glob.glob(os.path.join(HERE, "results", "cv-*")))
    run = os.path.abspath(args.run) if args.run else (runs[-1] if runs else None)
    if not run or not os.path.isdir(run):
        sys.exit("No cross-validation run found. Run cross-validate.bat first.")
    part_dirs = [d for d in sorted(glob.glob(os.path.join(run, "part-*")))
                 if os.path.exists(os.path.join(d, "model", "model.tar.gz"))]
    if not part_dirs:
        sys.exit("No trained parts in %s" % run)
    models = sorted(glob.glob(os.path.join(HERE, "models", "*.tar.gz")), key=os.path.getmtime)
    full_out = os.path.join(run, "handwritten-full-model")

    if args.summary_only:
        load = lambda d: json.load(open(os.path.join(d, "result.json"), encoding="utf-8"))
        parts = [load(os.path.join(d, "handwritten")) for d in part_dirs]
        full = load(full_out) if os.path.exists(os.path.join(full_out, "result.json")) else None
        summarize(run, parts, full)
        return 0

    say("Testing %d part models of %s%s" % (len(part_dirs), os.path.relpath(run, HERE),
                                             " and the full model " + os.path.basename(models[-1]) if models else ""))
    jdk = find_jdk()
    work = tempfile.mkdtemp(prefix="movebytext-handwritten-")
    try:
        classes = os.path.join(work, "classes")
        os.makedirs(classes)
        compile_tests(jdk, classes)
        config = os.path.join(work, "llmdata-rasa.json")
        write_config(config)
        parts = [evaluate(os.path.basename(d), os.path.join(d, "model", "model.tar.gz"),
                          os.path.join(d, "handwritten"), jdk, classes, config) for d in part_dirs]
        full = evaluate("full model", models[-1], full_out, jdk, classes, config) if models else None
    finally:
        shutil.rmtree(work, ignore_errors=True)
    summarize(run, parts, full)
    return 0


if __name__ == "__main__":
    sys.exit(main())
