"""Scores a spaCy NLU model on labelled examples in Rasa's nlu.yml format. cross_validate.py runs it for each part.

Intents: accuracy and F1 per intent, as scikit-learn computes them, over every example. Entities: only in examples
that are requests (the only ones whose entities are learned and used), and a predicted entity counts only when its
start, end and type all match a labelled one. "Entity F1" ignores the group; "entity+group F1" also needs the right
group (which fleet of a multi-fleet message). Rasa's entity reports score each word on its own, over every example,
so only the intent numbers compare directly with a Rasa run.

Writes into --out:
    scores.json                    the headline numbers and F1 per entity type
    intent_report.json             scikit-learn's per-intent report
    intent_successes.json, intent_errors.json   per example, in the same format Rasa writes
    entity_errors.json             examples whose entities don't match exactly

    python evaluate.py --model models/nlu-... --nlu ../rasa/data/nlu.yml --out results/check
"""

import argparse
import collections
import json
import os
import sys

import nlu_data


def f1(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return p, r, (2 * p * r / (p + r) if p + r else 0.0)


def without_group(spans):
    return {(start, end, nlu_data.split_label(label)[0]) for start, end, label in spans}


def main():
    parser = argparse.ArgumentParser(description="Scores a spaCy NLU model on a Rasa nlu.yml.")
    parser.add_argument("--model", required=True, help="model folder")
    parser.add_argument("--nlu", required=True, help="labelled examples (Rasa nlu.yml format)")
    parser.add_argument("--out", required=True, help="folder for the reports")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    import spacy
    from sklearn.metrics import classification_report

    nlp = spacy.load(args.model)
    examples = nlu_data.read(args.nlu)
    os.makedirs(args.out, exist_ok=True)

    truth, guess, successes, errors, entity_errors = [], [], [], [], []
    counts = collections.defaultdict(lambda: [0, 0, 0])  # entity type -> tp, fp, fn
    grouped = [0, 0, 0]
    for e, doc in zip(examples, nlp.pipe([e.text for e in examples], batch_size=256)):
        name, confidence = max(doc.cats.items(), key=lambda kv: kv[1])
        truth.append(e.intent)
        guess.append(name)
        row = dict(text=e.text, intent=e.intent, intent_prediction=dict(name=name, confidence=float(confidence)))
        (successes if name == e.intent else errors).append(row)

        if not nlu_data.is_request(e.intent):
            continue  # entities are only learned, and only used by the mod, in requests
        # The labels widened to whole tokens, the same way they are for training
        gold = {(s.start_char, s.end_char, s.label_) for s in nlu_data.aligned_spans(doc, e.spans)[0]}
        pred = {(s.start_char, s.end_char, s.label_) for s in doc.ents}
        grouped[0] += len(gold & pred)
        grouped[1] += len(pred - gold)
        grouped[2] += len(gold - pred)
        g, p = without_group(gold), without_group(pred)
        for kind in {label for _, _, label in g | p}:
            gk, pk = {x for x in g if x[2] == kind}, {x for x in p if x[2] == kind}
            counts[kind][0] += len(gk & pk)
            counts[kind][1] += len(pk - gk)
            counts[kind][2] += len(gk - pk)
        if gold != pred:
            show = lambda spans: sorted("%s=%s" % (l, e.text[a:b]) for a, b, l in spans)
            entity_errors.append(dict(text=e.text, expected=show(gold), predicted=show(pred)))

    report = classification_report(truth, guess, output_dict=True, zero_division=0)
    entities = {}
    for kind, (tp, fp, fn) in sorted(counts.items()):
        p, r, f = f1(tp, fp, fn)
        entities[kind] = {"precision": p, "recall": r, "f1": f, "support": tp + fn}
    micro = f1(*[sum(c[i] for c in counts.values()) for i in range(3)])[2] if counts else None
    scores = dict(
        intent_accuracy=report["accuracy"],
        intent_macro_f1=report["macro avg"]["f1-score"],
        intent_weighted_f1=report["weighted avg"]["f1-score"],
        entity_micro_f1=micro,
        entity_macro_f1=sum(v["f1"] for v in entities.values()) / len(entities) if entities else None,
        entity_group_micro_f1=f1(*grouped)[2] if any(grouped) else None,
        entities=entities,
    )

    def dump(name, data):
        with open(os.path.join(args.out, name), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

    dump("scores.json", scores)
    dump("intent_report.json", report)
    dump("intent_successes.json", successes)
    dump("intent_errors.json", errors)
    dump("entity_errors.json", entity_errors)
    print("%d examples: intent accuracy %.3f, intent F1 (macro) %.3f, entity F1 (micro) %s, entity+group F1 %s" % (
        len(examples), scores["intent_accuracy"], scores["intent_macro_f1"],
        "-" if micro is None else "%.3f" % micro,
        "-" if scores["entity_group_micro_f1"] is None else "%.3f" % scores["entity_group_micro_f1"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
