// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * UI checks for J11 "live thinking trace" against the real deterministic engine
 * (offline, mock connectors — see application.yml). No ADK/Copilot proxy needed.
 *
 * These exist because a live demo on the corp laptop surfaced two symptoms that
 * turned out to have the same root cause: a dev-only sample-rows section
 * (TASK-013 scaffolding) was still shipping in the production page and was
 * mistaken for live output. That section is now removed (see git history for
 * `.lt5-demo`) — the first test below is a regression guard against it (or
 * anything like it) coming back.
 */

const SEEDED_INCIDENT = 'INC0010005';

test.describe('J11 live thinking trace — deterministic engine', () => {
  test('page load shows no stray/demo trace content before Diagnose is clicked', async ({ page }) => {
    await page.goto('/');

    // The output area must be genuinely empty on load — nothing pre-rendered.
    const outHtml = await page.locator('#out').innerHTML();
    expect(outHtml.trim()).toBe('');

    // Regression guard: no dev-only demo/sample trace content anywhere on the
    // page. This is exactly what was mistaken for a live run on the corp
    // laptop (a static "GitLab … queued" row visible before Diagnose was
    // ever clicked).
    await expect(page.locator('.lt5-demo')).toHaveCount(0);
    await expect(page.locator('#lt5Sample')).toHaveCount(0);

    // No trace row of any kind should exist pre-click.
    await expect(page.locator('.trace-row')).toHaveCount(0);
  });

  test('clicking Diagnose reveals steps progressively, not all at once', async ({ page }) => {
    await page.goto('/');
    await page.fill('#inc', SEEDED_INCIDENT);
    await page.click('#go');

    // Rows are APPENDED one at a time, paced at max(realDuration, ~400ms) — the
    // same growth motion the live/ADK path uses, so switching engines doesn't
    // change the shape of what the audience sees. (This replaced a pre-rendered
    // grey skeleton that flipped in place; the two engines looked like different
    // products.) So the row count must GROW rather than arrive complete.
    const rows = page.locator('.tc-rows .trace-row');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });

    const firstCount = await rows.count();
    expect(firstCount).toBeGreaterThan(0);
    // Not everything at once — with a ~400ms floor per step, a 9+ step run
    // cannot possibly be fully rendered by the time the first row is visible.
    expect(firstCount).toBeLessThan(9);

    // ...and it keeps growing until the whole run is on screen.
    await expect.poll(async () => rows.count(), { timeout: 15_000 })
      .toBeGreaterThanOrEqual(9); // the deterministic engine's own step count

    // Every rendered row settles to 'done' — a clean deterministic run never
    // fails/denies/abandons a step.
    const states = await rows.evaluateAll(els => els.map(el => el.getAttribute('data-state')));
    expect(states.every(s => s === 'done')).toBe(true);

    // The header count tracks the rows actually on screen (shared by both engines).
    await expect(page.locator('.tc-count')).toHaveText(String(await rows.count()));
  });

  test('provenance renders as two separate chips, never one collapsed claim', async ({ page }) => {
    await page.goto('/');
    await page.fill('#inc', SEEDED_INCIDENT);
    await page.click('#go');

    const chips = page.locator('.lt7-chips .pill');
    await expect(chips).toHaveCount(2, { timeout: 10_000 });

    const texts = await chips.allTextContents();
    // One chip is connector provenance, the other is engine/backend — never
    // merged into a single "no network"-style string.
    expect(texts.some(t => /connector/i.test(t))).toBe(true);
    expect(texts.some(t => /deterministic|adk/i.test(t))).toBe(true);
  });

  test('TRIAGEMATE (model-think) rows render subordinate, not as a real vendor', async ({ page }) => {
    await page.goto('/');
    await page.fill('#inc', SEEDED_INCIDENT);
    await page.click('#go');

    const triagemateRows = page.locator('.tc-rows .trace-row[data-platform="triagemate"]');
    await expect
      .poll(async () => triagemateRows.count(), { timeout: 10_000 })
      .toBeGreaterThan(0);

    const triagemateRow = triagemateRows.first();
    // Subordinate treatment (TASK-013): no vendor badge text for the pseudo-platform.
    const badge = triagemateRow.locator('.trace-badge');
    await expect(badge).toHaveCount(0);
  });
});
