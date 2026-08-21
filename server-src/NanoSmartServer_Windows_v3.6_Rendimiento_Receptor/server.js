'use strict';

const crypto = require('node:crypto');
const dgram = require('node:dgram');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { URL } = require('node:url');

const { buildAck, parseHeartbeat, parsePacket } = require('./src/protocol');
const { CommandError, DeviceCommandService } = require('./src/commands');
const { FirebasePushService } = require('./src/fcm');
const { createPairingLabel, createProductionPairingLabels } = require('./src/pairing');
const { DeviceRegistry, RegistryError } = require('./src/registry');
const { EventStore } = require('./src/store');
const { readSupportClients } = require('./src/support-db');

const PROJECT_ROOT = __dirname;
const CONFIG_PATH = path.resolve(process.env.NANOSMART_CONFIG || path.join(PROJECT_ROOT, 'config.json'));
const STARTED_AT = new Date();

const APP_EMERGENCY_EVENTS = Object.freeze({
  MEDICA: Object.freeze({ eventCode: '100', description: 'Médica' }),
  PANICO: Object.freeze({ eventCode: '120', description: 'Pánico' }),
  INCENDIO: Object.freeze({ eventCode: '110', description: 'Incendio' }),
  VIDA: Object.freeze({ eventCode: '640', description: 'Botón Vida' })
});

function loadConfig() {
  const parsed = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  parsed.udp ||= {};
  parsed.http ||= {};
  parsed.storage ||= {};
  parsed.firebase ||= {};
  parsed.production ||= {};
  parsed.protocol ||= {};
  parsed.commands ||= {};

  parsed.udp.host ||= '0.0.0.0';
  parsed.udp.port = Number.parseInt(parsed.udp.port || 7050, 10);
  parsed.udp.maxPacketBytes = Number.parseInt(parsed.udp.maxPacketBytes || 8192, 10);
  parsed.http.host ||= '0.0.0.0';
  parsed.http.port = Number.parseInt(parsed.http.port || 18082, 10);
  parsed.http.corsOrigin ??= '*';
  parsed.http.restrictAdministrationToPrivateNetworks ??= true;
  parsed.http.maxBatchItems = Math.max(
    1,
    Math.min(1000, Number.parseInt(parsed.http.maxBatchItems || 500, 10) || 500)
  );
  parsed.storage.directory = path.resolve(PROJECT_ROOT, parsed.storage.directory || 'data');
  parsed.storage.registryFile = path.resolve(
    PROJECT_ROOT,
    parsed.storage.registryFile || path.join(parsed.storage.directory, 'registry.json')
  );
  parsed.storage.memoryLimit = Number.parseInt(parsed.storage.memoryLimit || 5000, 10);
  parsed.storage.registryFlushMs = Math.max(
    0,
    Math.min(5000, Number.parseInt(parsed.storage.registryFlushMs || 250, 10) || 250)
  );
  parsed.storage.asyncEventPersistence ??= true;
  parsed.production.enabled ??= true;
  parsed.production.host ||= '0.0.0.0';
  parsed.production.port = Number.parseInt(parsed.production.port || 18083, 10);
  parsed.production.authFailureLimit = Number.parseInt(parsed.production.authFailureLimit || 10, 10);
  parsed.production.authFailureWindowSeconds = Number.parseInt(
    parsed.production.authFailureWindowSeconds || 300,
    10
  );
  parsed.production.authBlockSeconds = Number.parseInt(parsed.production.authBlockSeconds || 900, 10);
  parsed.production.keyFile = path.resolve(
    PROJECT_ROOT,
    parsed.production.keyFile || path.join(parsed.storage.directory, 'production-key.txt')
  );
  parsed.firebase.enabled ??= false;
  parsed.firebase.projectId ||= '';
  parsed.firebase.serviceAccountFile = path.resolve(
    PROJECT_ROOT,
    parsed.firebase.serviceAccountFile || path.join('secrets', 'firebase-service-account.json')
  );
  parsed.firebase.timeoutMs = Number.parseInt(parsed.firebase.timeoutMs || 10000, 10);
  parsed.firebase.maxConcurrentSends = Math.max(
    1,
    Math.min(100, Number.parseInt(parsed.firebase.maxConcurrentSends || 20, 10) || 20)
  );
  parsed.protocol.startMarker ||= '$B';
  parsed.protocol.endMarker ||= '$E';
  parsed.protocol.duplicateWindowSeconds = Number.parseInt(parsed.protocol.duplicateWindowSeconds || 300, 10);
  parsed.protocol.packetTypeDescriptions ||= {};
  parsed.protocol.contactIdEventDescriptions ||= {};
  parsed.protocol.ack ||= {};
  parsed.protocol.ack.enabled ??= true;
  parsed.protocol.ack.timeZone ||= 'America/Argentina/Buenos_Aires';
  parsed.protocol.ack.lineEnding ||= '';
  parsed.protocol.appForwarding ||= {};
  parsed.protocol.appForwarding.enabled ??= true;
  parsed.protocol.appForwarding.qualifiers ||= ['1'];
  const requiredBurglaryEventCodes = [
    '130', '131', '132', '133', '134',
    '135', '136', '137', '138', '139'
  ];
  parsed.protocol.appForwarding.eventCodes = [
    ...new Set([
      ...(parsed.protocol.appForwarding.eventCodes || ['100', '110', '120', '640']),
      ...requiredBurglaryEventCodes,
      '640'
    ].map(String))
  ];
  parsed.commands.enabled ??= true;
  parsed.commands.inquiryPayload ||= '$B,DI01,MIC=0,$E';
  parsed.commands.endpointMaxAgeSeconds = Number.parseInt(parsed.commands.endpointMaxAgeSeconds || 90, 10);
  parsed.commands.inquiryRetrySeconds = Number.parseInt(parsed.commands.inquiryRetrySeconds || 45, 10);
  parsed.commands.commandTtlSeconds = Number.parseInt(parsed.commands.commandTtlSeconds || 180, 10);

  for (const [name, value] of [['udp.port', parsed.udp.port], ['http.port', parsed.http.port]]) {
    if (!Number.isInteger(value) || value < 1 || value > 65535) {
      throw new Error(`El valor ${name} debe ser un puerto entre 1 y 65535`);
    }
  }
  return parsed;
}

const config = loadConfig();

function initializeProductionCredential(options) {
  if (options.enabled !== true) return null;
  const fromEnvironment = String(process.env.NANOSMART_PRODUCTION_KEY || '').trim();
  if (fromEnvironment) return { value: fromEnvironment, keyFile: null, created: false };
  fs.mkdirSync(path.dirname(options.keyFile), { recursive: true });
  if (fs.existsSync(options.keyFile)) {
    const existing = fs.readFileSync(options.keyFile, 'utf8').trim();
    if (existing) return { value: existing, keyFile: options.keyFile, created: false };
  }
  const value = `NSP-${crypto.randomBytes(24).toString('base64url')}`;
  fs.writeFileSync(options.keyFile, `${value}\n`, 'utf8');
  return { value, keyFile: options.keyFile, created: true };
}

const productionCredential = initializeProductionCredential(config.production);
const store = new EventStore({
  directory: config.storage.directory,
  memoryLimit: config.storage.memoryLimit,
  asyncPersistence: config.storage.asyncEventPersistence
});
store.initialize();
const registry = new DeviceRegistry(config.storage.registryFile, {
  persistDebounceMs: config.storage.registryFlushMs
});
registry.initialize();
const pushService = new FirebasePushService(config.firebase);
const commandService = new DeviceCommandService(config.commands);

const dashboardPath = path.join(PROJECT_ROOT, 'public', 'index.html');
const dashboardHtml = fs.readFileSync(dashboardPath);
const adminHtml = fs.readFileSync(path.join(PROJECT_ROOT, 'public', 'admin.html'));
const appSimulatorHtml = fs.readFileSync(path.join(PROJECT_ROOT, 'public', 'app-simulator.html'));
const udpServer = dgram.createSocket('udp4');
const sseClients = new Set();
const recentHashes = new Map();
let lastRecentHashPruneAt = 0;
const runtimeState = {
  udpListening: false,
  udpError: null,
  httpListening: false,
  productionListening: false,
  productionAuthFailures: 0,
  productionBlockedRequests: 0,
  acksSent: 0,
  ackErrors: 0,
  pushAlertsProcessed: 0,
  pushMessagesSent: 0,
  pushErrors: 0,
  invalidPushTokensRemoved: 0,
  commandPushesProcessed: 0,
  panelStatePushesProcessed: 0
};

function normalizedIp(ip) {
  return String(ip || '').replace(/^::ffff:/, '');
}

function isPrivateOrLoopback(ip) {
  const value = normalizedIp(ip).toLowerCase();
  if (value === '::1' || value === 'localhost') return true;
  const parts = value.split('.').map((part) => Number.parseInt(part, 10));
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return value.startsWith('fc') || value.startsWith('fd') || value.startsWith('fe80:');
  }
  return parts[0] === 127 || parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);
}

function isPublicAppPath(pathname) {
  return pathname === '/api/health' || pathname === '/api/app/register' || pathname.startsWith('/api/app/');
}

function secretsEqual(leftValue, rightValue) {
  const left = crypto.createHash('sha256').update(String(leftValue || '')).digest();
  const right = crypto.createHash('sha256').update(String(rightValue || '')).digest();
  return crypto.timingSafeEqual(left, right);
}

const productionAuthAttempts = new Map();

function productionAuthState(ip, now = Date.now()) {
  const windowMs = config.production.authFailureWindowSeconds * 1000;
  const existing = productionAuthAttempts.get(ip);
  if (!existing || now - existing.windowStartedAt >= windowMs) {
    const state = { windowStartedAt: now, failures: 0, blockedUntil: 0 };
    productionAuthAttempts.set(ip, state);
    return state;
  }
  return existing;
}

function pruneProductionAuthAttempts(now = Date.now()) {
  const staleAfter = Math.max(config.production.authFailureWindowSeconds, config.production.authBlockSeconds) * 2000;
  for (const [ip, state] of productionAuthAttempts) {
    if (state.blockedUntil <= now && now - state.windowStartedAt > staleAfter) productionAuthAttempts.delete(ip);
  }
}

function authenticateProduction(req) {
  if (!productionCredential) throw new RegistryError('El acceso de producción está desactivado', 503, 'PRODUCTION_DISABLED');
  const sourceIp = normalizedIp(req.socket.remoteAddress) || 'desconocida';
  const now = Date.now();
  const state = productionAuthState(sourceIp, now);
  if (state.blockedUntil > now) {
    runtimeState.productionBlockedRequests += 1;
    throw new RegistryError('Acceso temporalmente bloqueado por intentos incorrectos', 429, 'PRODUCTION_AUTH_BLOCKED');
  }
  const supplied = String(req.headers['x-nanosmart-production-key'] || '').trim();
  if (!supplied || !secretsEqual(supplied, productionCredential.value)) {
    state.failures += 1;
    runtimeState.productionAuthFailures += 1;
    if (state.failures >= config.production.authFailureLimit) state.blockedUntil = now + config.production.authBlockSeconds * 1000;
    appendProductionAudit({ at: new Date(now).toISOString(), action: 'PRODUCTION_AUTH_DENIED', sourceIp, blocked: state.blockedUntil > now });
    throw new RegistryError('Clave de producción incorrecta', 401, 'INVALID_PRODUCTION_KEY');
  }
  productionAuthAttempts.delete(sourceIp);
}

function appendProductionAudit(entry) {
  const filePath = path.join(config.storage.directory, 'production-audit.jsonl');
  fs.appendFileSync(filePath, `${JSON.stringify(entry)}\n`, 'utf8');
}

function importSupportDatabase(databaseBuffer, sourceIp) {
  const sqliteHeader = Buffer.from('SQLite format 3\0', 'binary');
  if (!Buffer.isBuffer(databaseBuffer) || databaseBuffer.length < sqliteHeader.length || !databaseBuffer.subarray(0, sqliteHeader.length).equals(sqliteHeader)) {
    throw new RegistryError('El archivo seleccionado no es una base support.db valida', 400, 'INVALID_SUPPORT_DATABASE');
  }
  const temporaryPath = path.join(config.storage.directory, `.support-import-${process.pid}-${crypto.randomUUID()}.db`);
  try {
    fs.writeFileSync(temporaryPath, databaseBuffer, { flag: 'wx' });
    const clients = readSupportClients(temporaryPath);
    const imported = registry.importClients(clients);
    appendProductionAudit({ at: new Date().toISOString(), action: 'ADMIN_IMPORT_SUPPORT_DB', inserted: imported.inserted, updated: imported.updated, total: imported.total, sourceIp });
    return imported;
  } catch (error) {
    if (error instanceof RegistryError) throw error;
    throw new RegistryError('No se pudo leer la tabla clients de support.db', 400, 'INVALID_SUPPORT_DATABASE');
  } finally {
    if (fs.existsSync(temporaryPath)) fs.unlinkSync(temporaryPath);
  }
}

function packetHash(raw) {
  return crypto.createHash('sha256').update(raw).digest('hex');
}

function isDuplicate(raw, now) {
  const hash = packetHash(raw);
  const previousAt = recentHashes.get(hash);
  recentHashes.set(hash, now);
  const windowMs = config.protocol.duplicateWindowSeconds * 1000;
  const pruneEveryMs = Math.min(30000, Math.max(1000, Math.floor(windowMs / 2)));
  if (now - lastRecentHashPruneAt >= pruneEveryMs) {
    lastRecentHashPruneAt = now;
    for (const [key, timestamp] of recentHashes) if (now - timestamp > windowMs) recentHashes.delete(key);
  }
  return { hash, duplicate: Number.isFinite(previousAt) && now - previousAt <= windowMs };
}

for (const event of store.getEvents({ take: 1000 })) {
  const timestamp = Date.parse(event.receivedAt);
  const age = Date.now() - timestamp;
  if (event.packetHash && Number.isFinite(timestamp) && age <= config.protocol.duplicateWindowSeconds * 1000) {
    recentHashes.set(event.packetHash, timestamp);
  }
}

function sendSse(eventName, payload) {
  const message = `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
  for (const client of sseClients) {
    try { client.write(message); } catch { sseClients.delete(client); }
  }
}

async function forwardAlertToPhones(event) {
  if (!registry.shouldDeliverEventToApp(event.imei, event.eventCode)) {
    console.log(`[APP] Evento ${event.eventCode} omitido para el cliente del IMEI ${event.imei}`);
    return;
  }
  const targets = registry.listPushTargets(event.imei);
  const result = await pushService.sendAlert(event, targets);
  runtimeState.pushAlertsProcessed += 1;
  runtimeState.pushMessagesSent += result.sent;
  runtimeState.pushErrors += result.errors;
  runtimeState.invalidPushTokensRemoved += registry.clearPushTokens(result.invalidTargets);
  if (result.sent > 0) console.log(`[FCM] Alerta ${event.id} enviada a ${result.sent}/${result.requested} celular(es) del IMEI ${event.imei}`);
  else if (result.requested === 0) console.log(`[FCM] IMEI ${event.imei} sin celulares con token push registrado`);
  else if (result.skipped > 0) console.warn(`[FCM] Envío omitido: ${pushService.getStatus().error || 'servicio no disponible'}`);
  else console.warn(`[FCM] No se pudo enviar la alerta ${event.id}: ${(result.errorMessages || []).join(' | ')}`);
}

async function forwardCommandToPhones(command) {
  runtimeState.commandPushesProcessed += 1;
  const targets = registry.listPushTargets(command.imei);
  const result = await pushService.sendDeviceCommand(command, targets);
  runtimeState.pushMessagesSent += result.sent;
  runtimeState.pushErrors += result.errors;
  runtimeState.invalidPushTokensRemoved += registry.clearPushTokens(result.invalidTargets);
  if (result.sent > 0) console.log(`[FCM] Estado ${command.action} enviado a ${result.sent}/${result.requested} celular(es)`);
  else if (result.requested > 0 && result.errors > 0) console.warn(`[FCM] No se pudo notificar la orden ${command.id}: ${(result.errorMessages || []).join(' | ')}`);
}

async function forwardPanelStateToPhones(panelState) {
  runtimeState.panelStatePushesProcessed += 1;
  const targets = registry.listPushTargets(panelState.imei);
  const result = await pushService.sendPanelState(panelState, targets);
  runtimeState.pushMessagesSent += result.sent;
  runtimeState.pushErrors += result.errors;
  runtimeState.invalidPushTokensRemoved += registry.clearPushTokens(result.invalidTargets);
  if (result.sent > 0) console.log(`[FCM] Cambio del panel ${panelState.panelStatus} enviado a ${result.sent}/${result.requested} celular(es)`);
  else if (result.requested > 0 && result.errors > 0) console.warn(`[FCM] No se pudo notificar el estado del panel ${panelState.imei}: ${(result.errorMessages || []).join(' | ')}`);
}

function sendUdpPayload(payload, remote) {
  return new Promise((resolve, reject) => {
    udpServer.send(Buffer.from(payload, 'utf8'), remote.port, remote.address, (error) => error ? reject(error) : resolve());
  });
}

async function trySendInquiry(imei, options = {}) {
  if (!config.commands.enabled) return null;
  const now = Number.isFinite(options.now) ? options.now : Date.now();
  const command = commandService.inquiryCandidate(imei, { now, force: options.force === true });
  if (!command) return null;
  const endpoint = commandService.getEndpoint(imei, { now });
  if (!endpoint?.fresh) return command;
  commandService.markInquiryStarted(command.id, endpoint, { now });
  try {
    await sendUdpPayload(config.commands.inquiryPayload, endpoint);
    console.log(`[CONTROL] DI01 enviado al IMEI ${imei} en ${normalizedIp(endpoint.address)}:${endpoint.port} (intento ${command.attempts + 1})`);
    sendSse('device-command', commandService.getCommand(command.id));
  } catch (error) {
    commandService.markInquiryFailed(command.id, error);
    console.warn(`[CONTROL] No se pudo enviar DI01 al IMEI ${imei}: ${error.message}`);
  }
  return commandService.getCommand(command.id);
}

function sendAck(packet, remote, now, action = null) {
  if (!config.protocol.ack.enabled) return Promise.resolve({ sent: false, payload: null, error: null, disabled: true });
  const payload = buildAck(packet, { startMarker: config.protocol.startMarker, endMarker: config.protocol.endMarker, timeZone: config.protocol.ack.timeZone, lineEnding: config.protocol.ack.lineEnding, now: new Date(now), action });
  if (!payload) return Promise.resolve({ sent: false, payload: null, error: 'No se pudo construir el ACK: faltan identificador o secuencia', disabled: false });
  return new Promise((resolve) => {
    udpServer.send(Buffer.from(payload, 'utf8'), remote.port, remote.address, (error) => {
      if (error) { runtimeState.ackErrors += 1; resolve({ sent: false, payload, error: error.message, disabled: false }); return; }
      runtimeState.acksSent += 1;
      resolve({ sent: true, payload, error: null, disabled: false });
    });
  });
}

async function handleDatagram(message, remote) {
  const now = Date.now();
  if (message.length > config.udp.maxPacketBytes) { console.warn(`[UDP] Paquete descartado por tamaño: ${message.length} bytes`); return; }
  const raw = message.toString('utf8');
  const heartbeatPacket = parseHeartbeat(raw);
  if (heartbeatPacket) {
    const endpoint = commandService.recordEndpoint(heartbeatPacket.imei, remote, { now, source: 'heartbeat' });
    console.log(`[HEARTBEAT] IMEI ${heartbeatPacket.imei} | ${normalizedIp(endpoint.address)}:${endpoint.port} | sin ACK`);
    sendSse('device-heartbeat', { imei: heartbeatPacket.imei, receivedAt: endpoint.lastSeenAt, address: normalizedIp(endpoint.address), port: endpoint.port, heartbeatCount: endpoint.heartbeatCount, endpointChanged: endpoint.changed, raw: heartbeatPacket.raw });
    await trySendInquiry(heartbeatPacket.imei, { now, force: true });
    return;
  }
  const parsed = parsePacket(raw, config.protocol);
  if (parsed.imei) commandService.recordEndpoint(parsed.imei, remote, { now, source: 'packet' });
  const duplicateCheck = isDuplicate(parsed.raw, now);
  const commandForAck = parsed.imei ? commandService.awaitingResponse(parsed.imei, { now }) : null;
  const ack = await sendAck(parsed, remote, now, commandForAck?.action || null);
  let commandUpdate = null;
  if (commandForAck) {
    if (ack.sent) {
      commandUpdate = commandService.markActionSent(commandForAck.id, { now, transmitterId: parsed.transmitterId, sequence: parsed.sequence });
      console.log(`[CONTROL] ${commandUpdate.action} enviado al IMEI ${commandUpdate.imei} con ACK=${parsed.sequence}; esperando confirmación 43/44/45/46`);
      sendSse('device-command', commandUpdate);
    } else {
      commandService.markActionSendFailed(commandForAck.id, ack.error);
      console.warn(`[CONTROL] No se pudo entregar ${commandForAck.action} al IMEI ${commandForAck.imei}: ${ack.error || 'ACK no enviado'}`);
    }
  }
  if (!duplicateCheck.duplicate && parsed.imei && parsed.gprsPanelResult) {
    const confirmation = commandService.recordPanelResult(parsed.imei, parsed.gprsPanelResult, { now, eventCode: parsed.eventCode, transmitterId: parsed.transmitterId, sequence: parsed.sequence });
    sendSse('panel-state', confirmation.panelState);
    if (confirmation.matched) {
      commandUpdate = confirmation.command;
      console.log(`[CONTROL] ${commandUpdate.resultDescription} | IMEI ${commandUpdate.imei} | evento ${commandUpdate.resultCode}`);
      sendSse('device-command', commandUpdate);
      void forwardCommandToPhones(commandUpdate).catch((error) => { runtimeState.pushErrors += 1; console.error(`[FCM] Error notificando estado del equipo: ${error.stack || error.message}`); });
    } else {
      console.log(`[ESTADO PANEL] ${confirmation.panelState.resultDescription} | IMEI ${parsed.imei} | evento ${confirmation.panelState.resultCode}`);
      void forwardPanelStateToPhones(confirmation.panelState).catch((error) => { runtimeState.pushErrors += 1; console.error(`[FCM] Error notificando estado del panel: ${error.stack || error.message}`); });
    }
  }
  if (duplicateCheck.duplicate) {
    store.recordDuplicate(parsed.valid);
    const ackText = ack.sent ? `ACK=${parsed.sequence}` : `ACK ERROR=${ack.error || 'no enviado'}`;
    console.log(`[UDP DUPLICADO] ${normalizedIp(remote.address)}:${remote.port} | ${parsed.imei || 'sin IMEI'} | ${ackText} | no almacenado`);
    sendSse('duplicate', { receivedAt: new Date(now).toISOString(), imei: parsed.imei, sequence: parsed.sequence, ackSent: ack.sent, command: commandUpdate });
    return;
  }
  const contact = parsed.contactId;
  const event = store.append({
    receivedAt: new Date(now).toISOString(), sourceIp: normalizedIp(remote.address), sourcePort: remote.port,
    rawBytes: message.length, packetHash: duplicateCheck.hash, isDuplicate: false, valid: parsed.valid,
    parseErrors: parsed.errors, raw: parsed.raw, transmitterId: parsed.transmitterId, protocol: parsed.protocol,
    sequence: parsed.sequence, deviceTimestampRaw: parsed.deviceTimestampRaw, packetType: parsed.packetType,
    packetTypeDescription: parsed.packetTypeDescription, eventCode: parsed.eventCode, eventDescription: parsed.eventDescription,
    contactId: contact.raw, contactIdValid: contact.valid, contactIdErrors: contact.errors,
    contactQualifier: contact.qualifier, contactQualifierDescription: contact.qualifierDescription,
    contactCategory: contact.category, partition: contact.partition, partitionNumber: contact.partitionNumber,
    subject: contact.subject, subjectNumber: contact.subjectNumber, subjectKind: contact.subjectKind,
    zoneName: contact.subjectKind === 'ZONA' ? registry.getZoneName(parsed.imei, contact.subjectNumber) : null,
    checksum: contact.checksum, checksumValidated: contact.checksumValidated, abonado: parsed.abonado,
    imei: parsed.imei, firmware: parsed.firmware, channel: parsed.channel, shouldForwardToApp: parsed.shouldForwardToApp,
    ackSent: ack.sent, ackPayload: ack.payload, ackError: ack.error, fields: parsed.fields
  });
  const validity = event.valid ? 'OK' : 'INVÁLIDO';
  const account = event.abonado || 'sin abonado';
  const ackText = event.ackSent ? `ACK=${event.sequence}` : `ACK ERROR=${event.ackError || 'no enviado'}`;
  const deliverToApp = event.shouldForwardToApp && registry.shouldDeliverEventToApp(event.imei, event.eventCode);
  const forwarding = deliverToApp ? ' | ALERTA APP' : '';
  console.log(`[UDP ${validity}] ${event.sourceIp}:${event.sourcePort} | IMEI ${event.imei || 'desconocido'} | ${account} | ${event.eventDescription} | ${ackText}${forwarding} | ${event.raw}`);
  sendSse('packet', event);
  if (deliverToApp) {
    sendSse('app-alert', event);
    void forwardAlertToPhones(event).catch((error) => { runtimeState.pushErrors += 1; console.error(`[FCM] Error inesperado: ${error.stack || error.message}`); });
  }
  if (parsed.imei && !commandForAck) await trySendInquiry(parsed.imei, { now });
}

function json(res, statusCode, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length, 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': config.http.corsOrigin });
  res.end(data);
}

function compactAppAlert(event) {
  const zoneName = String(event.subjectKind || '').toUpperCase() === 'ZONA' ? registry.getZoneName(event.imei, event.subjectNumber ?? event.subject) : null;
  return {
    id: event.id, receivedAt: event.receivedAt, eventCode: event.eventCode, eventDescription: event.eventDescription,
    partition: event.partition, subject: event.subject, subjectNumber: event.subjectNumber, subjectKind: event.subjectKind,
    zoneName, abonado: event.abonado, imei: event.imei, actionSource: event.actionSource || null,
    actorName: event.actorName || null, latitude: Number.isFinite(event.latitude) ? event.latitude : null,
    longitude: Number.isFinite(event.longitude) ? event.longitude : null,
    locationAccuracyMeters: Number.isFinite(event.locationAccuracyMeters) ? event.locationAccuracyMeters : null,
    locationCapturedAt: event.locationCapturedAt || null, mapsUrl: mapsUrl(event.latitude, event.longitude),
    requestId: event.requestId || null, buttonId: event.buttonId || null,
    buttonBattery: Number.isFinite(event.buttonBattery) ? event.buttonBattery : null
  };
}

function appDeviceStatus(installation) {
  const endpoint = commandService.getEndpoint(installation.imei);
  return { installation, device: store.getDevice(installation.imei), gprs: { online: endpoint?.fresh === true, lastSeenAt: endpoint?.lastSeenAt || null }, panelState: commandService.getPanelState(installation.imei), latestCommand: commandService.getLatestCommand(installation.imei) };
}

function authenticateBatch(accessTokens) {
  if (!Array.isArray(accessTokens) || accessTokens.length === 0) throw new RegistryError('Debe enviar al menos un token de panel', 400, 'EMPTY_BATCH');
  if (accessTokens.length > config.http.maxBatchItems) throw new RegistryError(`La consulta agrupada admite hasta ${config.http.maxBatchItems} paneles`, 400, 'BATCH_LIMIT_EXCEEDED');
  const authenticated = new Map();
  return accessTokens.map((value) => {
    const accessToken = String(value || '').trim();
    if (!authenticated.has(accessToken)) authenticated.set(accessToken, registry.authenticate(accessToken));
    return authenticated.get(accessToken);
  });
}

function optionalNumber(value) {
  if (value === null || value === undefined || String(value).trim() === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function mapsUrl(latitude, longitude) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${latitude},${longitude}`)}`;
}

function emergencyLocation(body, receivedAt) {
  const latitude = optionalNumber(body.latitude);
  const longitude = optionalNumber(body.longitude);
  if (mapsUrl(latitude, longitude) === null) return { latitude: null, longitude: null, locationAccuracyMeters: null, locationCapturedAt: null };
  const accuracy = optionalNumber(body.locationAccuracyMeters);
  const requestedCapturedAt = String(body.locationCapturedAt || '').trim();
  const parsedCapturedAt = Date.parse(requestedCapturedAt);
  return {
    latitude, longitude,
    locationAccuracyMeters: accuracy !== null && accuracy >= 0 && accuracy <= 100000 ? accuracy : null,
    locationCapturedAt: Number.isFinite(parsedCapturedAt) ? new Date(parsedCapturedAt).toISOString() : receivedAt
  };
}

function normalizeLifeRequestId(value) {
  const requestId = String(value || '').trim();
  if (!/^[A-Za-z0-9._:-]{8,120}$/.test(requestId)) {
    throw new RegistryError('requestId inválido para Botón Vida', 400, 'INVALID_LIFE_REQUEST_ID');
  }
  return requestId;
}

function findExistingLifeRequest(installation, body) {
  if (String(body.type || '').trim().toUpperCase() !== 'VIDA') return null;
  const requestId = normalizeLifeRequestId(body.requestId);
  return store.getEvents({ take: 1000, imei: installation.imei })
    .find((event) => event.eventCode === '640' && event.requestId === requestId) || null;
}

async function provisionPairingLabel(imei) {
  return createPairingLabel(registry.provisionDevice(imei));
}

function appendAppEmergency(installation, body, req) {
  const emergencyType = String(body.type || '').trim().toUpperCase();
  const definition = APP_EMERGENCY_EVENTS[emergencyType];
  if (!definition) throw new RegistryError('El tipo de emergencia debe ser MEDICA, PANICO, INCENDIO o VIDA', 400, 'INVALID_EMERGENCY_TYPE');
  const receivedAt = new Date().toISOString();
  const location = emergencyLocation(body, receivedAt);
  const abonado = String(body.abonado || store.getDevice(installation.imei)?.abonado || '').trim().slice(0, 32) || null;
  const requestId = emergencyType === 'VIDA' ? normalizeLifeRequestId(body.requestId) : null;
  const buttonId = emergencyType === 'VIDA' ? String(body.buttonId || '').trim().slice(0, 160) || null : null;
  const requestedBattery = optionalNumber(body.buttonBattery);
  const buttonBattery = emergencyType === 'VIDA' && requestedBattery !== null ? Math.max(0, Math.min(100, Math.round(requestedBattery))) : null;
  return store.append({
    receivedAt, sourceIp: normalizedIp(req.socket.remoteAddress), sourcePort: req.socket.remotePort || null,
    rawBytes: 0, packetHash: null, isDuplicate: false, valid: true, parseErrors: [], raw: `APP:${emergencyType}`,
    transmitterId: 'APP', protocol: 'APP_HTTP', sequence: null, deviceTimestampRaw: null, packetType: 'APP',
    packetTypeDescription: emergencyType === 'VIDA' ? 'Botón Vida desde Bluetooth' : 'Botón de emergencia desde la aplicación',
    eventCode: definition.eventCode, eventDescription: definition.description, contactId: null, contactIdValid: false,
    contactIdErrors: [], contactQualifier: '1', contactQualifierDescription: 'Evento iniciado desde la aplicación',
    contactCategory: 'ALARMA', partition: '01', partitionNumber: 1, subject: '000', subjectNumber: 0,
    subjectKind: 'USUARIO', checksum: null, checksumValidated: false, abonado, imei: installation.imei,
    firmware: null, channel: emergencyType === 'VIDA' ? 'BLE' : 'APP', shouldForwardToApp: true,
    ackSent: false, ackPayload: null, ackError: null, fields: [emergencyType, installation.installationId],
    actionSource: emergencyType === 'VIDA' ? 'BOTON_VIDA' : 'APP', actorName: installation.name,
    actorInstallationId: installation.installationId, requestId, buttonId, buttonBattery, ...location
  });
}

function getHealth() {
  const provisionedDevices = registry.listProvisionedDevices();
  return {
    ok: true, service: 'NanoSmart Server', version: '3.6.0', startedAt: STARTED_AT.toISOString(), uptimeSeconds: Math.floor(process.uptime()),
    udp: { host: config.udp.host, port: config.udp.port, listening: runtimeState.udpListening, error: runtimeState.udpError },
    http: { host: config.http.host, port: config.http.port, listening: runtimeState.httpListening },
    production: { enabled: config.production.enabled, host: config.production.host, port: config.production.port, listening: runtimeState.productionListening, authFailures: runtimeState.productionAuthFailures, blockedRequests: runtimeState.productionBlockedRequests },
    ack: { enabled: config.protocol.ack.enabled, timeZone: config.protocol.ack.timeZone, sent: runtimeState.acksSent, errors: runtimeState.ackErrors },
    appForwarding: config.protocol.appForwarding,
    gprsControl: { enabled: config.commands.enabled, endpointMaxAgeSeconds: config.commands.endpointMaxAgeSeconds, inquiryRetrySeconds: config.commands.inquiryRetrySeconds, commandTtlSeconds: config.commands.commandTtlSeconds, ...commandService.getStats() },
    registry: { provisionedDevices: provisionedDevices.length, activeInstallations: provisionedDevices.reduce((total, device) => total + device.installations, 0), pushTokens: registry.countPushTokens() },
    firebasePush: { ...pushService.getStatus(), alertsProcessed: runtimeState.pushAlertsProcessed, commandUpdatesProcessed: runtimeState.commandPushesProcessed, panelStateUpdatesProcessed: runtimeState.panelStatePushesProcessed, messagesSent: runtimeState.pushMessagesSent, errors: runtimeState.pushErrors, invalidTokensRemoved: runtimeState.invalidPushTokensRemoved },
    stats: store.getStats()
  };
}

function readJsonBody(req, maxBytes = 65536) {
  return new Promise((resolve, reject) => {
    const chunks = []; let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > maxBytes) { reject(new RegistryError('Cuerpo de solicitud demasiado grande', 413, 'BODY_TOO_LARGE')); req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (total === 0) { resolve({}); return; }
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new RegistryError('El cuerpo debe ser JSON válido', 400, 'INVALID_JSON')); }
    });
    req.on('error', reject);
  });
}

function readBinaryBody(req, maxBytes = 10 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > maxBytes) { reject(new RegistryError('El archivo support.db es demasiado grande', 413, 'BODY_TOO_LARGE')); req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function bearerToken(req) {
  const authorization = String(req.headers.authorization || '');
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : null;
}

function expectedErrorResponse(res, error) {
  if (error instanceof RegistryError || error instanceof CommandError) { json(res, error.statusCode, { error: error.message, code: error.code }); return true; }
  return false;
}

const httpServer = http.createServer(async (req, res) => {
  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { 'Access-Control-Allow-Origin': config.http.corsOrigin, 'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type, Authorization' });
      res.end(); return;
    }
    const requestUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = decodeURIComponent(requestUrl.pathname);
    const privateClient = isPrivateOrLoopback(req.socket.remoteAddress);
    if (config.http.restrictAdministrationToPrivateNetworks && !privateClient && !isPublicAppPath(pathname)) {
      json(res, 403, { error: 'Esta ruta sólo está disponible desde el servidor o la red privada' }); return;
    }
    if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': dashboardHtml.length, 'Cache-Control': 'no-store' }); res.end(dashboardHtml); return;
    }
    if (req.method === 'GET' && (pathname === '/admin' || pathname === '/admin.html')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': adminHtml.length, 'Cache-Control': 'no-store' }); res.end(adminHtml); return;
    }
    if (req.method === 'GET' && (pathname === '/app-simulator' || pathname === '/app-simulator.html')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': appSimulatorHtml.length, 'Cache-Control': 'no-store' }); res.end(appSimulatorHtml); return;
    }
    const provisionMatch = pathname.match(/^\/api\/admin\/devices\/(\d{15})\/access-key$/);
    if (req.method === 'POST' && provisionMatch) { json(res, 201, await provisionPairingLabel(provisionMatch[1])); return; }
    if (req.method === 'POST' && pathname === '/api/admin/installations') { const body = await readJsonBody(req); json(res, 201, registry.registerInstallationFromAdmin(body)); return; }
    if (req.method === 'GET' && pathname === '/api/admin/devices') { json(res, 200, { devices: registry.listProvisionedDevices() }); return; }
    if (req.method === 'GET' && pathname === '/api/admin/clients') { json(res, 200, { clients: registry.listClients() }); return; }
    if (req.method === 'POST' && pathname === '/api/admin/clients/import-support-db') {
      const databaseBuffer = await readBinaryBody(req); const imported = importSupportDatabase(databaseBuffer, normalizedIp(req.socket.remoteAddress));
      console.log(`[ADMIN] Base support.db actualizada: ${imported.total} clientes`); json(res, 200, imported); return;
    }
    if (req.method === 'POST' && pathname === '/api/admin/clients/robbery-forwarding') {
      const body = await readJsonBody(req, 16384); const client = registry.setClientRobberyForwarding(body.clientId, body.enabled === true);
      appendProductionAudit({ at: new Date().toISOString(), action: 'ADMIN_SET_CLIENT_ROBBERY_FORWARDING', clientId: client.id, enabled: client.forwardRobberyToApp, sourceIp: normalizedIp(req.socket.remoteAddress) });
      json(res, 200, { client }); return;
    }
    const installationsMatch = pathname.match(/^\/api\/admin\/devices\/(\d{15})\/installations$/);
    if (req.method === 'GET' && installationsMatch) { json(res, 200, { imei: installationsMatch[1], installations: registry.listInstallations(installationsMatch[1]) }); return; }
    const revokeMatch = pathname.match(/^\/api\/admin\/installations\/([^/]+)\/revoke$/);
    if (req.method === 'POST' && revokeMatch) { json(res, 200, registry.revokeInstallation(revokeMatch[1])); return; }
    if (req.method === 'POST' && pathname === '/api/app/register') { const body = await readJsonBody(req); json(res, 201, registry.registerInstallation(body)); return; }
    if (req.method === 'POST' && pathname === '/api/app/batch/status') {
      const body = await readJsonBody(req, 1024 * 1024); const installations = authenticateBatch(body.accessTokens);
      json(res, 200, { count: installations.length, statuses: installations.map(appDeviceStatus) }); return;
    }
    if (req.method === 'POST' && pathname === '/api/app/batch/push-token') {
      const body = await readJsonBody(req, 1024 * 1024); const installations = authenticateBatch(body.accessTokens);
      const updated = installations.map((installation) => registry.updatePushToken(installation.installationId, body.pushToken, body.platform));
      json(res, 200, { count: updated.length, installations: updated }); return;
    }
    if (pathname.startsWith('/api/app/')) {
      const installation = registry.authenticate(bearerToken(req));
      if (req.method === 'GET' && pathname === '/api/app/me') { json(res, 200, installation); return; }
      if (req.method === 'POST' && pathname === '/api/app/me/name') { const body = await readJsonBody(req); json(res, 200, registry.updateInstallationName(installation.installationId, body.name)); return; }
      if (req.method === 'GET' && pathname === '/api/app/device/status') { json(res, 200, appDeviceStatus(installation)); return; }
      if (req.method === 'GET' && pathname === '/api/app/device/zones') { json(res, 200, { imei: installation.imei, zones: registry.getZoneNames(installation.imei) }); return; }
      if (req.method === 'PUT' && pathname === '/api/app/device/zones') { const body = await readJsonBody(req); json(res, 200, { imei: installation.imei, zones: registry.updateZoneNames(installation.imei, body.zones) }); return; }
      if (req.method === 'POST' && pathname === '/api/app/device/command') {
        if (!config.commands.enabled) throw new CommandError('El control GPRS está desactivado', 503, 'COMMANDS_DISABLED');
        const body = await readJsonBody(req);
        const currentInstallation = body.name ? registry.updateInstallationName(installation.installationId, body.name) : installation;
        const created = commandService.createCommand(currentInstallation.imei, body.action, currentInstallation.installationId, currentInstallation.name);
        await trySendInquiry(currentInstallation.imei);
        json(res, 202, { command: commandService.getCommand(created.id, currentInstallation.imei) }); return;
      }
      if (req.method === 'POST' && pathname === '/api/app/emergency') {
        const body = await readJsonBody(req);
        const existing = findExistingLifeRequest(installation, body);
        if (existing) {
          console.log(`[APP] Reintento Botón Vida ${existing.requestId} ya registrado; no se duplica`);
          json(res, 200, { alert: compactAppAlert(existing), duplicate: true });
          return;
        }
        const currentInstallation = body.name ? registry.updateInstallationName(installation.installationId, body.name) : installation;
        const event = appendAppEmergency(currentInstallation, body, req);
        console.log(`[APP] ${event.eventDescription} ejecutado por ${event.actorName} | IMEI ${event.imei}`);
        sendSse('packet', event); sendSse('app-alert', event);
        void forwardAlertToPhones(event).catch((error) => { runtimeState.pushErrors += 1; console.error(`[FCM] Error notificando emergencia: ${error.stack || error.message}`); });
        json(res, 201, { alert: compactAppAlert(event), duplicate: false }); return;
      }
      const commandStatusMatch = pathname.match(/^\/api\/app\/device\/commands\/([A-Za-z0-9-]+)$/);
      if (req.method === 'GET' && commandStatusMatch) { json(res, 200, { command: commandService.getCommand(commandStatusMatch[1], installation.imei) }); return; }
      if (req.method === 'GET' && pathname === '/api/app/alerts') {
        const requestedTake = Math.min(Math.max(Number.parseInt(requestUrl.searchParams.get('take') || '100', 10) || 100, 1), 1000);
        const alerts = store.getEvents({ take: 1000, afterId: requestUrl.searchParams.get('afterId'), imei: installation.imei, forwardOnly: true })
          .filter((event) => registry.shouldDeliverEventToApp(event.imei, event.eventCode)).slice(0, requestedTake);
        json(res, 200, { imei: installation.imei, alerts: alerts.map(compactAppAlert) }); return;
      }
      if (req.method === 'POST' && pathname === '/api/app/push-token') {
        const body = await readJsonBody(req); json(res, 200, registry.updatePushToken(installation.installationId, body.pushToken, body.platform)); return;
      }
    }
    if (req.method === 'GET' && pathname === '/api/health') {
      const health = getHealth();
      if (config.http.restrictAdministrationToPrivateNetworks && !privateClient) json(res, 200, { ok: health.ok, service: health.service, version: health.version, udpListening: health.udp.listening, httpListening: health.http.listening, firebaseReady: health.firebasePush.ready });
      else json(res, 200, health);
      return;
    }
    if (req.method === 'GET' && pathname === '/api/gprs/endpoints') {
      const endpoints = commandService.listEndpoints().map((endpoint) => ({ ...endpoint, panelState: commandService.getPanelState(endpoint.imei), latestCommand: commandService.getLatestCommand(endpoint.imei) }));
      json(res, 200, { endpoints, heartbeatTotal: commandService.getStats().heartbeatsReceived, endpointMaxAgeSeconds: config.commands.endpointMaxAgeSeconds }); return;
    }
    if (req.method === 'GET' && pathname === '/api/events') { json(res, 200, { events: store.getEvents({ take: requestUrl.searchParams.get('take'), afterId: requestUrl.searchParams.get('afterId'), abonado: requestUrl.searchParams.get('abonado'), imei: requestUrl.searchParams.get('imei'), forwardOnly: requestUrl.searchParams.get('forwardOnly') }) }); return; }
    if (req.method === 'GET' && pathname === '/api/devices') { json(res, 200, { devices: store.getDevices() }); return; }
    const deviceStatusMatch = pathname.match(/^\/api\/devices\/(\d{15})\/status$/);
    if (req.method === 'GET' && deviceStatusMatch) { const device = store.getDevice(deviceStatusMatch[1]); if (!device) { json(res, 404, { error: 'IMEI no encontrado' }); return; } json(res, 200, device); return; }
    const deviceEventsMatch = pathname.match(/^\/api\/devices\/(\d{15})\/events$/);
    if (req.method === 'GET' && deviceEventsMatch) { json(res, 200, { events: store.getEvents({ take: requestUrl.searchParams.get('take'), afterId: requestUrl.searchParams.get('afterId'), imei: deviceEventsMatch[1] }) }); return; }
    const deviceAlertsMatch = pathname.match(/^\/api\/devices\/(\d{15})\/alerts$/);
    if (req.method === 'GET' && deviceAlertsMatch) { json(res, 200, { imei: deviceAlertsMatch[1], alerts: store.getEvents({ take: requestUrl.searchParams.get('take'), afterId: requestUrl.searchParams.get('afterId'), imei: deviceAlertsMatch[1], forwardOnly: true }) }); return; }
    if (req.method === 'GET' && pathname === '/api/accounts') { json(res, 200, { accounts: store.getAccounts() }); return; }
    const accountStatusMatch = pathname.match(/^\/api\/accounts\/([^/]+)\/status$/);
    if (req.method === 'GET' && accountStatusMatch) { const account = store.getAccount(accountStatusMatch[1]); if (!account) { json(res, 404, { error: 'Abonado no encontrado' }); return; } json(res, 200, account); return; }
    const accountEventsMatch = pathname.match(/^\/api\/accounts\/([^/]+)\/events$/);
    if (req.method === 'GET' && accountEventsMatch) { json(res, 200, { events: store.getEvents({ take: requestUrl.searchParams.get('take'), afterId: requestUrl.searchParams.get('afterId'), abonado: accountEventsMatch[1] }) }); return; }
    if (req.method === 'GET' && pathname === '/api/stream') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive', 'Access-Control-Allow-Origin': config.http.corsOrigin });
      res.write(`event: ready\ndata: ${JSON.stringify({ connectedAt: new Date().toISOString() })}\n\n`); sseClients.add(res); req.on('close', () => sseClients.delete(res)); return;
    }
    json(res, 404, { error: 'Ruta no encontrada' });
  } catch (error) {
    console.error('[HTTP]', error);
    if (!res.headersSent && expectedErrorResponse(res, error)) return;
    if (!res.headersSent) json(res, 500, { error: 'Error interno del servidor' }); else res.end();
  }
});

httpServer.requestTimeout = 30000;
httpServer.headersTimeout = 15000;
httpServer.keepAliveTimeout = 5000;
httpServer.maxHeadersCount = 100;

function productionJson(res, statusCode, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer' });
  res.end(data);
}

const productionHttpServer = http.createServer({ requestTimeout: 15000, headersTimeout: 10000, keepAliveTimeout: 3000, maxHeaderSize: 16384 }, async (req, res) => {
  try {
    const requestUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = decodeURIComponent(requestUrl.pathname);
    const knownRoute = (req.method === 'GET' && pathname === '/api/production/health') || (req.method === 'GET' && pathname === '/api/production/clients') || (req.method === 'POST' && pathname === '/api/production/pairings');
    if (!knownRoute) { productionJson(res, 404, { error: 'Ruta no encontrada' }); return; }
    authenticateProduction(req);
    if (req.method === 'GET' && pathname === '/api/production/health') { productionJson(res, 200, { ok: true, service: 'NanoSmart Producción', version: '1.4.0', serverVersion: '3.6.0' }); return; }
    if (req.method === 'GET' && pathname === '/api/production/clients') { productionJson(res, 200, { clients: registry.listClients() }); return; }
    const body = await readJsonBody(req, 16384);
    const provisioned = registry.provisionMobilePairings(body.imei, body.labels, { reissue: body.reissue === true, clientId: body.clientId });
    const result = await createProductionPairingLabels(provisioned);
    appendProductionAudit({ at: new Date().toISOString(), action: 'GENERATE_TWO_MOBILE_QR', imei: result.imei, clientId: provisioned.client.id, clientName: provisioned.client.name, labels: result.pairings.map((item) => item.label), sourceIp: normalizedIp(req.socket.remoteAddress) });
    console.log(`[PRODUCCIÓN] IMEI ${result.imei}: dos QR móviles generados`); productionJson(res, 201, result);
  } catch (error) {
    if (error instanceof RegistryError || error instanceof CommandError) { productionJson(res, error.statusCode, { error: error.message, code: error.code }); return; }
    console.error(`[PRODUCCIÓN HTTP] ${error.stack || error.message}`);
    if (!res.headersSent) productionJson(res, 500, { error: 'Error interno del servidor' }); else res.end();
  }
});
productionHttpServer.maxHeadersCount = 50;

const heartbeat = setInterval(() => {
  pruneProductionAuthAttempts();
  for (const client of sseClients) { try { client.write(`: heartbeat ${Date.now()}\n\n`); } catch { sseClients.delete(client); } }
}, 15000);
heartbeat.unref();

udpServer.on('message', (message, remote) => { void handleDatagram(message, remote).catch((error) => { console.error(`[UDP] Error procesando paquete: ${error.stack || error.message}`); }); });
udpServer.on('error', (error) => { runtimeState.udpListening = false; runtimeState.udpError = error.code || error.message; console.error(`[UDP] ${error.stack || error.message}`); });
httpServer.on('error', (error) => { console.error(`[HTTP] ${error.stack || error.message}`); process.exitCode = 1; });
productionHttpServer.on('error', (error) => { runtimeState.productionListening = false; console.error(`[PRODUCCIÓN HTTP] ${error.stack || error.message}`); process.exitCode = 1; });

function lanAddresses() {
  const addresses = [];
  try {
    for (const entries of Object.values(os.networkInterfaces())) for (const info of entries || []) if (info.family === 'IPv4' && !info.internal) addresses.push(info.address);
  } catch (error) { console.warn(`[RED] No se pudieron enumerar las IP locales: ${error.message}`); }
  return addresses;
}

udpServer.bind(config.udp.port, config.udp.host, () => {
  runtimeState.udpListening = true; runtimeState.udpError = null;
  console.log('============================================================');
  console.log(' NanoSmart Server v3.6 - Rendimiento y carga optimizados');
  console.log('============================================================');
  console.log(`[UDP] Escuchando en ${config.udp.host}:${config.udp.port}`);
  console.log(`[ACK] Activo - zona horaria ${config.protocol.ack.timeZone}`);
  console.log(`[CONTROL] Heartbeat IMEI sin ACK - DI01 + ARMAR/DESARMAR (orden válida ${config.commands.commandTtlSeconds}s)`);
  const pushStatus = pushService.getStatus();
  if (pushStatus.ready) console.log(`[FCM] Activo - proyecto ${pushStatus.projectId}`);
  else if (pushStatus.enabled) console.warn(`[FCM] Pendiente de configuración - ${pushStatus.error}`);
  else console.log('[FCM] Desactivado');
});

httpServer.listen(config.http.port, config.http.host, () => {
  runtimeState.httpListening = true;
  console.log(`[WEB] Panel local: http://localhost:${config.http.port}`);
  console.log(`[WEB] Administración: http://localhost:${config.http.port}/admin`);
  console.log(`[WEB] Simulador de app: http://localhost:${config.http.port}/app-simulator`);
  for (const ip of lanAddresses()) console.log(`[WEB] Panel en red: http://${ip}:${config.http.port}`);
  console.log('[INFO] Presione Ctrl+C para detener el servidor.');
});

if (config.production.enabled) {
  productionHttpServer.listen(config.production.port, config.production.host, () => {
    runtimeState.productionListening = true;
    console.log(`[PRODUCCIÓN] API aislada en ${config.production.host}:${config.production.port} (no disponible en TCP ${config.http.port})`);
    console.log('[PRODUCCIÓN] Use MOSTRAR_CLAVE_PRODUCCION.bat para activar la estación.');
  });
} else console.log('[PRODUCCIÓN] API desactivada');

let shutdownStarted = false;
async function shutdown(signal) {
  if (shutdownStarted) return;
  shutdownStarted = true;
  console.log(`\n[INFO] ${signal}: cerrando NanoSmart Server...`);
  clearInterval(heartbeat);
  for (const client of sseClients) client.end();
  sseClients.clear();
  udpServer.close();
  const servers = [httpServer, productionHttpServer].filter((server) => server.listening);
  const serversClosed = Promise.all(servers.map((server) => new Promise((resolve) => { server.close(resolve); })));
  try { registry.close(); await store.close(); } catch (error) { console.error(`[ALMACENAMIENTO] Error al finalizar: ${error.message}`); }
  const graceful = await Promise.race([serversClosed.then(() => true), new Promise((resolve) => setTimeout(() => resolve(false), 3000))]);
  process.exit(graceful ? 0 : 1);
}

process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));
