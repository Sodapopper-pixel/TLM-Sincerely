export interface NearbyEntity {
  type: string;
  name: string;
  distance: number;
}

export interface VirtualMaid {
  uuid: string;
  name: string;
  language: string;
  systemPrompt: string;
  health: number;
  maxHealth: number;
  isSleeping: boolean;
  isFollowing: boolean;
  sitting: boolean;
  schedule: string;
  activity: string;
  task: string;
  timeOfDay: number;
  weather: string;
  dimension: string;
  biome: string;
  userName: string;
  userHealth: number;
  modelDesc?: string;
  riding?: string;
  userMainHand?: string;
  equipment?: Record<string, string>;
  effects?: string[];
  position?: { x: number; y: number; z: number };
  nearby?: NearbyEntity[];
}

export const DEFAULT_MAID: VirtualMaid = {
  uuid: "00000000-0000-0000-0000-0000000000aa",
  name: "Reimu",
  language: "en_us",
  systemPrompt: "",
  health: 20,
  maxHealth: 20,
  isSleeping: false,
  isFollowing: true,
  sitting: false,
  schedule: "DAY",
  activity: "idle",
  task: "none",
  timeOfDay: 1000,
  weather: "clear",
  dimension: "overworld",
  biome: "plains",
  userName: "Master",
  userHealth: 20,
};
