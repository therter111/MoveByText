package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's UI as fakes, for offline tests of the mod's panel: Global.getSettings().createCustom() gives a recording
 * CustomPanelAPI, whose tooltips record the buttons, text fields and paragraphs added to them. A test drives the
 * panel through its plugin callbacks (processInput, advance) and the buttons' action listener, and reads back what
 * it built. Rendering (GL11) is not faked; renderBelow is never called here.
 *
 * The screen is SCREEN_WIDTH x SCREEN_HEIGHT. Each panel has one Position that records its size and where inTL put
 * it; where the game says the panel is (getX, getY) is up to the test. Mouse events and the campaign screen the panel
 * is put on (CampaignScreen) are faked too, for dragging and showing the panel. Text is CHAR_WIDTH pixels a character
 * wide and TEXT_HEIGHT high, as the labels measure it.
 */
final class FakeUi {

    static final float SCREEN_WIDTH = 1920f;
    static final float SCREEN_HEIGHT = 1080f;
    static final float CHAR_WIDTH = 7f;
    static final float TEXT_HEIGHT = 15f;

    /** A button as the code made it, and what was done to it since. */
    static final class Button {
        /** The text it was made with; button(text) finds it by this. */
        final String text;
        final Object data;
        final Tooltip in;
        boolean enabled = true;
        int shortcut = -1;
        /** What it says now: text, or what setText gave it since. */
        String shown;
        ButtonAPI api;

        Button(String text, Object data, Tooltip in) {
            this.text = text;
            this.data = data;
            this.in = in;
            this.shown = text;
        }
    }

    /**
     * A PositionAPI: where the game says the component is (x, y: its bottom left on the screen, y upwards; a test
     * sets them), the size setSize gave it, and the top left corner the last inTL put it at (null: never).
     */
    static final class Position {
        float x, y, width, height;
        float[] corner;
        PositionAPI api;
    }

    /** A text field: its text, and whether it has the keyboard (a test sets that). */
    static final class Field {
        String text = "";
        boolean focus = false;
        TextFieldAPI api;
    }

    /** One paragraph: the format and the arguments addPara got, or the text its label's setText got since. */
    static final class Para {
        String format;
        List<String> args;
        final Color color;
        /** Where its label was put (inTL), and the color setColor gave it since. */
        final Position position = newPosition("position of a label");
        Color colorNow;

        Para(String format, List<String> args, Color color) {
            this.format = format;
            this.args = args;
            this.color = color;
        }

        /** What the player reads: the format with the arguments put in. */
        String text() {
            return String.format(format, args.toArray());
        }
    }

    /** A TooltipMakerAPI: what was added to it. */
    static final class Tooltip {
        final boolean scroller;
        final List<Button> buttons = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        final List<Para> paras = new ArrayList<>();
        TooltipMakerAPI.ActionListenerDelegate delegate;
        TooltipMakerAPI api;

        Tooltip(boolean scroller) {
            this.scroller = scroller;
        }
    }

    /** A CustomPanelAPI: its plugin (the mod's panel class), its tooltips and what is in it now. */
    static final class Panel {
        final CustomUIPanelPlugin plugin;
        final List<Tooltip> tooltips = new ArrayList<>();
        /** What addUIElement/addComponent put in, minus what removeComponent took out. */
        final List<Object> children = new ArrayList<>();
        /** What getPosition() gives: the same one every time. */
        final Position position = newPosition("position of panel");
        CustomPanelAPI api;

        Panel(CustomUIPanelPlugin plugin) {
            this.plugin = plugin;
        }
    }

    final FakeGame.World world = new FakeGame.World();
    final List<Panel> panels = new ArrayList<>();
    /** Every tooltip made, in order: the newest conversation is the last one with a scroller. */
    final List<Tooltip> tooltips = new ArrayList<>();

    FakeUi() {
        world.install();
        FakeGame.Answer createCustom = args -> newPanel((CustomUIPanelPlugin) args[2]).api;
        Global.setSettings(FakeGame.settings(Collections.<String, String>emptyMap(), true, "createCustom", createCustom,
                "getScreenWidth", SCREEN_WIDTH, "getScreenHeight", SCREEN_HEIGHT));
    }

    void stop() {
        TerminalLog.setScreenListener(null);
        FakeGame.uninstall();
    }

    private Panel newPanel(CustomUIPanelPlugin plugin) {
        Panel p = new Panel(plugin);
        p.api = FakeGame.deep(CustomPanelAPI.class, "panel of " + plugin.getClass().getSimpleName(),
                "getPosition", p.position.api,
                "createUIElement", (FakeGame.Answer) args -> {
                    Tooltip t = newTooltip((Boolean) args[2]);
                    p.tooltips.add(t);
                    return t.api;
                },
                "addUIElement", (FakeGame.Answer) args -> {
                    p.children.add(args[0]);
                    return position();
                },
                "addComponent", (FakeGame.Answer) args -> {
                    p.children.add(args[0]);
                    return position();
                },
                "removeComponent", (FakeGame.Answer) args -> p.children.remove(args[0]));
        panels.add(p);
        return p;
    }

    private Tooltip newTooltip(boolean scroller) {
        Tooltip t = new Tooltip(scroller);
        t.api = FakeGame.deep(TooltipMakerAPI.class, "tooltip",
                "setActionListenerDelegate", (FakeGame.Answer) args -> t.delegate = (TooltipMakerAPI.ActionListenerDelegate) args[0],
                "addButton", (FakeGame.Answer) args -> {
                    Button b = new Button((String) args[0], args[1], t);
                    b.api = FakeGame.deep(ButtonAPI.class, "button " + b.text,
                            "getCustomData", b.data,
                            "getText", (FakeGame.Answer) a -> b.shown,
                            "setText", (FakeGame.Answer) a -> b.shown = (String) a[0],
                            "setEnabled", (FakeGame.Answer) a -> b.enabled = (Boolean) a[0],
                            "isEnabled", (FakeGame.Answer) a -> b.enabled,
                            "setShortcut", (FakeGame.Answer) a -> b.shortcut = (Integer) a[0]);
                    t.buttons.add(b);
                    return b.api;
                },
                "addTextField", (FakeGame.Answer) args -> {
                    Field f = new Field();
                    f.api = FakeGame.deep(TextFieldAPI.class, "text field",
                            "getText", (FakeGame.Answer) a -> f.text,
                            "setText", (FakeGame.Answer) a -> f.text = (String) a[0],
                            "hasFocus", (FakeGame.Answer) a -> f.focus);
                    t.fields.add(f);
                    return f.api;
                },
                "addPara", (FakeGame.Answer) args -> {
                    // addPara(String format, float pad, Color color, Color highlight, String... highlights)
                    List<String> highlights = args.length > 4 && args[4] instanceof String[]
                            ? Arrays.asList((String[]) args[4]) : Collections.<String>emptyList();
                    Color color = args.length > 2 && args[2] instanceof Color ? (Color) args[2] : null;
                    Para p = new Para((String) args[0], highlights, color);
                    p.colorNow = color;
                    t.paras.add(p);
                    // The label shows the text setText gives it as it is, never as a format
                    return FakeGame.deep(LabelAPI.class, "label",
                            "setText", (FakeGame.Answer) a -> {
                                p.format = "%s";
                                p.args = Collections.singletonList((String) a[0]);
                                return null;
                            },
                            "getText", (FakeGame.Answer) a -> p.text(),
                            "setColor", (FakeGame.Answer) a -> p.colorNow = (Color) a[0],
                            "getPosition", p.position.api,
                            "computeTextWidth", (FakeGame.Answer) a -> CHAR_WIDTH * ((String) a[0]).length(),
                            "computeTextHeight", (FakeGame.Answer) a -> TEXT_HEIGHT * ((String) a[0]).split("\n", -1).length);
                });
        tooltips.add(t);
        return t;
    }

    private static PositionAPI position() {
        return FakeGame.deep(PositionAPI.class, "position");
    }

    /** A Position that records setSize and inTL; both give the position back, as the game's do. */
    static Position newPosition(String label) {
        Position pos = new Position();
        Object[] self = new Object[1]; // a Proxy can't name itself while it is made
        pos.api = FakeGame.deep(PositionAPI.class, label,
                "getX", (FakeGame.Answer) a -> pos.x,
                "getY", (FakeGame.Answer) a -> pos.y,
                "getWidth", (FakeGame.Answer) a -> pos.width,
                "getHeight", (FakeGame.Answer) a -> pos.height,
                "setSize", (FakeGame.Answer) a -> {
                    pos.width = (Float) a[0];
                    pos.height = (Float) a[1];
                    return self[0];
                },
                "inTL", (FakeGame.Answer) a -> {
                    pos.corner = new float[]{(Float) a[0], (Float) a[1]};
                    return self[0];
                });
        self[0] = pos.api;
        return pos;
    }

    /** The campaign screen the mod's panel is put on: what is on it now, and where each was put. */
    static final class CampaignScreen {
        final List<Object> children = new ArrayList<>();
        /** Each component addComponent got, by the component: the Position its inTL went to. */
        final Map<Object, Position> placed = new HashMap<>();
        UIPanelAPI api;

        /** The top left corner the component was last put at, or null. */
        float[] corner(Object component) {
            Position p = placed.get(component);
            return p == null ? null : p.corner;
        }
    }

    CampaignScreen screen() {
        CampaignScreen screen = new CampaignScreen();
        screen.api = FakeGame.deep(UIPanelAPI.class, "campaign screen",
                "addComponent", (FakeGame.Answer) a -> {
                    screen.children.add(a[0]);
                    Position p = newPosition("position on the campaign screen");
                    screen.placed.put(a[0], p);
                    return p.api;
                },
                "removeComponent", (FakeGame.Answer) a -> screen.children.remove(a[0]));
        return screen;
    }

    // ------------------------------------------------------------------------------------------
    // What a test looks for and does
    // ------------------------------------------------------------------------------------------

    /** The fake behind a panel the mod made. */
    Panel panel(CustomPanelAPI api) {
        for (Panel p : panels) if (p.api == api) return p;
        throw new AssertionError("no such panel");
    }

    /** The newest panel whose plugin is of this class (e.g. "EditorPanel"). */
    Panel panelOf(String pluginClass) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (panels.get(i).plugin.getClass().getSimpleName().equals(pluginClass)) return panels.get(i);
        }
        throw new AssertionError("no panel of " + pluginClass);
    }

    /** The newest button with this text. */
    Button button(String text) {
        for (int i = tooltips.size() - 1; i >= 0; i--) {
            for (Button b : tooltips.get(i).buttons) if (b.text.equals(text)) return b;
        }
        throw new AssertionError("no button \"" + text + "\"");
    }

    /** The newest text field. */
    Field field() {
        for (int i = tooltips.size() - 1; i >= 0; i--) {
            if (!tooltips.get(i).fields.isEmpty()) return tooltips.get(i).fields.get(0);
        }
        throw new AssertionError("no text field");
    }

    /** Clicks the button, as the game does: its tooltip's action listener gets the button's data and the button. */
    void click(Button b) {
        if (b.in.delegate == null) throw new AssertionError("no action listener for " + b.text);
        b.in.delegate.actionPerformed(b.data, b.api);
    }

    /** The paragraphs of the newest conversation (the newest tooltip with a scroller). */
    List<Para> log() {
        for (int i = tooltips.size() - 1; i >= 0; i--) {
            if (tooltips.get(i).scroller) return tooltips.get(i).paras;
        }
        throw new AssertionError("no conversation yet");
    }

    /** The newest conversation as the player reads it, one paragraph a line. */
    String logText() {
        List<String> lines = new ArrayList<>();
        for (Para p : log()) lines.add(p.text());
        return String.join("\n", lines);
    }

    /**
     * A key press as the game hands it to processInput: the key, the character it types ('\0' for none) and the
     * modifiers held; consumed() says whether the panel took it.
     */
    static final class Key {
        final InputEventAPI api;
        boolean consumed = false;

        Key(int key, char c, boolean ctrl, boolean shift, boolean alt) {
            api = FakeGame.fake(InputEventAPI.class, "key " + key + (c != '\0' ? " '" + c + "'" : ""),
                    "isKeyboardEvent", true,
                    "isKeyDownEvent", true,
                    "getEventValue", key,
                    "getEventChar", c,
                    "isCtrlDown", ctrl,
                    "isShiftDown", shift,
                    "isAltDown", alt,
                    "consume", (FakeGame.Answer) a -> consumed = true,
                    "isConsumed", (FakeGame.Answer) a -> consumed);
        }
    }

    /** Hands one key press to the plugin; returns it, to ask whether it was consumed. */
    static Key press(CustomUIPanelPlugin plugin, int key, boolean ctrl, boolean shift) {
        return press(plugin, new Key(key, '\0', ctrl, shift, false));
    }

    /** Hands a key that types c to the plugin, as a letter key does (its key code doesn't matter here). */
    static Key type(CustomUIPanelPlugin plugin, char c) {
        return press(plugin, new Key(Keyboard.KEY_NONE, c, false, Character.isUpperCase(c), false));
    }

    static Key press(CustomUIPanelPlugin plugin, Key k) {
        plugin.processInput(Collections.singletonList(k.api));
        return k;
    }

    /** What the mouse did, as one InputEventAPI reports it. */
    enum MouseAction { PRESS, DOUBLE_CLICK, SHIFT_PRESS, MOVE, RELEASE, WHEEL_UP, WHEEL_DOWN }

    /**
     * A mouse event as the game hands it to processInput: the left button pressed, the mouse moved, or the left
     * button let go, at (x, y) in screen pixels from the bottom left (y upwards, as the game counts).
     */
    static final class Mouse {
        final InputEventAPI api;
        boolean consumed = false;

        Mouse(MouseAction action, int x, int y) {
            boolean press = action == MouseAction.PRESS || action == MouseAction.DOUBLE_CLICK || action == MouseAction.SHIFT_PRESS;
            boolean wheel = action == MouseAction.WHEEL_UP || action == MouseAction.WHEEL_DOWN;
            api = FakeGame.fake(InputEventAPI.class, "mouse " + action + " at " + x + ", " + y,
                    "isMouseEvent", true,
                    "isLMBDownEvent", press,
                    "isDoubleClick", action == MouseAction.DOUBLE_CLICK,
                    "isShiftDown", action == MouseAction.SHIFT_PRESS,
                    "isMouseMoveEvent", action == MouseAction.MOVE,
                    "isLMBUpEvent", action == MouseAction.RELEASE,
                    "isMouseScrollEvent", wheel,
                    "getEventValue", action == MouseAction.WHEEL_UP ? 120 : action == MouseAction.WHEEL_DOWN ? -120 : 0,
                    "getX", x,
                    "getY", y,
                    "consume", (FakeGame.Answer) a -> consumed = true,
                    "isConsumed", (FakeGame.Answer) a -> consumed);
        }
    }

    /** Hands one mouse event to the plugin; returns it, to ask whether it was consumed. */
    static Mouse mouse(CustomUIPanelPlugin plugin, MouseAction action, float x, float y) {
        Mouse m = new Mouse(action, Math.round(x), Math.round(y));
        plugin.processInput(Collections.singletonList(m.api));
        return m;
    }
}
