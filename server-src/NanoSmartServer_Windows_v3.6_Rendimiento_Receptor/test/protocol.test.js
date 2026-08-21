'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  buildAck,
  parseContactId,
  parseHeartbeat,
  parsePacket,
  shouldForwardContactEvent
} = require('../src/protocol');

const USER_PACKET = '$B,TM10,15,25/11/2022-15:27,01,621118340100040B,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,000001278272947,133,0,186.191.130.232,0,999,$E';

const PANEL_RESULT_PACKETS = [
  ['$B,TM67,20,06/08/2026-13:46,43,0000000000000000,18,0,0,1234,10,MA_1.95GE-AR,1,0,0,0,133,1,0,869671077527867,0,133,10.162.200.38,39,00,10,4G,$E', 'ARMAR', 'ARMADO', 'ALREADY_IN_STATE', true],
  ['$B,TM67,6,06/08/2026-13:54,44,0000000000000000,19,0,0,1234,10,MA_1.95GE-AR,0,0,0,0,122,1,0,869671077527867,0,122,10.49.195.175,11,00,10,4G,$E', 'DESARMAR', 'DESARMADO', 'ALREADY_IN_STATE', true],
  ['$B,TM67,11,06/08/2026-13:58,45,0000000000000000,19,0,0,1234,10,MA_1.95GE-AR,1,0,0,0,133,1,0,869671077527867,0,133,10.185.134.48,21,00,10,4G,$E', 'ARMAR', 'ARMADO', 'EXECUTED', false],
  ['$B,TM67,14,06/08/2026-13:59,46,0000000000000000,18,0,0,1234,10,MA_1.95GE-AR,0,0,0,0,133,1,0,869671077527867,0,133,10.185.134.48,27,00,10,4G,$E', 'DESARMAR', 'DESARMADO', 'EXECUTED', false]
];

test('interpreta la estructura Contact-ID suministrada', () => {
  const contact = parseContactId('621118340100040B');
  assert.equal(contact.valid, true);
  assert.equal(contact.account, '6211');
  assert.equal(contact.format, '18');
  assert.equal(contact.qualifier, '3');
  assert.equal(contact.eventCode, '401');
  assert.equal(contact.eventDescription, 'Apertura/cierre por usuario');
  assert.equal(contact.partition, '00');
  assert.equal(contact.subject, '040');
  assert.equal(contact.subjectKind, 'USUARIO');
  assert.equal(contact.checksum, 'B');
});

test('interpreta el paquete exterior e identifica el IMEI', () => {
  const packet = parsePacket(USER_PACKET);
  assert.equal(packet.valid, true);
  assert.equal(packet.transmitterId, 'TM10');
  assert.equal(packet.sequence, '15');
  assert.equal(packet.packetType, '01');
  assert.equal(packet.abonado, '6211');
  assert.equal(packet.imei, '000001278272947');
  assert.equal(packet.eventCode, '401');
  assert.equal(packet.shouldForwardToApp, false);
});

test('genera exactamente el ACK del protocolo', () => {
  const packet = parsePacket(USER_PACKET);
  const ack = buildAck(packet, {
    now: new Date('2026-07-17T12:30:00.000Z'),
    timeZone: 'America/Argentina/Buenos_Aires'
  });
  assert.equal(ack, '$B,TM10,TI=17/07/2026-09:30,ACK=15,$E');
});

test('reconoce el heartbeat compuesto solamente por el IMEI y no confunde otros paquetes', () => {
  assert.deepEqual(parseHeartbeat(' 000001278272947\r\n'), {
    imei: '000001278272947',
    raw: '000001278272947'
  });
  assert.equal(parseHeartbeat(USER_PACKET), null);
  assert.equal(parseHeartbeat('1234'), null);
});

test('agrega ARMAR o DESARMAR al ACK usado como orden GPRS', () => {
  const packet = parsePacket(USER_PACKET);
  const ack = buildAck(packet, {
    now: new Date('2026-08-04T12:28:00.000Z'),
    timeZone: 'America/Argentina/Buenos_Aires',
    action: 'ARMAR'
  });
  assert.equal(ack, '$B,TM10,TI=04/08/2026-09:28,ACK=15,ARMAR,$E');
});

test('interpreta los eventos 43 a 46 como resultados del panel GPRS', () => {
  for (const [raw, action, panelStatus, result, alreadyInState] of PANEL_RESULT_PACKETS) {
    const packet = parsePacket(raw);
    assert.equal(packet.valid, true);
    assert.equal(packet.imei, '869671077527867');
    assert.equal(packet.eventCode, packet.packetType);
    assert.equal(packet.gprsPanelResult.action, action);
    assert.equal(packet.gprsPanelResult.panelStatus, panelStatus);
    assert.equal(packet.gprsPanelResult.result, result);
    assert.equal(packet.gprsPanelResult.alreadyInState, alreadyInState);
  }
});

test('selecciona sólo los disparos configurados para la app', () => {
  const alarmPacket = '$B,TM10,16,17/07/2026-09:35,01,1234181130010158,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,869671077527800,133,0,10.0.0.1,0,999,$E';
  const packet = parsePacket(alarmPacket, {
    appForwarding: { enabled: true, qualifiers: ['1'], eventCodes: ['130'] }
  });
  assert.equal(packet.eventCode, '130');
  assert.equal(packet.contactId.subject, '015');
  assert.equal(packet.shouldForwardToApp, true);
});

test('reenvía todos los robos Contact ID 130 a 139 y excluye sus restauraciones', () => {
  const definitions = {
    '130': 'Robo',
    '131': 'Perimetral',
    '132': 'Interior',
    '133': '24 horas',
    '134': 'Entrada/Salida',
    '135': 'Día/Noche',
    '136': 'Exterior',
    '137': 'Tamper (sabotaje)',
    '138': 'Alarma de proximidad',
    '139': 'Intrusión verificada'
  };
  const legacyAppForwarding = {
    enabled: true,
    qualifiers: ['1'],
    eventCodes: ['100', '110', '120', '130']
  };

  for (const [eventCode, description] of Object.entries(definitions)) {
    const contact = parseContactId(`1234181${eventCode}01015A`);
    assert.equal(contact.valid, true);
    assert.equal(contact.eventCode, eventCode);
    assert.equal(contact.eventDescription, description);
    assert.equal(shouldForwardContactEvent(contact, { appForwarding: legacyAppForwarding }), true);
    assert.equal(
      shouldForwardContactEvent(
        { ...contact, qualifier: '3' },
        { appForwarding: legacyAppForwarding }
      ),
      false
    );
  }
});

test('marca un Contact-ID inválido en un paquete tipo 01', () => {
  const packet = parsePacket('$B,TM10,1,fecha,01,NO-ES-CONTACT-ID,$E');
  assert.equal(packet.valid, false);
  assert.ok(packet.errors.some((error) => error.includes('Contact-ID inválido')));
});
