package KirizsKeve.MoveByText.command;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Reads the command tags out of a provider's reply. Assumes the reply is right: text that isn't a well-formed tag
 * (stories, <NONE>, a typo) is skipped without complaint.
 */
public final class CommandParser {

    /** Every command in the text, in the order written. */
    public List<FleetCommand> parse(String text) {
        // By where each tag starts; two tags never start at the same place
        TreeMap<Integer, FleetCommand> byPosition = new TreeMap<Integer, FleetCommand>();

        Matcher spawn = SpawnCommand.TAG.matcher(text);
        while (spawn.find()) byPosition.put(spawn.start(), SpawnCommand.from(spawn));

        Matcher trade = TradeCommand.TAG.matcher(text);
        while (trade.find()) byPosition.put(trade.start(), TradeCommand.from(trade));

        Matcher help = HelpCommand.TAG.matcher(text);
        while (help.find()) byPosition.put(help.start(), HelpCommand.from(help));

        return new ArrayList<FleetCommand>(byPosition.values());
    }
}
