#!/usr/bin/env node
/**
 * Keeps new list pages on the conventions the old ones follow
 * (`Docs/optimization/05-SearchFilterSortConsistency.md`).
 *
 * Why this exists: the convention was written in August and every list built
 * before it complies. My Cases was built after, missed it, and nobody noticed
 * until a maintainer did (issue 138). That is how a convention decays — not by
 * being overruled, but by the next screen not knowing it exists. A document
 * cannot catch that; something that runs can.
 *
 * It is deliberately narrow. It checks two things that every existing list
 * already does, so it locks in what is true rather than demanding a refactor
 * nobody asked for:
 *
 *   1. a paginated list offers search
 *   2. a paginated list distinguishes "empty" from "no results"
 *
 * Sort keys, page-size controls and filter layout are *not* checked: they vary
 * legitimately per screen, and a rule that fires on half the codebase gets
 * switched off rather than obeyed.
 *
 * Opting out is expected and must be argued in the file itself:
 *
 *   // list-conventions: no-search — the notifications endpoint has no search parameter
 *
 * An exemption with no reason after the dash fails the same as a missing
 * control. Exemptions are printed on every run so they stay visible instead of
 * quietly accumulating.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC = join(ROOT, 'src/app');

/** The hooks that mean "this renders a server-paginated list". */
const PAGINATED = /\b(usePagedApi|useUrlSyncedPagedApi)\s*[<(]/;

const RULES = [
  { id: 'no-search', needle: 'ListSearchInput', label: 'a search box (ListSearchInput)' },
  { id: 'no-empty-state', needle: 'ListStateBanner', label: 'an empty/error state (ListStateBanner)' },
];

function walk(dir) {
  return readdirSync(dir).flatMap(name => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return walk(full);
    return full.endsWith('.tsx') ? [full] : [];
  });
}

const failures = [];
const exemptions = [];

for (const file of walk(SRC)) {
  // `components/ui/` is vendored shadcn/Radix — not our screens.
  if (file.includes('/components/ui/')) continue;
  const source = readFileSync(file, 'utf8');
  if (!PAGINATED.test(source)) continue;

  const shown = relative(ROOT, file);
  for (const rule of RULES) {
    if (source.includes(rule.needle)) continue;

    // Matched after the rule id, so `list-conventions`'s own hyphen cannot be
    // mistaken for the dash that introduces the reason.
    // `[ \\t]*`, never `\\s*`: a whitespace class that crosses the newline
    // swallows the *next* line as the reason, so an empty exemption passes
    // carrying a stray line of code. Found by red-checking this very case.
    const exempt = source.match(new RegExp(`list-conventions:[ \\t]*${rule.id}[ \\t]*([\u2014\u2013-]?)[ \\t]*([^\\n]*)`));
    const reason = exempt ? (exempt[2] ?? '').trim() : '';
    if (reason) {
      exemptions.push(`${shown} [${rule.id}] ${reason}`);
    } else if (exempt) {
      failures.push(`${shown}: exempted from ${rule.id} with no reason given — say why, in the file.`);
    } else {
      failures.push(
        `${shown}: renders a paginated list without ${rule.label}.\n`
        + `    Add it, or exempt it with a reason:  // list-conventions: ${rule.id} — <why>`);
    }
  }
}

if (exemptions.length > 0) {
  console.log('list conventions: %d exemption(s), each with a stated reason:', exemptions.length);
  for (const e of exemptions.sort()) console.log('  - %s', e);
}

if (failures.length > 0) {
  console.error('\nlist conventions: %d problem(s)\n', failures.length);
  for (const f of failures.sort()) console.error('  %s\n', f);
  console.error('See Docs/optimization/05-SearchFilterSortConsistency.md for the shape these lists share.');
  process.exit(1);
}

console.log('list conventions: every paginated list offers search and an empty state.');
