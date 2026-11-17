package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The game's UI as fakes, for offline tests of the mod's panel: Global.getSettings().createCustom() gives a recording
 * CustomPanelAPI, whose tooltips record the buttons, text fields and paragraphs added to them. A test drives the
 * panel through its plugin callbacks (processInput, advance) and the buttons' action listener, and reads back what
 * it built. Rendering (GL11) is not faked; renderBelow is never called here.
 */
final class FakeUi {

    /** A button as the code made it, and what was done to it since. */
    static final class Button {
        final String text;
        final Object data;
        final Tooltip in;
        boolean enabled = true;
        int shortcut = -1;
        ButtonAPI api;

        Button(String text, Object data, Tooltip in) {
            this.text = text;
            this.data = data;
            this.in = in;
        }
    }

    /** A text field: its text, and whether it has the keyboard (a test sets that). */
    static final class Field {
        String text = "";
        boolean focus = false;
        TextFieldAPI api;
    }

    /** One paragraph: the format and the arguments addPara got. */
    static final class Para {
        final String format;
        final List<String> args;
        final Color color;

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
        Global.setSettings(FakeGame.settings(Collections.<String, String>emptyMap(), true, "createCustom", createCustom));
    }

    void stop() {
        TerminalLog.setScreenListener(null);
        FakeGame.uninstall();
    }

    private Panel newPanel(CustomUIPanelPlugin plugin) {
        Panel p = new Panel(plugin);
        p.api = FakeGame.deep(CustomPanelAPI.class, "panel of " + plugin.getClass().getSimpleName(),
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
                            "getText", b.text,
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
                    t.paras.add(new Para((String) args[0], highlights, color));
                    return null;
                });
        tooltips.add(t);
        return t;
    }

    private static PositionAPI position() {
        return FakeGame.deep(PositionAPI.class, "position");
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

    /** A key press as the game hands it to processInput; consumed() says whether the panel took it. */
    static final class Key {
        final InputEventAPI api;
        boolean consumed = false;

        Key(int key, boolean ctrl) {
            api = FakeGame.fake(InputEventAPI.class, "key " + key,
                    "isKeyboardEvent", true,
                    "isKeyDownEvent", true,
                    "getEventValue", key,
                    "isCtrlDown", ctrl,
                    "consume", (FakeGame.Answer) a -> consumed = true,
                    "isConsumed", (FakeGame.Answer) a -> consumed);
        }
    }

    /** Hands one key press to the plugin; returns it, to ask whether it was consumed. */
    static Key press(CustomUIPanelPlugin plugin, int key, boolean ctrl) {
        Key k = new Key(key, ctrl);
        plugin.processInput(Collections.singletonList(k.api));
        return k;
    }
}
