package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for the editor panel (MultiLineTextFieldPanel): the toggle button and its key, typing (Enter,
 * Shift+Enter, Backspace, Up, Down), Send, Cancel and Forget chat, the waiting state, and what the conversation shows.
 * FakeUi stands in for the game's UI and a fake EditorHost for the console. What the game draws (GL11) and real
 * keyboard focus can only be seen in the game.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class PanelTest {

    /** The console as the panel sees it: records what the panel asked for, and answers as a test sets it up. */
    static final class Host implements EditorHost {
        final PanelState state = new PanelState();
        final List<String> sent = new ArrayList<>();
        boolean busy = false;
        boolean accepts = true;
        int seconds = 0;
        int cancels = 0;
        int clears = 0;

        @Override public boolean submit(String message) {
            sent.add(message);
            return accepts;
        }
        @Override public boolean isBusy() { return busy; }
        @Override public int waitingSeconds() { return seconds; }
        @Override public void cancel() { cancels++; }
        @Override public String modelName() { return "the fake model"; }
        @Override public void clearHistory() { clears++; }
        @Override public int panelKey() { return Keyboard.KEY_F9; }
        @Override public PanelState state() { return state; }
        final List<String> pending = new ArrayList<>();
        int confirms = 0, discards = 0;
        @Override public List<String> pendingFleets() { return pending; }
        @Override public void confirm() { confirms++; pending.clear(); }
        @Override public void discard() { discards++; pending.clear(); }
        @Override public List<String> completionNames() { return java.util.Arrays.asList("Jangala", "Port Tse", "Hegemony"); }
        @Override public float[] startPosition() { return null; }
    }

    /** The fake UI, a host and the panel made with them. */
    static final class Ui {
        final FakeUi ui = new FakeUi();
        final Host host = new Host();
        MultiLineTextFieldPanel panel = new MultiLineTextFieldPanel(host);

        void stop() {
            ui.stop();
        }

        FakeUi.Panel outer() {
            return ui.panel(panel.getPanel());
        }

        /** Opens the editor with the toggle button, gives its field the keyboard and draws the first frame. */
        CustomUIPanelPlugin open() {
            ui.click(ui.button("Fleet commands (F9)"));
            CustomUIPanelPlugin editor = editor();
            ui.field().focus = true;
            editor.advance(0.1f);
            return editor;
        }

        CustomUIPanelPlugin editor() {
            return ui.panelOf("EditorPanel").plugin;
        }

        /** Types a line into the field, as the player would before pressing a key. */
        void type(String text) {
            ui.field().text = text;
            editor().advance(0.1f); // the frame between typing and the key
        }

        FakeUi.Key press(int key, boolean ctrl) {
            FakeUi.Key k = FakeUi.press(editor(), key, ctrl, false);
            editor().advance(0.1f);
            return k;
        }

        /** The key with Shift held, e.g. Shift+Enter for a new line. */
        FakeUi.Key shiftPress(int key) {
            FakeUi.Key k = FakeUi.press(editor(), key, false, true);
            editor().advance(0.1f);
            return k;
        }
    }

    public static final TestSuite<Ui> SUITE = new TestSuite<>("PanelTest", Ui::new, Ui::stop);

    private static void test(String name, TestSuite.Test<Ui> body) {
        SUITE.add(name, body);
    }

    static {
        // ------------------------------------------------------------------------------------------
        // The toggle button
        // ------------------------------------------------------------------------------------------

        test("toggle: the button names the panel's key and is pressed by it", u -> {
            FakeUi.Button toggle = u.ui.button("Fleet commands (F9)");
            same(Keyboard.KEY_F9, toggle.shortcut, "shortcut");
        });

        test("toggle: the editor is in the panel only while it is open", u -> {
            same(1, u.outer().children.size(), "closed: only the toggle button's tooltip");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            same(2, u.outer().children.size(), "open: the editor too");
            check(u.outer().children.contains(u.ui.panelOf("EditorPanel").api), "the editor's panel is in it");
            check(u.host.state.editorOpen, "the host's state says open");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            same(1, u.outer().children.size(), "closed again");
            check(!u.host.state.editorOpen, "and the state says so");
        });

        test("toggle: a panel made again after a save load opens as it was", u -> {
            u.host.state.editorOpen = true;
            MultiLineTextFieldPanel again = new MultiLineTextFieldPanel(u.host);
            same(2, u.ui.panel(again.getPanel()).children.size(), "open right away");
        });

        // ------------------------------------------------------------------------------------------
        // Typing
        // ------------------------------------------------------------------------------------------

        test("typing: the conversation starts with how to use it", u -> {
            u.open();
            has(u.ui.logText(), "Type an order for a fleet below and press Enter to send it. Shift+Enter starts a new"
                    + " line", "help line");
        });

        test("typing: keys only count while the field has the keyboard", u -> {
            u.open();
            u.ui.field().focus = false;
            u.type("spawn pirates");
            u.editor().advance(0.1f); // a frame without focus, so hadFocus is false too
            FakeUi.Key enter = u.press(Keyboard.KEY_RETURN, false);
            check(!enter.consumed, "Enter left to the game");
            same("spawn pirates", u.ui.field().text, "the text stays");
        });

        test("typing: Shift+Enter moves the line up as a draft, Backspace on an empty field takes it back", u -> {
            u.open();
            u.type("spawn a small");
            check(u.shiftPress(Keyboard.KEY_RETURN).consumed, "Shift+Enter taken");
            same(0, u.host.sent.size(), "nothing sent");
            same("", u.ui.field().text, "the field is empty again");
            has(u.ui.logText(), "Your message so far (Enter sends it):\nspawn a small", "the draft shows");
            check(u.press(Keyboard.KEY_BACK, false).consumed, "Backspace taken");
            same("spawn a small", u.ui.field().text, "the line is back in the field");
            check(!u.ui.logText().contains("Your message so far"), "no draft left: " + u.ui.logText());
        });

        test("typing: Backspace with text in the field is left to the field", u -> {
            u.open();
            u.type("spawn");
            check(!u.press(Keyboard.KEY_BACK, false).consumed, "not taken");
        });

        // ------------------------------------------------------------------------------------------
        // Sending
        // ------------------------------------------------------------------------------------------

        test("send: Enter sends every line, shows them and empties the editor", u -> {
            u.open();
            u.type("spawn a small");
            u.shiftPress(Keyboard.KEY_RETURN);
            u.type("pirate fleet");
            check(u.press(Keyboard.KEY_RETURN, false).consumed, "Enter taken");
            same(Collections.singletonList("spawn a small\npirate fleet"), u.host.sent, "sent");
            same("", u.ui.field().text, "field emptied");
            has(u.ui.logText(), "> spawn a small\n> pirate fleet", "shown as sent");
            check(!u.ui.logText().contains("Your message so far"), "no draft left");
        });

        test("send: Ctrl+Enter sends too, as it did before Enter did", u -> {
            u.open();
            u.type("spawn pirates");
            check(u.press(Keyboard.KEY_RETURN, true).consumed, "Ctrl+Enter taken");
            same(Collections.singletonList("spawn pirates"), u.host.sent, "sent");
        });

        test("send: the Send button does the same, and nothing happens with nothing written", u -> {
            u.open();
            u.ui.click(u.ui.button("Send"));
            same(0, u.host.sent.size(), "nothing sent for an empty editor");
            u.type("spawn pirates");
            u.ui.click(u.ui.button("Send"));
            same(Collections.singletonList("spawn pirates"), u.host.sent, "sent");
        });

        test("send: when the console doesn't take it, the player is told", u -> {
            u.open();
            u.host.accepts = false;
            u.type("spawn pirates");
            u.press(Keyboard.KEY_RETURN, false);
            has(u.ui.logText(), "Not sent: the mod isn't ready yet.", "note");
        });

        test("send: a % the player types is shown as typed, never used as a format", u -> {
            u.open();
            u.type("100% of the pirates, %s %d");
            u.press(Keyboard.KEY_RETURN, false);
            boolean found = false;
            for (FakeUi.Para p : u.ui.log()) {
                same("%s", p.format, "every paragraph's format");
                if (p.args.contains("> 100% of the pirates, %s %d")) found = true;
            }
            check(found, "the message is an argument: " + u.ui.logText());
        });

        // ------------------------------------------------------------------------------------------
        // Waiting for the answer
        // ------------------------------------------------------------------------------------------

        test("busy: Send is off and Cancel on, and the conversation says how long it has been", u -> {
            u.open();
            same(true, u.ui.button("Send").enabled, "Send on while idle");
            same(false, u.ui.button("Cancel").enabled, "Cancel off while idle");
            u.host.busy = true;
            u.host.seconds = 3;
            u.editor().advance(0.1f);
            same(false, u.ui.button("Send").enabled, "Send off while waiting");
            same(true, u.ui.button("Cancel").enabled, "Cancel on while waiting");
            has(u.ui.logText(), "Waiting for the fake model... 3 s", "waiting line");
            u.host.seconds = 4;
            u.editor().advance(0.1f);
            has(u.ui.logText(), "Waiting for the fake model... 4 s", "the count goes on");
            u.host.busy = false;
            u.editor().advance(0.1f);
            same(true, u.ui.button("Send").enabled, "Send back on");
            same(false, u.ui.button("Cancel").enabled, "Cancel back off");
            check(!u.ui.logText().contains("Waiting for"), "no waiting line: " + u.ui.logText());
        });

        test("busy: Enter keeps the text and only says why", u -> {
            u.open();
            u.host.busy = true;
            u.type("spawn pirates");
            u.press(Keyboard.KEY_RETURN, false);
            same(0, u.host.sent.size(), "nothing sent");
            same("spawn pirates", u.ui.field().text, "the text stays");
            has(u.ui.logText(), "Still waiting for the answer to the last message", "note");
        });

        test("busy: Cancel tells the console to stop waiting", u -> {
            u.open();
            u.host.busy = true;
            u.editor().advance(0.1f);
            u.ui.click(u.ui.button("Cancel"));
            same(1, u.host.cancels, "cancel() calls");
        });

        // ------------------------------------------------------------------------------------------
        // Earlier messages, Forget chat, what the console says
        // ------------------------------------------------------------------------------------------

        test("history: Up brings back sent messages, newest first; Down goes forward, then to an empty editor", u -> {
            u.open();
            for (String m : new String[]{"one", "two"}) {
                u.type(m);
                u.press(Keyboard.KEY_RETURN, false);
            }
            u.press(Keyboard.KEY_UP, false);
            same("two", u.ui.field().text, "Up");
            u.press(Keyboard.KEY_UP, false);
            same("one", u.ui.field().text, "Up again");
            u.press(Keyboard.KEY_DOWN, false);
            same("two", u.ui.field().text, "Down");
            u.press(Keyboard.KEY_DOWN, false);
            same("", u.ui.field().text, "past the newest");
        });

        test("history: the sent messages are the host's, so they outlive the panel", u -> {
            u.open();
            u.type("from before the load");
            u.press(Keyboard.KEY_RETURN, false);
            new MultiLineTextFieldPanel(u.host); // the panel made again after a save load
            u.ui.field().focus = true;
            u.press(Keyboard.KEY_UP, false);
            same("from before the load", u.ui.field().text, "the new panel's Up");
        });

        test("Forget chat: the conversation is cleared and the console forgets it too", u -> {
            u.open();
            u.type("spawn pirates");
            u.press(Keyboard.KEY_RETURN, false);
            u.ui.click(u.ui.button("Forget chat"));
            u.editor().advance(0.1f);
            same(1, u.host.clears, "clearHistory() calls");
            same("Chat forgotten: the next message starts a new conversation.", u.ui.logText(), "all that is left");
        });

        test("what the console shows the player appears in the conversation", u -> {
            u.open();
            new TerminalLog("TEST", PanelTest.class).show("Spawned Pirate Patrol (40 FP) in Corvus");
            new TerminalLog("TEST", PanelTest.class).fail("SPAWN FAILED: no such faction");
            u.editor().advance(0.1f);
            has(u.ui.logText(), "Spawned Pirate Patrol (40 FP) in Corvus\nSPAWN FAILED: no such faction", "shown");
            List<FakeUi.Para> log = u.ui.log();
            FakeUi.Para ok = log.get(log.size() - 2), failed = log.get(log.size() - 1);
            check(!ok.color.equals(failed.color), "a failure in another color than a message: " + ok.color + " / " + failed.color);
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }
}
