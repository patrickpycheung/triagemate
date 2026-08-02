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

    // The replay renderer (LT3) pre-renders every step as a PENDING skeleton
    // row immediately, then flips each to its resolved state one at a time,
    // paced at max(realDuration, ~400ms) apart. So: the full row COUNT should
    // appear quickly, but not all of them should be DONE immediately — that
    // would mean the pacing floor was silently bypassed and everything was
    // rendered in one shot.
    const rows = page.locator('#lt3Rows .trace-row');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });

    const totalRows = await rows.count();
    expect(totalRows).toBeGreaterThanOrEqual(9); // the deterministic engine's own step count

    // At least one row must still be non-DONE shortly after the rows first
    // appear, proving the reveal is staggered rather than instantaneous. This
    // polls instead of sleeping a fixed duration (flaky under CI/host load)
    // — but the bound stays well under the ~400ms pacing floor, so it can
    // only pass because the first row genuinely hasn't flipped yet, not
    // because the poll happened to get lucky on timing.
    await expect
      .poll(
        async () => {
          const states = await rows.evaluateAll(els => els.map(el => el.getAttribute('data-state')));
          return states.some(s => s !== 'done');
        },
        { timeout: 300 },
      )
      .toBe(true);

    // Eventually every row settles to 'done' (a clean deterministic run never
    // fails/denies/abandons a step) — proves the reveal actually completes,
    // not just that it starts staggered.
    await expect
      .poll(async () => (await rows.evaluateAll(els => els.map(el => el.getAttribute('data-state')))), {
        timeout: 10_000,
      })
      .toEqual(new Array(totalRows).fill('done'));
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

    const triagemateRows = page.locator('#lt3Rows .trace-row[data-platform="triagemate"]');
    await expect
      .poll(async () => triagemateRows.count(), { timeout: 10_000 })
      .toBeGreaterThan(0);

    const triagemateRow = triagemateRows.first();
    // Subordinate treatment (TASK-013): no vendor badge text for the pseudo-platform.
    const badge = triagemateRow.locator('.trace-badge');
    await expect(badge).toHaveCount(0);
  });
});
