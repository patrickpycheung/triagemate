const api = require('@forge/api');
const fs = require('fs');
const path = require('path');

const SN_INSTANCE = process.env.SN_INSTANCE_URL || 'https://<instance>.service-now.com';
const GITLAB_BASE = process.env.GITLAB_BASE_URL || 'https://gitlab.example.com';
const GITLAB_PROJECT_ID = process.env.GITLAB_PROJECT_ID || 'seed-repo';

// Fixed, seeded file set for the grounding guarantee (C2/C3): getSource for the
// seeded project always returns payment_service.py (+ order_api.py) so the
// agent's log<->code correlation has a real emitting line to quote.
const SEEDED_FILES = ['payment_service.py', 'order_api.py'];

/**
 * getTicket({ticketId}) -> {project, shortDesc, notes, orderId, openedAt}
 * Reads a ServiceNow incident via the table API.
 */
async function getTicket({ ticketId }) {
  if (!ticketId || typeof ticketId !== 'string') {
    throw new Error('getTicket: ticketId is required');
  }

  let response;
  try {
    response = await api.fetch(
      `${SN_INSTANCE}/api/now/table/incident?sysparm_query=number=${encodeURIComponent(
        ticketId
      )}&sysparm_limit=1`,
      {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${process.env.SN_TOKEN}`,
          Accept: 'application/json',
        },
      }
    );
  } catch (err) {
    throw new Error(`getTicket: network error contacting ServiceNow: ${err.message}`);
  }

  if (!response.ok) {
    throw new Error(`getTicket: ServiceNow returned ${response.status} for ${ticketId}`);
  }

  const body = await response.json();
  const record = body && body.result && body.result[0];
  if (!record) {
    throw new Error(`getTicket: no ticket found for ${ticketId}`);
  }

  const orderId = extractOrderId(record);

  return {
    project: record.u_project || record.category || 'unknown-project',
    shortDesc: record.short_description || '',
    notes: record.description || record.work_notes || '',
    orderId,
    openedAt: record.opened_at || record.sys_created_on || null,
  };
}

// Best-effort extraction of an order id embedded in a ticket's text fields
// (e.g. "order INC-ORD-4471"). Falls back to any explicit field ServiceNow
// might carry.
function extractOrderId(record) {
  if (record.u_order_id) return record.u_order_id;
  const haystack = `${record.short_description || ''} ${record.description || ''}`;
  const match = haystack.match(/\bINC-ORD-\d+\b/);
  return match ? match[0] : null;
}

/**
 * getSource({project}) -> {repoUrl, ref, files: [{path, content}]}
 * Reads master source files from GitLab. For the seeded project, returns a
 * FIXED file set that always includes payment_service.py (+ order_api.py) —
 * the grounding guarantee (C2/C3): correlation must always find a real file
 * to quote.
 */
async function getSource({ project }) {
  if (!project || typeof project !== 'string') {
    throw new Error('getSource: project is required');
  }

  const repoUrl = `${GITLAB_BASE}/${GITLAB_PROJECT_ID}`;
  const ref = 'master';
  const files = [];

  for (const filePath of SEEDED_FILES) {
    let response;
    try {
      response = await api.fetch(
        `${GITLAB_BASE}/api/v4/projects/${encodeURIComponent(
          GITLAB_PROJECT_ID
        )}/repository/files/${encodeURIComponent(filePath)}/raw?ref=${ref}`,
        {
          method: 'GET',
          headers: {
            'PRIVATE-TOKEN': process.env.GITLAB_TOKEN,
          },
        }
      );
    } catch (err) {
      throw new Error(`getSource: network error contacting GitLab for ${filePath}: ${err.message}`);
    }

    if (!response.ok) {
      throw new Error(`getSource: GitLab returned ${response.status} for ${filePath}`);
    }

    const content = await response.text();
    files.push({ path: filePath, content });
  }

  return { repoUrl, ref, files };
}

/**
 * getLogs({orderId}) -> {messages: [{time, level, logger, msg}]}
 * Reads the BUNDLED mock Sumo fixture (demo safety, C6) rather than calling
 * Sumo live, and filters to the given orderId's failure window.
 */
async function getLogs({ orderId }) {
  if (!orderId || typeof orderId !== 'string') {
    throw new Error('getLogs: orderId is required');
  }

  const fixturePath = path.join(__dirname, 'resources', 'sumo-fixture.json');
  const raw = fs.readFileSync(fixturePath, 'utf8');
  const fixture = JSON.parse(raw);

  const messages = (fixture.messages || []).filter((m) => m.msg && m.msg.includes(orderId));

  return { messages };
}

/**
 * postWorknote({ticketId, noteBody, confirmed}) -> {ok, worknoteId} | {ok:false, reason}
 * Refuses to write unless confirmed===true (G4). On network error/timeout it
 * does NOT auto-retry — a timed-out write may already have landed, so it
 * reports "unknown-outcome" instead of blindly retrying (avoids duplicate
 * work-notes).
 */
async function postWorknote({ ticketId, noteBody, confirmed }) {
  if (confirmed !== true) {
    return { ok: false, reason: 'not-confirmed' };
  }
  if (!ticketId || typeof ticketId !== 'string') {
    return { ok: false, reason: 'missing-ticketId' };
  }
  if (!noteBody || typeof noteBody !== 'string') {
    return { ok: false, reason: 'missing-noteBody' };
  }

  let response;
  try {
    response = await api.fetch(
      `${SN_INSTANCE}/api/now/table/incident`,
      {
        method: 'PATCH',
        headers: {
          Authorization: `Bearer ${process.env.SN_TOKEN}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify({
          number: ticketId,
          work_notes: noteBody,
        }),
      }
    );
  } catch (err) {
    // Network error/timeout: outcome is unknown, a write may have landed.
    // Do NOT auto-retry (idempotency, G4).
    return { ok: false, reason: 'unknown-outcome' };
  }

  if (!response.ok) {
    return { ok: false, reason: `servicenow-error-${response.status}` };
  }

  const body = await response.json().catch(() => ({}));
  const worknoteId = (body && body.result && body.result.sys_id) || null;

  return { ok: true, worknoteId };
}

module.exports = { getTicket, getSource, getLogs, postWorknote };
