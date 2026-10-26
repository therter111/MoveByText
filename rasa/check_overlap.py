"""Keeps the tests a fair check: finds training examples that are identical to a test message.

Reads every expect("...") message in test/KirizsKeve/MoveByText/LlmPrompt*.java, strips the labels from the
examples in data/nlu.yml, and compares the two ignoring case, spaces and final punctuation.

Run: python check_overlap.py            lists the overlaps
     python check_overlap.py --remove   also deletes them from data/nlu.yml (train.bat does this)
"""

import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
NLU = os.path.join(HERE, "data", "nlu.yml")
TESTS = os.path.join(HERE, "..", "test", "KirizsKeve", "MoveByText", "LlmPrompt*.java")
LABEL = re.compile(r"\[([^\]]+)\](?:\([^)]*\)|\{[^}]*\})")


def java_string(src, i):
    """Reads the Java string literal starting at src[i] == '"'; returns (text, index after it)."""
    out, i = [], i + 1
    while src[i] != '"':
        c = src[i]
        if c == "\\":
            n = src[i + 1]
            if n == "u":
                out.append(chr(int(src[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t", "r": "\r"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out), i + 1


def test_messages():
    """The first argument of every expect(...) call: string literals joined with +."""
    messages = []
    for path in glob.glob(TESTS):
        src = open(path, encoding="utf-8").read()
        for m in re.finditer(r"\bexpect\(", src):
            i, parts = m.end(), []
            while True:
                while src[i].isspace() or src[i] == "+":
                    i += 1
                if src[i] != '"':
                    break
                text, i = java_string(src, i)
                parts.append(text)
            if parts:
                messages.append("".join(parts))
    return messages


def normalize(text):
    return re.sub(r"\s+", " ", text.lower()).strip().rstrip(".!?")


def main():
    tests = {normalize(t) for t in test_messages()}
    lines = open(NLU, encoding="utf-8").read().split("\n")
    kept, removed = [], []
    for line in lines:
        m = re.match(r"^    - (.*)$", line)
        if m and normalize(LABEL.sub(r"\1", m.group(1))) in tests:
            removed.append(LABEL.sub(r"\1", m.group(1)))
            continue
        kept.append(line)

    print("%d test messages, %d training examples identical to one of them" % (len(tests), len(removed)))
    for r in removed[:20]:
        print("  " + r)
    if "--remove" in sys.argv and removed:
        open(NLU, "w", encoding="utf-8", newline="\n").write("\n".join(kept))
        print("removed them from data/nlu.yml")


if __name__ == "__main__":
    main()
