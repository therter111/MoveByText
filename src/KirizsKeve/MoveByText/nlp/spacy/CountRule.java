package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.CommandBuilder;
import KirizsKeve.MoveByText.nlp.NumberWords;
import org.json.JSONObject;

/** count: a count above CommandBuilder.MAX_COUNT is a misread size: "SPAWN PIRATES 200" is one 200-point fleet. */
final class CountRule extends SpacyRule {

    CountRule() {
        super("count");
    }

    @Override
    boolean keep(JSONObject entity) throws Exception {
        if (entity.optString("entity").equals("count")) {
            String value = entity.optString("value", "");
            Double n = NumberWords.parse(value);
            if (n != null && n > CommandBuilder.MAX_COUNT) {
                entity.put("entity", "points");
                LOG.info("spaCy rule count: " + value + " fleets is a size, read as points");
            }
        }
        return true;
    }
}
