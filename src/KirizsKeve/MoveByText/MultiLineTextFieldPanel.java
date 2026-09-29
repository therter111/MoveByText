package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.ActionListenerDelegate;
import com.fs.starfarer.api.util.Misc;

public class MultiLineTextFieldPanel extends CustomPanel {

    private TextFieldAPI textField;
    private LabelAPI displayLabel;

    private List<String> pastLines = new ArrayList<>();
    private String lastText = "";

    private static final float MAX_WIDTH = 280f;

    /** 1. Unique ID for our send button */
    private static final Object SEND_BTN = new Object();

    public MultiLineTextFieldPanel() {
        super(320f, 400f);
        System.out.println("Látja");

        /** We use withScroller = true so that if the user types 50 lines, it automatically creates a scrollbar */
        final TooltipMakerAPI content = getTooltip(300f, 400f, true);

        content.addTitle("Multi-line Text Editor", Misc.getBasePlayerColor());

        /** This label holds all previously confirmed lines */
        displayLabel = content.addPara("", 10f);

        /** The active typing line */
        textField = content.addTextField(MAX_WIDTH, 5f);

        /** 2. Set up the Action Listener BEFORE creating the button */
        content.setActionListenerDelegate(new ActionListenerDelegate() {
            @Override
            public void actionPerformed(Object data, Object src) {
                if (src instanceof ButtonAPI btn && btn.getCustomData() == SEND_BTN) {
                    onSendClicked();
                }
            }
        });

        /** 3. Add the Send Button right below the active typing line */
        content.addButton(
                "Send",                             // Text on the button
                SEND_BTN,                           // Custom Data ID
                Misc.getBasePlayerColor(),          // Text/Border color
                Misc.getDarkPlayerColor(),          // Background color
                Alignment.MID,                      // Text alignment
                CutStyle.ALL,                       // Corner cut style
                100f,                               // Width
                25f,                                // Height
                15f                                 // Pad (gap above button)
        );

        add(content);
        updateDisplay();
    }

    /** 4. Define what happens when the button is pressed */
    private void onSendClicked() {
        // Gather everything the user typed (past lines + whatever is currently in the text field)
        List<String> fullText = new ArrayList<>(pastLines);
        if (textField != null && !textField.getText().trim().isEmpty()) {
            fullText.add(textField.getText());
        }

        String combinedText = String.join("\n", fullText);

        // Pass the result to the Mod Plugin to handle the file writing
        MoveByTextImpl.writeSubmittedTextToTerminal(combinedText);

        // Clear the text box and the confirmed lines so the user can start fresh
        pastLines.clear();
        if (textField != null) {
            textField.setText("");
        }

        // Update the visual display to reflect the cleared text
        updateDisplay();
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (textField == null) return;

        for (InputEventAPI event : events) {
            /**
             * We ignore event.isConsumed() here because TextFieldAPI processes inputs first.
             * If we skipped consumed events, we wouldn't be able to detect backspaces. 
             */
            if (event.isKeyboardEvent() && event.isKeyDownEvent()) {
                int key = event.getEventValue();

                if (key == Keyboard.KEY_RETURN) {
                    /** User pressed Enter: submit current line and clear the active text field */
                    pastLines.add(textField.getText());
                    textField.setText("");
                    updateDisplay();
                    event.consume();
                }
                else if (key == Keyboard.KEY_BACK) {
                    /**
                     * User pressed Backspace.
                     * We use `lastText` instead of `textField.getText()` to check if it was empty 
                     * BEFORE this frame. This prevents deleting a character AND popping a line simultaneously. 
                     */
                    if (lastText.isEmpty() && !pastLines.isEmpty()) {
                        String popped = pastLines.remove(pastLines.size() - 1);
                        textField.setText(popped);
                        updateDisplay();
                        event.consume();
                    }
                }
            }
        }
    }

    @Override
    public void advance(float delta) {
        if (textField != null) {
            /** Cache the text state for the next frame's Backspace logic */
            lastText = textField.getText();
        }
    }

    private void updateDisplay() {
        if (pastLines.isEmpty()) {
            displayLabel.setText("");
            displayLabel.getPosition().setSize(MAX_WIDTH, 0f);
        } else {
            /** Reconstruct the past lines using line breaks */
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pastLines.size(); i++) {
                sb.append(pastLines.get(i));
                if (i < pastLines.size() - 1) sb.append("\n");
            }
            displayLabel.setText(sb.toString());
            displayLabel.autoSizeToWidth(MAX_WIDTH);
        }

        /**
         * CORRECTED: Trigger a position update to push the text field down.
         * Since PositionAPI lacks recompute(), we force it by re-applying its current size.
         */
        float currentWidth = textField.getPosition().getWidth();
        float currentHeight = textField.getPosition().getHeight();
        textField.getPosition().setSize(currentWidth, currentHeight);
    }

    @Override public void renderBelow(float alpha) {}
    @Override public void render(float alpha) {}
}