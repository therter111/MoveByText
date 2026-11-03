"""Rules the spaCy server applies around the trained model, without retraining it.

They use spaCy's pretrained English pipeline (en_core_web_md, already installed for its word vectors): its parser
and which words have a word vector. Starsector names (Jangala, Askonia) have none, ordinary English words do. Each
rule has a name; server.py --rules picks which run. See README.md for what each one adds.

    typos         before the model reads the sentence: a word with no vector that isn't a template word, but is
                  one edit (two for 8+ letters) from exactly one template word, becomes that word
    cues          a request of more than 8 words needs a request cue (an imperative verb, "please", "I want",
                  "can you", ...); otherwise it is read as not a request. Stories and reported speech have none.
    negation      a negated command ("don't spawn any pirates") with no alternative is not a request
    negated_size  a size word in a negated phrase ("don't make it a big one", "nothing huge") is dropped
    plain_word    marks places that are ordinary English words ("plain_word": true); SpacyClient drops those that
                  aren't known Starsector places (its "places" rule)

Tried and left out: adding numbers the English number finder sees before "points" (no gain, November 2026).
"""

import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
GENERATOR = os.path.join(os.path.dirname(HERE), "rasa", "generate_nlu.py")

ALL = ["typos", "cues", "negation", "negated_size", "plain_word"]
DEFAULT = ALL
LONG_SENTENCE_WORDS = 8

REQUEST_INTENTS = {"spawn_fleet", "trade_convoy", "request_help"}
COMMAND_VERBS = {"spawn", "send", "make", "bring", "give", "get", "have", "summon", "throw", "create", "dispatch",
                 "put", "start", "deliver", "ship", "move", "run", "defend", "protect", "guard", "help", "reinforce"}
# Phrases that ask for something, besides an imperative verb
CUE = re.compile(r"\b(please|pls|plz|i (?:\w+ )?(?:want|wanna|need)|i'd (?:\w+ )?(?:like|love)|i would like|"
                 r"i'm asking|i ask|"
                 r"can you|could you|would you|will you|can i get|can i have|could i get|let's|let me|give me|"
                 r"get me|how about|have the|make the|should (send|defend|guard|help|protect|go|come)|"
                 r"needs? (help|backup|defenders|support|reinforcements|a hand))\b", re.I)
ALTERNATIVE = re.compile(r"\b(instead|rather|but|only|just|i want|give me|i'd like)\b", re.I)
NEGATORS = {"not", "n't", "no", "nothing", "never", "without"}
PLACE_TYPES = {"destination", "origin", "target"}


def parse_names(value):
    """'all', 'none' or 'a,b' -> list of rule names (unknown names stop the server with a message)."""
    v = (value or "").strip().lower()
    if v in ("", "all"):
        return list(DEFAULT)
    if v == "none":
        return []
    names = [n.strip() for n in v.split(",") if n.strip()]
    unknown = [n for n in names if n not in ALL]
    if unknown:
        raise SystemExit("Unknown rule(s) %s. Known: %s" % (", ".join(unknown), ", ".join(ALL)))
    return names


def template_vocabulary():
    """Every word of 4+ letters in rasa/generate_nlu.py's templates and word lists, lowercase."""
    spec = importlib.util.spec_from_file_location("generate_nlu", GENERATOR)
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)  # only defines lists; writing the data happens in its main()
    words = set()

    def add(value):
        if isinstance(value, str):
            for w in re.findall(r"[a-z]+", re.sub(r"\{[a-z]+\d?\}", " ", value.lower())):
                if len(w) >= 4:
                    words.add(w)
        elif isinstance(value, (list, tuple, set)):
            for v in value:
                add(v)
        elif isinstance(value, dict):
            for k, v in value.items():
                add(k)
                add(v)

    for name, value in vars(gen).items():
        if name.isupper():
            add(value)
    return words


def distance(a, b):
    """Edits from a to b: a letter added, dropped or changed, or two neighbours swapped ("spwan" -> "spawn")."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] != b[j - 1]))
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[-1][-1]


def same_case(original, word):
    if original.isupper():
        return word.upper()
    if original[:1].isupper():
        return word[:1].upper() + word[1:]
    return word


class Rules:
    def __init__(self, names):
        import spacy
        self.names = set(names)
        # The pretrained pipeline as it comes: tagger, parser, number finder. Nothing of ours is trained into it.
        self.english = spacy.load("en_core_web_md")
        self.words = template_vocabulary() if "typos" in self.names else set()
        self.by_letter = {}
        for w in self.words:
            self.by_letter.setdefault(w[0], []).append(w)

    # ---- before the model --------------------------------------------------------------------------------------

    def before(self, text):
        """The text the model should read, and the corrections made ("hegmony -> hegemony")."""
        if "typos" not in self.names:
            return text, []
        doc = self.english.make_doc(text)
        out, corrections, last = [], [], 0
        for t in doc:
            fixed = self.correct(t)
            if fixed:
                out.append(text[last:t.idx])
                out.append(fixed)
                last = t.idx + len(t.text)
                corrections.append("%s -> %s" % (t.text, fixed))
        out.append(text[last:])
        return "".join(out), corrections

    def correct(self, token):
        w = token.lower_
        if not token.is_alpha or len(w) < 4 or w in self.words or token.has_vector:
            return None
        allowed = 2 if len(w) >= 8 else 1
        best, best_d, tie = None, allowed + 1, False
        for c in self.by_letter.get(w[0], ()):
            if abs(len(c) - len(w)) > allowed:
                continue
            d = distance(w, c)
            if d < best_d:
                best, best_d, tie = c, d, False
            elif d == best_d:
                tie = True
        return same_case(token.text, best) if best and not tie else None

    # ---- after the model ---------------------------------------------------------------------------------------

    def after(self, result):
        """Changes the model's answer in place; result["rules"] says what fired."""
        needs_parse = self.names & {"cues", "negation", "negated_size", "plain_word"}
        if not needs_parse:
            return result
        text = result["text"]
        doc = self.english(text)
        fired = []
        intent = result["intent"]["name"]
        is_request = any(part in REQUEST_INTENTS for part in intent.split("+"))

        if is_request and "negation" in self.names and self.negated_command(doc):
            self.not_a_request(result)
            fired.append("negation: the command is negated, not a request")
            is_request = False
        if is_request and "cues" in self.names and len(text.split()) > LONG_SENTENCE_WORDS and not self.has_cue(doc):
            self.not_a_request(result)
            fired.append("cues: no request cue in a long sentence, not a request")
            is_request = False

        if "negated_size" in self.names:
            kept = []
            for e in result["entities"]:
                if e["entity"] == "size" and self.negated(doc, e["start"], e["end"]):
                    fired.append("negated_size: dropped \"%s\"" % e["value"])
                else:
                    kept.append(e)
            result["entities"] = kept

        if "plain_word" in self.names:
            for e in result["entities"]:
                if e["entity"] in PLACE_TYPES:
                    words = [t for t in doc.char_span(e["start"], e["end"], alignment_mode="expand") or [] if t.is_alpha]
                    e["plain_word"] = bool(words) and all(t.has_vector for t in words)

        if fired:
            result["rules"] = result.get("rules", []) + fired
        return result

    @staticmethod
    def not_a_request(result):
        result["intent"] = {"name": "out_of_scope", "confidence": 1.0}

    @staticmethod
    def has_cue(doc):
        if CUE.search(doc.text):
            return True
        words = [t for t in doc if t.is_alpha]
        if words and (words[0].tag_ == "VB" or words[0].lower_ in COMMAND_VERBS):
            return True  # starts with a command: "Send a convoy to Askonia, I've got 5000 credits riding on it"
        roots = [t for t in doc if t.dep_ == "ROOT"]
        if roots and roots[0].pos_ in ("NOUN", "PROPN", "NUM"):
            return True  # no verb at the top, just the thing wanted: "huge diktat supply run to the Askonia system"
        for t in doc:
            if t.lower_ == "should" and not any(c.dep_ == "nsubj" and c.lower_ in ("i", "we") for c in t.head.children):
                return True  # "the hegemony should blockade Askonia", but not "I should go to bed"
            # An imperative: a verb in its base form with no subject and no "to" ("send defenders", not "to spawn")
            if t.tag_ == "VB" and t.dep_ in ("ROOT", "conj", "ccomp", "parataxis", "dep") and not any(
                    c.dep_ in ("nsubj", "nsubjpass", "expl") or c.tag_ in ("TO", "MD") for c in t.children):
                return True
        return False

    @staticmethod
    def negated_command(doc):
        negated = [t for t in doc if t.lemma_.lower() in COMMAND_VERBS and any(c.dep_ == "neg" for c in t.children)]
        if not negated:
            return False
        plain = [t for t in doc if t.lemma_.lower() in COMMAND_VERBS and t.pos_ in ("VERB", "AUX")
                 and t not in negated and not any(c.dep_ == "neg" for c in t.children)]
        return not plain and not ALTERNATIVE.search(doc.text)

    @staticmethod
    def negated(doc, start, end):
        """True when the words at start-end sit in a negated phrase."""
        span = doc.char_span(start, end, alignment_mode="expand")
        if span is None:
            return False
        before = doc[max(0, span.start - 3):span.start]
        if any(t.lower_ in NEGATORS for t in before):
            return True  # "nothing huge", "not too big", "no big fleets"
        head = span.root.head
        for _ in range(6):  # the nearest verb above the size word decides: "don't make it a big one"
            if head.pos_ in ("VERB", "AUX"):
                return any(c.dep_ == "neg" for c in head.children)
            if head.head is head:
                break
            head = head.head
        return False
