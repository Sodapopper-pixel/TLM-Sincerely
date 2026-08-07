import { DEFAULT_MEMORY_CONFIG, MaidMemory, saveToDir, type MemoryConfig } from "@tlm-harness/core";
import {
  DEFAULT_MAID,
  emptyTokenUsage,
  parseSkill,
  type LLMMessage,
  type SkillInstance,
  type TokenUsage,
  type TraceData,
  type VirtualMaid,
} from "@tlm-harness/agent";
import { existsSync, mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

export interface Session {
  id: string;
  maid: VirtualMaid;
  memory: MaidMemory;
  config: MemoryConfig;
  history: LLMMessage[];
  skills: SkillInstance[];
  lastTrace: TraceData | null;
  tokenUsage: TokenUsage;
  autoGenSetting: boolean;
}

export function loadSkills(skillsDir: string): SkillInstance[] {
  const skills: SkillInstance[] = [];
  if (!existsSync(skillsDir)) {
    return skills;
  }
  for (const entry of readdirSync(skillsDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue;
    }
    const file = join(skillsDir, entry.name, "skill.md");
    if (!existsSync(file)) {
      continue;
    }
    const parsed = parseSkill(readFileSync(file, "utf8"));
    if (parsed) {
      skills.push(parsed);
    }
  }
  return skills;
}

export class SessionManager {
  private readonly sessions = new Map<string, Session>();

  constructor(
    private readonly dataDir: string,
    private readonly skills: SkillInstance[],
  ) {}

  create(): Session {
    const id = randomUUID();
    const session: Session = {
      id,
      maid: { ...DEFAULT_MAID },
      memory: new MaidMemory(DEFAULT_MEMORY_CONFIG.maxMemories),
      config: { ...DEFAULT_MEMORY_CONFIG },
      history: [],
      skills: this.skills,
      lastTrace: null,
      tokenUsage: emptyTokenUsage(),
      autoGenSetting: true,
    };
    this.sessions.set(id, session);
    return session;
  }

  get(id: string): Session | undefined {
    return this.sessions.get(id);
  }

  delete(id: string): boolean {
    return this.sessions.delete(id);
  }

  list(): Session[] {
    return [...this.sessions.values()];
  }

  saveMemory(session: Session): void {
    const dir = join(this.dataDir, "maid_memories");
    mkdirSync(dir, { recursive: true });
    saveToDir(dir, session.id, session.memory);
  }
}
