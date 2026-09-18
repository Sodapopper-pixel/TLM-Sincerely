import { readFile } from "node:fs/promises";
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
      options: LLMRequestOptions,
    ): Promise<LLMResponse> {
      if (options.signal?.aborted) {
        throw new Error("aborted");
      }

      const currentTurn = turn;
      turn += 1;

      const filePath = join(recordDir, sessionName, `turn-${currentTurn}.json`);

      let raw: string;
      try {
        raw = await readFile(filePath, "utf8");
      } catch {
        throw new Error(
          `Replay record not found for turn ${currentTurn}: expected ${filePath}`,
        );
      }

      if (options.signal?.aborted) {
        throw new Error("aborted");
      }

      const recorded = JSON.parse(raw) as RecordedTurn;
      return recorded.response;
    },
  };
}
