'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  createPairingLabel,
  createPairingPayload,
  createProductionPairingLabels
} = require('../src/pairing');
const { DeviceRegistry } = require('../src/registry');

test('genera el contenido compacto que interpreta la app', () => {
  assert.equal(
    createPairingPayload('869671077527867', 'ns-abcd-efgh-jklm-npqr'),
    'NS1|869671077527867|NS-ABCD-EFGH-JKLM-NPQR'
  );
});

test('genera un QR SVG para una etiqueta de 50 por 25 mm', async () => {
  const label = await createPairingLabel({
    imei: '869671077527867',
    accessKey: 'NS-ABCD-EFGH-JKLM-NPQR'
  });
  assert.equal(label.labelWidthMm, 50);
  assert.equal(label.labelHeightMm, 25);
  assert.equal(label.qrPayload, 'NS1|869671077527867|NS-ABCD-EFGH-JKLM-NPQR');
  assert.match(label.qrSvg, /^<svg[^>]+>/);
  assert.match(label.qrSvg, /<path/);
});

test('el mismo QR crea un token diferente para cada celular', async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-qr-pairing-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const registry = new DeviceRegistry(path.join(directory, 'registry.json'));
  registry.initialize();

  const provisioned = registry.provisionDevice('869671077527867');
  const label = await createPairingLabel(provisioned);
  const [, imei, accessKey] = label.qrPayload.split('|');
  const phoneA = registry.registerInstallation({ imei, accessKey, name: 'Laura' });
  const phoneB = registry.registerInstallation({ imei, accessKey, name: 'Efraín' });

  assert.notEqual(phoneA.accessToken, phoneB.accessToken);
  assert.equal(registry.authenticate(phoneA.accessToken).name, 'Laura');
  assert.equal(registry.authenticate(phoneB.accessToken).name, 'Efraín');
});

test('genera dos imágenes QR diferentes para producción', async () => {
  const labels = await createProductionPairingLabels({
    imei: '869671077527867',
    generatedAt: '2026-08-13T12:00:00.000Z',
    pairings: [
      {
        slotId: '869671077527867:mobile-1',
        slotNumber: 1,
        label: 'Mobile 1',
        imei: '869671077527867',
        installationId: 'prod:869671077527867:mobile-1',
        accessKey: 'NS-ABCD-EFGH-JKLM-NPQR'
      },
      {
        slotId: '869671077527867:mobile-2',
        slotNumber: 2,
        label: 'Mobile 2',
        imei: '869671077527867',
        installationId: 'prod:869671077527867:mobile-2',
        accessKey: 'NS-2345-6789-ABCD-EFGH'
      }
    ]
  });

  assert.equal(labels.pairings.length, 2);
  assert.notEqual(labels.pairings[0].qrPayload, labels.pairings[1].qrPayload);
  assert.ok(Buffer.from(labels.pairings[0].qrPngBase64, 'base64').length > 100);
  assert.ok(Buffer.from(labels.pairings[1].qrPngBase64, 'base64').length > 100);
});
