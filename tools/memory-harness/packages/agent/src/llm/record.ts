import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import type {
  LLMMessage,
  LLMRequestOptions,
  LLMResponse,
  LLMTransport,
  ToolSchema,
} from "../types.js";

export function createRecordTransport(
  inner: LLMTransport,
  recordDir: string,
  sessionName: string,
): LLMTransport {
  let turn = 0;

  return {
    async chat(
      messages: LLMMessage[],
      tools: ToolSchema[],
      options: LLMRequestOptions,
    ): Promise<LLMResponse> {
      const currentTurn = turn;
      turn += 1;

      const response = await inner.chat(messages, tools, options);

      try {
        const dir = join(recordDir, sessionName);
        mkdirSync(dir, { recursive: true });
        const filePath = join(dir, `turn-${currentTurn}.json`);
        const record = {
          turn: currentTurn,
          request: { messages, tools, options },
          response,
        };
        writeFileSync(filePath, JSON.stringify(record, null, 2), "utf8");
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        console.error(
          `[record] failed to write turn-${currentTurn}.json: ${message}`,
        );
      }

      return response;
    },
  };
}
