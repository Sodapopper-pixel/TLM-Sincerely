import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("skill consistency (M5)", () => {
  it("fixtures skill.md matches mod resources skill.md", () => {
    const modPath = resolve(
      process.cwd(),
      "..",
      "..",
      "src",
      "main",
      "resources",
      "data",
      "touhou_little_maid",
      "skills",
      "memory-guidance",
      "skill.md",
    );
    const fixturePath = resolve(process.cwd(), "fixtures", "skills", "memory-guidance", "skill.md");
    const mod = readFileSync(modPath, "utf8");
    const fixture = readFileSync(fixturePath, "utf8");
    expect(fixture).toBe(mod);
  });
});
