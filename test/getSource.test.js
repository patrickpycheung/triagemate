const test = require('node:test');
const assert = require('node:assert/strict');
const api = require('@forge/api');
const { getSource } = require('../src/index.js');

test('getSource seeded set includes payment_service.py (grounding guarantee)', async () => {
  api.fetch = async (url) => {
    const isPaymentService = url.includes('payment_service.py');
    const content = isPaymentService
      ? 'logger.error(\n  "PAYMENT_RECONCILE_MISMATCH order=%s expected=%.2f charged=%.2f",\n'
      : '# order_api.py stub content';
    return {
      ok: true,
      text: async () => content,
    };
  };

  const result = await getSource({ project: 'seed-repo' });

  assert.ok(result.repoUrl);
  assert.equal(result.ref, 'master');
  assert.ok(Array.isArray(result.files));

  const paths = result.files.map((f) => f.path);
  assert.ok(paths.includes('payment_service.py'), 'must include payment_service.py');

  const paymentFile = result.files.find((f) => f.path === 'payment_service.py');
  assert.ok(paymentFile.content.includes('PAYMENT_RECONCILE_MISMATCH'));
});
