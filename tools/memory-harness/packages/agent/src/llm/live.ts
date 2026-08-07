import type {
  LLMClientConfig,
  LLMMessage,
  LLMRequestOptions,
  LLMResponse,
  LLMTransport,
  ToolCall,
  ToolSchema,
} from "../types.js";

interface OpenAIChatCompletionMessage {
  content?: string | null;
  tool_calls?: ToolCall[];
}

interface OpenAIChatCompletionChoice {
  message?: OpenAIChatCompletionMessage;
  finish_reason?: string;
}

interface OpenAIChatCompletionResponse {
  choices?: OpenAIChatCompletionChoice[];
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
}

function normalizeBaseURL(baseURL: string): string {
  return baseURL.endsWith("/") ? baseURL.slice(0, -1) : baseURL;
}

export function createLiveTransport(config: LLMClientConfig): LLMTransport {
  return {
    async chat(
      messages: LLMMessage[],
      tools: ToolSchema[],
      options: LLMRequestOptions,
    ): Promise<LLMResponse> {
      const url = `${normalizeBaseURL(config.baseURL)}/chat/completions`;

      const body: Record<string, unknown> = {
        model: options.model ?? config.model,
        messages,
        temperature: options.temperature ?? 0,
      };

      if (tools.length > 0) {
        body.tools = tools;
        body.tool_choice = "auto";
      }

      const headers: Record<string, string> = {
        "Content-Type": "application/json",
      };
      if (config.apiKey !== "") {
        headers.Authorization = `Bearer ${config.apiKey}`;
      }

      const response = await fetch(url, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
      });

      if (!response.ok) {
        const text = await response.text();
        const preview = text.slice(0, 500);
        throw new Error(
          `LLM request failed: HTTP ${response.status} ${response.statusText}; body: ${preview}`,
        );
      }

      const data = (await response.json()) as OpenAIChatCompletionResponse;
      const choice = data.choices?.[0];
      const message = choice?.message;

      const result: LLMResponse = {
        content: message?.content ?? null,
      };

      if (message?.tool_calls !== undefined) {
        result.tool_calls = message.tool_calls;
      }
      if (choice?.finish_reason !== undefined) {
        result.finish_reason = choice.finish_reason;
      }
      if (data.usage !== undefined) {
        result.usage = data.usage;
      }

      return result;
    },
  };
}
