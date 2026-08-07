import { readFileSync } from "node:fs";
import { join } from "node:path";

import type {
  LLMMessage,
  LLMRequestOptions,
  LLMResponse,
  LLMTransport,
  ToolSchema,
} from "../types.js";

interface RecordedTurn {
  turn: number;
  request: {
    messages: LLMMessage[];
    tools: ToolSchema[];
    options: LLMRequestOptions;
  };
  response: LLMResponse;
}

export function createReplayTransport(
  recordDir: string,
  sessionName: string,
): LLMTransport {
  let turn = 0;

  return {
    async chat(
      _messages: LLMMessage[],
      _tools: ToolSchema[],
      _options: LLMRequestOptions,
    ): Promise<LLMResponse> {
      const currentTurn = turn;
      turn += 1;

      const filePath = join(recordDir, sessionName, `turn-${currentTurn}.json`);

      let raw: string;
      try {
        raw = readFileSync(filePath, "utf8");
      } catch {
        throw new Error(
          `Replay record not found for turn ${currentTurn}: expected ${filePath}`,
        );
      }

      const recorded = JSON.parse(raw) as RecordedTurn;
      return recorded.response;
    },
  };
}
