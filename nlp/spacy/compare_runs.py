"""Compares hand-written test runs of the same cross-validation models, on two halves of the test cases.

The 629 cases are split in two: within each test group they alternate between the "design" half and the "check"
half. Rules are designed and chosen by looking at the design half only; the check half, which they were never tuned
on, gives the honest number at the end. So the check half is only shown when asked for (--check).

Runs are named as in test_handwritten.py --name; "handwritten" (or "") is the run without a name.
    python compare_runs.py BASE NEW                 design half: each model, the mean, how many models improved
    python compare_runs.py BASE NEW --check         the same, plus the check half
    python compare_runs.py --failures NAME          the full model's design-half failures in run NAME
    python compare_runs.py --flips BASE NEW         the full model's design-half cases that changed between runs
Options: --run results/cv-... (default: the newest), --half check (for --failures and --flips).
"""

import argparse
import glob
import json
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def folder(name):
    return "handwritten" if name in ("", "handwritten") else "handwritten-" + name


def halves(cases):
    """'design' or 'check' for each case: alternating within each test group, starting with design."""
    seen, out = {}, []
    for case in cases:
        group = case[1]
        out.append("design" if seen.get(group, 0) % 2 == 0 else "check")
        seen[group] = seen.get(group, 0) + 1
    return out


def load(run, name):
    """{model label: result.json} for the 10 parts and the full model."""
    results = {}
    for d in sorted(glob.glob(os.path.join(run, "part-*"))):
        path = os.path.join(d, folder(name), "result.json")
        if os.path.exists(path):
            results[os.path.basename(d)] = json.load(open(path, encoding="utf-8"))
    full = os.path.join(run, folder(name).replace("handwritten", "handwritten-full-model", 1), "result.json")
    if os.path.exists(full):
        results["full model"] = json.load(open(full, encoding="utf-8"))
    if not results:
        sys.exit("No results named %r in %s" % (name, run))
    return results


def rate(result, half):
    cases = result["cases"]
    picked = [c for c, h in zip(cases, halves(cases)) if h == half]
    return 100.0 * sum(c[2] == "PASS" for c in picked) / len(picked), len(picked)


def compare(run, base, new, show_check):
    a, b = load(run, base), load(run, new)
    labels = [k for k in a if k in b]
    shown = ["design"] + (["check"] if show_check else [])
    head = "| Model | " + " | ".join("%s: %s | %s: %s | change" % (h, base or "handwritten", h, new) for h in shown) + " |"
    print(head)
    print("|---|" + "---|---|---|" * len(shown))
    deltas = {h: [] for h in shown}
    for label in labels:
        cells = []
        for h in shown:
            x, n = rate(a[label], h)
            y, _ = rate(b[label], h)
            cells.append("%.1f%% | %.1f%% | %+.1f" % (x, y, y - x))
            if label != "full model":
                deltas[h].append(y - x)
        print("| %s | %s |" % (label, " | ".join(cells)))
    for h in shown:
        d = deltas[h]
        if d:
            print("%s half, 10 parts: mean change %+.2f points (sd %.2f), better on %d, worse on %d, same on %d"
                  % (h, statistics.mean(d), statistics.stdev(d) if len(d) > 1 else 0,
                     sum(x > 0 for x in d), sum(x < 0 for x in d), sum(x == 0 for x in d)))
            means = [statistics.mean(rate(r[l], h)[0] for l in labels if l != "full model") for r in (a, b)]
            print("    mean pass rate %.1f%% -> %.1f%%" % tuple(means))


def console_cases(path):
    """(group, status, input, reply, expected) per case, in order, from an LlmPromptTest console.txt."""
    lines = open(path, encoding="utf-8").read().split("\n")
    cases, group, i = [], None, 0
    while i < len(lines):
        line = lines[i]
        m = re.match(r"^== (.+) ==$", line)
        if m:
            group = m.group(1)
        m = re.match(r'^(PASS|FAIL|FLAKY|ERROR)\s+"(.*)"(?: -> (.*))?$', line)
        if m and group and group != "summary":
            status, text, reply, expected = m.group(1), m.group(2), m.group(3) or "", ""
            for extra in lines[i + 1:i + 5]:
                if extra.strip().startswith("1st:"):
                    reply = extra.split("1st:", 1)[1].strip()
                if extra.strip().startswith("expected:"):
                    expected = extra.split("expected:", 1)[1].strip()
                if extra.strip().startswith("expected:") or not extra.startswith("       "):
                    break
            cases.append((group, status, text, reply, expected))
        i += 1
    return cases


def full_console(run, name):
    path = os.path.join(run, folder(name).replace("handwritten", "handwritten-full-model", 1), "console.txt")
    cases = console_cases(path)
    return cases, halves([(None, c[0]) for c in cases])


def failures(run, name, half):
    cases, hs = full_console(run, name)
    picked = [c for c, h in zip(cases, hs) if h == half and c[1] != "PASS"]
    print("%d of %d %s-half cases fail with the full model of %s" % (len(picked), hs.count(half), half, name or "handwritten"))
    for group, status, text, reply, expected in picked:
        print("\n[%s] %s\n  got:      %s\n  expected: %s" % (group, text, reply, expected))


def flips(run, base, new, half):
    a, ha = full_console(run, base)
    b, _ = full_console(run, new)
    for (group, s1, text, r1, e1), (_, s2, _, r2, e2), h in zip(a, b, ha):
        if h == half and (s1 == "PASS") != (s2 == "PASS"):
            print("%s  [%s] %s\n    %s: %s\n    %s: %s\n    expected: %s" % (
                "FIXED" if s2 == "PASS" else "BROKE", group, text, base or "handwritten", r1, new, r2, e1 or e2))


def main():
    p = argparse.ArgumentParser(description="Compares hand-written test runs on the design and check halves.")
    p.add_argument("names", nargs="*", help="BASE NEW")
    p.add_argument("--run", help="cross-validation run folder (default: the newest results/cv-*)")
    p.add_argument("--check", action="store_true", help="also show the check half")
    p.add_argument("--failures", metavar="NAME", help="list the full model's failures in that run")
    p.add_argument("--flips", action="store_true", help="list the full model's cases that changed from BASE to NEW")
    p.add_argument("--half", default="design", choices=["design", "check"])
    args = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    runs = sorted(glob.glob(os.path.join(HERE, "results", "cv-*")))
    run = os.path.abspath(args.run) if args.run else runs[-1]
    if args.failures is not None:
        failures(run, args.failures, args.half)
    elif len(args.names) != 2:
        p.error("give BASE and NEW")
    elif args.flips:
        flips(run, args.names[0], args.names[1], args.half)
    else:
        compare(run, args.names[0], args.names[1], args.check)
    return 0


if __name__ == "__main__":
    sys.exit(main())
