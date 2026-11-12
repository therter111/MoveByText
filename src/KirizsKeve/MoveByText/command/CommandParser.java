package KirizsKeve.MoveByText.command;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Reads the command tags out of a provider's reply. Assumes the reply is right: text that isn't a well-formed tag
 * (stories, <NONE>, a typo) is skipped without complaint.
 */
public final class CommandParser {

    /** Every command in the text: the SPAWN tags first, then TRADE, then HELP, each in the order written. */
    public List<FleetCommand> parse(String text) {
        List<FleetCommand> commands = new ArrayList<FleetCommand>();

        Matcher spawn = SpawnCommand.TAG.matcher(text);
        while (spawn.find()) commands.add(SpawnCommand.from(spawn));

        Matcher trade = TradeCommand.TAG.matcher(text);
        while (trade.find()) commands.add(TradeCommand.from(trade));

        Matcher help = HelpCommand.TAG.matcher(text);
        while (help.find()) commands.add(HelpCommand.from(help));

        return commands;
    }
}
