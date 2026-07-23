const test = require('node:test');
const assert = require('node:assert/strict');
const { getLogs } = require('../src/index.js');

test('getLogs returns the PAYMENT_RECONCILE_MISMATCH ERROR line for INC-ORD-4471', async () => {
  const result = await getLogs({ orderId: 'INC-ORD-4471' });

  assert.ok(Array.isArray(result.messages));
  assert.ok(result.messages.length > 0);

  const mismatch = result.messages.find(
    (m) => m.logger === 'payment_service' && m.level === 'ERROR'
  );
  assert.ok(mismatch, 'expected a payment_service ERROR message');
  assert.equal(
    mismatch.msg,
    'PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471 expected=11.50 charged=11.25'
  );
});

test('getLogs returns an empty result for an unknown orderId', async () => {
  const result = await getLogs({ orderId: 'INC-ORD-NOPE' });
  assert.deepEqual(result.messages, []);
});
