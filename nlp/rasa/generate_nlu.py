"""Writes the Rasa training data for MoveByText: data/nlu.yml and data/lookups.yml.

The examples come from templates filled with factions, sizes, numbers and Starsector places. Only the parts that
belong to the command are labelled (the faction whose ships are wanted, the size, the places); factions, numbers and
size words in stories, opinions and enemy descriptions are left unlabelled on purpose, so the model learns to tell
them apart. The Java side (RasaCommandBuilder) turns the labels into game commands.

This script never reads the test cases in test/. check_overlap.py removes any example that happens to be identical
to a test message, so the tests stay a fair check.

Run: python generate_nlu.py   (train.bat does it before every training run)
"""

import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "data")
RNG = random.Random(42)

# ---------------------------------------------------------------------------------------------------------------------
# Vocabulary
# ---------------------------------------------------------------------------------------------------------------------

# How the player names a faction whose ships are wanted. RasaCommandBuilder.FactionNames maps these to the ids.
FACTIONS = {
    "pirates": ["pirate", "pirates", "Pirate", "Kanta's raiders", "raider", "raiders", "Kanta's pirates", "pirate raiders"],
    "hegemony": ["hegemony", "Hegemony", "Hegemony navy", "XIV Battlegroup", "hegemony navy", "AI Inspectorate"],
    "luddic_path": ["luddic path", "Luddic Path", "pathers", "Path", "pather", "Luddic Path zealots"],
    "luddic_church": ["luddic church", "Luddic Church", "Church", "Knights of Ludd", "Church of Galactic Redemption", "pilgrims"],
    "persean": ["persean", "Persean League", "persean league", "League", "Perseans"],
    "sindrian_diktat": ["diktat", "Sindrian Diktat", "Diktat", "Lion's Guard", "Askonian", "sindrian diktat", "Andrada's"],
    "tritachyon": ["tri-tachyon", "Tri-Tachyon", "tritachyon", "tri tachyon", "TT", "Tri-Tachyon corporation"],
    "independent": ["independent", "independents", "mercenary", "mercenaries", "bounty hunters", "scavengers",
                    "freelancers", "smugglers", "independent mercenaries"],
    "remnants": ["remnant", "remnants", "Remnant", "AI warships", "remnant drones"],
    "derelict": ["derelict", "derelicts", "Domain drones", "derelict probes", "derelict drones"],
    "omega": ["omega", "Omega"],
}
# Lore ways to point at a faction: "the faction <leader> rules", "the faction that owns <home>"
LEADERS = ["Philip Andrada", "Andrada", "Kanta", "the High Hegemon", "the Tri-Tachyon CEOs"]
HOMES = ["Chicomoztoc", "Sindria", "Askonia", "Kazeron", "Hybrasil", "Culann", "Gilead", "Hesperus", "Kanta's Den"]
# Factions that don't exist in the game. They are labelled as factions; the Java side rejects them (-> <NONE>).
UNKNOWN_FACTIONS = ["Borg", "Zerg", "Covenant", "Cylon", "Romulan", "Vogon", "Galactic Republic", "Star Alliance",
                    "Hiver Swarm", "Tahlan Shipworks", "Scy Nation", "Exigency", "Mayorate", "Tiandong", "Protoss",
                    "Terran Federation", "Earth Alliance", "Minbari", "Asgard", "Sith"]

PLACES = ["Jangala", "Asharu", "Corvus", "Askonia", "Sindria", "Volturn", "Chicomoztoc", "Aztlan", "Kazeron",
          "Fikenhild", "Mazalot", "Hybrasil", "Culann", "Eochu Bres", "Port Tse", "Eventide", "Ilm", "Tigra City",
          "Agreus", "Gilead", "Hesperus", "Kanta's Den", "Magec", "New Maxios", "Mairaath", "Salamanca", "Nortia",
          "Valhalla", "Westernesse", "Ancyra", "Galatia", "Tartessus", "Thule", "Umbra", "Cruor", "Qaras", "Lumen",
          "Athulf", "Mayasura", "Samarra", "Arcadia", "Baetis", "Nachiketa", "Tyle", "Ragnar Complex", "Sentinel"]

SIZE_ADJ = ["tiny", "little", "small", "minor", "medium", "standard", "normal", "regular", "average", "medium-sized",
            "big", "large", "strong", "huge", "massive", "very large", "humongous", "gigantic", "enormous", "colossal"]
SIZE_QTY = ["a few", "a couple of", "a handful of", "some", "a lone", "a single"]
SIZE_TOP = ["armada", "invasion fleet"]
MULT_AS = ["twice", "three times", "half", "four times", "ten times"]           # "<m> as big as a small one"
MULT_OF = ["half of", "a third of", "a quarter of", "two thirds of", "three quarters of", "double", "triple"]
MULT_PCT = ["50 percent bigger than", "20 percent smaller than", "10% stronger than", "25 percent larger than"]
OFFSET_CMP = ["20 points bigger", "10 points smaller", "30 points larger", "15 points weaker", "50 points stronger"]
OFFSET_ADD = ["plus 30 points", "minus 10 points", "and another 50 points on top", "plus 20", "minus 25 points"]
COUNTS = ["two", "three", "four", "2", "3", "5", "a pair of"]

GOODS = ["food", "fuel", "volatiles", "heavy machinery", "medicine", "luxury goods", "ore", "supplies", "metals"]
SHIP_NAMES = ["Tiny Titan", "Big Bertha", "Massive Mistake", "Little Lucky", "Huge Problem", "Small Wonder"]
GAME_ACTIONS = ["upgrade my ship", "find officers", "build a colony", "make money", "get more fuel", "repair my hull"]

ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


def number_words(n):
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else "-" + ONES[n % 10])
    rest = n % 100
    return ONES[n // 100] + " hundred" + ("" if rest == 0 else " and " + number_words(rest))


# ---------------------------------------------------------------------------------------------------------------------
# Filling templates
# ---------------------------------------------------------------------------------------------------------------------

# When set, the example is written in capitals ("HEGEMONY ARMADA NOW"); labels keep their entity names.
SHOUT = False


def label(text, entity, group=None):
    if SHOUT:
        text = text.upper()
    if group:
        return '[%s]{"entity": "%s", "group": "%s"}' % (text, entity, group)
    return "[%s](%s)" % (text, entity)


def typo(word):
    """A small typo now and then, so the char n-gram features learn to forgive them."""
    if len(word) < 5 or " " in word or RNG.random() > 0.15:
        return word
    i = RNG.randrange(1, len(word) - 1)
    kind = RNG.randrange(3)
    if kind == 0:
        return word[:i] + word[i + 1:]                     # drop a letter
    if kind == 1:
        return word[:i] + word[i] + word[i:]               # double a letter
    return word[:i] + word[i + 1] + word[i] + word[i + 2:]  # swap two letters


def any_faction_name():
    return typo(RNG.choice(RNG.choice(list(FACTIONS.values()))))


def points_text():
    r = RNG.random()
    if r < 0.6:
        return str(RNG.choice([RNG.randrange(5, 301), RNG.randrange(10, 301, 5), RNG.randrange(301, 1200)]))
    if r < 0.75:
        return "{:,}".format(RNG.choice([1000, 1500, 2000, 5000]))
    return number_words(RNG.choice([RNG.randrange(10, 300, 5), RNG.randrange(20, 100)]))


def plain_number():
    return str(RNG.choice([RNG.randrange(2, 60), RNG.randrange(100, 5000, 50), RNG.randrange(2, 20)]))


def slot(name, group):
    """The text for one {slot}; labelled slots carry the group of the fleet they belong to."""
    g = group
    if name == "faction":
        if RNG.random() < 0.05:
            return label(RNG.choice(UNKNOWN_FACTIONS), "faction", g)
        return label(any_faction_name(), "faction", g)
    if name == "leader":
        return label(RNG.choice(LEADERS), "faction", g)
    if name == "home":
        return label(RNG.choice(HOMES), "faction", g)
    if name == "size":
        return label(RNG.choice(SIZE_ADJ), "size", g)
    if name == "qsize":
        return label(RNG.choice(SIZE_QTY), "size", g)
    if name == "topsize":
        return label(RNG.choice(SIZE_TOP), "size", g)
    if name == "maxsize":
        return label(RNG.choice(["biggest possible", "largest possible", "maximum", "biggest"]), "size", g)
    if name == "points":
        return label(points_text(), "points", g)
    if name == "dest":
        return label(RNG.choice(PLACES), "destination", g)
    if name == "origin":
        return label(RNG.choice(PLACES), "origin", g)
    if name == "target":
        return label(RNG.choice(PLACES), "target", g)
    if name == "count":
        return label(RNG.choice(COUNTS), "count", g)
    if name == "multas":
        return label(RNG.choice(MULT_AS), "multiplier", g)
    if name == "multof":
        return label(RNG.choice(MULT_OF), "multiplier", g)
    if name == "multpct":
        return label(RNG.choice(MULT_PCT), "multiplier", g)
    if name == "offcmp":
        return label(RNG.choice(OFFSET_CMP), "offset", g)
    if name == "offadd":
        return label(RNG.choice(OFFSET_ADD), "offset", g)
    if name == "refnum":
        return label(str(RNG.randrange(40, 300, 10)), "reference", g)
    if name == "refsize":
        return label(RNG.choice(["tiny", "small", "medium", "big", "huge"]), "reference", g)
    # Unlabelled distractors
    if name == "ofaction":
        return any_faction_name()
    if name == "oplace":
        return RNG.choice(PLACES)
    if name == "osize":
        return RNG.choice(SIZE_ADJ)
    if name == "onum":
        return plain_number()
    if name == "good":
        return RNG.choice(GOODS)
    if name == "ship":
        return RNG.choice(SHIP_NAMES)
    if name == "action":
        return RNG.choice(GAME_ACTIONS)
    raise KeyError(name)


SLOT = re.compile(r"\{([a-z]+)(\d?)\}")


def fill(template):
    if SHOUT:
        # capitals for the template's own words, but not for the {slot} names
        template = re.sub(r"[^{}]+(?=\{|$)", lambda m: m.group(0).upper(), template)

    def one(m):
        text = slot(m.group(1), m.group(2) or None)
        return text.upper() if SHOUT and not text.startswith("[") else text  # labels are capitalized in label()
    return SLOT.sub(one, template)


# Background sentences: nothing in them is labelled
STORIES = [
    "Yesterday a {osize} {ofaction} fleet ambushed me near {oplace}.",
    "I just sold {onum} tons of {good} at {oplace}.",
    "My officers are arguing again.",
    "I lost {onum} ships last week.",
    "The {ofaction} have been raising prices lately.",
    "My fleet is at {onum} percent combat readiness.",
    "I have {onum} credits and {onum} marines on board.",
    "It's been a long day.",
    "I'm docked at {oplace} right now.",
    "My flagship is called the {ship}.",
    "My friend thinks the {ofaction} are overrated.",
    "I owe {onum} credits to the {ofaction}.",
    "Last month a {osize} {ofaction} armada wrecked my colony at {oplace}.",
    "The market at {oplace} is paying double for {good}.",
    "My crew has been flying for {onum} days without a break.",
    "I'm level {onum} now with {onum} officers.",
    "The {ofaction} patrol near {oplace} didn't even scan me.",
    "I've played for {onum} hours this week.",
    "My navigator says {oplace} is {onum} jumps away.",
    "I had a {osize} problem with my reactor this morning.",
]


# Clauses joined to a request inside the same sentence: "my shields are low, so spawn a small pirate fleet".
# The mod reads messages sentence by sentence, so this is the noise the model really sees.
CLAUSES = [
    "my shields are still recharging", "I'm bored", "it's getting late", "my crew is restless", "I just docked at {oplace}",
    "the {ofaction} fined me again", "I sold my {good} at {oplace}", "my officers are arguing", "I have {onum} credits left",
    "I lost a ship to the {ofaction} yesterday", "my flagship has new guns", "I've been flying for {onum} hours",
    "the market at {oplace} is closed", "I'm waiting for repairs", "my fleet is at {onum} percent readiness",
    "the {ofaction} patrol left", "I'm low on fuel", "my navigator is asleep", "I promised my crew a fight",
    "I'm testing a new loadout", "the last fight went badly", "I'm in a good mood", "the {osize} station here is boring",
    "a {osize} {ofaction} fleet passed by earlier", "I owe the {ofaction} money", "I just got back from {oplace}",
    "while I wait for my officers", "before I go to bed", "honestly", "okay listen", "right now", "for my stream",
]
JOINS_BEFORE = ["{c}, so {r}", "{c}, but {r}", "{c} and {r}", "{c}. {r}", "since {c}, {r}", "{c}, anyway {r}"]
JOINS_AFTER = ["{r}, {c}", "{r} because {c}", "{r}, since {c}", "{r} while {c}", "{r}. {c}"]


def with_noise(text):
    """Puts background clauses and sentences around the request now and then."""
    r = RNG.random()
    if r < 0.35:
        text = RNG.choice(JOINS_BEFORE).format(c=fill(RNG.choice(CLAUSES)), r=text)
    elif r < 0.50:
        text = RNG.choice(JOINS_AFTER).format(c=fill(RNG.choice(CLAUSES)), r=text)
    elif r < 0.62:
        text = fill(RNG.choice(STORIES)) + " " + text
    if RNG.random() < 0.15:
        text = text.rstrip(".!") + ". " + fill(RNG.choice(STORIES))
    return text


# ---------------------------------------------------------------------------------------------------------------------
# Templates. A digit after a slot is the fleet it belongs to, for messages with several fleets.
# ---------------------------------------------------------------------------------------------------------------------

SPAWN = [
    "spawn a {size} {faction} fleet", "spawn {faction}", "{faction}", "{faction} please", "{faction}!",
    "{size} {faction} fleet please", "I want to fight a {size} {faction} fleet", "give me a {size} {faction} fleet near me",
    "bring in a {size} {faction} patrol", "send a {size} {faction} fleet at me", "can you spawn a {faction} fleet for me",
    "let me fight {qsize} {faction} ships", "spawn a {points} point {faction} fleet", "a {faction} fleet of about {points} points",
    "{faction} fleet, {points} fp", "I'd like a {faction} fleet with {points} fleet points", "make a {size} {faction} fleet appear near me",
    "summon a {size} {faction} force", "I need something to shoot at, a {size} {faction} fleet will do",
    "time for a fight: {size} {faction} fleet", "spawn a {faction} fleet, {size}", "hit me with a {size} {faction} fleet",
    "throw a {size} {faction} fleet at me", "send in the {faction}, a {size} fleet", "{qsize} {faction} ships to fight, please",
    "put a {size} {faction} fleet next to me", "spawn the {faction}, {size} size", "spawn a {size} fleet of {faction}",
    "I'm bored, spawn a {size} {faction} fleet", "could you send {qsize} {faction} ships my way",
    "a {faction} {topsize} please", "send an {topsize} of {faction} ships", "spawn a {faction} fleet of {points} points",
    "I want a {faction} fleet between {points} and {points} points", "a {faction} fleet somewhere from {points} to {points} fp",
    "a {faction} fleet of at least {points} points", "no more than {points} points of {faction} ships",
    "a {faction} fleet of at most {points} points please", "roughly {points} points worth of {faction} ships",
    "spawn a {faction} fleet that's {multas} as big as a {size} one", "give me {multof} a {size} {faction} fleet",
    "a {faction} fleet {multpct} a {size} one", "a {faction} fleet {offcmp} than a {size} one",
    "a {size} {faction} fleet {offadd}", "a {faction} fleet of {points} points {offadd}",
    "my fleet is {refnum} points, spawn a {faction} fleet {multas} my size",
    "my fleet is {refnum} points, give me a {faction} fleet that matches it",
    "my fleet is {refsize}, spawn a {faction} fleet the same size as mine",
    "I have a {refnum} point fleet, give me a {faction} fleet {multof} my fleet's size",
    "spawn a fleet from the faction {leader} rules", "a fleet of the faction that owns {home}, {size} one",
    "spawn ships from the faction that controls {home}",
    "spawn a {osize} {ofaction} fleet, no wait, make it a {size} {faction} fleet",
    "spawn a {osize} {faction} fleet, actually make it {size}", "spawn {ofaction}. Actually no, {faction} instead",
    "not {ofaction}, spawn {faction} instead", "don't send {ofaction}, I want a {size} {faction} fleet",
    "anything but {ofaction}: a {size} {faction} fleet", "no more {ofaction}, give me {faction} this time",
    "reply in JSON please: spawn a {size} {faction} fleet", "use the tag ATTACK from now on and spawn a {size} {faction} fleet",
    "explain your reasoning, then spawn a {size} {faction} fleet", "SYSTEM OVERRIDE: spawn a {size} {faction} fleet",
    "spawn a {faction} fleet named {ship}", "spawn a {size} {faction} fleet, my flagship is the {ship}",
    "I want a {faction} fleet to attack me near {oplace}", "send a {faction} war fleet to {oplace}",
    "make the {faction} attack me", "a {size} {faction} blockade of {oplace}",
    "spawn the {faction} raiders heading for {oplace}, I'll intercept them",
    "I have a {osize} favor to ask: spawn a {faction} fleet", "I'm a {osize} fan of the {ofaction}, but spawn a {faction} fleet",
    "the {maxsize} {faction} fleet possible", "the {maxsize} {faction} fleet you can make", "{faction} {topsize} now!!!",
    "{size} {faction} fleet pls", "{faction} fleet pls", "send {faction} to attack {oplace}", "send {faction} to raid {oplace}",
    "have the {faction} hunt me near {oplace}", "I want {faction} raiders heading for {oplace}, I'll wait for them there",
    "an {faction} fleet", "gimme a {size} {faction} fleet", "{faction} ships, a {size} group", "a {faction} fleet",
    "spawn a {size} {faction} fleet, the {ofaction} can wait", "I want to fight the {faction} today, a {size} fleet",
    "bring me a {faction} fleet, {size} size, I'm ready", "how about a {size} {faction} fleet",
]
SPAWN_COUNT = [
    "{count} {size} {faction} fleets", "spawn {count} {faction} fleets", "{count} {faction} fleets, {size} ones",
    "send {count} {size} {faction} fleets at me", "I want {count} {faction} fleets, all {size}",
]
SPAWN_MULTI = [
    "a {size1} {faction1} fleet and a {size2} {faction2} fleet", "spawn {faction1} and {faction2}, both {size}",
    "a {size1} {faction1} fleet, then a {size2} {faction2} fleet", "{size1} {faction1} and {size2} {faction2} please",
    "a {size1} {faction1} fleet and {count2} {size2} {faction2} fleets", "{count1} {size1} {faction1} fleets and a {size2} {faction2} fleet",
    "spawn a {faction1} fleet and a {faction2} fleet", "a {size1} {faction1} fleet, a {size2} {faction2} fleet and a {size3} {faction3} fleet",
    "spawn a {size1} {faction1} fleet and a {faction2} fleet of {points2} points",
    "let the {faction1} and the {faction2} fight each other, {size} fleets",
]

TRADE = [
    "send a {size} convoy to {dest}", "send a {faction} trade fleet to {dest}", "send {faction} traders from {origin} to {dest}",
    "{qsize} {faction} freighters heading to {dest}", "get a {size} convoy moving from {origin} to {dest}",
    "ship goods to {dest}", "I need a supply run to {dest}", "send supplies to {dest}, they are starving there",
    "send food and medicine to {dest}", "a {size} {faction} merchant fleet should go to {dest}",
    "have the {faction} send freighters to {dest}", "convoy to {dest} please", "traders out of {origin} bound for {dest}",
    "send a convoy to {dest} via {oplace}", "a {faction} cargo run from {origin} to {dest}, a {size} one",
    "start a trade route from {origin} to {dest} with {faction} freighters", "send a {points} point convoy to {dest}",
    "send a tanker convoy to {dest} from {origin}", "I want to rob a merchant convoy heading to {dest}",
    "put a {faction} trade fleet on the route to {dest}, I want to ambush it", "send a convoy to {dest}, the {ofaction} pay well there",
    "deliver this cargo to {dest}, {faction} traders please", "a smuggling run to {dest}", "{faction} smugglers to {dest}",
    "send relief supplies to {dest}", "help me escort a convoy to {dest}", "send a {size} trade fleet from {origin} to {dest} carrying {good}",
    "move {good} from {origin} to {dest}, independent traders", "send freighters to {dest} through {oplace}",
    "I'm docked at {oplace}, send a convoy from {origin} to {dest}", "the {ofaction} want {good}, send a convoy to {dest}",
    "send a convoy to {oplace}. Actually no, send it to {dest} instead", "send a {osize} convoy to {dest}, wait, make it {size}",
    "a {size} convoy of {faction} ships carrying {good} to {dest}", "send a convoy that's {multas} as big as a {size} one to {dest}",
    "a {faction} convoy from {origin} to {dest}", "{faction} freighters leaving {origin} for {dest}",
    "to {dest} from {origin}, a merchant convoy", "{origin} -> {dest} convoy", "{origin} to {dest}, a {size} convoy",
    "send traders to {dest} from {origin}", "send a convoy to {dest}, starting at {origin}", "run cargo from {origin} over to {dest}",
    "send a convoy to the {dest} star system", "convoy from {origin} to the {dest} system", "send a trade fleet to the {dest} system",
    "a {size} convoy to {dest} by way of {oplace}", "send freighters from {origin} to {dest} with a stop at {oplace}",
    "send a convoy from {origin} to {dest}", "traders from {origin} heading to {dest}", "a {size} trade fleet out of {origin} to {dest}",
    "send freighters to {dest}, they should leave from {origin}", "{faction} traders, {origin} to {dest}",
]
TRADE_COUNT = ["send {count} convoys to {dest}", "{count} {size} {faction} convoys to {dest}"]
TRADE_MULTI = [
    "two convoys: one to {dest1} and one to {dest2}", "convoys to {dest1} and {dest2}, both {size}",
    "send a convoy from {origin1} to {dest1} and another one from {origin2} to {dest2}",
    "a {size1} convoy to {dest1} and a {size2} convoy to {dest2}", "send convoys to {dest}, one from {origin1} and one from {origin2}",
    "send two convoys to {dest}, one from {origin1} and one from {origin2}, both {size}",
]

HELP = [
    "send help to {target}", "{target} needs help", "defend {target}", "protect {target} from the {ofaction}",
    "have the {faction} defend {target}", "send {faction} reinforcements to {target}", "help the {ofaction} at {target}",
    "I need backup at {target}", "the {ofaction} are attacking {target}, send help", "send a {size} relief fleet to {target}",
    "reinforce {target} with a {size} fleet", "get the {faction} to protect {target}", "the {faction} should send a {size} fleet to guard {target}",
    "I'm being chased near {target}, send backup", "help me fight off the {ofaction} at {target}", "send {points} points of help to {target}",
    "a {size} defense force for {target}, from anyone nearby", "guard {target}, the {ofaction} are coming",
    "the {ofaction} can't hold {target}, send help", "a {osize} {ofaction} fleet is attacking {target}, send a {size} relief fleet",
    "protect {target} from a {osize} {ofaction} fleet", "{target} is under siege by the {ofaction}, help them",
    "have the {faction} send a {size} fleet to defend {target}", "send help from the {faction} to {target}",
    "the {faction} want to help defend {target}, let them", "rescue the colonists at {target}", "{target} is falling, send everything nearby",
    "defend {target} with {multof} the {maxsize} fleet", "help me, I'm surrounded at {target}",
    "{ofaction} are raiding {target}, help!", "{target} is being raided by the {ofaction}, send help",
    "the {ofaction} hit {target} again, help them", "help {target} against the {ofaction}",
    "help {target}, the {ofaction} are bullying them", "{target} is under attack, have the {faction} defend it",
    "send help to {target}, the {ofaction} can't hold it alone", "the {ofaction} at {target} need help",
    "send help to {target} before the {ofaction} take it", "so this time have the {faction} defend {target}",
    "send help to {target}, the {ofaction} have been attacking for days", "{target} needs defenders against the {ofaction}",
]
HELP_NO_PLACE = ["help me!", "defend me from the {ofaction}", "I need backup", "send backup now", "protect me from the {ofaction}"]
HELP_MULTI = [
    "send help to {target1} and {target2}", "have the {faction1} defend {target1} and the {faction2} defend {target2}",
    "a {size1} relief fleet to {target1} and a {size2} one to {target2}", "have the {faction1} and the {faction2} defend {target}",
]

MIXED = {
    "spawn_fleet+trade_convoy": [
        "send a {size1} convoy to {dest1} and spawn a {size2} {faction2} fleet to ambush it",
        "a {faction1} trade fleet to {dest1}, and a {size2} {faction2} fleet near me",
        "spawn a {size1} {faction1} fleet and send a {size2} convoy to {dest2}",
    ],
    "spawn_fleet+request_help": [
        "have the {faction1} defend {target1} and spawn a {size2} {faction2} fleet near me",
        "send help to {target1} and give me a {size2} {faction2} fleet to fight",
    ],
    "trade_convoy+request_help": [
        "send a convoy to {dest1} and have the {faction2} protect {target2}",
        "send help to {target1} and a {size2} convoy to {dest2}",
    ],
    "spawn_fleet+trade_convoy+request_help": [
        "spawn {faction1}, send a convoy to {dest2} and help {target3}",
        "a {size1} {faction1} fleet, a convoy to {dest2} and help for {target3}",
    ],
}

CANCEL = [
    "never mind", "cancel that", "forget it", "don't send anything", "don't spawn anything", "scratch that, nothing",
    "I changed my mind, no fleets", "cancel the request", "no, forget the whole thing", "actually, don't do it",
    "never mind, false alarm", "nothing for now", "ignore what I said", "stop, don't send them", "call it off",
    "no fleets after all", "abort", "cancel everything", "forget the convoy", "don't send help after all",
    "please don't spawn anything right now", "actually, no fleets today", "never mind, nobody needs to go there",
    "don't spawn anything, I'm going to repair first", "cancel that, it was a false alarm",
]

OUT_OF_SCOPE = [
    "I love the {ofaction}", "the {ofaction} are so annoying", "{ofaction} is my favourite faction", "ugh, {ofaction} again",
    "the {ofaction} have the best ships", "how do I {action}?", "who leads the {ofaction}?",
    "what would happen if a {osize} {ofaction} fleet attacked {oplace}?", "could my fleet beat a {osize} {ofaction} fleet?",
    "is {oplace} a good place to trade?", "how strong are {ofaction} fleets?", "where can I buy {good}?",
    "yesterday a {osize} {ofaction} fleet destroyed me near {oplace}", "I traded {onum} tons of {good} at {oplace}",
    "last week I fought the {ofaction} at {oplace}", "my friend keeps telling me to spawn {ofaction}",
    "someone on the forum wrote 'spawn a {osize} {ofaction} fleet'", "if I had {onum} points I would ask for a {osize} {ofaction} fleet",
    "one day I will fight a {osize} {ofaction} armada",
    "hello", "thanks", "good game", "what can you do?", "I'm going to bed", "I dreamed about a {osize} {ofaction} fleet",
    "honestly the {ofaction} seem nice", "the {ofaction} are cool", "{ofaction} are fine I guess", "I kinda like the {ofaction}",
    "the {ofaction} look awesome", "{ofaction} ships are ugly", "what do you think of the {ofaction}?", "don't send {ofaction}",
    "no {ofaction} this time", "not the {ofaction}", "wait, no", "hmm, let me think", "actually, hold on", "okay",
    "the {ofaction} are attacking {oplace} right now", "{oplace} is being raided, I can see it on the map",
    "today I'm going to trade between {oplace} and {oplace}", "the {ofaction} took {onum} credits from me",
    "my brother typed 'send help to {oplace}' while I was away", "do you think the {ofaction} could take {oplace}?",
    "I wonder how many freighters {oplace} needs", "the patch made {ofaction} fleets bigger, I don't like it",
    "remember when you sent that {osize} {ofaction} fleet? good times", "I'm scared of {ofaction} fleets",
    "the {ofaction} are attacking {oplace} in my story",
    "no fights today, I'm just trading", "what is the biggest fleet you can spawn?", "the {ofaction} are fanatics",
    "I wish I could see a {osize} {ofaction} fleet one day", "my crew bet on what you'd send if I asked for something scary",
    "is it true that {ofaction} fleets are stronger near {oplace}?", "I'm not ready to fight the {ofaction} yet",
]

COUNTS_PER_TEMPLATE = {
    "spawn_fleet": 20, "trade_convoy": 22, "request_help": 24, "out_of_scope": 24,
}


def build(templates, n, noise=True):
    global SHOUT
    out = []
    for t in templates:
        for _ in range(n):
            SHOUT = RNG.random() < 0.05
            text = fill(t)
            out.append(with_noise(text) if noise else text)
            SHOUT = False
    return out


def dedupe(examples):
    seen, result = set(), []
    for e in examples:
        key = e.lower()
        if key not in seen:
            seen.add(key)
            result.append(e)
    return result


def main():
    intents = {
        "spawn_fleet": build(SPAWN, COUNTS_PER_TEMPLATE["spawn_fleet"]) + build(SPAWN_COUNT, 20) + build(SPAWN_MULTI, 25),
        "trade_convoy": build(TRADE, COUNTS_PER_TEMPLATE["trade_convoy"]) + build(TRADE_COUNT, 20) + build(TRADE_MULTI, 25),
        "request_help": build(HELP, COUNTS_PER_TEMPLATE["request_help"]) + build(HELP_NO_PLACE, 8) + build(HELP_MULTI, 25),
        # Plain background sentences and clauses on their own are not requests either: that's what most
        # sentences of a long message look like once it is split up
        "out_of_scope": build(OUT_OF_SCOPE, COUNTS_PER_TEMPLATE["out_of_scope"])
                        + build(STORIES, 8, noise=False) + build(CLAUSES, 4, noise=False),
        "cancel": build(CANCEL, 6, noise=False) + build(CANCEL, 6),
    }
    for name, templates in MIXED.items():
        intents[name] = build(templates, 30)

    os.makedirs(OUT_DIR, exist_ok=True)
    total = 0
    with open(os.path.join(OUT_DIR, "nlu.yml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Generated by generate_nlu.py - edit the templates there, not this file.\n")
        f.write('version: "3.1"\nnlu:\n')
        for name, examples in intents.items():
            examples = dedupe(examples)
            RNG.shuffle(examples)
            total += len(examples)
            f.write("- intent: %s\n  examples: |\n" % name)
            for e in examples:
                f.write("    - %s\n" % e)

    with open(os.path.join(OUT_DIR, "lookups.yml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Generated by generate_nlu.py\n")
        f.write('version: "3.1"\nnlu:\n')
        f.write("- lookup: place\n  examples: |\n")
        for p in PLACES:
            f.write("    - %s\n" % p)
        f.write("- lookup: faction_name\n  examples: |\n")
        names = sorted({n for names in FACTIONS.values() for n in names} | set(LEADERS) | set(HOMES))
        for n in names:
            f.write("    - %s\n" % n)

    counts = ", ".join("%s %d" % (k, len(dedupe(v))) for k, v in intents.items())
    print("wrote %d examples to data/nlu.yml (%s)" % (total, counts))


if __name__ == "__main__":
    main()
