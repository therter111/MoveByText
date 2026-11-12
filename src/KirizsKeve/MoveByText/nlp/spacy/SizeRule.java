package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.SizeWords;

import java.util.Arrays;
import java.util.List;

/** size: in a short request with no size marked, the size word is looked up in the text. */
final class SizeRule extends SpacyRule {

    private static final List<String> SIZE_TYPES = Arrays.asList("size", "points", "multiplier", "offset", "reference");

    SizeRule() {
        super("size");
    }

    @Override
    void finish(Sentence sentence) throws Exception {
        if (!sentence.isShort() || !sentence.isRequest() || sentence.hasAny(SIZE_TYPES)) return;
        Integer tier = SizeWords.tier(sentence.text());
        if (tier == null) return;
        // CommandBuilder reads the size word out of the whole sentence
        sentence.addWholeSentence("size", sentence.text());
        LOG.info("spaCy rule size: no size marked, found size " + tier + " in the text");
    }
}
