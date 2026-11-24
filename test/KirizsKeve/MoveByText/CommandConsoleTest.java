package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;
import KirizsKeve.MoveByText.nlp.Backend;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for CommandConsole: one message at a time (busy until the answer arrives), cancel(), swapping
 * the backend when a game load reads the settings again, carrying out a reply's commands (at most
 * MAX_COMMANDS_PER_REPLY), the fleets that wait for the player's OK (confirm first: Spawn, Discard, a newer answer,
 * a save load), and what goes to the campaign screen and the panel. A fake backend holds every callback until the
 * test answers it.
 *
 * Without game() Global.getSector() is null, so nothing is spawned or shown on a campaign screen; a reply with a
 * fleet command in it would fail there, which is how "ignored" is told apart from "carried out". game() gives the
 * console a small fake sector and a fleet maker that records every fleet asked for.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class CommandConsoleTest {

    public static final TestSuite<Screen> SUITE = new TestSuite<>("CommandConsoleTest", Screen::new, Screen::stop);

    private static void test(String name, TestSuite.Test<Screen> body) {
        SUITE.add(name, body);
    }

    /** A reply that would try to spawn a fleet, which can't work outside the game. */
    private static final String SPAWN_REPLY = "<SPAWN:pirates:40>";

    static {
        test("submit: the text goes to the backend, busy until the answer arrives", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            check(!console.isBusy(), "idle at first");
            check(console.submit("spawn some pirates"), "sent");
            same(Collections.singletonList("spawn some pirates"), backend.asked, "asked");
            check(console.isBusy(), "busy while waiting");
            same(null, console.pollReply(), "no reply yet");

            backend.answer(0, "<NONE>");
            check(!console.isBusy(), "idle once answered");
            same("<NONE>", console.pollReply(), "the reply");
            has(String.join("\n", s.shown), "Nothing to do", "shown");
        });

        test("submit: empty and blank text is not sent", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            for (String blank : new String[]{null, "", "  \n\t"}) {
                check(!console.submit(blank), "not sent: " + show(blank));
            }
            same(0, backend.asked.size(), "nothing asked");
            check(!console.isBusy(), "idle");
        });

        test("busy: a second message is refused until the first is answered", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            check(console.submit("first"), "first sent");
            check(!console.submit("second"), "second refused");
            check(!console.submit("third"), "third refused");
            same(Collections.singletonList("first"), backend.asked, "only the first reached the backend");

            backend.answer(0, "<NONE>");
            check(console.submit("second"), "sent once the first was answered");
            same(Arrays.asList("first", "second"), backend.asked, "asked");
        });

        test("busy: an error ends the wait too, and its text is never read for commands", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.submit("spawn");
            backend.fail(0, "timed out " + SPAWN_REPLY);
            check(!console.isBusy(), "idle after the error");
            same(null, console.pollReply(), "no reply"); // a tag in the error would throw here if it were run
            has(String.join("\n", s.failed), "No answer: timed out", "shown as a failure");
        });

        test("busy: a backend that fails at once (not configured) never leaves the console busy", s -> {
            FakeBackend backend = new FakeBackend();
            backend.configured = false;
            CommandConsole console = new CommandConsole(backend);
            check(console.submit("spawn"), "handed to the backend");
            check(!console.isBusy(), "not busy");
            same(null, console.pollReply(), "no reply");
            has(String.join("\n", s.failed), "not configured", "the backend's reason is shown");
        });

        test("cancel: stops waiting at once, and the late answer is ignored", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.submit("spawn some pirates");
            console.cancel();
            check(!console.isBusy(), "not busy after cancel");
            has(String.join("\n", s.shown), "Cancelled", "shown");
            check(console.submit("something else"), "the next message can be sent");

            backend.answer(0, SPAWN_REPLY); // would throw outside the game if it were carried out
            same(null, console.pollReply(), "the cancelled answer is not carried out");
            check(console.isBusy(), "still waiting for the second message");
            backend.answer(1, "<NONE>");
            same("<NONE>", console.pollReply(), "the second answer is");
        });

        test("cancel: with nothing awaited it does nothing", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.cancel();
            same(0, s.shown.size(), "nothing shown");
            console.submit("hi");
            backend.answer(0, "<NONE>");
            same("<NONE>", console.pollReply(), "a later answer is not ignored");
        });

        test("waitingSeconds: 0 when idle, counts while waiting", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            same(0, console.waitingSeconds(), "idle");
            console.submit("hi");
            Thread.sleep(1100);
            check(console.waitingSeconds() >= 1, "at least a second: " + console.waitingSeconds());
            console.cancel();
            same(0, console.waitingSeconds(), "cancelled");
        });

        test("useBackend: new messages go to the new backend, the old one is closed and still answers", s -> {
            FakeBackend old = new FakeBackend(), next = new FakeBackend();
            CommandConsole console = new CommandConsole(old);
            console.submit("on the way");
            console.useBackend(next);
            check(old.closed, "old backend closed");
            check(!next.closed, "new one open");
            check(console.isBusy(), "the message on its way is still awaited");
            same("fake " + next.hashCode(), console.modelName(), "modelName from the new backend");

            old.answer(0, "<NONE>");
            same("<NONE>", console.pollReply(), "its answer arrives as usual");
            console.submit("next one");
            same(Collections.singletonList("next one"), next.asked, "asked the new backend");
            console.clearHistory();
            same(1, next.cleared, "clearHistory goes to the new backend");
            same(0, old.cleared, "not the old one");
        });

        test("useBackend: the same backend again is not closed", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.useBackend(backend);
            check(!backend.closed, "not closed");
        });
    }

    static {
        // ------------------------------------------------------------------------------------------
        // Carrying out a reply
        // ------------------------------------------------------------------------------------------

        test("carry out: the commands of a reply are carried out at once, in the order written", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.submit("pirates and the hegemony");
            backend.answer(0, "<SPAWN:pirates:40> and <SPAWN:hegemony:150>");
            same("<SPAWN:pirates:40> and <SPAWN:hegemony:150>", console.pollReply(), "the reply");
            same("pirates:40, hegemony:150", s.made(), "fleets made");
            same(Collections.emptyList(), console.pendingFleets(), "nothing waits for an OK");
            same(null, console.pollReply(), "nothing more");
        });

        test("carry out: up to MAX_COMMANDS_PER_REPLY commands; past that only the first ones, and the player is told", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.submit("ten");
            backend.answer(0, spawns(CommandConsole.MAX_COMMANDS_PER_REPLY));
            console.pollReply();
            same(CommandConsole.MAX_COMMANDS_PER_REPLY, s.made.size(), "all ten made");
            same(0, s.failed.size(), "no warning: " + s.failed);

            s.made.clear();
            console.submit("twelve");
            backend.answer(1, spawns(12));
            console.pollReply();
            same("pirates:1, pirates:2, pirates:3, pirates:4, pirates:5, pirates:6, pirates:7, pirates:8, pirates:9, pirates:10",
                    s.made(), "the first ten made, in order");
            has(String.join("\n", s.failed), "The answer had 12 commands, only the first 10 are carried out.", "warning");
        });

        // ------------------------------------------------------------------------------------------
        // Confirm first: the fleets wait for the player's OK
        // ------------------------------------------------------------------------------------------

        test("confirm first: the fleets wait, described in words, and spawn on confirm()", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            same("<SPAWN:pirates:40>", console.pollReply(), "the reply is still given back");
            same("", s.made(), "nothing made yet");
            same(Collections.singletonList("Pirates war fleet of 40 FP, next to you"), console.pendingFleets(),
                    "waiting, in words, with the faction's name as the game shows it");
            String shown = String.join("\n", s.shown);
            has(shown, "Ready: Pirates war fleet of 40 FP, next to you", "the player is told what waits");
            has(shown, "Spawn it or discard it in the Fleet commands panel.", "and what to do");
            fails(() -> console.pendingFleets().add("one more"), "the list the panel gets can't be changed");

            console.confirm();
            same("pirates:40", s.made(), "made on Spawn");
            same(Collections.emptyList(), console.pendingFleets(), "nothing waits any more");
            console.confirm();
            same("pirates:40", s.made(), "a second Spawn makes nothing");
        });

        test("confirm first: several fleets wait in order and spawn together; the wording says them", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("two fleets");
            backend.answer(0, "<SPAWN:pirates:40><SPAWN:hegemony:150>");
            console.pollReply();
            same(Arrays.asList("Pirates war fleet of 40 FP, next to you", "Hegemony war fleet of 150 FP, next to you"),
                    console.pendingFleets(), "both, in order");
            has(String.join("\n", s.shown), "Spawn them or discard them in the Fleet commands panel.", "plural");
            console.confirm();
            same("pirates:40, hegemony:150", s.made(), "both made");
        });

        test("confirm first: discard() drops them and nothing is spawned", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            console.pollReply();
            console.discard();
            same(Collections.emptyList(), console.pendingFleets(), "nothing waits");
            same(1, count(s.shown, "Discarded: nothing was spawned."), "told once");
            console.discard();
            console.confirm();
            same("", s.made(), "nothing made, by a second Discard or a Spawn after it");
            same(1, count(s.shown, "Discarded: nothing was spawned."), "a second Discard says nothing");
        });

        test("confirm first: a newer answer replaces the fleets that wait, and says so", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            console.pollReply();
            console.submit("no, the hegemony");
            backend.answer(1, "<SPAWN:hegemony:150>");
            console.pollReply();
            has(String.join("\n", s.shown), "The fleets that were waiting for your OK are dropped: a newer answer came.",
                    "told");
            same(Collections.singletonList("Hegemony war fleet of 150 FP, next to you"), console.pendingFleets(), "the newer one");
            console.confirm();
            same("hegemony:150", s.made(), "only the newer fleet made");
        });

        test("confirm first: an answer without commands, or an error, leaves the waiting fleets alone", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            console.pollReply();
            console.submit("thanks!");
            backend.answer(1, "<NONE>");
            console.pollReply();
            console.submit("and more?");
            backend.fail(2, "timed out");
            console.pollReply();
            same(Collections.singletonList("Pirates war fleet of 40 FP, next to you"), console.pendingFleets(), "still waiting");
            same(0, count(s.shown, "The fleets that were waiting for your OK are dropped: a newer answer came."), "not dropped");
            console.confirm();
            same("pirates:40", s.made(), "made");
        });

        test("dropPending: the waiting fleets go without a word (another save was loaded)", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.setConfirmFirst(true);
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            console.pollReply();
            int shownBefore = s.shown.size();
            console.dropPending();
            same(Collections.emptyList(), console.pendingFleets(), "nothing waits");
            console.confirm();
            console.discard();
            same("", s.made(), "nothing made");
            same(shownBefore, s.shown.size(), "nothing shown: " + s.shown);
        });

        test("confirm and discard with nothing waiting do nothing", s -> {
            CommandConsole console = s.game(new FakeBackend());
            console.setConfirmFirst(true);
            console.confirm();
            console.discard();
            same("", s.made(), "nothing made");
            same(0, s.shown.size() + s.failed.size(), "nothing shown: " + s.shown + s.failed);
        });

        // ------------------------------------------------------------------------------------------
        // What the player is shown, and where
        // ------------------------------------------------------------------------------------------

        test("screen: \"Sent to ...\" goes to the campaign screen only, as the panel has its waiting line", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.submit("some pirates");
            same(Collections.singletonList("Sent to " + backend.modelName() + ", waiting for the answer..."), s.campaign,
                    "campaign screen");
            same(0, s.shown.size(), "not in the panel: " + s.shown);
        });

        test("screen: a failure goes to the campaign screen in the warning color, the rest in the plain one", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            console.submit("one");
            backend.fail(0, "timed out");
            console.pollReply();
            console.submit("two");
            backend.answer(1, "<NONE>");
            console.pollReply();
            check(s.campaign.contains("[warning] No answer: timed out"), "the error, in color: " + s.campaign);
            check(s.campaign.contains("Nothing to do: the answer had no fleet command in it."), "plain: " + s.campaign);
        });

        test("screen: a panel listener that throws doesn't stop the command; the campaign screen still gets it", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = s.game(backend);
            TerminalLog.setScreenListener((message, failed) -> {
                throw new IllegalStateException("a broken panel");
            });
            console.submit("some pirates");
            backend.answer(0, "<SPAWN:pirates:40>");
            same("<SPAWN:pirates:40>", console.pollReply(), "the reply");
            same("pirates:40", s.made(), "made");
            check(s.campaign.stream().anyMatch(m -> m.contains("(40 FP) in Corvus")), "the spawn reached the campaign screen: "
                    + s.campaign);
        });
    }

    /** A reply of n SPAWN tags: <SPAWN:pirates:1><SPAWN:pirates:2>... */
    private static String spawns(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("<SPAWN:pirates:").append(i).append('>');
        return sb.toString();
    }

    private static int count(List<String> messages, String message) {
        return Collections.frequency(messages, message);
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    // ------------------------------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------------------------------

    /** Collects what the console shows the player; one per test, so tests don't see each other's messages. */
    static final class Screen {
        final List<String> shown = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        /** After game(): the campaign screen's message list; a failure as "[warning] message". */
        final List<String> campaign = new ArrayList<>();
        /** After game(): every fleet the console's FleetSpawner asked for. */
        final List<FleetSpawner.FleetRequest> made = new ArrayList<>();

        Screen() {
            TerminalLog.setScreenListener((message, isFailure) -> (isFailure ? failed : shown).add(message));
        }

        /**
         * A console that can carry commands out: a sector with the pirates and the Hegemony, the player in Corvus,
         * and a fleet maker that records each request and gives a fleet of 5 ships.
         */
        CommandConsole game(Backend backend) {
            FakeGame.World world = new FakeGame.World();
            world.faction("pirates", "Pirates", "Pirates", "Pirate", true);
            world.faction("hegemony", "Hegemony", "Hegemony", "Hegemony", true);
            StarSystemAPI corvus = world.system("Corvus", "*Corvus", "Jangala");
            world.market("Jangala", corvus, "hegemony");
            world.playerLocation = corvus;
            world.campaignUi = FakeGame.fake(CampaignUIAPI.class, "campaign UI", "addMessage", (FakeGame.Answer) a ->
                    campaign.add(a.length > 1 ? "[warning] " + a[0] : String.valueOf(a[0])));
            world.install();
            Global.setSettings(FakeGame.settings(Collections.<String, String>emptyMap(), true));
            FleetSpawner fleets = new FleetSpawner(request -> {
                made.add(request);
                return world.newFleet(request.factionId, 5, Math.round(request.combatPts)).api;
            });
            return new CommandConsole(backend, new PlaceFinder(), fleets);
        }

        /** The fleets asked for, as "faction:points, ...". */
        String made() {
            List<String> out = new ArrayList<>();
            for (FleetSpawner.FleetRequest r : made) out.add(r.factionId + ":" + Math.round(r.combatPts));
            return String.join(", ", out);
        }

        void stop() {
            TerminalLog.setScreenListener(null);
            FakeGame.uninstall();
        }
    }

    /** Keeps every callback until the test answers it; ask() answers at once only when not configured. */
    static final class FakeBackend implements Backend {
        final List<String> asked = new ArrayList<>();
        final List<Callback> callbacks = new ArrayList<>();
        boolean configured = true;
        boolean closed = false;
        int cleared = 0;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void ask(String userText, Callback callback) {
            if (!configured) {
                callback.onError("not configured");
                return;
            }
            asked.add(userText);
            callbacks.add(callback);
        }

        void answer(int i, String reply) {
            callbacks.get(i).onResponse(reply);
        }

        void fail(int i, String message) {
            callbacks.get(i).onError(message);
        }

        @Override
        public String askNow(String userText) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearHistory() {
            cleared++;
        }

        @Override
        public String modelName() {
            return "fake " + hashCode();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
