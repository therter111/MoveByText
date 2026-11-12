package KirizsKeve.MoveByText.nlp.spacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** split: "spawn X and send Y" is read as two pieces, one command each. */
final class SplitRule extends SpacyRule {

    /** "... and send ...", ", then spawn ...": where a sentence is cut, before the second command verb. */
    private static final Pattern SECOND_COMMAND = Pattern.compile("(?i)(?:\\s*,\\s*|\\s+)(?:and\\s+then|and|then)\\s+"
            + "(?=(?:send|spawn|have|give|get|bring|make|summon|throw|put|dispatch)\\b)");

    SplitRule() {
        super("split");
    }

    @Override
    List<String> pieces(List<String> sentences, int max) {
        List<String> out = new ArrayList<String>();
        for (String sentence : sentences) {
            for (String piece : SECOND_COMMAND.split(sentence)) {
                String p = piece.trim();
                if (!p.isEmpty() && out.size() < max) out.add(p);
            }
        }
        return out.isEmpty() ? sentences : out;
    }
}
