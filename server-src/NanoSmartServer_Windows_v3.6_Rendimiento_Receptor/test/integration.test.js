'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const dgram = require('node:dgram');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PROJECT_ROOT = path.resolve(__dirname, '..');
const IMEI = '000001278272947';
const SECOND_IMEI = '869671077527800';
const ADMIN_IMEI = '869671077527867';
const PRODUCTION_IMEI = '869671077527899';
const USER_PACKET = `$B,TM10,15,25/11/2022-15:27,01,621118340100040B,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,${IMEI},133,0,186.191.130.232,0,999,$E`;
const ALARM_PACKET = `$B,TM10,16,17/07/2026-09:35,01,6211181130010158,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,${IMEI},133,0,186.191.130.232,0,999,$E`;
const SECOND_ALARM_PACKET = `$B,TM20,17,17/07/2026-09:36,01,777718112000003A,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,${SECOND_IMEI},133,0,186.191.130.233,0,999,$E`;
const ARM_CONFIRMED_PACKET = `$B,TM67,11,06/08/2026-13:58,45,0000000000000000,19,0,0,1234,10,MA_1.95GE-AR,1,0,0,0,133,1,0,${ADMIN_IMEI},0,133,10.185.134.48,21,00,10,4G,$E`;

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitUntil(check, timeoutMs = 5000) {
  const limit = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < limit) {
    try {
      const result = await check();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await delay(50);
  }
  throw lastError || new Error('Tiempo de espera agotado');
}

function sendUdpAndReceiveAck(packet, port) {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4');
    const timeout = setTimeout(() => {
      socket.close();
      reject(new Error('No se recibió ACK'));
    }, 3000);

    socket.on('message', (message) => {
      clearTimeout(timeout);
      const ack = message.toString('utf8');
      socket.close();
      resolve(ack);
    });
    socket.on('error', (error) => {
      clearTimeout(timeout);
      socket.close();
      reject(error);
    });
    socket.bind(0, '127.0.0.1', () => {
      socket.send(Buffer.from(packet), port, '127.0.0.1', (error) => {
        if (error) {
          clearTimeout(timeout);
          socket.close();
          reject(error);
        }
      });
    });
  });
}

function receiveUdp(socket, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off('message', onMessage);
      reject(new Error('No se recibió el paquete UDP esperado'));
    }, timeoutMs);
    function onMessage(message) {
      clearTimeout(timeout);
      socket.off('message', onMessage);
      resolve(message.toString('utf8'));
    }
    socket.on('message', onMessage);
  });
}

function sendUdp(socket, payload, port) {
  return new Promise((resolve, reject) => {
    socket.send(Buffer.from(payload), port, '127.0.0.1', (error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}

test('responde ACK, separa por IMEI y no guarda duplicados', { timeout: 15000 }, async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-integration-'));
  const udpPort = 20000 + Math.floor(Math.random() * 10000);
  const httpPort = 30000 + Math.floor(Math.random() * 10000);
  const productionPort = 40000 + Math.floor(Math.random() * 10000);
  const configPath = path.join(directory, 'config.json');
  fs.writeFileSync(configPath, JSON.stringify({
    udp: { host: '127.0.0.1', port: udpPort, maxPacketBytes: 8192 },
    http: { host: '127.0.0.1', port: httpPort, corsOrigin: '*' },
    production: {
      enabled: true,
      host: '127.0.0.1',
      port: productionPort,
      authFailureLimit: 3,
      authFailureWindowSeconds: 60,
      authBlockSeconds: 60
    },
    storage: { directory: path.join(directory, 'data'), memoryLimit: 500 },
    commands: {
      enabled: true,
      inquiryPayload: '$B,DI01,MIC=0,$E',
      endpointMaxAgeSeconds: 90,
      inquiryRetrySeconds: 1,
      commandTtlSeconds: 30
    },
    protocol: {
      startMarker: '$B',
      endMarker: '$E',
      duplicateWindowSeconds: 300,
      packetTypeDescriptions: {},
      contactIdEventDescriptions: {},
      ack: { enabled: true, timeZone: 'America/Argentina/Buenos_Aires', lineEnding: '' },
      appForwarding: { enabled: true, qualifiers: ['1'], eventCodes: ['120', '130'] }
    }
  }));

  const child = spawn(process.execPath, [path.join(PROJECT_ROOT, 'server.js')], {
    cwd: PROJECT_ROOT,
    env: { ...process.env, NANOSMART_CONFIG: configPath },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk.toString(); });
  child.stderr.on('data', (chunk) => { output += chunk.toString(); });

  context.after(async () => {
    if (child.exitCode === null) {
      child.kill('SIGTERM');
      await Promise.race([
        new Promise((resolve) => child.once('exit', resolve)),
        delay(3000)
      ]);
    }
    fs.rmSync(directory, { recursive: true, force: true });
  });

  await waitUntil(() => output.includes('[INFO] Presione Ctrl+C'));

  const productionKey = fs.readFileSync(
    path.join(directory, 'data', 'production-key.txt'),
    'utf8'
  ).trim();
  const removedFromMobilePort = await fetch(
    `http://127.0.0.1:${httpPort}/api/production/health`,
    { headers: { 'X-NanoSmart-Production-Key': productionKey } }
  );
  assert.equal(removedFromMobilePort.status, 404);
  const unauthorizedProduction = await waitUntil(async () => {
    const response = await fetch(`http://127.0.0.1:${productionPort}/api/production/health`);
    return response.status === 401 ? response : null;
  });
  assert.equal(unauthorizedProduction.status, 401);

  const mobileRouteOnProductionPort = await fetch(
    `http://127.0.0.1:${productionPort}/api/app/me`
  );
  assert.equal(mobileRouteOnProductionPort.status, 404);

  const productionResponse = await fetch(
    `http://127.0.0.1:${productionPort}/api/production/pairings`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-NanoSmart-Production-Key': productionKey
      },
      body: JSON.stringify({
        imei: PRODUCTION_IMEI,
        labels: ['Mobile 1', 'Mobile 2']
      })
    }
  );
  const production = await productionResponse.json();
  assert.equal(productionResponse.status, 201);
  assert.equal(production.imei, PRODUCTION_IMEI);
  assert.equal(production.pairings.length, 2);
  assert.notEqual(production.pairings[0].qrPayload, production.pairings[1].qrPayload);
  assert.ok(production.pairings[0].qrPngBase64.length > 100);

  const productionPhones = [];
  for (const [index, pairing] of production.pairings.entries()) {
    const [, imei, accessKey] = pairing.qrPayload.split('|');
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/app/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imei, accessKey, name: `Persona ${index + 1}` })
    });
    assert.equal(response.status, 201);
    productionPhones.push(await response.json());
  }
  assert.notEqual(productionPhones[0].accessToken, productionPhones[1].accessToken);
  assert.notEqual(productionPhones[0].installationId, productionPhones[1].installationId);
  assert.equal(fs.existsSync(path.join(directory, 'data', 'production-audit.jsonl')), true);

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const wrongKeyResponse = await fetch(
      `http://127.0.0.1:${productionPort}/api/production/health`,
      { headers: { 'X-NanoSmart-Production-Key': 'NSP-INCORRECTA' } }
    );
    assert.equal(wrongKeyResponse.status, 401);
  }
  const blockedProduction = await fetch(
    `http://127.0.0.1:${productionPort}/api/production/health`,
    { headers: { 'X-NanoSmart-Production-Key': productionKey } }
  );
  assert.equal(blockedProduction.status, 429);

  const adminPage = await fetch(`http://127.0.0.1:${httpPort}/admin`);
  const adminPageText = await adminPage.text();
  assert.match(adminPageText, /Administración NanoSmart/);
  assert.match(adminPageText, /Crear celular y obtener token/);
  const simulatorPage = await fetch(`http://127.0.0.1:${httpPort}/app-simulator`);
  assert.match(await simulatorPage.text(), /Simulador de celulares/);

  const directPhoneResponse = await fetch(`http://127.0.0.1:${httpPort}/api/admin/installations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imei: ADMIN_IMEI, name: 'Alta directa' })
  });
  const directPhone = await directPhoneResponse.json();
  assert.equal(directPhoneResponse.status, 201);
  assert.equal(directPhone.imei, ADMIN_IMEI);
  assert.equal(directPhone.deviceProvisioned, true);
  assert.ok(directPhone.accessToken);

  const directPhoneMeResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/me`, {
    headers: { Authorization: `Bearer ${directPhone.accessToken}` }
  });
  const directPhoneMe = await directPhoneMeResponse.json();
  assert.equal(directPhoneMeResponse.status, 200);
  assert.equal(directPhoneMe.imei, ADMIN_IMEI);

  const batchStatusResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/app/batch/status`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accessTokens: [productionPhones[0].accessToken, directPhone.accessToken]
      })
    }
  );
  const batchStatus = await batchStatusResponse.json();
  assert.equal(batchStatusResponse.status, 200);
  assert.equal(batchStatus.count, 2);
  assert.deepEqual(
    batchStatus.statuses.map((item) => item.installation.imei),
    [PRODUCTION_IMEI, ADMIN_IMEI]
  );

  const batchPushResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/app/batch/push-token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accessTokens: [productionPhones[0].accessToken, directPhone.accessToken],
        pushToken: 'fcm-simulado-multipanel',
        platform: 'ANDROID'
      })
    }
  );
  const batchPush = await batchPushResponse.json();
  assert.equal(batchPushResponse.status, 200);
  assert.equal(batchPush.count, 2);

  const invalidBatchResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/app/batch/status`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accessTokens: ['token-invalido'] })
    }
  );
  assert.equal(invalidBatchResponse.status, 401);

  const commandResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/device/command`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${directPhone.accessToken}`
    },
    body: JSON.stringify({ action: 'ARMAR' })
  });
  const queuedCommand = await commandResponse.json();
  assert.equal(commandResponse.status, 202);
  assert.equal(queuedCommand.command.status, 'PENDING');

  const deviceSocket = dgram.createSocket('udp4');
  await new Promise((resolve) => deviceSocket.bind(0, '127.0.0.1', resolve));
  const inquiryPromise = receiveUdp(deviceSocket);
  await sendUdp(deviceSocket, ADMIN_IMEI, udpPort);
  assert.equal(await inquiryPromise, '$B,DI01,MIC=0,$E');

  const controlReport = `$B,2010,04,06/08/2026-09:28,00,0000000000000000,${ADMIN_IMEI},GPRS,$E`;
  const actionAckPromise = receiveUdp(deviceSocket);
  await sendUdp(deviceSocket, controlReport, udpPort);
  const actionAck = await actionAckPromise;
  assert.match(actionAck, /^\$B,2010,TI=\d{2}\/\d{2}\/\d{4}-\d{2}:\d{2},ACK=04,ARMAR,\$E$/);

  const sentCommand = await waitUntil(async () => {
    const response = await fetch(
      `http://127.0.0.1:${httpPort}/api/app/device/commands/${queuedCommand.command.id}`,
      { headers: { Authorization: `Bearer ${directPhone.accessToken}` } }
    );
    const body = await response.json();
    return body.command?.status === 'AWAITING_RESULT' ? body.command : null;
  });
  assert.equal(sentCommand.action, 'ARMAR');

  const resultAckPromise = receiveUdp(deviceSocket);
  await sendUdp(deviceSocket, ARM_CONFIRMED_PACKET, udpPort);
  assert.match(await resultAckPromise, /ACK=11,\$E$/);
  deviceSocket.close();

  const confirmedCommand = await waitUntil(async () => {
    const response = await fetch(
      `http://127.0.0.1:${httpPort}/api/app/device/commands/${queuedCommand.command.id}`,
      { headers: { Authorization: `Bearer ${directPhone.accessToken}` } }
    );
    const body = await response.json();
    return body.command?.status === 'CONFIRMED' ? body.command : null;
  });
  assert.equal(confirmedCommand.action, 'ARMAR');
  assert.equal(confirmedCommand.panelStatus, 'ARMADO');
  assert.equal(confirmedCommand.resultCode, '45');
  assert.equal(confirmedCommand.alreadyInState, false);
  assert.equal(confirmedCommand.actionSource, 'APP');
  assert.equal(confirmedCommand.actorName, 'Alta directa');

  const deviceStatusResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/device/status`, {
    headers: { Authorization: `Bearer ${directPhone.accessToken}` }
  });
  const deviceStatus = await deviceStatusResponse.json();
  assert.equal(deviceStatus.gprs.online, true);
  assert.equal(deviceStatus.latestCommand.status, 'CONFIRMED');
  assert.equal(deviceStatus.panelState.panelStatus, 'ARMADO');
  assert.equal(deviceStatus.panelState.resultCode, '45');
  assert.equal(deviceStatus.panelState.actorName, 'Alta directa');

  const gprsResponse = await fetch(`http://127.0.0.1:${httpPort}/api/gprs/endpoints`);
  const gprs = await gprsResponse.json();
  assert.equal(gprsResponse.status, 200);
  assert.equal(gprs.heartbeatTotal, 1);
  assert.equal(gprs.endpoints[0].imei, ADMIN_IMEI);
  assert.equal(gprs.endpoints[0].heartbeatCount, 1);
  assert.equal(gprs.endpoints[0].latestCommand.status, 'CONFIRMED');
  assert.equal(gprs.endpoints[0].panelState.panelStatus, 'ARMADO');

  const firstAck = await sendUdpAndReceiveAck(USER_PACKET, udpPort);
  assert.match(firstAck, /^\$B,TM10,TI=\d{2}\/\d{2}\/\d{4}-\d{2}:\d{2},ACK=15,\$E$/);

  const duplicateAck = await sendUdpAndReceiveAck(USER_PACKET, udpPort);
  assert.match(duplicateAck, /ACK=15/);

  const alarmAck = await sendUdpAndReceiveAck(ALARM_PACKET, udpPort);
  assert.match(alarmAck, /ACK=16/);

  const health = await waitUntil(async () => {
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/health`);
    const body = await response.json();
    return body.stats.totalReceived === 5 ? body : null;
  });
  assert.equal(health.stats.validPackets, 5);
  assert.equal(health.stats.eventsStored, 4);
  assert.equal(health.stats.duplicatePackets, 1);
  assert.equal(health.stats.devicesDetected, 2);
  assert.equal(health.stats.appAlertsGenerated, 1);
  assert.equal(health.ack.sent, 5);
  assert.equal(health.gprsControl.heartbeatsReceived, 1);
  assert.equal(health.gprsControl.commandsDelivered, 1);

  const eventsResponse = await fetch(`http://127.0.0.1:${httpPort}/api/devices/${IMEI}/events`);
  const events = await eventsResponse.json();
  assert.equal(events.events.length, 2);
  assert.equal(events.events[1].eventCode, '401');
  assert.equal(events.events[1].subject, '040');
  assert.equal(events.events[1].ackSent, true);

  const alertsResponse = await fetch(`http://127.0.0.1:${httpPort}/api/devices/${IMEI}/alerts`);
  const alerts = await alertsResponse.json();
  assert.equal(alerts.alerts.length, 1);
  assert.equal(alerts.alerts[0].eventCode, '130');
  assert.equal(alerts.alerts[0].subject, '015');

  const provisionResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/admin/devices/${IMEI}/access-key`,
    { method: 'POST' }
  );
  const provisioned = await provisionResponse.json();
  assert.equal(provisionResponse.status, 201);
  assert.match(provisioned.accessKey, /^NS-(?:[A-Z0-9]{4}-){3}[A-Z0-9]{4}$/);

  async function registerPhone(name) {
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/app/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imei: IMEI, accessKey: provisioned.accessKey, name })
    });
    assert.equal(response.status, 201);
    return response.json();
  }

  const phoneA = await registerPhone('Celular A');
  const phoneB = await registerPhone('Celular B');
  assert.notEqual(phoneA.installationId, phoneB.installationId);

  const pushTokenResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/push-token`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${phoneA.accessToken}`
    },
    body: JSON.stringify({ pushToken: 'fcm-token-celular-a' })
  });
  const pushInstallation = await pushTokenResponse.json();
  assert.equal(pushTokenResponse.status, 200);
  assert.equal(pushInstallation.hasPushToken, true);

  for (const phone of [phoneA, phoneB]) {
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/app/alerts`, {
      headers: { Authorization: `Bearer ${phone.accessToken}` }
    });
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(body.imei, IMEI);
    assert.equal(body.alerts.length, 1);
    assert.equal(body.alerts[0].eventCode, '130');
    assert.equal(body.alerts[0].raw, undefined);

    const incrementalResponse = await fetch(
      `http://127.0.0.1:${httpPort}/api/app/alerts?afterId=${body.alerts[0].id}`,
      { headers: { Authorization: `Bearer ${phone.accessToken}` } }
    );
    const incrementalBody = await incrementalResponse.json();
    assert.equal(incrementalResponse.status, 200);
    assert.equal(incrementalBody.alerts.length, 0);
  }

  const renameResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/me/name`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${phoneA.accessToken}`
    },
    body: JSON.stringify({ name: 'Efraín' })
  });
  assert.equal(renameResponse.status, 200);
  assert.equal((await renameResponse.json()).name, 'Efraín');

  const emergencyResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/emergency`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${phoneA.accessToken}`
    },
    body: JSON.stringify({
      type: 'PANICO',
      name: 'Efraín',
      abonado: '6211',
      latitude: -34.6037,
      longitude: -58.3816,
      locationAccuracyMeters: 22.4,
      locationCapturedAt: '2026-08-13T11:30:00.000Z'
    })
  });
  const emergency = await emergencyResponse.json();
  assert.equal(emergencyResponse.status, 201);
  assert.equal(emergency.alert.eventCode, '120');
  assert.equal(emergency.alert.actorName, 'Efraín');
  assert.equal(emergency.alert.actionSource, 'APP');
  assert.equal(emergency.alert.latitude, -34.6037);
  assert.equal(emergency.alert.longitude, -58.3816);
  assert.equal(emergency.alert.locationAccuracyMeters, 22.4);
  assert.match(emergency.alert.mapsUrl, /google\.com\/maps/);

  const alertsWithActor = await waitUntil(async () => {
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/app/alerts`, {
      headers: { Authorization: `Bearer ${phoneB.accessToken}` }
    });
    const body = await response.json();
    return body.alerts.length === 2 ? body.alerts : null;
  });
  assert.equal(alertsWithActor[0].actorName, 'Efraín');
  assert.equal(alertsWithActor[0].latitude, -34.6037);
  assert.equal(alertsWithActor[0].longitude, -58.3816);

  const secondAck = await sendUdpAndReceiveAck(SECOND_ALARM_PACKET, udpPort);
  assert.match(secondAck, /\$B,TM20,.*ACK=17,\$E/);
  const secondProvisionResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/admin/devices/${SECOND_IMEI}/access-key`,
    { method: 'POST' }
  );
  const secondProvisioned = await secondProvisionResponse.json();
  const secondPhoneResponse = await fetch(`http://127.0.0.1:${httpPort}/api/app/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      imei: SECOND_IMEI,
      accessKey: secondProvisioned.accessKey,
      name: 'Celular de otro equipo'
    })
  });
  const secondPhone = await secondPhoneResponse.json();
  const secondPhoneAlerts = await waitUntil(async () => {
    const response = await fetch(`http://127.0.0.1:${httpPort}/api/app/alerts`, {
      headers: { Authorization: `Bearer ${secondPhone.accessToken}` }
    });
    const body = await response.json();
    return body.alerts.length === 1 ? body : null;
  });
  assert.equal(secondPhoneAlerts.alerts.length, 1);
  assert.equal(secondPhoneAlerts.alerts[0].imei, SECOND_IMEI);
  assert.equal(secondPhoneAlerts.alerts[0].eventCode, '120');

  const firstPhoneAfterSecondDevice = await fetch(`http://127.0.0.1:${httpPort}/api/app/alerts`, {
    headers: { Authorization: `Bearer ${phoneA.accessToken}` }
  });
  const firstPhoneAlerts = await firstPhoneAfterSecondDevice.json();
  assert.equal(firstPhoneAlerts.alerts.length, 2);
  assert.equal(firstPhoneAlerts.alerts[0].imei, IMEI);

  const installationsResponse = await fetch(
    `http://127.0.0.1:${httpPort}/api/admin/devices/${IMEI}/installations`
  );
  const installations = await installationsResponse.json();
  assert.equal(installations.installations.filter((item) => item.enabled).length, 2);

  const badRegistration = await fetch(`http://127.0.0.1:${httpPort}/api/app/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imei: IMEI, accessKey: 'CLAVE-INCORRECTA', name: 'Intruso' })
  });
  assert.equal(badRegistration.status, 401);
  assert.match(output, /UDP DUPLICADO/);
  assert.match(output, /ALERTA APP/);
});
