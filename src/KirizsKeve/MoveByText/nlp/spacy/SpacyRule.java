package KirizsKeve.MoveByText.nlp.spacy;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One of SpacyClient's fixes to the spaCy model's answer, named in spacy.rules. A rule overrides the steps it
 * needs; SpacyClient runs every step for all its rules, in ALL order, before going on to the next step:
 * pieces (the message's sentences), then intent, keep (each entity), finish (the sentence as a whole).
 */
abstract class SpacyRule {

    static final Logger LOG = Global.getLogger(SpacyClient.class);

    static final List<String> PLACE_TYPES = Arrays.asList("destination", "origin", "target");

    /** Every rule, in the order they run. Rules hold no state, so these are shared by all clients. */
    static final List<SpacyRule> ALL = Collections.unmodifiableList(Arrays.<SpacyRule>asList(
            new CancelRule(), new FactionRule(), new SplitRule(), new PlacesRule(), new RolesRule(), new SizeRule(),
            new CountRule()));

    private final String name;

    SpacyRule(String name) {
        this.name = name;
    }

    final String name() {
        return name;
    }

    /** The pieces sent to the server, from the message's sentences; at most max of them. */
    List<String> pieces(List<String> sentences, int max) {
        return sentences;
    }

    /** May change which intent the sentence has. */
    void intent(Sentence sentence) throws Exception {
    }

    /** False drops the entity; a rule may also change it. */
    boolean keep(JSONObject entity) throws Exception {
        return true;
    }

    /** Changes to the kept entities, once all of them are read. */
    void finish(Sentence sentence) throws Exception {
    }

    /** The names of ALL, in order. */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        for (SpacyRule rule : ALL) names.add(rule.name());
        return names;
    }

    /** The rules with these names, in ALL order whatever order the names are in. */
    static List<SpacyRule> named(Set<String> names) {
        List<SpacyRule> rules = new ArrayList<>();
        for (SpacyRule rule : ALL) {
            if (names.contains(rule.name())) rules.add(rule);
        }
        return rules;
    }
}
