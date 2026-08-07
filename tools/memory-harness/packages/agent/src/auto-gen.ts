import type { LLMMessage, LLMTransport } from "./types.js";
import type { VirtualMaid } from "./virtual-maid.js";

// Mirrors TouhouLittleMaid 1.5.2 MaidAIChatManager.autoGenSetting prompt.
const AUTO_GEN_PROMPT = `Generate a character profile for a Minecraft maid companion based on the given name. Include:
- Character setting and role
- Personality traits
- Language style and speech patterns
- Background story
- Appearance features

## Notes
- The profile must fit the Minecraft game world.
- If the name comes from a game, anime, or manga character, follow the original source material as closely as possible.

## Output Format
- About 300 words
- Divide into paragraphs separated by blank lines
- Write in \${chat_language}

Character: \${model_name}`;

export function buildAutoGenPrompt(maid: VirtualMaid): string {
  let prompt = AUTO_GEN_PROMPT
    .replace("${chat_language}", maid.language || "en_us")
    .replace("${model_name}", maid.name || "Maid");
  if (maid.modelDesc && maid.modelDesc.trim() !== "") {
    prompt += `\nCharacter Description Section: ${maid.modelDesc}`;
  }
  return prompt;
}

export async function generateSetting(maid: VirtualMaid, transport: LLMTransport): Promise<string> {
  const prompt = buildAutoGenPrompt(maid);
  const messages: LLMMessage[] = [{ role: "user", content: prompt }];
  const resp = await transport.chat(messages, [], { temperature: 0.7 });
  return (resp.content ?? "").trim();
}
