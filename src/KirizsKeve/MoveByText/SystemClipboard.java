package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import org.lwjgl.Sys;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * The system clipboard, for the editor's Ctrl+C, Ctrl+X and Ctrl+V. It is read through LWJGL, as the game's own
 * text fields paste, and written through AWT, which LWJGL 2 has no call for. Neither may stop the editor: a
 * failure is logged and reported as nothing read or written. Game thread only.
 */
final class SystemClipboard {

    private SystemClipboard() {
    }

    /** The clipboard's text; empty when there is none or it can't be read. */
    static String read() {
        try {
            String text = Sys.getClipboard();
            return text != null ? text : "";
        } catch (RuntimeException | LinkageError e) {
            Global.getLogger(SystemClipboard.class).warn("Could not read the clipboard", e);
            return "";
        }
    }

    /** Puts text on the clipboard; false when that didn't work. */
    static boolean write(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        } catch (RuntimeException | LinkageError e) {
            Global.getLogger(SystemClipboard.class).warn("Could not write to the clipboard", e);
            return false;
        }
    }
}
