"""Cross-validation of the Rasa NLU model, one process per part.

Splits data/nlu.yml into 10 parts with the same mix of intents in each. For every part it trains a model on the other
9 parts and tests it on that one. Each training and each test runs in its own "rasa" process, so memory is freed
between parts. Rasa's own "rasa test nlu --cross-validation" keeps every part in one process, which got slower with
each part and ran the PC out of memory.

Everything goes into results/cv-<date>-<time>/:
    config.yml, data/          the config and the full dataset, copied at the start
    full.log                   everything this script and Rasa printed, all parts
    summary.md, summary.json   scores per part, their mean and spread, and scores per intent over all parts
    part-01/ ... part-10/
        train/                 this part's training data: the other parts plus the lookup tables
        test.yml               the examples held out for this part
        model/model.tar.gz     the model trained for this part
        results/               Rasa's reports, mistakes and plots for this part
        part.log               what Rasa printed for this part
        summary.json           this part's scores and times

Run cross-validate.bat, or in the Rasa environment:
    python cross_validate.py                              new run with 10 parts
    python cross_validate.py --folds 5                    new run with 5 parts
    python cross_validate.py --resume results/cv-...      continue a stopped run, skipping the parts already done
"""

import argparse
import collections
import ctypes
import datetime
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
RESULTS = os.path.join(HERE, "results")
SEED = 42  # Same split every time for the same data

ENV = dict(
    os.environ,
    PYTHONUTF8="1",
    PYTHONIOENCODING="utf-8",
    TF_CPP_MIN_LOG_LEVEL="2",
    SQLALCHEMY_SILENCE_UBER_WARNING="1",
    # Each part trains on different data, so Rasa's training cache would only fill up with entries never reused
    RASA_MAX_CACHE_SIZE="0",
)

METRICS = [
    ("intent_accuracy", "Intent accuracy"),
    ("intent_macro_f1", "Intent F1 (macro)"),
    ("intent_weighted_f1", "Intent F1 (weighted)"),
    ("entity_micro_f1", "Entity F1 (micro)"),
    ("entity_macro_f1", "Entity F1 (macro)"),
]


class Log:
    """Writes to full.log, to the current part's part.log and to the console."""

    def __init__(self, path):
        self.full = open(path, "a", encoding="utf-8")
        self.part = None

    def write(self, text):
        for f in (self.full, self.part, sys.stdout):
            if f:
                f.write(text)
                f.flush()

    def say(self, message):
        self.write("[%s] %s\n" % (datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), message))


class _MemoryStatus(ctypes.Structure):
    _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
        (name, ctypes.c_ulonglong)
        for name in ("total", "available", "page_total", "page_free", "virtual_total", "virtual_free", "extended")
    ]


def free_ram_gb():
    """Free physical memory on this PC in GB, or None when it can't be read."""
    if os.name != "nt":
        return None
    status = _MemoryStatus(length=ctypes.sizeof(_MemoryStatus))
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        return None
    return status.available / 2 ** 30


def part_dir(run, n):
    return os.path.join(run, "part-%02d" % n)


def rel(path):
    """Short path from the rasa folder; absolute when outside it, where a "..\\.." path can pass Windows' 260-char limit."""
    short = os.path.relpath(os.path.abspath(path), HERE)
    return os.path.abspath(path) if short.startswith("..") else short


def read_examples(path):
    """(intent, example line) pairs from a nlu.yml written by generate_nlu.py, in file order."""
    pairs, intent = [], None
    for line in open(path, encoding="utf-8").read().split("\n"):
        m = re.match(r"^- intent: (.+)$", line)
        if m:
            intent = m.group(1).strip()
        elif line.startswith("    - "):
            pairs.append((intent, line))
    return pairs


def write_examples(path, pairs, intent_order):
    """Writes the example lines back unchanged, grouped under their intents."""
    by_intent = collections.defaultdict(list)
    for intent, line in pairs:
        by_intent[intent].append(line)
    out = ['version: "3.1"', "nlu:"]
    for intent in intent_order:
        if by_intent[intent]:
            out += ["- intent: " + intent, "  examples: |"] + by_intent[intent]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")


def make_parts(log, run, folds):
    """Splits the run's copy of the data into stratified parts and writes each part's train and test files."""
    from sklearn.model_selection import StratifiedKFold

    pairs = read_examples(os.path.join(run, "data", "nlu.yml"))
    intents = [intent for intent, _ in pairs]
    order = list(dict.fromkeys(intents))
    lookups = os.path.join(run, "data", "lookups.yml")
    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=SEED)
    for n, (train, test) in enumerate(splitter.split(intents, intents), 1):
        part = part_dir(run, n)
        os.makedirs(os.path.join(part, "train"))
        write_examples(os.path.join(part, "train", "nlu.yml"), [pairs[i] for i in train], order)
        shutil.copy(lookups, os.path.join(part, "train", "lookups.yml"))
        write_examples(os.path.join(part, "test.yml"), [pairs[i] for i in test], order)
    log.say("Split %d examples (%s) into %d parts of about %d"
            % (len(pairs), ", ".join("%s %d" % (i, intents.count(i)) for i in order), folds, len(pairs) // folds))


def run_rasa(log, args):
    """Runs one rasa command and copies its output to the logs. Returns (exit code, minutes, lowest free RAM)."""
    log.say("rasa " + " ".join(args))
    start, lowest = time.time(), free_ram_gb()
    # Text mode turns the progress bar's carriage returns into new lines, so the logs stay readable
    proc = subprocess.Popen([sys.executable, "-m", "rasa"] + args, cwd=HERE, env=ENV, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    for line in proc.stdout:
        log.write(line)
        free = free_ram_gb()
        if free is not None and (lowest is None or free < lowest):
            lowest = free
    code = proc.wait()
    return code, (time.time() - start) / 60, lowest


def _f1(report, key):
    return report[key]["f1-score"] if key in report else None


def read_scores(results):
    """The headline numbers from Rasa's intent and entity reports for one part."""
    intents = json.load(open(os.path.join(results, "intent_report.json"), encoding="utf-8"))
    scores = {
        "intent_accuracy": intents["accuracy"] if "accuracy" in intents else _f1(intents, "micro avg"),
        "intent_macro_f1": _f1(intents, "macro avg"),
        "intent_weighted_f1": _f1(intents, "weighted avg"),
    }
    path = os.path.join(results, "DIETClassifier_report.json")
    if os.path.exists(path):
        entities = json.load(open(path, encoding="utf-8"))
        scores["entity_micro_f1"] = entities["accuracy"] if "accuracy" in entities else _f1(entities, "micro avg")
        scores["entity_macro_f1"] = _f1(entities, "macro avg")
        scores["entities"] = {label: {"f1": v["f1-score"], "support": v["support"]}
                              for label, v in entities.items() if isinstance(v, dict) and "avg" not in label}
    return scores


def run_part(log, run, n, folds):
    """Trains and tests one part. Returns True when it finished."""
    part = part_dir(run, n)
    for leftover in ("model", "results"):  # From an earlier attempt that was stopped
        shutil.rmtree(os.path.join(part, leftover), ignore_errors=True)
    train_count = len(read_examples(os.path.join(part, "train", "nlu.yml")))
    test_count = len(read_examples(os.path.join(part, "test.yml")))
    log.part = open(os.path.join(part, "part.log"), "w", encoding="utf-8")
    try:
        free = free_ram_gb()
        log.say("Part %d of %d: training on %d examples, testing on %d%s"
                % (n, folds, train_count, test_count, ", %.1f GB RAM free" % free if free is not None else ""))
        code, train_minutes, low_train = run_rasa(log, [
            "train", "nlu", "--config", rel(os.path.join(run, "config.yml")), "--nlu", rel(os.path.join(part, "train")),
            "--out", rel(os.path.join(part, "model")), "--fixed-model-name", "model"])
        if code:
            log.say("Part %d: training failed (exit code %d)" % (n, code))
            return False
        code, test_minutes, low_test = run_rasa(log, [
            "test", "nlu", "--model", rel(os.path.join(part, "model", "model.tar.gz")),
            "--nlu", rel(os.path.join(part, "test.yml")), "--out", rel(os.path.join(part, "results")), "--successes"])
        if code:
            log.say("Part %d: testing failed (exit code %d)" % (n, code))
            return False

        lows = [x for x in (low_train, low_test) if x is not None]
        summary = dict(part=n, train_examples=train_count, test_examples=test_count,
                       train_minutes=round(train_minutes, 1), test_minutes=round(test_minutes, 1),
                       lowest_free_ram_gb=round(min(lows), 1) if lows else None, **read_scores(os.path.join(part, "results")))
        json.dump(summary, open(os.path.join(part, "summary.json"), "w", encoding="utf-8"), indent=2)
        log.say("Part %d done in %.0f min: intent accuracy %.3f, intent F1 (macro) %.3f, entity F1 (micro) %s"
                % (n, train_minutes + test_minutes, summary["intent_accuracy"], summary["intent_macro_f1"],
                   "%.3f" % summary["entity_micro_f1"] if summary.get("entity_micro_f1") is not None else "-"))
        return True
    finally:
        log.part.close()
        log.part = None


def _num(x, digits=3):
    return "-" if x is None else "%.*f" % (digits, x)


def pooled_intents(run, done):
    """Per-intent scores over the test examples of every finished part together."""
    from sklearn.metrics import classification_report

    truth, guess = [], []
    for n in done:
        for name in ("intent_successes.json", "intent_errors.json"):
            path = os.path.join(part_dir(run, n), "results", name)
            if os.path.exists(path):
                for r in json.load(open(path, encoding="utf-8")):
                    truth.append(r["intent"])
                    guess.append(r["intent_prediction"]["name"])
    if not truth:
        return {}
    labels = sorted(set(truth))
    report = classification_report(truth, guess, labels=labels, output_dict=True, zero_division=0)
    mistakes = collections.defaultdict(collections.Counter)
    for t, g in zip(truth, guess):
        if t != g:
            mistakes[t][g] += 1
    return {label: dict(precision=report[label]["precision"], recall=report[label]["recall"],
                        f1=report[label]["f1-score"], examples=int(report[label]["support"]),
                        mistaken_for=mistakes[label].most_common(3))
            for label in labels}


def write_summary(run, folds):
    """Writes summary.md and summary.json for the parts finished so far."""
    done = [n for n in range(1, folds + 1) if os.path.exists(os.path.join(part_dir(run, n), "summary.json"))]
    if not done:
        return None
    parts = [json.load(open(os.path.join(part_dir(run, n), "summary.json"), encoding="utf-8")) for n in done]

    overall = {}
    for key, _ in METRICS:
        values = [p[key] for p in parts if p.get(key) is not None]
        if values:
            overall[key] = dict(mean=statistics.mean(values), std=statistics.stdev(values) if len(values) > 1 else 0.0,
                                min=min(values), max=max(values))
    per_intent = pooled_intents(run, done)
    per_entity = {}
    for p in parts:
        for label, v in p.get("entities", {}).items():
            per_entity.setdefault(label, {"f1": [], "support": 0})
            per_entity[label]["f1"].append(v["f1"])
            per_entity[label]["support"] += v["support"]
    per_entity = {k: dict(mean_f1=statistics.mean(v["f1"]), examples=v["support"]) for k, v in sorted(per_entity.items())}

    md = ["# Rasa NLU cross-validation", "",
          "Run `%s`: %d of %d parts done. Each part trains a model on the other %d parts and tests it on its own "
          "examples, so every example is tested once by a model that never saw it."
          % (os.path.basename(run), len(done), folds, folds - 1), "",
          "## Overall", "", "| | Mean | Std | Min | Max |", "|---|---|---|---|---|"]
    for key, name in METRICS:
        if key in overall:
            o = overall[key]
            md.append("| %s | %s | %s | %s | %s |" % (name, _num(o["mean"]), _num(o["std"]), _num(o["min"]), _num(o["max"])))

    md += ["", "## Per part", "",
           "| Part | Train | Test | " + " | ".join(name for _, name in METRICS) + " | Train min | Test min | Lowest free RAM GB |",
           "|" + "---|" * (len(METRICS) + 6)]
    for p in parts:
        md.append("| %d | %d | %d | %s | %s | %s | %s |" % (
            p["part"], p["train_examples"], p["test_examples"], " | ".join(_num(p.get(k)) for k, _ in METRICS),
            _num(p["train_minutes"], 1), _num(p["test_minutes"], 1), _num(p.get("lowest_free_ram_gb"), 1)))

    if per_intent:
        md += ["", "## Per intent, all parts' test examples together", "",
               "| Intent | Precision | Recall | F1 | Examples | Most often mistaken for |", "|---|---|---|---|---|---|"]
        for label, v in per_intent.items():
            md.append("| %s | %s | %s | %s | %d | %s |" % (
                label, _num(v["precision"]), _num(v["recall"]), _num(v["f1"]), v["examples"],
                ", ".join("%s (%d)" % (g, c) for g, c in v["mistaken_for"]) or "-"))
    if per_entity:
        md += ["", "## Per entity, mean over parts", "", "| Entity | F1 | Examples |", "|---|---|---|"]
        for label, v in per_entity.items():
            md.append("| %s | %s | %d |" % (label, _num(v["mean_f1"]), v["examples"]))

    with open(os.path.join(run, "summary.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md) + "\n")
    json.dump(dict(run=os.path.basename(run), folds=folds, parts_done=done, overall=overall, per_part=parts,
                   per_intent=per_intent, per_entity=per_entity),
              open(os.path.join(run, "summary.json"), "w", encoding="utf-8"), indent=2)
    return overall


def main():
    parser = argparse.ArgumentParser(description="Cross-validates the Rasa NLU model, one process per part.")
    parser.add_argument("--folds", type=int, default=10, help="number of parts for a new run (default 10)")
    parser.add_argument("--resume", metavar="RUN_FOLDER", help="continue a stopped run, e.g. results/cv-20261027-153000")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if args.resume:
        run = os.path.abspath(args.resume)
        folds = len([d for d in os.listdir(run) if re.match(r"^part-\d+$", d)])
        if not folds:
            sys.exit("No parts in %s, start a new run instead" % run)
        log = Log(os.path.join(run, "full.log"))
        log.say("Resuming %s, %d parts" % (rel(run), folds))
    else:
        if args.folds < 2:
            sys.exit("--folds must be at least 2")
        folds = args.folds
        run = os.path.join(RESULTS, "cv-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
        os.makedirs(os.path.join(run, "data"))
        shutil.copy(os.path.join(HERE, "config.yml"), run)
        for name in ("nlu.yml", "lookups.yml"):
            shutil.copy(os.path.join(DATA, name), os.path.join(run, "data"))
        log = Log(os.path.join(run, "full.log"))
        log.say("New %d-part cross-validation in %s" % (folds, rel(run)))
        make_parts(log, run, folds)

    for n in range(1, folds + 1):
        if os.path.exists(os.path.join(part_dir(run, n), "summary.json")):
            log.say("Part %d already done, skipping it" % n)
            continue
        if not run_part(log, run, n, folds):
            write_summary(run, folds)
            log.say("Stopped at part %d. Continue with: cross-validate.bat --resume %s" % (n, rel(run)))
            return 1
        write_summary(run, folds)

    overall = write_summary(run, folds)
    log.say("All %d parts done. Intent accuracy %s +- %s, entity F1 (micro) %s +- %s. Summary: %s" % (
        folds, _num(overall["intent_accuracy"]["mean"]), _num(overall["intent_accuracy"]["std"]),
        _num(overall.get("entity_micro_f1", {}).get("mean")), _num(overall.get("entity_micro_f1", {}).get("std")),
        rel(os.path.join(run, "summary.md"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
