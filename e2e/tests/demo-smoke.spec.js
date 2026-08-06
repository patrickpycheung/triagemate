// Pre-demo smoke: walks the paths a presenter actually takes, in a real browser, against
// the real app. Complements live-thinking-trace.spec.js, which pins UI mechanics — this one
// answers "if I run the demo right now, does it hold up?".
//
// Every check here is a thing that has actually broken at least once: the fixture wiring
// (a recorded incident coming back "not found"), the stand-in (an invented number 404ing),
// case-sensitivity (a lower-cased ticket rejected by the endpoint's @Pattern), and staff
// identities leaking out of the captured fixtures.
const { test, expect } = require('@playwright/test');

const REAL_NAMES = /VELA|Chuong|Narayanan|Bharatia|Dickens|Fischer|Cheung|Sajid|auspost\.com\.au/i;

async function diagnose(page, number) {
  await page.goto('/');
  if (number !== null) await page.fill('#inc', number);
  const typed = await page.inputValue('#inc');
  await page.click('#go');
  // Wait for the RESULT, not for a fixed interval. '.card' appears instantly — it is also
  // the "Investigating…" placeholder — and the mock connectors now take a randomised
  // 120-400ms each (triage.connectors.mock-latency), so a run lands around 3-5s and any
  // sleep long enough today is a flake waiting for a slower machine.
  //
  // Scoped to #out, NOT document.body: the page's <script> is inside <body>, so
  // body.textContent contains the SOURCE too — including the literal 'Investigating…' and
  // every other string the code mentions. A body-wide check can never go false, and a
  // body-wide assertion tests the source rather than the screen.
  await page.waitForFunction(
      () => !document.getElementById('out').textContent.includes('Investigating'),
      null, { timeout: 60_000 });
  await page.waitForTimeout(500);           // let the last trace rows paint
  return { typed, body: await page.textContent('#out') };
}

test('the pre-filled incident diagnoses with real Delivery Hazards evidence', async ({ page }) => {
  const { typed, body } = await diagnose(page, null);

  expect(typed).toBe('INC0010015');
  expect(body).toContain('INC0010015');
  expect(body).toContain('Delivery Hazards');
  // The J7 payment-reconcile story belongs to INC0010005 only. Seeing it here would mean the
  // fixture lookup missed and a mock fell back to invented data — the failure the whole
  // fixture layer exists to prevent.
  expect(body).not.toContain('payment_service');
});

test('an arbitrary number the presenter invents is diagnosed under that number', async ({ page }) => {
  const { body } = await diagnose(page, 'INC0042424');

  expect(body).toContain('INC0042424');
  // The stand-in supplies the EVIDENCE, never the heading: a report labelled with a
  // different ticket than the one asked about is the FND-54 failure wearing a new hat.
  expect(body).not.toContain('INC0010015');
  expect(body).toContain('Delivery Hazards');
});

test('a lower-cased ticket number is accepted', async ({ page }) => {
  // The endpoint's path variable is @Pattern("INC\\d{6,10}") — case-SENSITIVE — so this used
  // to surface as a bare 400 on screen when a presenter typed it by hand.
  const { body } = await diagnose(page, 'inc0010015');

  expect(body).not.toMatch(/invalid incident/i);
  expect(body).toContain('Delivery Hazards');
});

test('the legacy walkthrough incident still tells its own story', async ({ page }) => {
  const { body } = await diagnose(page, 'INC0010005');

  expect(body).toContain('INC0010005');
  expect(body).not.toContain('Delivery Hazards');   // must not inherit the recorded bundle
});

test('no real staff identity appears anywhere on screen', async ({ page }) => {
  for (const number of [null, 'INC0042424', 'INC0010005']) {
    const { body } = await diagnose(page, number);
    expect(body, `real identity leaked for ${number ?? 'default'}`).not.toMatch(REAL_NAMES);
  }
});

test('the page raises no unexpected browser errors', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => {
    // A 404 on /api/runs/{id}/steps is EXPECTED and handled: the client mints the runId and
    // starts polling before the server has registered the run, and treats the 404 as the
    // documented signal (see index.html's startLt4Poll). Filtering it out here rather than
    // asserting zero console noise, so this test fails on real errors only.
    const t = m.text();
    if (m.type() === 'error' && !/api\/runs|Failed to load resource/.test(t)) errors.push(t);
  });

  await diagnose(page, null);

  expect(errors).toEqual([]);
});

test('next actions render as a numbered worklist, every line an action', async ({ page }) => {
  await diagnose(page, null);
  const card = page.locator('.card', { hasText: 'Recommended next actions' });
  const items = await card.locator('li').allTextContents();

  // A list, not a paragraph.
  expect(items.length).toBeGreaterThan(1);
  // Each step is a thing to DO. "Fill the gap: N other systems appeared in the window but
  // nothing evidences them" was a disclosure wearing an imperative — the steps are built
  // from structured fields now, so it cannot come back.
  expect(items.join(' ')).not.toContain('Fill the gap');
  for (const t of items) expect(t.trim().length).toBeGreaterThan(10);
});

test('mocked contacts are capped but still show every source', async ({ page }) => {
  const { body } = await diagnose(page, null);
  const who = body.slice(body.indexOf('Who to talk to'), body.indexOf('Evidence'));

  // triage.connectors.mock-contact-limit caps PER SOURCE, so the point being demonstrated
  // — names arriving from three systems independently — survives the trim. The uncapped
  // recorded bundle yields 23, ten of them Confluence.
  for (const source of ['ServiceNow', 'Confluence', 'GitLab']) {
    expect(who, `${source} should still be represented`).toContain(source);
  }
  expect((who.match(/@example\.com/g) || []).length).toBeLessThanOrEqual(8);
});
