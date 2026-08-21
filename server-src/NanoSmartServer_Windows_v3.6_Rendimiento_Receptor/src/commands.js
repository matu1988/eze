'use strict';

const crypto = require('node:crypto');

const VALID_ACTIONS = new Set(['ARMAR', 'DESARMAR']);
const ACTIVE_STATUSES = new Set(['PENDING', 'DELIVERING', 'AWAITING_RESULT']);

class CommandError extends Error {
  constructor(message, statusCode = 400, code = 'COMMAND_ERROR') {
    super(message);
    this.name = 'CommandError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

function normalizeImei(value) {
  const imei = String(value || '').trim();
  if (!/^\d{15}$/.test(imei)) {
    throw new CommandError('El IMEI debe contener exactamente 15 dígitos', 400, 'INVALID_IMEI');
  }
  return imei;
}

function normalizeAction(value) {
  const action = String(value || '').trim().toUpperCase();
  if (!VALID_ACTIONS.has(action)) {
    throw new CommandError('La acción debe ser ARMAR o DESARMAR', 400, 'INVALID_ACTION');
  }
  return action;
}

function normalizeRemote(remote) {
  const address = String(remote?.address || '').trim();
  const port = Number.parseInt(remote?.port, 10);
  if (!address || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new CommandError('Dirección UDP del equipo inválida', 400, 'INVALID_REMOTE');
  }
  return { address, port };
}

class DeviceCommandService {
  constructor(options = {}) {
    this.now = options.now || (() => Date.now());
    this.commandTtlMs = Number.parseInt(options.commandTtlSeconds || 180, 10) * 1000;
    this.endpointMaxAgeMs = Number.parseInt(options.endpointMaxAgeSeconds || 90, 10) * 1000;
    this.inquiryRetryMs = Number.parseInt(options.inquiryRetrySeconds || 45, 10) * 1000;
    this.maxHistory = Number.parseInt(options.maxHistory || 1000, 10);
    this.endpoints = new Map();
    this.commands = new Map();
    this.activeByImei = new Map();
    this.latestByImei = new Map();
    this.panelStates = new Map();
    this.stats = {
      heartbeatsReceived: 0,
      endpointsUpdated: 0,
      commandsRequested: 0,
      inquiriesSent: 0,
      inquiryErrors: 0,
      commandsSent: 0,
      commandsConfirmed: 0,
      commandsAlreadyInState: 0,
      commandsDelivered: 0,
      commandsExpired: 0
    };
  }

  recordEndpoint(imeiValue, remote, options = {}) {
    const imei = normalizeImei(imeiValue);
    const endpoint = normalizeRemote(remote);
    const now = Number.isFinite(options.now) ? options.now : this.now();
    const previous = this.endpoints.get(imei);
    const next = {
      imei,
      address: endpoint.address,
      port: endpoint.port,
      lastSeenAtMs: now,
      lastSeenAt: new Date(now).toISOString(),
      source: options.source === 'heartbeat' ? 'HEARTBEAT' : 'PACKET',
      heartbeatCount: (previous?.heartbeatCount || 0) + (options.source === 'heartbeat' ? 1 : 0),
      lastHeartbeatAtMs: options.source === 'heartbeat'
        ? now
        : (previous?.lastHeartbeatAtMs || null),
      lastHeartbeatAt: options.source === 'heartbeat'
        ? new Date(now).toISOString()
        : (previous?.lastHeartbeatAt || null)
    };
    this.endpoints.set(imei, next);
    this.stats.endpointsUpdated += 1;
    if (next.source === 'HEARTBEAT') this.stats.heartbeatsReceived += 1;
    return {
      ...next,
      changed: !previous || previous.address !== next.address || previous.port !== next.port
    };
  }

  getEndpoint(imeiValue, options = {}) {
    const imei = normalizeImei(imeiValue);
    const now = Number.isFinite(options.now) ? options.now : this.now();
    const endpoint = this.endpoints.get(imei);
    if (!endpoint) return null;
    const ageMs = Math.max(0, now - endpoint.lastSeenAtMs);
    return {
      ...endpoint,
      ageMs,
      fresh: ageMs <= this.endpointMaxAgeMs
    };
  }

  listEndpoints(options = {}) {
    const now = Number.isFinite(options.now) ? options.now : this.now();
    return [...this.endpoints.values()]
      .map((endpoint) => {
        const ageMs = Math.max(0, now - endpoint.lastSeenAtMs);
        const heartbeatAgeMs = endpoint.lastHeartbeatAtMs === null
          ? null
          : Math.max(0, now - endpoint.lastHeartbeatAtMs);
        return {
          imei: endpoint.imei,
          address: endpoint.address,
          port: endpoint.port,
          lastSeenAt: endpoint.lastSeenAt,
          lastHeartbeatAt: endpoint.lastHeartbeatAt,
          heartbeatCount: endpoint.heartbeatCount,
          source: endpoint.source,
          ageSeconds: Math.floor(ageMs / 1000),
          heartbeatAgeSeconds: heartbeatAgeMs === null ? null : Math.floor(heartbeatAgeMs / 1000),
          online: ageMs <= this.endpointMaxAgeMs
        };
      })
      .sort((left, right) => String(right.lastSeenAt).localeCompare(String(left.lastSeenAt)));
  }

  createCommand(imeiValue, actionValue, requestedBy = null, requestedByName = null) {
    const imei = normalizeImei(imeiValue);
    const action = normalizeAction(actionValue);
    const now = this.now();
    this.expireCommands(now);

    const active = this.#activeCommand(imei);
    if (active) {
      if (active.action === action) return this.publicCommand(active);
      throw new CommandError(
        `Ya existe una orden ${active.action} pendiente para este equipo`,
        409,
        'COMMAND_IN_PROGRESS'
      );
    }

    const command = {
      id: crypto.randomUUID(),
      imei,
      action,
      status: 'PENDING',
      requestedAtMs: now,
      requestedAt: new Date(now).toISOString(),
      expiresAtMs: now + this.commandTtlMs,
      expiresAt: new Date(now + this.commandTtlMs).toISOString(),
      requestedBy: requestedBy ? String(requestedBy) : null,
      requestedByName: requestedByName ? String(requestedByName).trim().slice(0, 80) : null,
      attempts: 0,
      lastInquiryAtMs: null,
      lastInquiryAt: null,
      actionSentAt: null,
      confirmedAt: null,
      deliveredAt: null,
      transmitterId: null,
      sequence: null,
      resultCode: null,
      result: null,
      panelStatus: null,
      alreadyInState: false,
      resultDescription: null,
      error: null,
      lastSendError: null
    };
    this.commands.set(command.id, command);
    this.activeByImei.set(imei, command.id);
    this.latestByImei.set(imei, command.id);
    this.stats.commandsRequested += 1;
    this.#trimHistory();
    return this.publicCommand(command);
  }

  inquiryCandidate(imeiValue, options = {}) {
    const imei = normalizeImei(imeiValue);
    const now = Number.isFinite(options.now) ? options.now : this.now();
    this.expireCommands(now);
    const command = this.#activeCommand(imei);
    if (!command) return null;
    if (command.status === 'PENDING') return { ...command };
    if (options.force === true && command.status === 'DELIVERING') return { ...command };
    if (command.status === 'DELIVERING' &&
        (!command.lastInquiryAtMs || now - command.lastInquiryAtMs >= this.inquiryRetryMs)) {
      return { ...command };
    }
    return null;
  }

  awaitingResponse(imeiValue, options = {}) {
    const imei = normalizeImei(imeiValue);
    const now = Number.isFinite(options.now) ? options.now : this.now();
    this.expireCommands(now);
    const command = this.#activeCommand(imei);
    return command?.status === 'DELIVERING' ? { ...command } : null;
  }

  markInquiryStarted(commandId, endpoint, options = {}) {
    const command = this.#requireActive(commandId);
    const now = Number.isFinite(options.now) ? options.now : this.now();
    const normalizedEndpoint = normalizeRemote(endpoint);
    command.status = 'DELIVERING';
    command.attempts += 1;
    command.lastInquiryAtMs = now;
    command.lastInquiryAt = new Date(now).toISOString();
    command.lastEndpoint = normalizedEndpoint;
    command.error = null;
    command.lastSendError = null;
    this.stats.inquiriesSent += 1;
    return this.publicCommand(command);
  }

  markInquiryFailed(commandId, error) {
    const command = this.commands.get(String(commandId || ''));
    if (!command || !ACTIVE_STATUSES.has(command.status)) return null;
    command.status = 'PENDING';
    command.lastSendError = String(error?.message || error || 'No se pudo enviar DI01');
    this.stats.inquiryErrors += 1;
    return this.publicCommand(command);
  }

  markActionSent(commandId, details = {}) {
    const command = this.#requireActive(commandId);
    const now = Number.isFinite(details.now) ? details.now : this.now();
    command.status = 'AWAITING_RESULT';
    command.actionSentAt = new Date(now).toISOString();
    command.transmitterId = details.transmitterId ? String(details.transmitterId) : null;
    command.sequence = details.sequence ? String(details.sequence) : null;
    command.error = null;
    command.lastSendError = null;
    this.stats.commandsSent += 1;
    return this.publicCommand(command);
  }

  recordPanelResult(imeiValue, panelResult, details = {}) {
    const imei = normalizeImei(imeiValue);
    if (!panelResult?.action || !panelResult?.panelStatus) return null;
    const now = Number.isFinite(details.now) ? details.now : this.now();
    const confirmedAt = new Date(now).toISOString();
    const command = this.#activeCommand(imei);
    const matched = Boolean(
      command && command.status === 'AWAITING_RESULT' && command.action === panelResult.action
    );
    const panelState = {
      imei,
      action: String(panelResult.action),
      panelStatus: String(panelResult.panelStatus),
      result: String(panelResult.result || ''),
      alreadyInState: panelResult.alreadyInState === true,
      resultCode: String(panelResult.code || details.eventCode || ''),
      resultDescription: String(panelResult.description || ''),
      confirmedAt,
      transmitterId: details.transmitterId ? String(details.transmitterId) : null,
      sequence: details.sequence ? String(details.sequence) : null,
      actionSource: matched ? 'APP' : 'PANEL',
      actorName: matched ? (command.requestedByName || 'Aplicación') : 'Teclado del panel',
      actorInstallationId: matched ? command.requestedBy : null
    };
    this.panelStates.set(imei, panelState);

    if (!matched) {
      return { matched: false, panelState: { ...panelState }, command: null };
    }

    command.status = 'CONFIRMED';
    command.confirmedAt = confirmedAt;
    command.deliveredAt = confirmedAt;
    command.resultCode = panelState.resultCode;
    command.result = panelState.result;
    command.panelStatus = panelState.panelStatus;
    command.alreadyInState = panelState.alreadyInState;
    command.resultDescription = panelState.resultDescription;
    command.actionSource = panelState.actionSource;
    command.actorName = panelState.actorName;
    command.error = null;
    command.lastSendError = null;
    this.activeByImei.delete(imei);
    this.stats.commandsConfirmed += 1;
    this.stats.commandsDelivered += 1;
    if (command.alreadyInState) this.stats.commandsAlreadyInState += 1;
    return {
      matched: true,
      panelState: { ...panelState },
      command: this.publicCommand(command)
    };
  }

  getPanelState(imeiValue) {
    const imei = normalizeImei(imeiValue);
    const state = this.panelStates.get(imei);
    return state ? { ...state } : null;
  }

  markActionSendFailed(commandId, error) {
    const command = this.commands.get(String(commandId || ''));
    if (!command || !ACTIVE_STATUSES.has(command.status)) return null;
    command.status = 'PENDING';
    command.lastSendError = String(error?.message || error || 'No se pudo enviar la orden al equipo');
    return this.publicCommand(command);
  }

  getCommand(commandId, imeiValue = null) {
    this.expireCommands();
    const command = this.commands.get(String(commandId || ''));
    if (!command) throw new CommandError('Orden no encontrada', 404, 'COMMAND_NOT_FOUND');
    if (imeiValue !== null && command.imei !== normalizeImei(imeiValue)) {
      throw new CommandError('La orden no pertenece a este equipo', 404, 'COMMAND_NOT_FOUND');
    }
    return this.publicCommand(command);
  }

  getLatestCommand(imeiValue) {
    const imei = normalizeImei(imeiValue);
    this.expireCommands();
    const commandId = this.latestByImei.get(imei);
    const command = commandId ? this.commands.get(commandId) : null;
    return command ? this.publicCommand(command) : null;
  }

  expireCommands(nowValue = this.now()) {
    const now = Number.isFinite(nowValue) ? nowValue : this.now();
    for (const [imei, commandId] of this.activeByImei) {
      const command = this.commands.get(commandId);
      if (!command || !ACTIVE_STATUSES.has(command.status)) {
        this.activeByImei.delete(imei);
        continue;
      }
      if (now < command.expiresAtMs) continue;
      command.status = 'EXPIRED';
      command.error = 'El equipo no respondió antes de vencer la orden';
      this.activeByImei.delete(imei);
      this.stats.commandsExpired += 1;
    }
  }

  getStats() {
    this.expireCommands();
    return {
      ...this.stats,
      knownEndpoints: this.endpoints.size,
      activeCommands: this.activeByImei.size,
      commandsInMemory: this.commands.size
    };
  }

  publicCommand(command) {
    if (!command) return null;
    return {
      id: command.id,
      imei: command.imei,
      action: command.action,
      status: command.status,
      requestedAt: command.requestedAt,
      expiresAt: command.expiresAt,
      lastInquiryAt: command.lastInquiryAt,
      attempts: command.attempts,
      actionSentAt: command.actionSentAt,
      confirmedAt: command.confirmedAt,
      deliveredAt: command.deliveredAt,
      resultCode: command.resultCode,
      result: command.result,
      panelStatus: command.panelStatus,
      alreadyInState: command.alreadyInState === true,
      resultDescription: command.resultDescription,
      actionSource: command.actionSource || (command.requestedBy ? 'APP' : null),
      actorName: command.actorName || command.requestedByName || null,
      error: command.error
    };
  }

  #activeCommand(imei) {
    const commandId = this.activeByImei.get(imei);
    const command = commandId ? this.commands.get(commandId) : null;
    return command && ACTIVE_STATUSES.has(command.status) ? command : null;
  }

  #requireActive(commandId) {
    const command = this.commands.get(String(commandId || ''));
    if (!command || !ACTIVE_STATUSES.has(command.status)) {
      throw new CommandError('La orden ya no está activa', 409, 'COMMAND_NOT_ACTIVE');
    }
    return command;
  }

  #trimHistory() {
    if (this.commands.size <= this.maxHistory) return;
    for (const [commandId, command] of this.commands) {
      if (this.commands.size <= this.maxHistory) break;
      if (ACTIVE_STATUSES.has(command.status)) continue;
      this.commands.delete(commandId);
      if (this.latestByImei.get(command.imei) === commandId) {
        this.latestByImei.delete(command.imei);
      }
    }
  }
}

module.exports = {
  CommandError,
  DeviceCommandService,
  normalizeAction,
  normalizeImei
};
