export interface SkillInstance {
  name: string;
  description: string;
  body: string;
}

export function parseSkill(md: string): SkillInstance | null {
  const match = md.match(/^---\s*\n([\s\S]*?)\n---\s*\n?([\s\S]*)$/);
  if (!match) {
    return null;
  }
  const frontMatter = match[1];
  const body = match[2].trim();
  const name = readScalar(frontMatter, "name");
  const description = readScalar(frontMatter, "description");
  if (!name || !description || !body) {
    return null;
  }
  return { name: name.trim(), description: description.trim(), body };
}

function readScalar(frontMatter: string, key: string): string | null {
  const lines = frontMatter.split("\n");
  const keyPrefix = `${key}:`;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.startsWith(keyPrefix)) {
      continue;
    }
    const rest = line.slice(keyPrefix.length).trim();
    if (rest === ">" || rest === "|" || rest === ">-" || rest === "|-") {
      const folded: string[] = [];
      for (let j = i + 1; j < lines.length; j++) {
        const foldedLine = lines[j];
        if (/^\S/.test(foldedLine)) {
          break;
        }
        folded.push(foldedLine.trim());
      }
      return folded.join(" ");
    }
    if (rest.length > 0) {
      return rest;
    }
    return null;
  }
  return null;
}

export function findSkill(skills: SkillInstance[], name: string): SkillInstance | undefined {
  return skills.find((s) => s.name === name);
}
