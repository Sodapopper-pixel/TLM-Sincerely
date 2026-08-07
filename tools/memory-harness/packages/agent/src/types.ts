export type LLMRole = "system" | "user" | "assistant" | "tool";

export interface ToolCallFunction {
  name: string;
  arguments: string;
}

export interface ToolCall {
  id: string;
  type: "function";
  function: ToolCallFunction;
}

export interface LLMMessage {
  role: LLMRole;
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}

export interface ToolFunctionSchema {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface ToolSchema {
  type: "function";
  function: ToolFunctionSchema;
}

export interface LLMUsage {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

export interface LLMResponse {
  content: string | null;
  tool_calls?: ToolCall[];
  finish_reason?: string;
  usage?: LLMUsage;
}

export interface LLMRequestOptions {
  temperature?: number;
  model?: string;
}

export interface LLMTransport {
  chat(messages: LLMMessage[], tools: ToolSchema[], options: LLMRequestOptions): Promise<LLMResponse>;
}

export interface LLMClientConfig {
  baseURL: string;
  apiKey: string;
  model: string;
}
