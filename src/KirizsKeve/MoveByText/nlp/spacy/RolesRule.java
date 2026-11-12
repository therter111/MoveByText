package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.CommandBuilder;
import org.json.JSONObject;

import java.util.List;

/** roles: in a sentence with one intent, a place has the role that intent gives it: a help request defends its place. */
final class RolesRule extends SpacyRule {

    RolesRule() {
        super("roles");
    }

    @Override
    void finish(Sentence sentence) throws Exception {
        String intent = sentence.intent();
        boolean trade = intent.equals(CommandBuilder.TRADE_INTENT);
        boolean help = intent.equals(CommandBuilder.HELP_INTENT);
        boolean spawn = intent.equals(CommandBuilder.SPAWN_INTENT);
        if (!trade && !help && !spawn) return;

        List<JSONObject> entities = sentence.entities();
        for (int i = entities.size() - 1; i >= 0; i--) {
            JSONObject e = entities.get(i);
            String type = e.optString("entity");
            if (!PLACE_TYPES.contains(type)) continue;
            String role = type;
            if (spawn) role = null; // war fleets never go to a place
            else if (help) role = type.equals("origin") ? null : "target";
            else if (type.equals("target")) role = sentence.has("destination") ? null : "destination";
            if (role == null) {
                entities.remove(i);
                LOG.info("spaCy rule roles: " + type + " \"" + e.optString("value") + "\" dropped for " + intent);
            } else if (!role.equals(type)) {
                e.put("entity", role);
                LOG.info("spaCy rule roles: \"" + e.optString("value") + "\" is the " + role + " of " + intent);
            }
        }
    }
}
