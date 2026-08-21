'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const { FirebasePushService } = require('../src/fcm');
const { DeviceRegistry } = require('../src/registry');
const { EventStore } = require('../src/store');

const EQUIPMENT_COUNT = Number.parseInt(process.env.NANOSMART_STRESS_EQUIPMENT || '10000', 10);
const EVENT_COUNT = Number.parseInt(process.env.NANOSMART_STRESS_EVENTS || '20000', 10);
const PUSH_COUNT = Number.parseInt(process.env.NANOSMART_STRESS_PUSH || '2000', 10);
const AUTH_COUNT = Number.parseInt(process.env.NANOSMART_STRESS_AUTH || '50000', 10);

function hashToken(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function response(status, body) {
  const text = JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => JSON.parse(text),
    text: async () => text
  };
}

function createServiceAccount(directory) {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const filePath = path.join(directory, 'firebase-service-account.json');
  fs.writeFileSync(filePath, JSON.stringify({
    project_id: 'nanosmart-stress-simulado',
    client_email: 'stress@example.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    token_uri: 'https://oauth2.googleapis.com/token'
  }));
  return filePath;
}

function createRegistryFile(filePath) {
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
  for (let index = 0; index < EQUIPMENT_COUNT; index += 1) {
    const imei = String(800000000000000 + index);
    const installationId = `stress-${index}`;
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
      name: `Celular simulado ${index}`,
      platform: index % 2 === 0 ? 'ANDROID' : 'IOS',
      enabled: true,
      pushToken: `push-simulado-${index}`,
      tokenHash: hashToken(`access-simulado-${index}`),
      createdAt: '2026-08-20T00:00:00.000Z',
      updatedAt: '2026-08-20T00:00:00.000Z',
      lastRegisteredAt: '2026-08-20T00:00:00.000Z'
    };
  }
  fs.writeFileSync(filePath, JSON.stringify(data));
}

async function run() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-stress-v36-'));
  const startedAt = performance.now();
  try {
    const registryFile = path.join(directory, 'registry.json');
    createRegistryFile(registryFile);
    const registry = new DeviceRegistry(registryFile, { persistDebounceMs: 250 });
    const registryStartedAt = performance.now();
    registry.initialize();
    const registryLoadMs = performance.now() - registryStartedAt;

    const authStartedAt = performance.now();
    for (let index = 0; index < AUTH_COUNT; index += 1) {
      registry.authenticate(`access-simulado-${index % EQUIPMENT_COUNT}`);
    }
    for (let index = 0; index < EQUIPMENT_COUNT; index += 1) {
      registry.listPushTargets(String(800000000000000 + index));
    }
    const registryQueriesMs = performance.now() - authStartedAt;

    const eventDirectory = path.join(directory, 'events');
    const store = new EventStore({
      directory: eventDirectory,
      memoryLimit: 5000,
      trimBatch: 256,
      asyncPersistence: true
    });
    store.initialize();
    const eventsStartedAt = performance.now();
    for (let index = 0; index < EVENT_COUNT; index += 1) {
      store.append({
        receivedAt: '2026-08-20T12:00:00.000Z',
        valid: true,
        abonado: String(1000 + (index % 1000)),
        imei: String(800000000000000 + (index % EQUIPMENT_COUNT)),
        eventCode: '130',
        eventDescription: 'Robo simulado',
        shouldForwardToApp: true
      });
    }
    const eventEnqueueMs = performance.now() - eventsStartedAt;
    const eventFlushStartedAt = performance.now();
    await store.close();
    const eventFlushMs = performance.now() - eventFlushStartedAt;

    const serviceAccountFile = createServiceAccount(directory);
    let activeNetwork = 0;
    let maximumNetwork = 0;
    const fakeFetch = async (url) => {
      if (String(url).includes('oauth2.googleapis.com/token')) {
        return response(200, { access_token: 'oauth-simulado', expires_in: 3600 });
      }
      activeNetwork += 1;
      maximumNetwork = Math.max(maximumNetwork, activeNetwork);
      await new Promise((resolve) => setImmediate(resolve));
      activeNetwork -= 1;
      return response(200, { name: 'projects/stress/messages/simulado' });
    };
    const pushService = new FirebasePushService({
      enabled: true,
      projectId: 'nanosmart-stress-simulado',
      serviceAccountFile,
      fetchImpl: fakeFetch,
      maxConcurrentSends: 20
    });
    const pushStartedAt = performance.now();
    const pushResult = await pushService.sendAlert({
      id: 1,
      imei: '800000000000000',
      eventCode: '130',
      eventDescription: 'Robo simulado',
      partition: '01',
      subject: '001',
      subjectKind: 'ZONA',
      abonado: '1000',
      receivedAt: '2026-08-20T12:00:00.000Z'
    }, Array.from({ length: PUSH_COUNT }, (_, index) => ({
      installationId: `push-stress-${index}`,
      pushToken: `push-stress-${index}`,
      platform: index % 2 === 0 ? 'ANDROID' : 'IOS'
    })));
    const pushMs = performance.now() - pushStartedAt;
    registry.close();

    const memory = process.memoryUsage();
    const result = {
      simulated: true,
      realFirebaseRequests: 0,
      equipment: EQUIPMENT_COUNT,
      authenticationChecks: AUTH_COUNT,
      events: EVENT_COUNT,
      pushNotifications: PUSH_COUNT,
      registryLoadMs: Number(registryLoadMs.toFixed(1)),
      registryQueriesMs: Number(registryQueriesMs.toFixed(1)),
      eventEnqueueMs: Number(eventEnqueueMs.toFixed(1)),
      eventFlushMs: Number(eventFlushMs.toFixed(1)),
      pushMs: Number(pushMs.toFixed(1)),
      pushSent: pushResult.sent,
      pushErrors: pushResult.errors,
      maximumConcurrentPushes: maximumNetwork,
      configuredConcurrentPushLimit: pushService.getStatus().maxConcurrentSends,
      eventsRetainedInMemory: store.getStats().eventsInMemory,
      rssMemoryMb: Number((memory.rss / 1024 / 1024).toFixed(1)),
      totalMs: Number((performance.now() - startedAt).toFixed(1))
    };
    console.log(JSON.stringify(result, null, 2));
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

run().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
