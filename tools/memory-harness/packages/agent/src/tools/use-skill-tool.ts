import type { ToolSchema } from "../types.js";
import { type Tool, type ToolContext, type ToolResult, invalidParam } from "../tool-registry.js";
import { findSkill } from "../skill-loader.js";

const TOOL_ID = "use_skill";

export class UseSkillTool implements Tool {
  readonly id = TOOL_ID;

  schema(): ToolSchema {
    return {
      type: "function",
      function: {
        name: TOOL_ID,
        description: "Load a skill's guidance text into the conversation. Use this to follow a skill's instructions.",
        parameters: {
          type: "object",
          properties: {
            name: { type: "string", description: "The skill name to load" },
          },
          required: ["name"],
        },
      },
    };
  }

  execute(args: Record<string, unknown>, ctx: ToolContext): ToolResult {
    const name = String(args.name ?? "");
    const allNames = ctx.skills.map((s) => s.name);
    if (!name) {
      return { content: invalidParam("name", allNames, "name is required") };
    }
    const skill = findSkill(ctx.skills, name);
    if (!skill) {
      return { content: invalidParam("name", allNames, `Unknown skill name '${name}'`) };
    }
    return { content: skill.body };
  }
}
