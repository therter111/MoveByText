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

    /** Unique ID for our toggle button */
    private static final Object TOGGLE_BTN = new Object();

    private boolean isEditorVisible = false;
    private TooltipMakerAPI toggleTooltip;
    private EditorPanel editorPanel;

    public MultiLineTextFieldPanel() {
        super(320f, 440f);

        // Create a separate tooltip to house just the toggle button
        toggleTooltip = getTooltip(320f, 40f, false);
        toggleTooltip.setActionListenerDelegate(new ActionListenerDelegate() {
            @Override
            public void actionPerformed(Object data, Object src) {
                if (src instanceof ButtonAPI btn && btn.getCustomData() == TOGGLE_BTN) {
                    onToggleClicked();
                }
            }
        });

        // Add the toggle button
        toggleTooltip.addButton(
                "Toggle Editor", TOGGLE_BTN,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.ALL, 120f, 25f, 0f
        );

        // Anchor the toggle button tooltip to the top left of the main panel
        add(toggleTooltip).inTL(0f, 0f);

        // Initialize the actual editor panel, but do NOT add() it to the UI tree yet.
        editorPanel = new EditorPanel();
    }

    private void onToggleClicked() {
        isEditorVisible = !isEditorVisible;

        if (isEditorVisible) {
            add(editorPanel).inTL(0f, 35f);
        } else {
            remove(editorPanel);
        }
    }

    @Override public void renderBelow(float alpha) {}
    @Override public void render(float alpha) {}
    @Override public void advance(float delta) {}
    @Override public void processInput(List<InputEventAPI> events) {}

    // -------------------------------------------------------------
    // Nested CustomPanel class that encapsulates the editor's UI.
    // -------------------------------------------------------------
    private static class EditorPanel extends CustomPanel {
        private TextFieldAPI textField;
        private LabelAPI displayLabel;
        private ButtonAPI sendButton; // NEW: Track the Send Button

        private List<String> pastLines = new ArrayList<>();
        private String lastText = "";

        private static final float MAX_WIDTH = 700f;
        private static final Object SEND_BTN = new Object();

        public EditorPanel() {
            super(320f, 400f);

            final TooltipMakerAPI content = getTooltip(300f, 400f, true);
            content.addTitle("Multi-line Text Editor", Misc.getBasePlayerColor());

            displayLabel = content.addPara("", 10f);
            textField = content.addTextField(MAX_WIDTH, 5f);

            content.setActionListenerDelegate(new ActionListenerDelegate() {
                @Override
                public void actionPerformed(Object data, Object src) {
                    if (src instanceof ButtonAPI btn && btn.getCustomData() == SEND_BTN) {
                        onSendClicked();
                    }
                }
            });

            // Capture the ButtonAPI reference so we can update its position later
            sendButton = content.addButton(
                    "Send", SEND_BTN,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, 100f, 25f, 15f
            );

            add(content).inTL(0f, 0f);
            updateDisplay();
        }

        private void onSendClicked() {
            List<String> fullText = new ArrayList<>(pastLines);
            if (textField != null && !textField.getText().trim().isEmpty()) {
                fullText.add(textField.getText());
            }

            String combinedText = String.join("\n", fullText);
            MoveByTextImpl.writeSubmittedTextToTerminal(combinedText);

            pastLines.clear();
            if (textField != null) {
                textField.setText("");
            }
            updateDisplay();
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (textField == null) return;

            for (InputEventAPI event : events) {
                if (event.isKeyboardEvent() && event.isKeyDownEvent()) {
                    int key = event.getEventValue();

                    if (key == Keyboard.KEY_RETURN) {
                        pastLines.add(textField.getText());
                        textField.setText("");
                        updateDisplay();
                        event.consume();
                    }
                    else if (key == Keyboard.KEY_BACK) {
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
                lastText = textField.getText();
            }
        }

        private void updateDisplay() {
            if (pastLines.isEmpty()) {
                displayLabel.setText("");
                displayLabel.getPosition().setSize(MAX_WIDTH, 0f);
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pastLines.size(); i++) {
                    sb.append(pastLines.get(i));
                    if (i < pastLines.size() - 1) sb.append("\n");
                }
                displayLabel.setText(sb.toString());
                displayLabel.autoSizeToWidth(MAX_WIDTH);
            }

            // 1. Force position recompute for the text field
            float currentWidth = textField.getPosition().getWidth();
            float currentHeight = textField.getPosition().getHeight();
            textField.getPosition().setSize(currentWidth, currentHeight);

            // 2. Force position recompute for the Send Button so it continues to shift down
            if (sendButton != null) {
                float btnWidth = sendButton.getPosition().getWidth();
                float btnHeight = sendButton.getPosition().getHeight();
                sendButton.getPosition().setSize(btnWidth, btnHeight);
            }
        }

        @Override public void renderBelow(float alpha) {}
        @Override public void render(float alpha) {}
    }
}