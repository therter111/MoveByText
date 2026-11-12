package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.PlaceNames;
import org.json.JSONObject;

/**
 * places: a place that is an ordinary English word ("Context", "Camembert") and not a known place is dropped.
 * The server says which words are ordinary: "plain_word".
 */
final class PlacesRule extends SpacyRule {

    PlacesRule() {
        super("places");
    }

    @Override
    boolean keep(JSONObject entity) {
        String value = entity.optString("value", "");
        if (PLACE_TYPES.contains(entity.optString("entity")) && entity.optBoolean("plain_word", false)
                && !PlaceNames.isKnown(value)) {
            LOG.info("spaCy rule places: \"" + value + "\" is an ordinary word, not a place, dropped");
            return false;
        }
        return true;
    }
}
