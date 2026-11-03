"""Cross-validation of the spaCy NLU model, one process per part, laid out like rasa/cross_validate.py.

Splits rasa/data/nlu.yml into 10 parts with the same mix of intents in each, with the same code and seed as the Rasa
script, so a run made from the same data has exactly the same parts as a Rasa run. For every part it trains a model
on the other 9 parts (train.py) and tests it on that one (evaluate.py), each in its own process.

Everything goes into results/cv-<date>-<time>/:
    config.cfg, data/          the config and the full dataset, copied at the start
    full.log                   everything this script, train.py and evaluate.py printed, all parts
    summary.md, summary.json   scores per part, their mean and spread, and scores per intent over all parts
    part-01/ ... part-10/
        train/                 this part's training data: the other parts plus the lookup tables
        test.yml               the examples held out for this part
        model/                 the model trained for this part
        results/               evaluate.py's reports and mistakes for this part
        part.log               what was printed for this part
        summary.json           this part's scores and times

Run cross-validate.bat, or in the spaCy environment:
    python cross_validate.py                              new run with 10 parts
    python cross_validate.py --folds 5                    new run with 5 parts
    python cross_validate.py --resume results/cv-...      continue a stopped run, skipping the parts already done
"""

import argparse
import datetime
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
DATA = os.path.join(MOD, "rasa", "data")
RESULTS = os.path.join(HERE, "results")
ENV = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
TITLE = "spaCy NLU cross-validation"


def load_rasa_script(name):
    """A script from the rasa folder as a module. Loaded by path: this script has the same name as the Rasa one."""
    spec = importlib.util.spec_from_file_location("rasa_" + name, os.path.join(MOD, "rasa", name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rasa_cv = load_rasa_script("cross_validate")
METRICS = rasa_cv.METRICS + [("entity_group_micro_f1", "Entity+group F1 (micro)")]


def rel(path):
    """Short path from the spacy folder; absolute when outside it."""
    short = os.path.relpath(os.path.abspath(path), HERE)
    return os.path.abspath(path) if short.startswith("..") else short


def run_python(log, args):
    """Runs one of the scripts here and copies its output to the logs. Returns (exit code, minutes, lowest free RAM)."""
    log.say("python " + " ".join(args))
    start, lowest = time.time(), rasa_cv.free_ram_gb()
    proc = subprocess.Popen([sys.executable] + args, cwd=HERE, env=ENV, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    for line in proc.stdout:
        log.write(line)
        free = rasa_cv.free_ram_gb()
        if free is not None and (lowest is None or free < lowest):
            lowest = free
    code = proc.wait()
    return code, (time.time() - start) / 60, lowest


def run_part(log, run, n, folds):
    """Trains and tests one part. Returns True when it finished."""
    part = rasa_cv.part_dir(run, n)
    for leftover in ("model", "results"):  # From an earlier attempt that was stopped
        shutil.rmtree(os.path.join(part, leftover), ignore_errors=True)
    train_nlu = os.path.join(part, "train", "nlu.yml")
    train_count = len(rasa_cv.read_examples(train_nlu))
    test_count = len(rasa_cv.read_examples(os.path.join(part, "test.yml")))
    log.part = open(os.path.join(part, "part.log"), "w", encoding="utf-8")
    try:
        free = rasa_cv.free_ram_gb()
        log.say("Part %d of %d: training on %d examples, testing on %d%s"
                % (n, folds, train_count, test_count, ", %.1f GB RAM free" % free if free is not None else ""))
        code, train_minutes, low_train = run_python(log, [
            "train.py", "--nlu", rel(train_nlu), "--config", rel(os.path.join(run, "config.cfg")),
            "--out", rel(os.path.join(part, "model"))])
        if code:
            log.say("Part %d: training failed (exit code %d)" % (n, code))
            return False
        code, test_minutes, low_test = run_python(log, [
            "evaluate.py", "--model", rel(os.path.join(part, "model")), "--nlu", rel(os.path.join(part, "test.yml")),
            "--out", rel(os.path.join(part, "results"))])
        if code:
            log.say("Part %d: testing failed (exit code %d)" % (n, code))
            return False

        lows = [x for x in (low_train, low_test) if x is not None]
        scores = json.load(open(os.path.join(part, "results", "scores.json"), encoding="utf-8"))
        summary = dict(part=n, train_examples=train_count, test_examples=test_count,
                       train_minutes=round(train_minutes, 1), test_minutes=round(test_minutes, 1),
                       lowest_free_ram_gb=round(min(lows), 1) if lows else None, **scores)
        json.dump(summary, open(os.path.join(part, "summary.json"), "w", encoding="utf-8"), indent=2)
        log.say("Part %d done in %.0f min: intent accuracy %.3f, intent F1 (macro) %.3f, entity F1 (micro) %s"
                % (n, train_minutes + test_minutes, summary["intent_accuracy"], summary["intent_macro_f1"],
                   rasa_cv._num(summary.get("entity_micro_f1"))))
        return True
    finally:
        log.part.close()
        log.part = None


def write_summary(run, folds):
    return rasa_cv.write_summary(run, folds, TITLE, METRICS)


def main():
    parser = argparse.ArgumentParser(description="Cross-validates the spaCy NLU model, one process per part.")
    parser.add_argument("--folds", type=int, default=10, help="number of parts for a new run (default 10)")
    parser.add_argument("--resume", metavar="RUN_FOLDER", help="continue a stopped run, e.g. results/cv-20261103-153000")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if args.resume:
        run = os.path.abspath(args.resume)
        folds = len([d for d in os.listdir(run) if re.match(r"^part-\d+$", d)])
        if not folds:
            sys.exit("No parts in %s, start a new run instead" % run)
        log = rasa_cv.Log(os.path.join(run, "full.log"))
        log.say("Resuming %s, %d parts" % (rel(run), folds))
    else:
        if args.folds < 2:
            sys.exit("--folds must be at least 2")
        if not os.path.exists(os.path.join(DATA, "nlu.yml")):
            sys.exit("No training data in %s. Run cross-validate.bat, which writes it first." % DATA)
        folds = args.folds
        run = os.path.join(RESULTS, "cv-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
        os.makedirs(os.path.join(run, "data"))
        shutil.copy(os.path.join(HERE, "config.cfg"), run)
        for name in ("nlu.yml", "lookups.yml"):
            shutil.copy(os.path.join(DATA, name), os.path.join(run, "data"))
        log = rasa_cv.Log(os.path.join(run, "full.log"))
        log.say("New %d-part cross-validation in %s" % (folds, rel(run)))
        rasa_cv.make_parts(log, run, folds)

    for n in range(1, folds + 1):
        if os.path.exists(os.path.join(rasa_cv.part_dir(run, n), "summary.json")):
            log.say("Part %d already done, skipping it" % n)
            continue
        if not run_part(log, run, n, folds):
            write_summary(run, folds)
            log.say("Stopped at part %d. Continue with: cross-validate.bat --resume %s" % (n, rel(run)))
            return 1
        write_summary(run, folds)

    overall = write_summary(run, folds)
    num = rasa_cv._num
    log.say("All %d parts done. Intent accuracy %s +- %s, entity F1 (micro) %s +- %s. Summary: %s" % (
        folds, num(overall["intent_accuracy"]["mean"]), num(overall["intent_accuracy"]["std"]),
        num(overall.get("entity_micro_f1", {}).get("mean")), num(overall.get("entity_micro_f1", {}).get("std")),
        rel(os.path.join(run, "summary.md"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
