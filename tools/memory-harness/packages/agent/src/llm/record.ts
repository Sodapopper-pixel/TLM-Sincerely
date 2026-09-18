import { mkdir, writeFile } from "node:fs/promises";
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
      if (options.signal?.aborted) {
        throw new Error("aborted");
      }

      const currentTurn = turn;
      turn += 1;

      const response = await inner.chat(messages, tools, options);

      if (options.signal?.aborted) {
        return response;
      }

      try {
        const dir = join(recordDir, sessionName);
        await mkdir(dir, { recursive: true });
        await writeFile(
          join(dir, `turn-${currentTurn}.json`),
          JSON.stringify(
            {
              turn: currentTurn,
              request: { messages, tools, options },
              response,
            },
            null,
            2,
          ),
          "utf8",
        );
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
