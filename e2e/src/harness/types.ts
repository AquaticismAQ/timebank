import type { BrowserContext, Page } from 'playwright';
import type { Narrator } from './narrator.js';
import type { Config } from './config.js';
import type { Api } from './api.js';
import type { SlotAllocator } from './slots.js';

/**
 * A named capability a phase can require. Profiles are sets of these
 * (`profiles.ts`), which is how one flow serves both a demonstration and a
 * regression run without becoming two scripts that drift apart.
 */
export type Gate = 'core' | 'branch:return' | 'branch:reject' | 'audit' | 'tts' | 'pace';

export interface FlowContext {
  readonly config: Config;
  readonly api: Api;
  /** An admin-scoped API session, for reads a student is not allowed (audit logs). */
  readonly adminApi: Api;
  readonly narrator: Narrator;
  readonly slots: SlotAllocator;
  readonly runId: string;
  /** True when the gate is active for this run. */
  has(gate: Gate): boolean;
  /** The browser context for a role; switching roles discards the previous one. */
  as(role: 'admin' | 'student'): Promise<Page>;
  /** Whatever the current page is, for a phase that does not switch roles. */
  page(): Page;
  /** Per-run scratch space, so a phase can hand an id to a later one. */
  readonly state: Record<string, unknown>;
}

export interface Phase {
  titleZh: string;
  /** Absent means the phase always runs. */
  gate?: Gate;
  run(ctx: FlowContext): Promise<void>;
}

export interface Flow {
  id: string;
  titleZh: string;
  phases: Phase[];
}

export interface RoleContext {
  context: BrowserContext;
  page: Page;
}
