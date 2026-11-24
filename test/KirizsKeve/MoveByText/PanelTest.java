package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for the editor panel (MultiLineTextFieldPanel): the toggle button, its key and its "*", typing
 * (Enter, Shift+Enter, Backspace, Up, Down, Tab, Esc), Send, Cancel, Forget chat, Spawn and Discard, the waiting
 * state, what the conversation shows, the panel's size, putting it on the screen and dragging it by the grip, what
 * PanelState keeps across save loads, and when the panel is shown (CampaignMultiLineScreenPlugin.shouldShow).
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
        /** "panelSize" as the test sets it; null for the default. */
        float[] startSize = null;
        @Override public float[] startSize() { return startSize; }
        /** The clipboard: what Ctrl+V reads and Ctrl+C writes; writable false makes writing fail. */
        String clipboard = "";
        boolean writable = true;
        @Override public String readClipboard() { return clipboard; }
        @Override public boolean writeClipboard(String text) {
            if (writable) clipboard = text;
            return writable;
        }
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

        /** Opens the editor with the toggle button, gives its box the keyboard and draws the first frame. */
        CustomUIPanelPlugin open() {
            ui.click(ui.button("Fleet commands (F9)"));
            CustomUIPanelPlugin editor = editor();
            ui.field().focus = true; // the box's focus field: the newest text field
            editor.advance(0.1f);
            return editor;
        }

        CustomUIPanelPlugin editor() {
            return ui.panelOf("EditorPanel").plugin;
        }

        /** The box the message is written in. */
        TextAreaPanel area() {
            return (TextAreaPanel) ui.panelOf("TextAreaPanel").plugin;
        }

        /** The message being written, as the editor holds it. */
        String text() {
            return host.state.editor.text();
        }

        /** Types text key by key, as the player would; a line break is Shift+Enter. Then a frame goes by. */
        void type(String text) {
            for (char c : text.toCharArray()) {
                if (c == '\n') FakeUi.press(editor(), Keyboard.KEY_RETURN, false, true);
                else FakeUi.type(editor(), c);
            }
            editor().advance(0.1f);
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

        /** The key with Ctrl and Shift held. */
        FakeUi.Key ctrlShiftPress(int key) {
            FakeUi.Key k = FakeUi.press(editor(), new FakeUi.Key(key, '\0', true, true, false));
            editor().advance(0.1f);
            return k;
        }

        /** The lines the box shows now, as "[line|line|...]", the empty ones at the end left out. */
        String shownLines() {
            List<String> lines = new ArrayList<>();
            List<FakeUi.Para> paras = ui.panelOf("TextAreaPanel").tooltips.get(0).paras;
            for (int i = 0; i < TextAreaPanel.VISIBLE_LINES; i++) lines.add(paras.get(i).text());
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
            return "[" + String.join("|", lines) + "]";
        }

        /** The character count right of the buttons: the editor's second tooltip's only paragraph. */
        FakeUi.Para counter() {
            return ui.panelOf("EditorPanel").tooltips.get(1).paras.get(0);
        }

        /** The box's hint label: the paragraph after its lines. */
        String hintShown() {
            return ui.panelOf("TextAreaPanel").tooltips.get(0).paras.get(TextAreaPanel.VISIBLE_LINES).text();
        }

        /**
         * Tells the box where the game says it is: bottom left at (x, y) on the screen. Returns the point on the
         * screen of the gap before character column on the box's row-th line, in the middle of its height.
         */
        float[] placeBox(float x, float y, int row, int column) {
            FakeUi.Position at = ui.panelOf("TextAreaPanel").position;
            at.x = x;
            at.y = y;
            float top = y + at.height - TextAreaPanel.INSET;
            return new float[]{x + TextAreaPanel.INSET + column * FakeUi.CHAR_WIDTH,
                    top - row * area().lineHeight() - area().lineHeight() / 2};
        }

        /**
         * Puts the closed panel on the screen with its top left corner at (x, y), and tells it where the game then
         * says it is (bottom left, y upwards). Returns the middle of the grip, in the mouse's coordinates.
         */
        float[] attach(FakeUi.CampaignScreen screen, float x, float y) {
            panel.attachTo(screen.api, x, y);
            FakeUi.Position at = outer().position;
            at.x = x;
            at.y = FakeUi.SCREEN_HEIGHT - y - at.height;
            float gripLeft = at.x + MultiLineTextFieldPanel.CLOSED_WIDTH - MultiLineTextFieldPanel.GRIP_WIDTH;
            return new float[]{gripLeft + MultiLineTextFieldPanel.GRIP_WIDTH / 2, at.y + at.height / 2};
        }

        /**
         * Puts the panel on the screen at (x, y), opens the editor and tells the panel where the game says it is
         * now. Returns the middle of the resize corner, in the mouse's coordinates.
         */
        float[] attachOpen(float x, float y) {
            panel.attachTo(ui.screen().api, x, y);
            open();
            return corner(x, y);
        }

        /** The middle of the open editor's resize corner, with the panel's top left at (x, y), as the game reports it. */
        float[] corner(float x, float y) {
            FakeUi.Position at = outer().position;
            at.x = x;
            at.y = FakeUi.SCREEN_HEIGHT - y - at.height;
            return new float[]{at.x + at.width - MultiLineTextFieldPanel.RESIZE_GRIP / 2, at.y + MultiLineTextFieldPanel.RESIZE_GRIP / 2};
        }

        /** A mouse event for the panel itself, which handles the grip. */
        FakeUi.Mouse mouse(FakeUi.MouseAction action, float x, float y) {
            return FakeUi.mouse(panel, action, x, y);
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

        test("typing: keys only count while the box has the keyboard", u -> {
            u.open();
            u.host.state.editor.setText("spawn pirates");
            u.ui.field().focus = false;
            u.editor().advance(0.1f); // a frame without focus, so hadFocus is false too
            check(!FakeUi.type(u.editor(), 'x').consumed, "a letter left to the game");
            FakeUi.Key enter = u.press(Keyboard.KEY_RETURN, false);
            check(!enter.consumed, "Enter left to the game");
            same("spawn pirates", u.text(), "the text stays");
            same(0, u.host.sent.size(), "nothing sent");
        });

        test("typing: every key is the box's while it has the keyboard, so the game's hotkeys stay off; but not the panel key", u -> {
            u.open();
            check(FakeUi.type(u.editor(), 'i').consumed, "a letter (I is the intel tab)");
            check(FakeUi.type(u.editor(), ' ').consumed, "space (pause)");
            check(u.press(Keyboard.KEY_F1, false).consumed, "a key that types nothing");
            check(u.press(Keyboard.KEY_S, true).consumed, "a Ctrl key the editor has no use for");
            check(!u.press(Keyboard.KEY_F9, false).consumed, "the panel's own key, so its button closes the editor");
            same("i ", u.text(), "typed");
        });

        test("typing: characters go in at the cursor; Shift+Enter starts a new line in the box", u -> {
            u.open();
            u.type("spawn pirates");
            for (int i = 0; i < "pirates".length(); i++) u.press(Keyboard.KEY_LEFT, false);
            u.type("big ");
            same("spawn big pirates", u.text(), "inserted at the cursor");
            u.press(Keyboard.KEY_END, true);
            check(u.shiftPress(Keyboard.KEY_RETURN).consumed, "Shift+Enter taken");
            u.type("near Jangala");
            same("spawn big pirates\nnear Jangala", u.text(), "two lines");
            same("[spawn big pirates|near Jangala]", u.shownLines(), "both shown in the box");
            same(0, u.host.sent.size(), "nothing sent");
            check(!u.ui.logText().contains("spawn big"), "nothing of it in the conversation: " + u.ui.logText());
        });

        test("typing: AltGr (Ctrl+Alt) types its character, as for @ on many keyboards", u -> {
            u.open();
            FakeUi.press(u.editor(), new FakeUi.Key(Keyboard.KEY_V, '@', true, false, true));
            same("@", u.text(), "typed, not pasted");
        });

        test("typing: Backspace and Delete, by a word with Ctrl", u -> {
            u.open();
            u.type("send the hegemony fleet");
            u.press(Keyboard.KEY_BACK, false);
            same("send the hegemony flee", u.text(), "Backspace");
            u.press(Keyboard.KEY_BACK, true);
            same("send the hegemony ", u.text(), "Ctrl+Backspace");
            u.press(Keyboard.KEY_HOME, true);
            u.press(Keyboard.KEY_DELETE, true);
            same("the hegemony ", u.text(), "Ctrl+Home, Ctrl+Delete");
            u.press(Keyboard.KEY_DELETE, false);
            same("he hegemony ", u.text(), "Delete");
        });

        test("typing: Up and Down move between the lines, Home and End along one, and never bring back old messages", u -> {
            u.open();
            u.type("sent before");
            u.press(Keyboard.KEY_RETURN, false);
            u.type("abcdef\nab\nabcdef");
            u.press(Keyboard.KEY_LEFT, false); // column 5 of the third line
            u.press(Keyboard.KEY_UP, false);
            same(9, u.host.state.editor.cursor(), "Up: the end of the short line");
            u.press(Keyboard.KEY_UP, false);
            same(5, u.host.state.editor.cursor(), "Up again: column 5 of the first line");
            u.press(Keyboard.KEY_UP, false);
            same(0, u.host.state.editor.cursor(), "Up on the first line: its start");
            same("abcdef\nab\nabcdef", u.text(), "the old message didn't come back");
            u.press(Keyboard.KEY_END, false);
            same(6, u.host.state.editor.cursor(), "End");
            u.press(Keyboard.KEY_DOWN, false);
            u.press(Keyboard.KEY_HOME, false);
            same(7, u.host.state.editor.cursor(), "Down, Home: the second line's start");
        });

        test("typing: Shift with the arrows, Home and End selects; typing replaces the selection", u -> {
            u.open();
            u.type("spawn pirates now");
            u.press(Keyboard.KEY_HOME, false);
            u.ctrlShiftPress(Keyboard.KEY_RIGHT);
            same("spawn ", u.host.state.editor.selected(), "Ctrl+Shift+Right");
            u.type("send ");
            same("send pirates now", u.text(), "replaced");
            u.shiftPress(Keyboard.KEY_END);
            same("pirates now", u.host.state.editor.selected(), "Shift+End");
            u.press(Keyboard.KEY_BACK, false);
            same("send ", u.text(), "Backspace takes the selection");
        });

        test("typing: a long message wraps over the box's lines, and the box scrolls to the cursor", u -> {
            u.open();
            StringBuilder words = new StringBuilder();
            for (int i = 0; i < 70; i++) words.append("pirates ");
            u.type(words.toString().trim());
            TextLayout layout = u.area().layout();
            check(layout.lines() > TextAreaPanel.VISIBLE_LINES, "more lines than the box shows: " + layout.lines());
            same(layout.lines() - TextAreaPanel.VISIBLE_LINES, u.area().firstLine(), "scrolled to the last lines");
            String shown = u.shownLines();
            same(layout.line(layout.lines() - 1), shown.substring(shown.lastIndexOf('|') + 1, shown.length() - 1),
                    "the last line shown is the cursor's");
            float widest = 0;
            for (int i = 0; i < layout.lines(); i++) widest = Math.max(widest, FakeUi.CHAR_WIDTH * layout.line(i).trim().length());
            check(widest <= MultiLineTextFieldPanel.DEFAULT_WIDTH - 20 - 2 * TextAreaPanel.INSET, "no line wider than the box: " + widest);
            u.press(Keyboard.KEY_HOME, true);
            same(0, u.area().firstLine(), "Ctrl+Home: back at the top");
        });

        test("typing: at most 1000 characters; the count says how many, in warning colors near and at the limit", u -> {
            u.open();
            same("0 / 1000", u.counter().text(), "empty");
            u.type("spawn");
            same("5 / 1000", u.counter().text(), "five");
            Color plain = u.counter().colorNow;
            u.host.state.editor.setText("x".repeat(950));
            u.editor().advance(0.1f);
            check(!u.counter().colorNow.equals(plain), "past 900 another color");
            Color near = u.counter().colorNow;
            u.host.state.editor.setText("x".repeat(999));
            u.type("yz");
            same(1000, u.text().length(), "cut at 1000");
            same("1000 / 1000", u.counter().text(), "full");
            check(!u.counter().colorNow.equals(near) && !u.counter().colorNow.equals(plain), "a third color when full");
        });

        test("typing: the box shows a hint while it is empty", u -> {
            u.open();
            has(u.hintShown(), "Type an order", "empty: the hint");
            u.type("s");
            same("", u.hintShown(), "typing: no hint");
            u.press(Keyboard.KEY_BACK, false);
            has(u.hintShown(), "Type an order", "empty again: the hint again");
        });

        test("typing: the focus field the game types into is emptied every frame, so nothing is typed twice", u -> {
            u.open();
            u.ui.field().text = "s"; // the game's own field took the letter too
            FakeUi.type(u.editor(), 's');
            same("", u.ui.field().text, "emptied after the keys");
            same("s", u.text(), "the letter once");
            u.ui.field().text = "x";
            u.editor().advance(0.1f);
            same("", u.ui.field().text, "and on every frame");
        });

        // ------------------------------------------------------------------------------------------
        // Clipboard, Clear, Ctrl+A
        // ------------------------------------------------------------------------------------------

        test("clipboard: Ctrl+C copies the selection, Ctrl+X cuts it, Ctrl+V pastes at the cursor", u -> {
            u.open();
            u.type("spawn pirates");
            u.press(Keyboard.KEY_LEFT, true);
            u.ctrlShiftPress(Keyboard.KEY_END);
            check(u.press(Keyboard.KEY_C, true).consumed, "Ctrl+C taken");
            same("pirates", u.host.clipboard, "copied");
            same("spawn pirates", u.text(), "copying leaves the text");
            u.press(Keyboard.KEY_X, true);
            same("spawn ", u.text(), "cut");
            u.host.clipboard = "a huge\r\nhegemony\tfleet";
            u.press(Keyboard.KEY_V, true);
            same("spawn a huge\nhegemony fleet", u.text(), "pasted, line ends and tabs cleaned");
        });

        test("clipboard: Ctrl+C with nothing selected changes nothing; a failed copy is said and cuts nothing", u -> {
            u.open();
            u.type("spawn pirates");
            u.host.clipboard = "before";
            u.press(Keyboard.KEY_C, true);
            same("before", u.host.clipboard, "nothing selected, nothing copied");
            u.press(Keyboard.KEY_A, true);
            u.host.writable = false;
            u.press(Keyboard.KEY_X, true);
            same("spawn pirates", u.text(), "nothing cut");
            has(u.ui.logText(), "Couldn't copy", "said");
        });

        test("clipboard: a paste that doesn't fit goes in as far as it does, and the player is told", u -> {
            u.open();
            u.host.state.editor.setText("x".repeat(995));
            u.host.clipboard = "0123456789";
            u.press(Keyboard.KEY_V, true);
            same("x".repeat(995) + "01234", u.text(), "what fits");
            has(u.ui.logText(), "Pasted what fits: a message holds at most 1000 characters.", "said");
        });

        test("Ctrl+A selects everything; Clear empties the box", u -> {
            u.open();
            u.type("spawn\npirates");
            u.press(Keyboard.KEY_A, true);
            same("spawn\npirates", u.host.state.editor.selected(), "all of it");
            u.ui.click(u.ui.button("Clear"));
            same("", u.text(), "cleared");
            same(0, u.host.sent.size(), "nothing sent");
        });

        // ------------------------------------------------------------------------------------------
        // The mouse in the box
        // ------------------------------------------------------------------------------------------

        test("mouse: a click puts the cursor at the nearest gap and gives the box the keyboard", u -> {
            u.open();
            u.type("spawn pirates\nnear Jangala");
            float[] at = u.placeBox(100, 300, 1, 5);
            FakeUi.Mouse click = FakeUi.mouse(u.area(), FakeUi.MouseAction.PRESS, at[0] + 2, at[1]);
            check(click.consumed, "taken, so the click doesn't move the fleet");
            same(19, u.host.state.editor.cursor(), "line 2, column 5");
            FakeUi.mouse(u.area(), FakeUi.MouseAction.RELEASE, at[0], at[1]);
            float[] off = u.placeBox(100, 300, 0, 0);
            check(!FakeUi.mouse(u.area(), FakeUi.MouseAction.PRESS, off[0] - 50, off[1]).consumed, "a click left of the box isn't");
        });

        test("mouse: dragging selects, Shift+click extends the selection, a double click selects a word", u -> {
            u.open();
            u.type("send to Kanta's Den now");
            float[] from = u.placeBox(100, 300, 0, 8);
            float[] to = u.placeBox(100, 300, 0, 19);
            FakeUi.mouse(u.area(), FakeUi.MouseAction.PRESS, from[0], from[1]);
            FakeUi.mouse(u.area(), FakeUi.MouseAction.MOVE, to[0], to[1]);
            FakeUi.mouse(u.area(), FakeUi.MouseAction.RELEASE, to[0], to[1]);
            same("Kanta's Den", u.host.state.editor.selected(), "dragged over");
            float[] end = u.placeBox(100, 300, 0, 23);
            FakeUi.mouse(u.area(), FakeUi.MouseAction.SHIFT_PRESS, end[0], end[1]);
            same("Kanta's Den now", u.host.state.editor.selected(), "Shift+click");
            FakeUi.mouse(u.area(), FakeUi.MouseAction.RELEASE, end[0], end[1]);
            float[] word = u.placeBox(100, 300, 0, 10);
            FakeUi.mouse(u.area(), FakeUi.MouseAction.DOUBLE_CLICK, word[0], word[1]);
            same("Kanta's", u.host.state.editor.selected(), "double click");
            check(!FakeUi.mouse(u.area(), FakeUi.MouseAction.MOVE, end[0], end[1]).consumed, "no drag after a double click");
        });

        test("mouse: the wheel scrolls a long message without moving the cursor", u -> {
            u.open();
            u.type("1\n2\n3\n4\n5\n6\n7\n8");
            same(3, u.area().firstLine(), "at the end");
            float[] in = u.placeBox(100, 300, 0, 0);
            check(FakeUi.mouse(u.area(), FakeUi.MouseAction.WHEEL_UP, in[0], in[1]).consumed, "wheel taken");
            same(2, u.area().firstLine(), "a line up");
            same("[3|4|5|6|7]", u.shownLines(), "shown");
            same(u.text().length(), u.host.state.editor.cursor(), "the cursor stays");
            for (int i = 0; i < 5; i++) FakeUi.mouse(u.area(), FakeUi.MouseAction.WHEEL_UP, in[0], in[1]);
            same(0, u.area().firstLine(), "not past the top");
            for (int i = 0; i < 9; i++) FakeUi.mouse(u.area(), FakeUi.MouseAction.WHEEL_DOWN, in[0], in[1]);
            same(3, u.area().firstLine(), "not past the end");
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
            same("", u.text(), "editor emptied");
            has(u.ui.logText(), "> spawn a small\n> pirate fleet", "shown as sent");
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
            same("spawn pirates", u.text(), "the text stays");
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

        test("history: Ctrl+Up brings back sent messages, newest first; Ctrl+Down goes forward, then to what was being written", u -> {
            u.open();
            for (String m : new String[]{"one", "two\nlines"}) {
                u.type(m);
                u.press(Keyboard.KEY_RETURN, false);
            }
            u.type("half writ");
            u.press(Keyboard.KEY_UP, true);
            same("two\nlines", u.text(), "Ctrl+Up: the newest, all its lines");
            same(u.text().length(), u.host.state.editor.cursor(), "the cursor at its end");
            u.press(Keyboard.KEY_UP, true);
            same("one", u.text(), "Ctrl+Up again");
            u.press(Keyboard.KEY_DOWN, true);
            same("two\nlines", u.text(), "Ctrl+Down");
            u.press(Keyboard.KEY_DOWN, true);
            same("half writ", u.text(), "past the newest: what was being written");
        });

        test("history: the sent messages are the host's, so they outlive the panel", u -> {
            u.open();
            u.type("from before the load");
            u.press(Keyboard.KEY_RETURN, false);
            new MultiLineTextFieldPanel(u.host); // the panel made again after a save load
            u.ui.field().focus = true;
            u.press(Keyboard.KEY_UP, true);
            same("from before the load", u.text(), "the new panel's Ctrl+Up");
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

    static {
        // ------------------------------------------------------------------------------------------
        // Keys: Tab and Esc
        // ------------------------------------------------------------------------------------------

        test("Tab: completes the name before the cursor; the key never reaches the game", u -> {
            u.open();
            u.type("send help to port t");
            check(u.press(Keyboard.KEY_TAB, false).consumed, "Tab taken");
            same("send help to Port Tse ", u.text(), "completed");
            check(u.press(Keyboard.KEY_TAB, false).consumed, "Tab again taken");
            same("send help to Port Tse ", u.text(), "one name fits: Tab again changes nothing");
            u.host.state.editor.clear();
            u.type("spawn xyz");
            check(u.press(Keyboard.KEY_TAB, false).consumed, "taken with nothing to complete too");
            same("spawn xyz", u.text(), "the text stays");
            same(0, u.host.sent.size(), "nothing sent");
        });

        test("Tab: in the middle of the text, the rest stays after the name", u -> {
            u.open();
            u.type("send jan to heg");
            u.press(Keyboard.KEY_HOME, false);
            for (int i = 0; i < "send jan".length(); i++) u.press(Keyboard.KEY_RIGHT, false);
            u.press(Keyboard.KEY_TAB, false);
            same("send Jangala  to heg", u.text(), "completed where the cursor was");
            same("send Jangala ".length(), u.host.state.editor.cursor(), "the cursor after the name");
            u.press(Keyboard.KEY_END, false);
            u.press(Keyboard.KEY_TAB, false);
            same("send Jangala  to Hegemony ", u.text(), "and at the end");
        });

        test("Esc: closes the editor, and the text is still there when it opens again", u -> {
            u.open();
            u.type("spawn pir");
            check(u.press(Keyboard.KEY_ESCAPE, false).consumed, "Esc taken");
            check(!u.host.state.editorOpen, "closed");
            same(1, u.outer().children.size(), "the editor is out of the panel");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            same("spawn pir", u.text(), "the text kept");
            same(0, u.host.sent.size(), "nothing sent");
        });

        // ------------------------------------------------------------------------------------------
        // Fleets that wait for the player's OK
        // ------------------------------------------------------------------------------------------

        test("Spawn and Discard: on only while fleets wait, and they OK or drop them", u -> {
            u.open();
            same(false, u.ui.button("Spawn").enabled, "Spawn off with nothing waiting");
            same(false, u.ui.button("Discard").enabled, "Discard off with nothing waiting");
            u.host.pending.add("Pirates war fleet of 40 FP, next to you");
            u.editor().advance(0.1f);
            same(true, u.ui.button("Spawn").enabled, "Spawn on");
            same(true, u.ui.button("Discard").enabled, "Discard on");
            u.ui.click(u.ui.button("Spawn"));
            same(1, u.host.confirms, "confirm() calls");
            u.editor().advance(0.1f);
            same(false, u.ui.button("Spawn").enabled, "off again once they spawned");

            u.host.pending.add("Hegemony war fleet of 150 FP, next to you");
            u.editor().advance(0.1f);
            u.ui.click(u.ui.button("Discard"));
            same(1, u.host.discards, "discard() calls");
            same(1, u.host.confirms, "no confirm() for Discard");
            u.editor().advance(0.1f);
            same(false, u.ui.button("Discard").enabled, "off again once dropped");
        });

        test("Spawn and Discard: an editor made while fleets wait starts with them on", u -> {
            u.host.pending.add("Pirates war fleet of 40 FP, next to you");
            new MultiLineTextFieldPanel(u.host);
            same(true, u.ui.button("Spawn").enabled, "Spawn");
            same(true, u.ui.button("Discard").enabled, "Discard");
        });

        test("toggle: \"*\" on the button while the editor is closed and something new came, or fleets wait", u -> {
            FakeUi.Button toggle = u.ui.button("Fleet commands (F9)");
            u.panel.advance(0.1f);
            same("Fleet commands (F9)", toggle.shown, "nothing new");
            new TerminalLog("TEST", PanelTest.class).show("Spawned Pirate Patrol (40 FP) in Corvus");
            u.panel.advance(0.1f);
            same("Fleet commands (F9) *", toggle.shown, "a message while closed");
            u.ui.click(toggle);
            u.panel.advance(0.1f);
            same("Fleet commands (F9)", toggle.shown, "seen once opened");
            u.ui.click(toggle);
            u.panel.advance(0.1f);
            same("Fleet commands (F9)", toggle.shown, "closed again, nothing new since");

            u.host.pending.add("Pirates war fleet of 40 FP, next to you");
            u.panel.advance(0.1f);
            same("Fleet commands (F9) *", toggle.shown, "fleets wait while closed");
            u.host.pending.clear();
            u.panel.advance(0.1f);
            same("Fleet commands (F9)", toggle.shown, "none wait any more");
        });

        // ------------------------------------------------------------------------------------------
        // A save load
        // ------------------------------------------------------------------------------------------

        test("save load: the conversation goes on after a line saying so, which doesn't mark the button", u -> {
            new MultiLineTextFieldPanel(u.host);
            same(0, u.host.state.entries().size(), "no conversation yet: no line");

            u.open();
            u.type("spawn pirates");
            u.press(Keyboard.KEY_RETURN, false);
            u.ui.click(u.ui.button("Fleet commands (F9)")); // closed before the load
            MultiLineTextFieldPanel again = new MultiLineTextFieldPanel(u.host);
            List<PanelState.Entry> entries = u.host.state.entries();
            same("- another save was loaded -", entries.get(entries.size() - 1).text, "the last line");
            same("> spawn pirates", entries.get(entries.size() - 2).text, "the conversation before it");
            again.advance(0.1f);
            same("Fleet commands (F9)", u.ui.button("Fleet commands (F9)").shown, "no \"*\" for it");
        });

        // ------------------------------------------------------------------------------------------
        // Size, the screen, dragging
        // ------------------------------------------------------------------------------------------

        test("size: closed, only the button and the grip; open, the whole editor", u -> {
            FakeUi.Position at = u.outer().position;
            same(MultiLineTextFieldPanel.CLOSED_WIDTH + " x " + MultiLineTextFieldPanel.CLOSED_HEIGHT, at.width + " x " + at.height,
                    "closed");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            same(MultiLineTextFieldPanel.DEFAULT_WIDTH + " x " + MultiLineTextFieldPanel.DEFAULT_HEIGHT, at.width + " x " + at.height, "open");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            same(MultiLineTextFieldPanel.CLOSED_WIDTH + " x " + MultiLineTextFieldPanel.CLOSED_HEIGHT, at.width + " x " + at.height,
                    "closed again");
        });

        test("screen: attachTo puts the panel on it at its corner; setShown(false) takes it off, true puts it back", u -> {
            FakeUi.CampaignScreen screen = u.ui.screen();
            u.panel.setShown(true);
            check(!u.panel.isShown(), "not shown before it has a screen");
            u.panel.attachTo(screen.api, 20, 120);
            check(u.panel.isShown(), "shown");
            same(Collections.singletonList(u.panel.getPanel()), screen.children, "on the screen");
            same("20.0, 120.0", xy(screen.corner(u.panel.getPanel())), "at its corner");
            u.panel.setShown(false);
            check(!u.panel.isShown(), "hidden");
            same(0, screen.children.size(), "off the screen");
            u.panel.setShown(false);
            same(0, screen.children.size(), "hiding twice is harmless");
            u.panel.setShown(true);
            same(Collections.singletonList(u.panel.getPanel()), screen.children, "back, once");
            same("20.0, 120.0", xy(screen.corner(u.panel.getPanel())), "where it was");
        });

        test("drag: the grip moves the panel with the mouse; the state keeps the place once it is let go", u -> {
            FakeUi.CampaignScreen screen = u.ui.screen();
            float[] grip = u.attach(screen, 20, 120);
            FakeUi.Mouse press = u.mouse(FakeUi.MouseAction.PRESS, grip[0], grip[1]);
            check(press.consumed, "the press on the grip is taken");
            FakeUi.Mouse move = u.mouse(FakeUi.MouseAction.MOVE, grip[0] + 100, grip[1] - 50);
            check(move.consumed, "the move is taken");
            // 100 right, 50 down: the mouse's y grows upwards, the corner's downwards
            same("120.0, 170.0", xy(u.panel.topLeft()), "moved");
            same("120.0, 170.0", xy(u.outer().position.corner), "moved in the UI too");
            same(null, u.host.state.position, "not kept before it is let go");
            check(u.mouse(FakeUi.MouseAction.RELEASE, grip[0] + 100, grip[1] - 50).consumed, "the release is taken");
            same("120.0, 170.0", xy(u.host.state.position), "kept for the next panel");
            check(!u.mouse(FakeUi.MouseAction.MOVE, grip[0] + 300, grip[1]).consumed, "after the release the mouse is the game's");
            same("120.0, 170.0", xy(u.panel.topLeft()), "and the panel stays");

            u.panel.setShown(false);
            u.panel.setShown(true);
            same("120.0, 170.0", xy(screen.corner(u.panel.getPanel())), "hidden and shown again, it is where it was dragged to");
        });

        test("drag: the panel stays on the screen, with room for the open editor even while it is closed", u -> {
            float[] grip = u.attach(u.ui.screen(), 20, 120);
            u.mouse(FakeUi.MouseAction.PRESS, grip[0], grip[1]);
            u.mouse(FakeUi.MouseAction.MOVE, grip[0] + 5000, grip[1] - 5000);
            same((FakeUi.SCREEN_WIDTH - MultiLineTextFieldPanel.DEFAULT_WIDTH) + ", " + (FakeUi.SCREEN_HEIGHT - MultiLineTextFieldPanel.DEFAULT_HEIGHT),
                    xy(u.panel.topLeft()), "bottom right");
            u.mouse(FakeUi.MouseAction.MOVE, grip[0] - 5000, grip[1] + 5000);
            same("0.0, 0.0", xy(u.panel.topLeft()), "top left");
        });

        test("drag: a press off the grip is left to the buttons and the game", u -> {
            float[] grip = u.attach(u.ui.screen(), 20, 120);
            float onButton = grip[0] - MultiLineTextFieldPanel.GRIP_WIDTH - MultiLineTextFieldPanel.GRIP_GAP - 20;
            for (float[] at : new float[][]{{onButton, grip[1]}, {grip[0] + MultiLineTextFieldPanel.GRIP_WIDTH, grip[1]},
                    {grip[0], grip[1] + MultiLineTextFieldPanel.CLOSED_HEIGHT}, {grip[0], grip[1] - MultiLineTextFieldPanel.CLOSED_HEIGHT}}) {
                check(!u.mouse(FakeUi.MouseAction.PRESS, at[0], at[1]).consumed, "press at " + xy(at) + " not taken");
                check(!u.mouse(FakeUi.MouseAction.MOVE, at[0] + 100, at[1]).consumed, "nor the move after it");
                same("20.0, 120.0", xy(u.panel.topLeft()), "the panel stays");
            }
        });

        test("drag: hiding the panel ends a drag", u -> {
            float[] grip = u.attach(u.ui.screen(), 20, 120);
            u.mouse(FakeUi.MouseAction.PRESS, grip[0], grip[1]);
            u.panel.setShown(false);
            check(!u.mouse(FakeUi.MouseAction.MOVE, grip[0] + 100, grip[1]).consumed, "the move is left alone");
            same("20.0, 120.0", xy(u.panel.topLeft()), "not moved");
            same(null, u.host.state.position, "nothing kept");
        });

        test("resize: dragging the open editor's corner resizes the panel; the editor is made again, the text kept", u -> {
            float[] corner = u.attachOpen(20, 120);
            u.type("spawn pirates");
            CustomUIPanelPlugin before = u.editor();
            FakeUi.Position at = u.outer().position;
            check(u.mouse(FakeUi.MouseAction.PRESS, corner[0], corner[1]).consumed, "the press on the corner is taken");
            check(u.mouse(FakeUi.MouseAction.MOVE, corner[0] + 100, corner[1] - 50).consumed, "the move is taken");
            same(MultiLineTextFieldPanel.DEFAULT_WIDTH + " x " + MultiLineTextFieldPanel.DEFAULT_HEIGHT, at.width + " x " + at.height,
                    "only an outline while it is dragged");
            same(null, u.host.state.size, "nothing kept yet");
            check(u.mouse(FakeUi.MouseAction.RELEASE, corner[0] + 100, corner[1] - 50).consumed, "the release is taken");
            // 100 right, 50 down: wider and taller
            same("820.0, 485.0", xy(u.host.state.size), "kept for the next panel");
            same("820.0 x 485.0", at.width + " x " + at.height, "the panel");
            same("820.0, 485.0", u.panel.width() + ", " + u.panel.height(), "its size");
            check(u.editor() != before, "a new editor for the new size");
            same(800f, u.ui.panelOf("TextAreaPanel").position.width, "the box as wide as the new editor");
            same("spawn pirates", u.text(), "the text kept");
            same("[spawn pirates]", u.shownLines(), "and shown");
            same("20.0, 120.0", xy(u.panel.topLeft()), "the top left corner stays");
        });

        test("resize: never smaller than the buttons need, never past the screen's edges", u -> {
            float[] corner = u.attachOpen(20, 120);
            u.mouse(FakeUi.MouseAction.PRESS, corner[0], corner[1]);
            u.mouse(FakeUi.MouseAction.MOVE, corner[0] - 1000, corner[1] + 1000);
            u.mouse(FakeUi.MouseAction.RELEASE, corner[0] - 1000, corner[1] + 1000);
            same(MultiLineTextFieldPanel.MIN_WIDTH + ", " + MultiLineTextFieldPanel.MIN_HEIGHT, xy(u.host.state.size), "smallest");

            corner = u.corner(20, 120);
            u.mouse(FakeUi.MouseAction.PRESS, corner[0], corner[1]);
            u.mouse(FakeUi.MouseAction.MOVE, corner[0] + 5000, corner[1] - 5000);
            u.mouse(FakeUi.MouseAction.RELEASE, corner[0] + 5000, corner[1] - 5000);
            same((FakeUi.SCREEN_WIDTH - 20) + ", " + (FakeUi.SCREEN_HEIGHT - 120), xy(u.host.state.size),
                    "to the screen's right and bottom edges from where the panel is");
        });

        test("resize: only the open editor's corner; a press next to it, or on a closed panel, is left alone", u -> {
            float[] corner = u.attachOpen(20, 120);
            check(!u.mouse(FakeUi.MouseAction.PRESS, corner[0] - 30, corner[1]).consumed, "left of the corner");
            check(!u.mouse(FakeUi.MouseAction.PRESS, corner[0], corner[1] + 30).consumed, "above it");
            u.ui.click(u.ui.button("Fleet commands (F9)"));
            check(!u.mouse(FakeUi.MouseAction.PRESS, corner[0], corner[1]).consumed, "the editor closed");
            same(null, u.host.state.size, "nothing resized");
        });

        test("size: \"panelSize\" sets the size it starts with; a dragged size wins; both kept on the screen", u -> {
            u.host.startSize = new float[]{900, 500};
            MultiLineTextFieldPanel p = new MultiLineTextFieldPanel(u.host);
            same("900.0, 500.0", p.width() + ", " + p.height(), "panelSize");
            same(880f, u.ui.panelOf("TextAreaPanel").position.width, "the box fits it");
            u.host.state.size = new float[]{800, 400};
            p = new MultiLineTextFieldPanel(u.host);
            same("800.0, 400.0", p.width() + ", " + p.height(), "the dragged size");
            u.host.state.size = null;
            u.host.startSize = new float[]{100, 100};
            p = new MultiLineTextFieldPanel(u.host);
            same(MultiLineTextFieldPanel.MIN_WIDTH + ", " + MultiLineTextFieldPanel.MIN_HEIGHT, p.width() + ", " + p.height(),
                    "too small");
            u.host.startSize = new float[]{5000, 5000};
            p = new MultiLineTextFieldPanel(u.host);
            same(FakeUi.SCREEN_WIDTH + ", " + FakeUi.SCREEN_HEIGHT, p.width() + ", " + p.height(), "too big");
        });

        test("save load: the message being written survives a new panel", u -> {
            u.open();
            u.type("half an\norder");
            new MultiLineTextFieldPanel(u.host);
            same("half an\norder", u.text(), "the text");
            same("[half an|order]", u.shownLines(), "shown in the new box");
        });

        // ------------------------------------------------------------------------------------------
        // What the panel keeps across save loads, and when it is on the screen
        // ------------------------------------------------------------------------------------------

        test("state: at most MAX_ENTRIES lines, the oldest go first; the list can't be changed from outside", u -> {
            PanelState state = new PanelState();
            for (int i = 0; i < PanelState.MAX_ENTRIES + 5; i++) state.add("line " + i, Color.WHITE);
            same(PanelState.MAX_ENTRIES, state.entries().size(), "lines kept");
            same("line 5", state.entries().get(0).text, "the oldest left");
            same("line " + (PanelState.MAX_ENTRIES + 4), state.entries().get(PanelState.MAX_ENTRIES - 1).text, "the newest");
            fails(() -> state.entries().add(new PanelState.Entry("x", Color.WHITE)), "entries() is read-only");
        });

        test("state: a line while the editor is closed marks it unseen, the mod's own notes don't", u -> {
            PanelState state = new PanelState();
            state.addQuietly("- another save was loaded -", Color.GRAY);
            check(!state.unseen, "a quiet line");
            state.editorOpen = true;
            state.add("seen as it comes", Color.WHITE);
            check(!state.unseen, "the editor is open");
            state.editorOpen = false;
            state.add("while closed", Color.WHITE);
            check(state.unseen, "a line while closed");
        });

        test("state: Forget chat clears the conversation, not the messages Up brings back", u -> {
            PanelState state = new PanelState();
            state.add("> spawn pirates", Color.WHITE);
            state.history.remember("spawn pirates");
            state.clearConversation();
            same(0, state.entries().size(), "conversation");
            same(1, state.history.size(), "sent messages");
        });

        test("shown: only while the campaign UI is neither hidden nor covered by a dialog, a menu or a tab", u -> {
            check(CampaignMultiLineScreenPlugin.shouldShow(null), "no campaign UI to ask");
            check(CampaignMultiLineScreenPlugin.shouldShow(campaignUi(false, false, false, null)), "the map in view");
            check(!CampaignMultiLineScreenPlugin.shouldShow(campaignUi(true, false, false, null)), "UI hidden (H)");
            check(!CampaignMultiLineScreenPlugin.shouldShow(campaignUi(false, true, false, null)), "a dialog");
            check(!CampaignMultiLineScreenPlugin.shouldShow(campaignUi(false, false, true, null)), "a menu");
            for (CoreUITabId tab : CoreUITabId.values()) {
                check(!CampaignMultiLineScreenPlugin.shouldShow(campaignUi(false, false, false, tab)), "the " + tab + " tab");
            }
        });
    }

    /** The campaign UI as shouldShow asks it. */
    private static CampaignUIAPI campaignUi(boolean hidden, boolean dialog, boolean menu, CoreUITabId tab) {
        return FakeGame.fake(CampaignUIAPI.class, "campaign UI", "isHideUI", hidden, "isShowingDialog", dialog,
                "isShowingMenu", menu, "getCurrentCoreTab", tab);
    }

    /** "x, y", or null. */
    private static String xy(float[] at) {
        return at == null ? null : at[0] + ", " + at[1];
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }
}
