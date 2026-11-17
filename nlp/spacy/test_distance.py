"""Checks distance() in rules.py against test/data/distance_cases.txt, the table FactionNames.distance (Java) is
tested against too, so the server and the mod forgive the same typos.

Needs no spaCy: rules.py only loads it when its rules are created. build.py runs this with plain Python:
    python nlp/spacy/test_distance.py
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(os.path.dirname(HERE))
TABLE = os.path.join(MOD, "test", "data", "distance_cases.txt")

sys.path.insert(0, HERE)
import rules  # noqa: E402


def cases():
    for n, line in enumerate(open(TABLE, encoding="utf-8"), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        a, b, edits = line.split()
        yield n, "" if a == "-" else a, "" if b == "-" else b, int(edits)


def main():
    failed = run = 0
    for n, a, b, edits in cases():
        run += 1
        got = rules.distance(a, b)
        if got != edits:
            failed += 1
            print("FAIL   line %d: distance(%r, %r) is %d, the table says %d" % (n, a, b, got, edits))
    print("rules.py distance(): %d/%d cases of %s pass" % (run - failed, run, os.path.relpath(TABLE, MOD)))
    sys.exit(1 if failed or not run else 0)


if __name__ == "__main__":
    main()
