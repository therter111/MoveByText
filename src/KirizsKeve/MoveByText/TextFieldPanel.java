package KirizsKeve.MoveByText;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public class TextFieldPanel extends CustomPanel {

    private TextFieldAPI textField;
    private LabelAPI displayLabel;
    private String lastText = "";

    public TextFieldPanel() {
        super(250f, 100f);

        /** Create a tooltip as our UI container */
        final TooltipMakerAPI content = getTooltip(250f, 100f, false);

        content.addTitle("Enter text below:", Misc.getBasePlayerColor());

        /** addTextField returns the TextFieldAPI. We store it to poll it later. */
        textField = content.addTextField(240f, 10f);

        /** A label to dynamically display what the user types. */
        displayLabel = content.addPara("You typed: ", 10f);

        add(content);
    }

    @Override
    public void advance(float delta) {
        if (textField == null) return;

        /** Because there is no accessible listener, we must poll the text field manually */
        final String currentText = textField.getText();

        if (!currentText.equals(lastText)) {
            lastText = currentText;
            final String newLabelText = "You typed: " + currentText;

            displayLabel.setText(newLabelText);
            /** Resize the label width so it doesn't wrap unnecessarily */
            displayLabel.autoSizeToWidth(displayLabel.computeTextWidth(newLabelText));
        }
    }

    @Override public void renderBelow(float alpha) {}
    @Override public void render(float alpha) {}
    @Override public void processInput(List<InputEventAPI> events) {}
}