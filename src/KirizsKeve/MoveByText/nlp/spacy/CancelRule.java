package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.CommandBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/** cancel: a sentence only counts as "cancel" when it says so; the model read stories as "never mind". */
final class CancelRule extends SpacyRule {

    /** Words a real "cancel" sentence has: "never mind", "forget it", "don't send them", "no fleets after all". */
    private static final Pattern CANCEL_WORDS = Pattern.compile("(?i)\\b(never ?mind|nevermind|cancel\\w*|forget|scratch"
            + "|abort|call (it|that|them) off|ignore|changed my mind|don'?t|do not|no (fleets?|need|more)|nothing"
            + "|stop|hold off|not anymore|undo|take (it|that) back)\\b");

    CancelRule() {
        super("cancel");
    }

    @Override
    void intent(Sentence sentence) throws Exception {
        if (!sentence.intent().equals(CommandBuilder.CANCEL_INTENT) || CANCEL_WORDS.matcher(sentence.text()).find()) {
            return;
        }
        sentence.setIntent(nextIntent(sentence.parse()));
        LOG.info("spaCy rule cancel: no cancel words, read as " + sentence.intent());
    }

    /** The best intent after "cancel" in the server's ranking, or "not a request" when there is none. */
    private static JSONObject nextIntent(JSONObject parse) throws Exception {
        JSONArray ranking = parse.optJSONArray("intent_ranking");
        for (int i = 0; ranking != null && i < ranking.length(); i++) {
            JSONObject r = ranking.optJSONObject(i);
            if (r != null && !r.optString("name").equals(CommandBuilder.CANCEL_INTENT)) {
                return new JSONObject().put("name", r.optString("name")).put("confidence", r.optDouble("confidence", 0));
            }
        }
        return new JSONObject().put("name", "out_of_scope").put("confidence", 1.0);
    }
}
