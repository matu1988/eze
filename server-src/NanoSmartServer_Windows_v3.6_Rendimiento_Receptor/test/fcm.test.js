'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { FirebasePushService, compactBody } = require('../src/fcm');

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
    project_id: 'nanosmart-eventos-prueba',
    client_email: 'firebase-adminsdk@example.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    token_uri: 'https://oauth2.googleapis.com/token'
  }));
  return filePath;
}

const EVENT = {
  id: 42,
  imei: '000001278272947',
  eventCode: '130',
  eventDescription: 'Robo',
  partition: '01',
  subject: '015',
  subjectKind: 'ZONA',
  abonado: '6211',
  receivedAt: '2026-07-17T15:00:00.000Z'
};

test('usa el nombre compartido de la zona en la notificacion', () => {
  const body = compactBody({
    ...EVENT,
    subjectNumber: 15,
    zoneName: 'Dormitorio principal'
  });
  assert.equal(body.includes('Dormitorio principal (Zona 15)'), true);
  assert.equal(body.includes('Zona 015'), false);
});

test('arma el texto compacto de la notificación', () => {
  assert.equal(compactBody(EVENT), 'Robo · Partición 01 · Zona 015 · Abonado 6211');
  assert.equal(
    compactBody({ ...EVENT, eventDescription: 'Pánico', actorName: 'Laura' }),
    'Pánico · Ejecutado por Laura · Partición 01 · Zona 015 · Abonado 6211'
  );
});

test('autentica una vez y envía una notificación por celular', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-fcm-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const serviceAccountFile = createServiceAccount(directory);
  const calls = [];
  const fakeFetch = async (url, options) => {
    calls.push({ url: String(url), options });
    if (String(url).includes('oauth2.googleapis.com/token')) {
      return response(200, { access_token: 'oauth-token-de-prueba', expires_in: 3600 });
    }
    return response(200, { name: 'projects/test/messages/1' });
  };

  const service = new FirebasePushService({
    enabled: true,
    projectId: 'nanosmart-eventos-prueba',
    serviceAccountFile,
    fetchImpl: fakeFetch
  });
  const locatedEvent = {
    ...EVENT,
    latitude: -34.6037,
    longitude: -58.3816,
    locationAccuracyMeters: 18.5,
    locationCapturedAt: '2026-08-13T11:00:00.000Z'
  };
  const result = await service.sendAlert(locatedEvent, [
    { installationId: 'phone-a', pushToken: 'push-a' },
    { installationId: 'phone-b', pushToken: 'push-b' }
  ]);

  assert.equal(service.getStatus().ready, true);
  assert.equal(result.sent, 2);
  assert.equal(result.errors, 0);
  assert.equal(calls.filter((call) => call.url.includes('oauth2.googleapis.com/token')).length, 1);
  const messages = calls.filter((call) => call.url.includes('fcm.googleapis.com'));
  assert.equal(messages.length, 2);
  const payload = JSON.parse(messages[0].options.body);
  assert.equal(payload.message.data.imei, EVENT.imei);
  assert.equal(payload.message.data.type, 'ALERT');
  assert.equal(payload.message.data.zoneName, '');
  assert.equal(payload.message.data.latitude, '-34.6037');
  assert.equal(payload.message.data.longitude, '-58.3816');
  assert.equal(payload.message.data.locationAccuracyMeters, '18.5');
  assert.match(payload.message.data.mapsUrl, /google\.com\/maps/);
  assert.equal(payload.message.data.body.includes('disponible'), true);
  assert.equal(payload.message.android.priority, 'HIGH');
  assert.equal(payload.message.android.restricted_package_name, 'com.nanocomm.nanosmart.eventos');
  assert.equal(payload.message.notification, undefined);
  assert.equal(payload.message.android.notification, undefined);
  assert.equal(messages[0].options.headers.Authorization, 'Bearer oauth-token-de-prueba');
});

test('envía la confirmación de armado como actualización de estado', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-fcm-command-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const serviceAccountFile = createServiceAccount(directory);
  const calls = [];
  const fakeFetch = async (url, options) => {
    calls.push({ url: String(url), options });
    if (String(url).includes('oauth2.googleapis.com/token')) {
      return response(200, { access_token: 'oauth-token-de-prueba', expires_in: 3600 });
    }
    return response(200, { name: 'projects/test/messages/command' });
  };
  const service = new FirebasePushService({
    enabled: true,
    projectId: 'nanosmart-eventos-prueba',
    serviceAccountFile,
    fetchImpl: fakeFetch
  });

  const result = await service.sendDeviceCommand({
    id: 'command-1',
    imei: EVENT.imei,
    action: 'ARMAR',
    status: 'CONFIRMED',
    panelStatus: 'ARMADO',
    result: 'EXECUTED',
    resultCode: '45',
    resultDescription: 'Panel armado correctamente',
    actionSource: 'APP',
    actorName: 'Efraín',
    alreadyInState: false,
    confirmedAt: '2026-08-06T12:00:00.000Z',
    deliveredAt: '2026-08-06T12:00:00.000Z'
  }, [{ installationId: 'phone-a', pushToken: 'push-a' }]);

  assert.equal(result.sent, 1);
  const call = calls.find((item) => item.url.includes('fcm.googleapis.com'));
  const payload = JSON.parse(call.options.body);
  assert.equal(payload.message.data.type, 'DEVICE_COMMAND');
  assert.equal(payload.message.data.action, 'ARMAR');
  assert.equal(payload.message.data.imei, EVENT.imei);
  assert.equal(payload.message.data.panelStatus, 'ARMADO');
  assert.equal(payload.message.data.resultCode, '45');
  assert.equal(payload.message.data.alreadyInState, 'false');
  assert.equal(payload.message.data.actorName, 'Efraín');
  assert.equal(payload.message.data.body, 'Panel armado correctamente por Efraín');
  assert.equal(payload.message.notification, undefined);

  const keyboardResult = await service.sendPanelState({
    imei: EVENT.imei,
    panelStatus: 'DESARMADO',
    result: 'EXECUTED',
    resultCode: '46',
    resultDescription: 'Panel desarmado correctamente',
    actionSource: 'PANEL',
    actorName: 'Teclado del panel',
    alreadyInState: false,
    confirmedAt: '2026-08-06T12:05:00.000Z'
  }, [{ installationId: 'phone-a', pushToken: 'push-a' }]);

  assert.equal(keyboardResult.sent, 1);
  const fcmCalls = calls.filter((item) => item.url.includes('fcm.googleapis.com'));
  const keyboardPayload = JSON.parse(fcmCalls.at(-1).options.body);
  assert.equal(keyboardPayload.message.data.type, 'PANEL_STATE');
  assert.equal(keyboardPayload.message.data.panelStatus, 'DESARMADO');
  assert.equal(keyboardPayload.message.data.resultCode, '46');
  assert.equal(keyboardPayload.message.data.actorName, 'Teclado del panel');
  assert.equal(keyboardPayload.message.data.body, 'Panel desarmado desde el teclado');
  assert.equal(keyboardPayload.message.notification, undefined);
});

test('detecta tokens FCM dados de baja para poder quitarlos del registro', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-fcm-invalid-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const serviceAccountFile = createServiceAccount(directory);
  const fakeFetch = async (url) => {
    if (String(url).includes('oauth2.googleapis.com/token')) {
      return response(200, { access_token: 'oauth-token-de-prueba', expires_in: 3600 });
    }
    return response(404, {
      error: {
        message: 'Requested entity was not found.',
        details: [{ errorCode: 'UNREGISTERED' }]
      }
    });
  };
  const service = new FirebasePushService({
    enabled: true,
    projectId: 'nanosmart-eventos-prueba',
    serviceAccountFile,
    fetchImpl: fakeFetch
  });
  const result = await service.sendAlert(EVENT, [
    { installationId: 'phone-a', pushToken: 'push-vencido' }
  ]);

  assert.equal(result.sent, 0);
  assert.equal(result.errors, 1);
  assert.equal(result.invalidTargets[0].installationId, 'phone-a');
});

test('envia a iPhone una configuracion APNs y no el bloque Android', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-fcm-ios-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const serviceAccountFile = createServiceAccount(directory);
  const calls = [];
  const fakeFetch = async (url, options) => {
    calls.push({ url: String(url), options });
    if (String(url).includes('oauth2.googleapis.com/token')) {
      return response(200, { access_token: 'oauth-token-ios', expires_in: 3600 });
    }
    return response(200, { name: 'projects/test/messages/ios' });
  };
  const service = new FirebasePushService({
    enabled: true,
    projectId: 'nanosmart-eventos-prueba',
    serviceAccountFile,
    fetchImpl: fakeFetch
  });

  const result = await service.sendAlert(EVENT, [{
    installationId: 'iphone-laura',
    pushToken: 'fcm-ios-laura',
    platform: 'IOS'
  }]);

  assert.equal(result.sent, 1);
  const call = calls.find((item) => item.url.includes('fcm.googleapis.com'));
  const payload = JSON.parse(call.options.body);
  assert.equal(payload.message.apns.headers['apns-priority'], '10');
  assert.equal(payload.message.apns.payload.aps.sound, 'default');
  assert.equal(payload.message.android, undefined);
  assert.equal(payload.message.notification.body, 'Robo · Partición 01 · Zona 015 · Abonado 6211');
  assert.equal(payload.message.data.imei, EVENT.imei);
});

test('limita los envios simultaneos cuando llegan muchas alertas juntas', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-fcm-limit-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const serviceAccountFile = createServiceAccount(directory);
  let active = 0;
  let maximum = 0;
  const fakeFetch = async (url) => {
    if (String(url).includes('oauth2.googleapis.com/token')) {
      return response(200, { access_token: 'oauth-token-de-prueba', expires_in: 3600 });
    }
    active += 1;
    maximum = Math.max(maximum, active);
    await new Promise((resolve) => setTimeout(resolve, 5));
    active -= 1;
    return response(200, { name: 'projects/test/messages/stress' });
  };
  const service = new FirebasePushService({
    enabled: true,
    projectId: 'nanosmart-eventos-prueba',
    serviceAccountFile,
    fetchImpl: fakeFetch,
    maxConcurrentSends: 5
  });
  const targets = Array.from({ length: 60 }, (_, index) => ({
    installationId: `phone-${index}`,
    pushToken: `push-${index}`
  }));

  const result = await service.sendAlert(EVENT, targets);
  assert.equal(result.sent, 60);
  assert.ok(maximum <= 5);
  assert.equal(service.getStatus().maxObservedConcurrentSends, maximum);
  assert.equal(service.getStatus().activeSends, 0);
  assert.equal(service.getStatus().queuedSends, 0);
});
