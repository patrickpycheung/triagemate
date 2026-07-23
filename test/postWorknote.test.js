const test = require('node:test');
const assert = require('node:assert/strict');
const api = require('@forge/api');
const { postWorknote } = require('../src/index.js');

test('postWorknote refuses to write when confirmed=false', async () => {
  let called = false;
  api.fetch = async () => {
    called = true;
    return { ok: true, json: async () => ({}) };
  };

  const result = await postWorknote({
    ticketId: 'INC0012345',
    noteBody: 'draft note',
    confirmed: false,
  });

  assert.equal(result.ok, false);
  assert.ok(result.reason);
  assert.equal(called, false, 'must not call ServiceNow when not confirmed');
});

test('postWorknote posts the work-note when confirmed=true', async () => {
  let capturedUrl;
  let capturedOptions;
  api.fetch = async (url, options) => {
    capturedUrl = url;
    capturedOptions = options;
    return {
      ok: true,
      json: async () => ({ result: { sys_id: 'wn-123' } }),
    };
  };

  const result = await postWorknote({
    ticketId: 'INC0012345',
    noteBody: 'draft note',
    confirmed: true,
  });

  assert.equal(result.ok, true);
  assert.equal(result.worknoteId, 'wn-123');
  assert.ok(capturedUrl.includes('service-now.com'));
  assert.equal(capturedOptions.method, 'PATCH');
  const body = JSON.parse(capturedOptions.body);
  assert.equal(body.number, 'INC0012345');
  assert.equal(body.work_notes, 'draft note');
});

test('postWorknote returns unknown-outcome on network error and does not retry', async () => {
  let callCount = 0;
  api.fetch = async () => {
    callCount += 1;
    throw new Error('ETIMEDOUT');
  };

  const result = await postWorknote({
    ticketId: 'INC0012345',
    noteBody: 'draft note',
    confirmed: true,
  });

  assert.equal(result.ok, false);
  assert.equal(result.reason, 'unknown-outcome');
  assert.equal(callCount, 1, 'must not auto-retry on network error');
});
