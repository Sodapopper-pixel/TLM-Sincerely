import type { ToolSchema } from "../types.js";
import type { Tool, ToolContext, ToolResult } from "../tool-registry.js";
import { statusContext, worldContext } from "../context-builder.js";

const TOOL_ID = "query_game_context";

const CATEGORIES = ["status", "world", "equipment", "user", "effects", "position", "nearby"] as const;
type Category = (typeof CATEGORIES)[number];

export class QueryContextTool implements Tool {
  readonly id = TOOL_ID;

  schema(): ToolSchema {
    return {
      type: "function",
      function: {
        name: TOOL_ID,
        description:
          "Query on-demand game context that is not auto-injected. Returns the requested category's current values.",
        parameters: {
          type: "object",
          properties: {
            category: {
              type: "string",
              enum: [...CATEGORIES],
              description: "The context category to query",
            },
          },
          required: ["category"],
        },
      },
    };
  }

  execute(args: Record<string, unknown>, ctx: ToolContext): ToolResult {
    const category = String(args.category ?? "") as Category;
    const maid = ctx.maid;
    let value: string;
    switch (category) {
      case "status":
        value = statusContext(maid);
        break;
      case "world":
        value = worldContext(maid);
        break;
      case "equipment":
        value = maid.equipment ? Object.entries(maid.equipment).map(([k, v]) => `${k}=${v}`).join(", ") : "None";
        break;
      case "user":
        value = `userName=${maid.userName}, userHealth=${maid.userHealth}, userMainHand=${maid.userMainHand ?? "empty"}`;
        break;
      case "effects":
        value = maid.effects && maid.effects.length > 0 ? maid.effects.join(", ") : "None";
        break;
      case "position":
        value = maid.position ? `x=${maid.position.x}, y=${maid.position.y}, z=${maid.position.z}` : "None";
        break;
      case "nearby":
        value = maid.nearby && maid.nearby.length > 0
          ? maid.nearby.map((n) => `${n.name}(${n.type}, ${n.distance}m)`).join(", ")
          : "None";
        break;
      default:
        return { content: `Unknown category: ${category}. Choose one of [${CATEGORIES.join(",")}]` };
    }
    return { content: `${category}: ${value}` };
  }
}
