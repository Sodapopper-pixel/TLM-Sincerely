import type { LLMMessage, LLMResponse, LLMTransport, ToolSchema, LLMRequestOptions } from "./types.js";

/** Fixed mock usage so offline token counters are non-zero (not a real estimate). */
const MOCK_USAGE = {
  prompt_tokens: 120,
  completion_tokens: 40,
  total_tokens: 160,
};

export function createMockTransport(responses: LLMResponse[]): LLMTransport {
  let i = 0;
  return {
    async chat(_messages: LLMMessage[], _tools: ToolSchema[], _options: LLMRequestOptions): Promise<LLMResponse> {
      const r = responses[i] ?? { content: "" };
      i++;
      return { ...r, usage: r.usage ?? { ...MOCK_USAGE } };
    },
  };
}

export function mockToolCall(id: string, name: string, args: Record<string, unknown>): LLMResponse {
  return {
    content: null,
    tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
    usage: { ...MOCK_USAGE },
  };
}

export function mockFinal(text: string): LLMResponse {
  return { content: text, usage: { ...MOCK_USAGE } };
}
