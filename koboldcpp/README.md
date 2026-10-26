# KoboldCpp server for MoveByText

The `koboldcpp` provider sends the player's text to a Qwen 3.5 9B model run by KoboldCpp, usually on another PC with
an NVIDIA card (tested on a 4070 Super) that the game reaches over Tailscale. `KoboldCppClient.java` builds the
prompt. KoboldCpp and the model file are not in this repo; download them on the PC that runs the model.

## On the PC that runs the model
1. Download `koboldcpp.exe` (the build with NVIDIA support) from
   https://github.com/LostRuins/koboldcpp/releases/latest. There's nothing to install. Tested with version 1.121.
2. Download a Qwen 3.5 9B GGUF file, for example from https://huggingface.co/unsloth/Qwen3.5-9B-GGUF:
   `Q6_K` (7.5 GB), or `Q4_K_M` (5.7 GB) to leave more video memory free. The scores below were measured with a
   `Q5_K_M` file.
3. Start it from a terminal in that folder. You can save the line as a `.bat` file:
   ```
   koboldcpp.exe --model Qwen3.5-9B-Q6_K.gguf --usecuda --gpulayers 99 --contextsize 12288 --port 5001
   ```
   The system prompt is about 3,000 tokens, so 12288 leaves plenty of room for the chat. KoboldCpp listens on the
   network by default. Add `--password something` if you want one, and put the same value in `llmdata.json`.
4. Allow only Tailscale traffic to the port. Run this in an admin PowerShell:
   ```
   New-NetFirewallRule -DisplayName "KoboldCpp (Tailscale only)" -Direction Inbound -Protocol TCP -LocalPort 5001 -RemoteAddress 100.64.0.0/10 -Action Allow
   ```
   If Windows asks about network access and you click Cancel, it creates a block rule that overrides this one.
   Delete that rule under Windows Defender Firewall > Advanced settings > Inbound Rules.
5. Make sure the PC doesn't go to sleep while you play.

## On the PC that runs the game
1. Check that the server can be reached. This should print the loaded model's name:
   ```
   curl.exe http://THAT-PC-NAME:5001/api/v1/model
   ```
   `THAT-PC-NAME` is the PC's Tailscale name, or its `100.x.y.z` address.
2. Copy `data/config/llmdata.json.example` to `data/config/llmdata.json` (git ignores the copy), keep
   `"provider": "koboldcpp"` and set `"url"` in the `koboldcpp` block to `http://THAT-PC-NAME:5001`.
3. Rebuild the jar. Running "Run Starsector" from IntelliJ does this automatically.
4. Optional: run `LlmPromptTest` to see how well the model follows the prompt. It writes
   `test/logs/llm-prompt-test_koboldcpp_<date>.log`, including the name of the loaded model file.

## How well it does
On `LlmPromptTest` (October 2026) it passed 300 of the first 329 cases (91%) and 265 of the 300 long ones (88%).
Rasa, the no-LLM provider, passes about 42% overall (see `rasa/README.md`).

## Limits
- The prompt is built in ChatML, the format Qwen models use. Thinking is switched off with an empty `<think>` block.
  A model with a different format (Llama, Gemma, Mistral) would need changes to `KoboldCppClient`.
- KoboldCpp answers one request at a time. If you use the same server for something else, the game's request waits
  or gets a "busy" error.
