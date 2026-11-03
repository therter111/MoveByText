"""Runs the hand-written LlmPromptTest cases against every model of a spaCy cross-validation run, and the full model.

The spaCy twin of rasa/test_handwritten.py, and it uses that script's code: for each part of the run, and then for
the newest model in models/, it starts server.py on port 5008, runs LlmPromptTest against it, saves the outputs and
stops the server. The test gets its own config file, so data/config/llmdata.json is never touched. Port 5008 keeps
clear of start-server.bat (5007) and of the Rasa version (5006), so both can run at the same time.

Outputs, in the run folder (with --name X: handwritten-X instead of handwritten):
    part-XX/handwritten/            report.log (same format as test/logs), console.txt, server.log, result.json
    handwritten-full-model/         the same for the newest model in models/
    handwritten-summary.md, .json   pass rates per model, per set (basic, advanced, extensive) and per group,
                                    and how many cases pass with all, none or some of the part models

The rules (see README.md) can be chosen, to measure what each one adds; compare_runs.py compares two runs:
    --rules a,b         SpacyClient's rules (spacy.rules in llmdata.json), "all" or "none"; default: its defaults
    --server-rules a,b  server.py's rules, "all" or "none"; default: its defaults

Needs a JDK 17 or newer (JAVA_HOME, or the newest one in ~/.jdks) and the Starsector and mod jars the IntelliJ
project uses. Run in the spaCy environment, in the spacy folder:
    python test_handwritten.py                                   newest results/cv-* run
    python test_handwritten.py results/cv-20261103-152032        a given run
    python test_handwritten.py --name nocues --server-rules typos,numbers
    python test_handwritten.py --summary-only results/cv-...     rebuild the summary from the saved results
"""

import argparse
import glob
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
PORT = 5008


def load_rasa_script(name):
    """A script from the rasa folder as a module. Loaded by path: this script has the same name as the Rasa one."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("rasa_" + name, os.path.join(MOD, "rasa", name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rasa_th = load_rasa_script("test_handwritten")


def is_model(folder):
    return os.path.exists(os.path.join(folder, "meta.json"))


def folder_name(name):
    """Where one run's results go inside each part: handwritten, or handwritten-<name>."""
    return "handwritten" + ("-" + name if name else "")


def evaluate(label, model, out, jdk, classes, config, port=PORT, server_rules=None):
    server = [sys.executable, os.path.join(HERE, "server.py"), "--model", model, "--port", str(port)]
    if server_rules is not None:
        server += ["--rules", server_rules]
    return rasa_th.evaluate(label, model, out, jdk, classes, config, server_cmd=server, port=port, name="spaCy")


def write_config(path, port, rules):
    rasa_th.write_config(path, provider="spacy", port=port)
    if rules is not None:
        cfg = json.load(open(path, encoding="utf-8"))
        cfg["spacy"]["rules"] = rules
        json.dump(cfg, open(path, "w", encoding="utf-8"), indent=2)


def main():
    parser = argparse.ArgumentParser(description="Runs the hand-written test cases against a spaCy cross-validation run.")
    parser.add_argument("run", nargs="?", help="cross-validation run folder (default: the newest results/cv-*)")
    parser.add_argument("--summary-only", action="store_true", help="rebuild the summary from the saved results")
    parser.add_argument("--name", default="", help="name of this run's result folders (default: handwritten)")
    parser.add_argument("--rules", help="SpacyClient rules: a,b or all or none (default: its defaults)")
    parser.add_argument("--server-rules", help="server.py rules: a,b or all or none (default: its defaults)")
    parser.add_argument("--port", type=int, default=PORT, help="port for the test servers (default %d)" % PORT)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    runs = sorted(glob.glob(os.path.join(HERE, "results", "cv-*")))
    run = os.path.abspath(args.run) if args.run else (runs[-1] if runs else None)
    if not run or not os.path.isdir(run):
        sys.exit("No cross-validation run found. Run cross-validate.bat first.")
    part_dirs = [d for d in sorted(glob.glob(os.path.join(run, "part-*"))) if is_model(os.path.join(d, "model"))]
    if not part_dirs:
        sys.exit("No trained parts in %s" % run)
    models = sorted([d for d in glob.glob(os.path.join(HERE, "models", "nlu-*")) if is_model(d)], key=os.path.getmtime)
    folder = folder_name(args.name)
    full_out = os.path.join(run, folder.replace("handwritten", "handwritten-full-model", 1))
    summary = folder.replace("handwritten", "handwritten-summary", 1)

    if args.summary_only:
        load = lambda d: json.load(open(os.path.join(d, "result.json"), encoding="utf-8"))
        parts = [load(os.path.join(d, folder)) for d in part_dirs]
        full = load(full_out) if os.path.exists(os.path.join(full_out, "result.json")) else None
        rasa_th.summarize(run, parts, full, name="spaCy", filename=summary)
        return 0

    rasa_th.say("Testing %d part models of %s%s" % (len(part_dirs), os.path.relpath(run, HERE),
                                                     " and the full model " + os.path.basename(models[-1]) if models else ""))
    jdk = rasa_th.find_jdk()
    work = tempfile.mkdtemp(prefix="movebytext-handwritten-spacy-")
    try:
        classes = os.path.join(work, "classes")
        os.makedirs(classes)
        rasa_th.compile_tests(jdk, classes)
        config = os.path.join(work, "llmdata-spacy.json")
        write_config(config, args.port, args.rules)
        parts = [evaluate(os.path.basename(d), os.path.join(d, "model"), os.path.join(d, folder),
                          jdk, classes, config, args.port, args.server_rules) for d in part_dirs]
        full = evaluate("full model", models[-1], full_out, jdk, classes, config, args.port,
                        args.server_rules) if models else None
    finally:
        shutil.rmtree(work, ignore_errors=True)
    rasa_th.summarize(run, parts, full, name="spaCy", filename=summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
