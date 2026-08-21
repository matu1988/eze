'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const { DeviceRegistry } = require('../src/registry');

function tokenHash(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

test('autentica y busca destinos en tiempo constante con 10000 celulares', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-registry-load-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const filePath = path.join(directory, 'registry.json');
  const data = {
    version: 4,
    devices: {},
    installations: {},
    pairingSlots: {},
    clients: {
      automonitoreo: {
        id: 'automonitoreo',
        name: 'Automonitoreo',
        forwardRobberyToApp: true,
        locked: true
      }
    }
  };

  for (let index = 0; index < 10000; index += 1) {
    const imei = String(800000000000000 + index);
    const installationId = `stress-phone-${index}`;
    data.devices[imei] = {
      imei,
      enabled: true,
      clientId: 'automonitoreo',
      createdAt: '2026-08-20T00:00:00.000Z',
      updatedAt: '2026-08-20T00:00:00.000Z'
    };
    data.installations[installationId] = {
      installationId,
      imei,
      name: `Celular ${index}`,
      platform: index % 2 === 0 ? 'ANDROID' : 'IOS',
      enabled: true,
      pushToken: `push-${index}`,
      tokenHash: tokenHash(`access-token-${index}`),
      createdAt: '2026-08-20T00:00:00.000Z',
      updatedAt: '2026-08-20T00:00:00.000Z',
      lastRegisteredAt: '2026-08-20T00:00:00.000Z'
    };
  }
  fs.writeFileSync(filePath, JSON.stringify(data));

  const registry = new DeviceRegistry(filePath, { persistDebounceMs: 250 });
  registry.initialize();
  const startedAt = performance.now();
  for (let index = 0; index < 20000; index += 1) {
    assert.equal(
      registry.authenticate(`access-token-${index % 10000}`).installationId,
      `stress-phone-${index % 10000}`
    );
  }
  for (let index = 0; index < 10000; index += 1) {
    assert.equal(registry.listPushTargets(String(800000000000000 + index)).length, 1);
  }
  const elapsedMs = performance.now() - startedAt;

  assert.equal(registry.countPushTokens(), 10000);
  assert.equal(registry.listProvisionedDevices().length, 10000);
  assert.ok(elapsedMs < 3000, `Las consultas tardaron ${elapsedMs.toFixed(1)} ms`);
  registry.close();
});
