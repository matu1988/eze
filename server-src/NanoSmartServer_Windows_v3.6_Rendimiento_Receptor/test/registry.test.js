'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DeviceRegistry, RegistryError } = require('../src/registry');

test('vincula varios celulares a un IMEI y aísla otros equipos', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-registry-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const firstDevice = registry.provisionDevice('000001278272947');
  const secondDevice = registry.provisionDevice('869671077527800');

  const phoneA = registry.registerInstallation({
    imei: firstDevice.imei,
    accessKey: firstDevice.accessKey,
    name: 'Celular A'
  });
  const phoneB = registry.registerInstallation({
    imei: firstDevice.imei,
    accessKey: firstDevice.accessKey,
    name: 'Celular B'
  });

  assert.notEqual(phoneA.installationId, phoneB.installationId);
  assert.equal(registry.authenticate(phoneA.accessToken).imei, firstDevice.imei);
  assert.equal(registry.authenticate(phoneB.accessToken).imei, firstDevice.imei);
  assert.equal(registry.listInstallations(firstDevice.imei).length, 2);

  const renamed = registry.updateInstallationName(phoneA.installationId, 'Efraín');
  assert.equal(renamed.name, 'Efraín');
  assert.equal(registry.authenticate(phoneA.accessToken).name, 'Efraín');
  assert.throws(
    () => registry.updateInstallationName(phoneA.installationId, '   '),
    (error) => error instanceof RegistryError && error.code === 'INVALID_INSTALLATION_NAME'
  );

  registry.updatePushToken(phoneB.installationId, 'fcm-token-celular-b');
  assert.equal(registry.countPushTokens(), 1);
  assert.deepEqual(registry.listPushTargets(firstDevice.imei), [{
    installationId: phoneB.installationId,
    pushToken: 'fcm-token-celular-b',
    platform: 'ANDROID'
  }]);
  assert.equal(registry.listPushTargets(secondDevice.imei).length, 0);

  registry.updatePushToken(phoneA.installationId, 'fcm-token-celular-b');
  assert.deepEqual(registry.listPushTargets(firstDevice.imei), [{
    installationId: phoneA.installationId,
    pushToken: 'fcm-token-celular-b',
    platform: 'ANDROID'
  }]);

  assert.equal(registry.clearPushToken(phoneA.installationId, 'token-que-no-coincide'), false);
  assert.equal(registry.countPushTokens(), 1);
  assert.equal(registry.clearPushToken(phoneA.installationId, 'fcm-token-celular-b'), true);
  assert.equal(registry.countPushTokens(), 0);

  assert.throws(
    () => registry.registerInstallation({ imei: secondDevice.imei, accessKey: firstDevice.accessKey }),
    (error) => error instanceof RegistryError && error.code === 'INVALID_ACCESS_KEY'
  );

  registry.revokeInstallation(phoneA.installationId);
  assert.throws(
    () => registry.authenticate(phoneA.accessToken),
    (error) => error instanceof RegistryError && error.code === 'INVALID_TOKEN'
  );
  assert.equal(registry.authenticate(phoneB.accessToken).imei, firstDevice.imei);

  const persisted = fs.readFileSync(filePath, 'utf8');
  assert.equal(persisted.includes(firstDevice.accessKey), false);
  assert.equal(persisted.includes(phoneB.accessToken), false);
});

test('un mismo celular recibe push de varios paneles sin perder registros', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-multipanel-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const home = registry.provisionDevice('000001278272947');
  const office = registry.provisionDevice('869671077527800');
  const homePhone = registry.registerInstallation({
    imei: home.imei,
    accessKey: home.accessKey,
    name: 'Efraín'
  });
  const officePhone = registry.registerInstallation({
    imei: office.imei,
    accessKey: office.accessKey,
    name: 'Efraín'
  });

  registry.updatePushToken(homePhone.installationId, 'mismo-fcm-del-celular');
  registry.updatePushToken(officePhone.installationId, 'mismo-fcm-del-celular');

  assert.equal(registry.countPushTokens(), 2);
  assert.equal(registry.listPushTargets(home.imei)[0].pushToken, 'mismo-fcm-del-celular');
  assert.equal(registry.listPushTargets(office.imei)[0].pushToken, 'mismo-fcm-del-celular');
});

test('el administrador obtiene el token del celular en un solo paso', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-admin-token-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const phone = registry.registerInstallationFromAdmin({
    imei: '869671077527867',
    name: 'Celular real'
  });

  assert.equal(phone.imei, '869671077527867');
  assert.equal(phone.name, 'Celular real');
  assert.equal(phone.deviceProvisioned, true);
  assert.equal(typeof phone.accessToken, 'string');
  assert.ok(phone.accessToken.length >= 32);
  assert.equal(phone.accessKey, undefined);
  assert.equal(registry.authenticate(phone.accessToken).installationId, phone.installationId);
  assert.equal(registry.listProvisionedDevices()[0].installations, 1);

  const secondPhone = registry.registerInstallationFromAdmin({
    imei: '869671077527867',
    name: 'Segundo celular'
  });
  assert.equal(secondPhone.deviceProvisioned, false);
  assert.notEqual(secondPhone.accessToken, phone.accessToken);
  assert.equal(registry.listProvisionedDevices()[0].installations, 2);
});

test('registra iPhone y conserva la plataforma junto con su token FCM', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-registry-ios-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const phone = registry.registerInstallationFromAdmin({
    imei: '869671077527867',
    name: 'iPhone Laura',
    platform: 'IOS'
  });

  assert.equal(phone.platform, 'IOS');
  const installation = registry.authenticate(phone.accessToken);
  registry.updatePushToken(installation.installationId, 'fcm-ios-laura', 'IOS');
  assert.deepEqual(registry.listPushTargets(phone.imei), [{
    installationId: phone.installationId,
    pushToken: 'fcm-ios-laura',
    platform: 'IOS'
  }]);
  assert.equal(registry.listInstallations(phone.imei)[0].platform, 'IOS');
});

test('comparte los nombres de zonas entre todos los celulares del mismo IMEI', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-zones-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const device = registry.provisionDevice('869671077527867');
  const phoneA = registry.registerInstallation({
    imei: device.imei,
    accessKey: device.accessKey,
    name: 'Celular A'
  });
  const phoneB = registry.registerInstallation({
    imei: device.imei,
    accessKey: device.accessKey,
    name: 'Celular B'
  });

  const imeiA = registry.authenticate(phoneA.accessToken).imei;
  registry.updateZoneNames(imeiA, {
    1: 'Puerta principal',
    2: '  Dormitorio   principal  ',
    16: 'Patio',
    17: 'No se debe guardar'
  });

  const imeiB = registry.authenticate(phoneB.accessToken).imei;
  const zones = registry.getZoneNames(imeiB);
  assert.equal(zones['1'], 'Puerta principal');
  assert.equal(zones['2'], 'Dormitorio principal');
  assert.equal(zones['3'], '');
  assert.equal(zones['16'], 'Patio');
  assert.equal(zones['17'], undefined);
  assert.equal(registry.getZoneName(device.imei, '002'), 'Dormitorio principal');

  registry.updateZoneNames(device.imei, { 1: '' });
  assert.equal(registry.getZoneName(device.imei, 1), null);
});

test('producción genera dos QR que representan dos cupos móviles independientes', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-production-slots-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  const generated = registry.provisionMobilePairings(
    '869671077527867',
    ['Mobile 1', 'Mobile 2']
  );

  assert.equal(generated.pairings.length, 2);
  assert.notEqual(generated.pairings[0].accessKey, generated.pairings[1].accessKey);
  assert.throws(
    () => registry.provisionMobilePairings(generated.imei),
    (error) => error instanceof RegistryError && error.code === 'PRODUCTION_PAIRINGS_EXIST'
  );
  const phoneA = registry.registerInstallation({
    imei: generated.imei,
    accessKey: generated.pairings[0].accessKey,
    name: 'Laura'
  });
  const phoneB = registry.registerInstallation({
    imei: generated.imei,
    accessKey: generated.pairings[1].accessKey,
    name: 'Efraín'
  });

  assert.notEqual(phoneA.installationId, phoneB.installationId);
  assert.notEqual(phoneA.accessToken, phoneB.accessToken);
  assert.equal(registry.authenticate(phoneA.accessToken).name, 'Laura');
  assert.equal(registry.authenticate(phoneB.accessToken).name, 'Efraín');

  const replacementA = registry.registerInstallation({
    imei: generated.imei,
    accessKey: generated.pairings[0].accessKey,
    name: 'Nuevo Mobile 1'
  });
  assert.equal(replacementA.installationId, phoneA.installationId);
  assert.throws(
    () => registry.authenticate(phoneA.accessToken),
    (error) => error instanceof RegistryError && error.code === 'INVALID_TOKEN'
  );
  assert.equal(registry.authenticate(phoneB.accessToken).name, 'Efraín');

  const persisted = fs.readFileSync(filePath, 'utf8');
  assert.equal(persisted.includes(generated.pairings[0].accessKey), false);
  assert.equal(persisted.includes(generated.pairings[1].accessKey), false);
  assert.equal(registry.listProvisionedDevices()[0].productionPairings, 2);
});

test('asocia clientes y filtra robos sin afectar los demas eventos', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-client-filter-'));
  const filePath = path.join(directory, 'registry.json');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const registry = new DeviceRegistry(filePath);
  registry.initialize();
  assert.equal(registry.listClients()[0].id, 'automonitoreo');
  assert.equal(registry.listClients()[0].forwardRobberyToApp, true);

  registry.importClients([
    { externalId: '10', name: 'Empresa Uno', account: '1001' },
    { externalId: '20', name: 'Empresa Dos', account: '1002' }
  ]);
  registry.setClientRobberyForwarding('reparaciones:10', false);

  // Al volver a importar, se actualizan los datos pero no se pierde la decision.
  registry.importClients([{ externalId: '10', name: 'Empresa Uno Actualizada' }]);
  const company = registry.listClients().find((item) => item.id === 'reparaciones:10');
  assert.equal(company.name, 'Empresa Uno Actualizada');
  assert.equal(company.forwardRobberyToApp, false);

  const generated = registry.provisionMobilePairings(
    '869671077527867',
    ['Mobile 1', 'Mobile 2'],
    { clientId: company.id }
  );
  assert.equal(generated.client.id, company.id);
  assert.equal(registry.shouldDeliverEventToApp(generated.imei, '130'), false);
  assert.equal(registry.shouldDeliverEventToApp(generated.imei, '139'), false);
  assert.equal(registry.shouldDeliverEventToApp(generated.imei, '100'), true);

  assert.throws(
    () => registry.setClientRobberyForwarding('automonitoreo', false),
    (error) => error instanceof RegistryError && error.code === 'AUTOMONITORING_ALWAYS_ENABLED'
  );
});
