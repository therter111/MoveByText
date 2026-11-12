package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.CommandBuilder;
import KirizsKeve.MoveByText.nlp.FactionNames;
import org.json.JSONObject;

/**
 * faction: ordinary words marked as a faction ("a nearby faction should help Jangala") are dropped, and in a short
 * spawn command with no faction marked, the faction is looked up in the text.
 */
final class FactionRule extends SpacyRule {

    FactionRule() {
        super("faction");
    }

    @Override
    boolean keep(JSONObject entity) {
        String value = entity.optString("value", "");
        if (entity.optString("entity").equals("faction")
                && FactionNames.resolve(value) == null && value.equals(value.toLowerCase())) {
            LOG.info("spaCy rule faction: \"" + value + "\" is not a faction, dropped");
            return false; // an ordinary word; made-up names like "Klingon" are kept, so they still give <NONE>
        }
        return true;
    }

    @Override
    void finish(Sentence sentence) throws Exception {
        if (!sentence.isShort() || !sentence.intent().equals(CommandBuilder.SPAWN_INTENT) || sentence.has("faction")) {
            return;
        }
        String faction = FactionNames.resolve(sentence.text());
        if (faction != null) {
            sentence.addWholeSentence("faction", faction);
            LOG.info("spaCy rule faction: no faction marked, found " + faction + " in the text");
        }
    }
}
