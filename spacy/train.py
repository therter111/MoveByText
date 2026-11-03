"""Trains the MoveByText spaCy NLU model from the Rasa training data.

Reads a Rasa nlu.yml (by default rasa/data/nlu.yml, which train.bat writes first with rasa/generate_nlu.py), holds
out 10% of each intent as a dev set for picking the best checkpoint, and trains config.cfg: a text classifier for
the intent and an entity recognizer for the faction, size, places and so on, on top of the en_core_web_md word
vectors. The best checkpoint is copied to the output folder.

Run train.bat, or in the spaCy environment:
    python train.py                                         new model in models/nlu-<date>-<time>, older ones deleted
    python train.py --nlu part/train/nlu.yml --out part/model   (what cross_validate.py does for each part)
"""

import argparse
import collections
import datetime
import glob
import os
import shutil
import sys
import tempfile
import time

import nlu_data

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
SEED = 42
DEV_FRACTION = 0.1


def main():
    parser = argparse.ArgumentParser(description="Trains the spaCy NLU model from a Rasa nlu.yml.")
    parser.add_argument("--nlu", default=nlu_data.DEFAULT_NLU, help="Rasa training data (default rasa/data/nlu.yml)")
    parser.add_argument("--config", default=os.path.join(HERE, "config.cfg"), help="spaCy config (default config.cfg)")
    parser.add_argument("--out", help="model folder to write (default models/nlu-<date>-<time>, older ones deleted)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    import spacy
    from spacy.cli.train import train

    if not os.path.exists(args.nlu):
        sys.exit("No training data at %s. Run train.bat, which writes it first." % args.nlu)
    examples = nlu_data.read(args.nlu)
    intents = sorted({e.intent for e in examples})
    labels = collections.Counter(label for e in examples for _, _, label in e.spans)
    print("%d examples, %d intents, %d entity labels" % (len(examples), len(intents), len(labels)))
    print("  intents: " + ", ".join("%s %d" % (i, sum(e.intent == i for e in examples)) for i in intents))

    train_set, dev_set = nlu_data.stratified_split(examples, DEV_FRACTION, SEED)
    out = os.path.abspath(args.out) if args.out else os.path.join(
        MODELS, "nlu-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))

    work = tempfile.mkdtemp(prefix="movebytext-spacy-")
    try:
        nlp = spacy.blank("en")
        dropped = 0
        for name, part in (("train", train_set), ("dev", dev_set)):
            db, lost = nlu_data.to_docbin(nlp, part, intents)
            db.to_disk(os.path.join(work, name + ".spacy"))
            dropped += lost
        print("training on %d, choosing the best checkpoint on %d%s" % (
            len(train_set), len(dev_set), ", %d labels dropped (no token boundary)" % dropped if dropped else ""))

        start = time.time()
        train(args.config, os.path.join(work, "output"), use_gpu=-1, overrides={
            "paths.train": os.path.join(work, "train.spacy"), "paths.dev": os.path.join(work, "dev.spacy")})
        shutil.rmtree(out, ignore_errors=True)
        shutil.copytree(os.path.join(work, "output", "model-best"), out)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    if not args.out:  # Keep only the newest model, like rasa/train.bat
        for old in glob.glob(os.path.join(MODELS, "nlu-*")):
            if os.path.abspath(old) != out:
                shutil.rmtree(old, ignore_errors=True)
    print("model saved to %s in %.1f min" % (out, (time.time() - start) / 60))
    return 0


if __name__ == "__main__":
    sys.exit(main())
