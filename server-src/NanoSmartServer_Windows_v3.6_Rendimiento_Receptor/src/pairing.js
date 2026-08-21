'use strict';

const QRCode = require('qrcode');

function createPairingPayload(imei, accessKey) {
  return `NS1|${String(imei || '').trim()}|${String(accessKey || '').trim().toUpperCase()}`;
}

async function createPairingLabel(provisioned) {
  const qrPayload = createPairingPayload(provisioned.imei, provisioned.accessKey);
  const qrSvg = await QRCode.toString(qrPayload, {
    type: 'svg',
    errorCorrectionLevel: 'M',
    margin: 4,
    width: 256,
    color: { dark: '#000000', light: '#FFFFFF' }
  });
  return {
    ...provisioned,
    qrPayload,
    qrSvg,
    labelWidthMm: 50,
    labelHeightMm: 25
  };
}

async function createProductionPairingLabels(provisioned) {
  const pairings = await Promise.all(provisioned.pairings.map(async (pairing) => {
    const qrPayload = createPairingPayload(pairing.imei, pairing.accessKey);
    const qrPngDataUrl = await QRCode.toDataURL(qrPayload, {
      errorCorrectionLevel: 'M',
      margin: 3,
      width: 512,
      color: { dark: '#000000', light: '#FFFFFF' }
    });
    return {
      slotId: pairing.slotId,
      slotNumber: pairing.slotNumber,
      label: pairing.label,
      imei: pairing.imei,
      installationId: pairing.installationId,
      qrPayload,
      qrPngBase64: qrPngDataUrl.split(',', 2)[1],
      labelWidthMm: 50,
      labelHeightMm: 25
    };
  }));
  return {
    imei: provisioned.imei,
    client: provisioned.client,
    generatedAt: provisioned.generatedAt,
    reissued: provisioned.reissued === true,
    pairings
  };
}

module.exports = { createPairingLabel, createPairingPayload, createProductionPairingLabels };
