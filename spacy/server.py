"""The spaCy NLU server the mod talks to (provider "spacy" in data/config/llmdata.json).

Answers like a Rasa server, so the mod reads both the same way (SpacyClient, RasaCommandBuilder):
    POST /model/parse  {"text": "spawn a small pirate fleet"}
        -> {"text": ..., "intent": {"name": "spawn_fleet", "confidence": 0.98}, "intent_ranking": [...],
            "entities": [{"entity": "size", "value": "small", "start": 8, "end": 13}, ...]}
           Entities of messages with several fleets also have a "group": "1", "2", ...
    GET /status        -> {"model_file": "models/nlu-...", "spacy_version": "3.8..."}

The answer is the model's, after the rules in rules.py (typo fixes, request cues, negations, plain-word places);
"rules" lists what they changed, and SpacyClient.java applies the rest.

Run start-server.bat, or in the spaCy environment:
    python server.py                                    newest model in models/, 127.0.0.1:5007, all rules
    python server.py --model results/cv-.../part-01/model --port 5008
    python server.py --rules none                       the model's own answers
"""

import argparse
import datetime
import glob
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

import nlu_data

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PORT = 5007
MAX_BODY = 1 << 20


def newest_model():
    models = [d for d in glob.glob(os.path.join(HERE, "models", "nlu-*")) if os.path.isdir(d)]
    return max(models, key=os.path.getmtime) if models else None


def parse(nlp, text, rules=None):
    """What the model reads in one message, in Rasa's /model/parse format, after the rules (rules.py) if given."""
    corrections = []
    if rules is not None:
        text, corrections = rules.before(text)
    result = model_parse(nlp, text)
    if corrections:
        result["rules"] = ["typos: " + c for c in corrections]
    if rules is not None:
        rules.after(result)
    return result


def model_parse(nlp, text):
    doc = nlp(text)
    ranking = sorted(doc.cats.items(), key=lambda kv: -kv[1])
    entities = []
    for ent in doc.ents:
        entity, group = nlu_data.split_label(ent.label_)
        e = {"entity": entity, "value": ent.text, "start": ent.start_char, "end": ent.end_char, "extractor": "spacy"}
        if group:
            e["group"] = group
        entities.append(e)
    top = ranking[0] if ranking else ("nlu_fallback", 0.0)
    return {"text": text, "intent": {"name": top[0], "confidence": float(top[1])},
            "intent_ranking": [{"name": n, "confidence": float(c)} for n, c in ranking],
            "entities": entities}


def describe(result):
    """'spawn_fleet 0.98: size=small faction=pirate' for the console."""
    parts = ["%s=%s%s" % (e["entity"], e["value"], "#" + e["group"] if "group" in e else "") for e in result["entities"]]
    return "%s %.2f%s" % (result["intent"]["name"], result["intent"]["confidence"], ": " + " ".join(parts) if parts else "")


class Server(HTTPServer):
    # On Windows SO_REUSEADDR lets a second server take the same port, so a forgotten window would answer instead
    allow_reuse_address = os.name != "nt"

    def __init__(self, address, nlp, model_file, rules):
        super().__init__(address, Handler)
        self.nlp = nlp
        self.model_file = model_file
        self.rules = rules


class Handler(BaseHTTPRequestHandler):
    server_version = "MoveByText-spaCy"

    def answer(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if urlparse(self.path).path.rstrip("/") == "/status":
            import spacy
            self.answer(200, {"model_file": self.server.model_file, "spacy_version": spacy.__version__,
                              "rules": sorted(self.server.rules.names) if self.server.rules else []})
        else:
            self.answer(404, {"message": "Unknown path. Use POST /model/parse or GET /status."})

    def do_POST(self):
        if urlparse(self.path).path.rstrip("/") != "/model/parse":
            self.answer(404, {"message": "Unknown path. Use POST /model/parse or GET /status."})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise ValueError("the message is too long")
            body = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            text = body.get("text") if isinstance(body, dict) else None
            if not isinstance(text, str):
                raise ValueError('the body must be JSON with a "text" field')
        except (ValueError, UnicodeDecodeError) as e:
            self.answer(400, {"message": "Bad request: %s" % e})
            return
        result = parse(self.server.nlp, text, self.server.rules)
        print("[%s] %s  <- %s" % (datetime.datetime.now().strftime("%H:%M:%S"), describe(result),
                                  text if len(text) <= 80 else text[:77] + "..."), flush=True)
        for fired in result.get("rules", []):
            print("           rule %s" % fired, flush=True)
        self.answer(200, result)

    def log_message(self, format, *args):
        pass  # do_POST prints one readable line per message instead


def main():
    parser = argparse.ArgumentParser(description="Serves the spaCy NLU model in Rasa's /model/parse format.")
    parser.add_argument("--model", help="model folder (default: the newest in models/)")
    parser.add_argument("--host", default="127.0.0.1", help="address to listen on (default 127.0.0.1, this PC only)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="port (default %d)" % DEFAULT_PORT)
    parser.add_argument("--rules", default="all",
                        help="rules from rules.py: all (default), none, or names like typos,cues")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    model = os.path.abspath(args.model) if args.model else newest_model()
    if not model or not os.path.exists(os.path.join(model, "meta.json")):
        sys.exit("No trained model%s. Run train.bat first." % (" at " + model if model else ""))
    import spacy
    import rules as rules_module
    names = rules_module.parse_names(args.rules)
    rules = rules_module.Rules(names) if names else None
    nlp = spacy.load(model)
    try:
        model_file = os.path.relpath(model, HERE).replace("\\", "/")
    except ValueError:  # another drive
        model_file = model.replace("\\", "/")

    server = Server((args.host, args.port), nlp, model_file, rules)
    print("spaCy server is up and running on http://%s:%d with %s, rules: %s" % (
        args.host, args.port, model_file, ", ".join(names) or "none"), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
