'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { CommandError, DeviceCommandService } = require('../src/commands');

const IMEI = '000001278272947';

test('mantiene la orden activa hasta recibir la confirmación real del panel', () => {
  let now = Date.parse('2026-08-06T12:00:00.000Z');
  const service = new DeviceCommandService({
    now: () => now,
    commandTtlSeconds: 180,
    endpointMaxAgeSeconds: 90,
    inquiryRetrySeconds: 45
  });

  const queued = service.createCommand(IMEI, 'armar', 'telefono-1', 'Efraín');
  assert.equal(queued.status, 'PENDING');
  assert.equal(service.getEndpoint(IMEI), null);

  service.recordEndpoint(IMEI, { address: '186.1.2.3', port: 45678 }, { source: 'heartbeat', now });
  assert.equal(service.getEndpoint(IMEI).fresh, true);
  assert.equal(service.listEndpoints()[0].heartbeatCount, 1);
  assert.equal(service.listEndpoints()[0].online, true);
  assert.equal(service.inquiryCandidate(IMEI).id, queued.id);

  service.markInquiryStarted(queued.id, service.getEndpoint(IMEI), { now });
  assert.equal(service.getCommand(queued.id).status, 'DELIVERING');
  assert.equal(service.awaitingResponse(IMEI).action, 'ARMAR');

  now += 1000;
  const sent = service.markActionSent(queued.id, {
    now,
    transmitterId: '2010',
    sequence: '04'
  });
  assert.equal(sent.status, 'AWAITING_RESULT');
  assert.equal(sent.action, 'ARMAR');
  assert.equal(service.awaitingResponse(IMEI), null);

  now += 1000;
  const confirmation = service.recordPanelResult(IMEI, {
    code: '45',
    action: 'ARMAR',
    panelStatus: 'ARMADO',
    result: 'EXECUTED',
    alreadyInState: false,
    description: 'Panel armado correctamente'
  }, { now, transmitterId: 'TM67', sequence: '11' });
  assert.equal(confirmation.matched, true);
  assert.equal(confirmation.command.status, 'CONFIRMED');
  assert.equal(confirmation.command.panelStatus, 'ARMADO');
  assert.equal(confirmation.command.resultCode, '45');
  assert.equal(confirmation.command.actionSource, 'APP');
  assert.equal(confirmation.command.actorName, 'Efraín');
  assert.equal(service.getPanelState(IMEI).actorName, 'Efraín');
  assert.equal(service.getPanelState(IMEI).panelStatus, 'ARMADO');
  assert.equal(service.getStats().commandsSent, 1);
  assert.equal(service.getStats().commandsConfirmed, 1);
  assert.equal(service.getStats().commandsDelivered, 1);
});

test('distingue cuando el panel ya se encontraba en el estado solicitado', () => {
  let now = 3_000_000;
  const service = new DeviceCommandService({ now: () => now });
  const command = service.createCommand(IMEI, 'DESARMAR');
  service.markInquiryStarted(command.id, { address: '10.0.0.1', port: 4000 }, { now });
  service.markActionSent(command.id, { now, transmitterId: 'TM67', sequence: '6' });
  now += 1000;
  const confirmation = service.recordPanelResult(IMEI, {
    code: '44',
    action: 'DESARMAR',
    panelStatus: 'DESARMADO',
    result: 'ALREADY_IN_STATE',
    alreadyInState: true,
    description: 'El panel ya estaba desarmado'
  }, { now });
  assert.equal(confirmation.command.status, 'CONFIRMED');
  assert.equal(confirmation.command.alreadyInState, true);
  assert.equal(confirmation.command.resultDescription, 'El panel ya estaba desarmado');
  assert.equal(service.getStats().commandsAlreadyInState, 1);
});

test('actualiza el estado aunque la acción provenga del teclado del panel', () => {
  const service = new DeviceCommandService({ now: () => 4_000_000 });
  const confirmation = service.recordPanelResult(IMEI, {
    code: '46',
    action: 'DESARMAR',
    panelStatus: 'DESARMADO',
    result: 'EXECUTED',
    alreadyInState: false,
    description: 'Panel desarmado correctamente'
  }, { transmitterId: 'TM67', sequence: '14' });

  assert.equal(confirmation.matched, false);
  assert.equal(confirmation.command, null);
  assert.equal(confirmation.panelState.panelStatus, 'DESARMADO');
  assert.equal(confirmation.panelState.actionSource, 'PANEL');
  assert.equal(confirmation.panelState.actorName, 'Teclado del panel');
  assert.equal(service.getPanelState(IMEI).resultCode, '46');
});

test('reintenta DI01 con el próximo heartbeat si el equipo no respondió', () => {
  let now = 1_000_000;
  const service = new DeviceCommandService({
    now: () => now,
    inquiryRetrySeconds: 45,
    commandTtlSeconds: 180
  });
  service.recordEndpoint(IMEI, { address: '10.0.0.1', port: 4000 }, { source: 'heartbeat', now });
  const command = service.createCommand(IMEI, 'DESARMAR');
  service.markInquiryStarted(command.id, service.getEndpoint(IMEI), { now });
  assert.equal(service.inquiryCandidate(IMEI), null);

  now += 5_000;
  service.recordEndpoint(IMEI, { address: '10.0.0.2', port: 5000 }, { source: 'heartbeat', now });
  assert.equal(service.inquiryCandidate(IMEI, { force: true }).id, command.id);

  now += 55_000;
  service.recordEndpoint(IMEI, { address: '10.0.0.2', port: 5000 }, { source: 'heartbeat', now });
  const retry = service.inquiryCandidate(IMEI);
  assert.equal(retry.id, command.id);
  assert.equal(service.getEndpoint(IMEI).address, '10.0.0.2');
});

test('vence la orden y bloquea acciones contradictorias simultáneas', () => {
  let now = 2_000_000;
  const service = new DeviceCommandService({ now: () => now, commandTtlSeconds: 120 });
  const command = service.createCommand(IMEI, 'ARMAR');
  assert.throws(
    () => service.createCommand(IMEI, 'DESARMAR'),
    (error) => error instanceof CommandError && error.code === 'COMMAND_IN_PROGRESS'
  );

  now += 121_000;
  assert.equal(service.getCommand(command.id).status, 'EXPIRED');
  assert.match(service.getCommand(command.id).error, /no respondió/);
});
