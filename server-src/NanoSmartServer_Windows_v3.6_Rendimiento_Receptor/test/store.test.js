'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { EventStore } = require('../src/store');

test('separa equipos por IMEI y conserva sólo sus alertas', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-store-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const store = new EventStore({ directory });
  store.initialize();

  const first = store.append({
    receivedAt: '2026-07-17T12:30:00.000Z',
    valid: true,
    abonado: '1234',
    imei: '869671077527800',
    eventCode: '401',
    eventDescription: 'Cierre por usuario',
    shouldForwardToApp: false
  });
  store.append({
    receivedAt: '2026-07-17T12:31:00.000Z',
    valid: true,
    abonado: '1234',
    imei: '869671077527800',
    eventCode: '130',
    eventDescription: 'Robo zona 15',
    shouldForwardToApp: true
  });

  assert.equal(first.id, 1);
  assert.equal(store.getDevice('869671077527800').eventCount, 2);
  assert.equal(store.getDevice('869671077527800').appAlertCount, 1);
  assert.equal(store.getEvents({ imei: '869671077527800', forwardOnly: true }).length, 1);
  assert.equal(store.getStats().devicesDetected, 1);
  assert.equal(store.getStats().eventsStored, 2);

  store.recordDuplicate(true);
  assert.equal(store.getStats().totalReceived, 3);
  assert.equal(store.getStats().duplicatePackets, 1);
  assert.equal(store.getStats().eventsStored, 2);
});

test('agrupa escrituras sin bloquear y conserva las consultas por IMEI', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-store-async-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const store = new EventStore({
    directory,
    memoryLimit: 1000,
    trimBatch: 32,
    asyncPersistence: true
  });
  store.initialize();

  for (let index = 0; index < 2500; index += 1) {
    store.append({
      receivedAt: '2026-08-20T12:00:00.000Z',
      valid: true,
      abonado: String(1000 + (index % 100)),
      imei: String(800000000000000 + (index % 100)),
      eventCode: '130',
      eventDescription: 'Robo simulado',
      shouldForwardToApp: true
    });
  }

  assert.ok(store.getStats().eventsInMemory <= 1032);
  assert.equal(store.getEvents({ imei: '800000000000099', take: 1000 }).length > 0, true);
  await store.close();
  assert.equal(store.getStats().pendingWrites, 0);
  assert.ok(fs.statSync(path.join(directory, 'events-2026-08-20.jsonl')).size > 1000);
});
