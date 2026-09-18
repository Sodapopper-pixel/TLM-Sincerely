import { DEFAULT_MEMORY_CONFIG, MaidMemory, saveToDir, type MemoryConfig } from "@tlm-harness/core";
import {
  DEFAULT_MAID,
  emptyTokenUsage,
  parseSkill,
  type LLMMessage,
  type LLMTransport,
  type SkillInstance,
  type TokenUsage,
  type TraceData,
  type VirtualMaid,
} from "@tlm-harness/agent";
import { existsSync, readFileSync, readdirSync } from "node:fs";
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
  /** Cached transport for per-session chat cursor persistence */
  cachedTransport?: LLMTransport;
  /** Last activity timestamp (ms since epoch) */
  lastActivityAt: number;
  /** Creation timestamp (ms since epoch) */
  createdAt: number;
}

export class ChatBusyError extends Error {
  constructor() {
    super("Session is busy with another chat request");
    this.name = "ChatBusyError";
  }
}

const MAX_SESSIONS = 100;
const SESSION_TTL_MS = 30 * 60 * 1000; // 30 minutes
const SAVE_DEBOUNCE_MS = 30;

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
  private readonly chatLocks = new Map<string, Promise<void>>();
  private readonly saveTimers = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(
    private readonly dataDir: string,
    private readonly skills: SkillInstance[],
  ) {}

  create(): Session {
    this.evictExpired();

    if (this.sessions.size >= MAX_SESSIONS) {
      this.evictOldest();
    }

    const now = Date.now();
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
      lastActivityAt: now,
      createdAt: now,
    };
    this.sessions.set(id, session);
    return session;
  }

  get(id: string): Session | undefined {
    const session = this.sessions.get(id);
    if (!session) {
      return undefined;
    }
    if (Date.now() - session.lastActivityAt > SESSION_TTL_MS) {
      this.sessions.delete(id);
      this.chatLocks.delete(id);
      clearSaveTimer(id, this.saveTimers);
      return undefined;
    }
    return session;
  }

  delete(id: string): boolean {
    this.chatLocks.delete(id);
    clearSaveTimer(id, this.saveTimers);
    return this.sessions.delete(id);
  }

  list(): Session[] {
    this.evictExpired();
    return [...this.sessions.values()];
  }

  async runChat(sessionId: string, fn: () => Promise<void>): Promise<void> {
    const existing = this.chatLocks.get(sessionId);
    if (existing) {
      throw new ChatBusyError();
    }
    const p = fn();
    this.chatLocks.set(sessionId, p);
    try {
      await p;
    } finally {
      this.chatLocks.delete(sessionId);
    }
  }

  /** Schedule a best-effort non-critical persistence write. */
  scheduleSaveMemory(session: Session): void {
    const key = session.id;
    if (this.saveTimers.has(key)) {
      clearTimeout(this.saveTimers.get(key)!);
    }
    const timer = setTimeout(() => {
      this.saveTimers.delete(key);
      void this.doSaveMemory(session).catch((e) => {
        console.error(`[harness] scheduled memory save failed for ${session.id}:`, e);
      });
    }, SAVE_DEBOUNCE_MS) as unknown as ReturnType<typeof setTimeout>;
    this.saveTimers.set(key, timer);
  }

  async forceSaveMemory(session: Session): Promise<void> {
    const key = session.id;
    if (this.saveTimers.has(key)) {
      clearTimeout(this.saveTimers.get(key)!);
      this.saveTimers.delete(key);
    }
    await this.doSaveMemory(session);
  }

  private async doSaveMemory(session: Session): Promise<void> {
    const dir = join(this.dataDir, "maid_memories");
    await saveToDir(dir, session.id, session.memory);
  }

  private evictExpired(): void {
    const now = Date.now();
    for (const [id, session] of this.sessions) {
      if (now - session.lastActivityAt > SESSION_TTL_MS) {
        this.sessions.delete(id);
        this.chatLocks.delete(id);
        clearSaveTimer(id, this.saveTimers);
      }
    }
  }

  private evictOldest(): void {
    let oldestId: string | null = null;
    let oldest = Date.now();
    for (const [id, session] of this.sessions) {
      if (session.lastActivityAt < oldest) {
        oldest = session.lastActivityAt;
        oldestId = id;
      }
    }
    if (oldestId) {
      this.sessions.delete(oldestId);
      this.chatLocks.delete(oldestId);
      clearSaveTimer(oldestId, this.saveTimers);
    }
  }
}

function clearSaveTimer(
  id: string,
  timers: Map<string, ReturnType<typeof setTimeout>>,
): void {
  const timer = timers.get(id);
  if (timer) {
    clearTimeout(timer);
    timers.delete(id);
  }
}
