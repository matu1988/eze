'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';

class FcmSendError extends Error {
  constructor(message, options = {}) {
    super(message);
    this.name = 'FcmSendError';
    this.statusCode = options.statusCode || null;
    this.errorCode = options.errorCode || null;
    this.invalidToken = options.invalidToken === true;
  }
}

function stringValue(value) {
  if (value === null || value === undefined) return '';
  return String(value);
}

function mapsUrl(event) {
  if (event.latitude === null || event.latitude === undefined || event.latitude === '' ||
      event.longitude === null || event.longitude === undefined || event.longitude === '') return '';
  const latitude = Number(event.latitude);
  const longitude = Number(event.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return '';
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return '';
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${latitude},${longitude}`)}`;
}

function compactBody(event) {
  const parts = [event.eventDescription || 'Evento de alarma'];
  if (event.actorName) parts.push(`Ejecutado por ${event.actorName}`);
  if (event.partition) parts.push(`Partición ${event.partition}`);
  if (event.subject) {
    const label = String(event.subjectKind || '').toUpperCase() === 'USUARIO' ? 'Usuario' : 'Zona';
    const subjectNumber = Number.parseInt(event.subjectNumber ?? event.subject, 10);
    if (label === 'Zona' && event.zoneName && Number.isFinite(subjectNumber)) {
      parts.push(`${event.zoneName} (Zona ${subjectNumber})`);
    } else {
      parts.push(`${label} ${event.subject}`);
    }
  }
  if (event.abonado) parts.push(`Abonado ${event.abonado}`);
  if (mapsUrl(event)) parts.push('Ubicación disponible');
  return parts.join(' · ');
}

function lifeStatusMessage(item) {
  const name = item.actorName || item.panelName || 'Botón Vida';
  const battery = Number.isFinite(Number(item.buttonBattery)) ? ` (${Number(item.buttonBattery)}%)` : '';
  switch (String(item.status || '').toUpperCase()) {
    case 'DISCONNECTED':
      return { title: 'Botón Vida desconectado', body: `${name}: el botón está fuera de alcance o apagado` };
    case 'BATTERY_LOW':
      return { title: 'Batería baja · Botón Vida', body: `${name}: batería baja${battery}` };
    case 'BATTERY_RESTORED':
      return { title: 'Batería restablecida · Botón Vida', body: `${name}: batería normal${battery}` };
    default:
      return { title: 'Estado Botón Vida', body: `${name}: ${String(item.status || 'actualización')}` };
  }
}

class FirebasePushService {
  constructor(options = {}) {
    this.enabled = options.enabled === true;
    this.projectId = String(options.projectId || '').trim();
    this.serviceAccountFile = options.serviceAccountFile ? path.resolve(options.serviceAccountFile) : null;
    this.timeoutMs = Number.parseInt(options.timeoutMs || 10000, 10);
    this.maxConcurrentSends = Math.max(1, Math.min(100, Number.parseInt(options.maxConcurrentSends || 20, 10) || 20));
    this.fetchImpl = options.fetchImpl || globalThis.fetch;
    this.now = options.now || (() => Date.now());
    this.credentials = null;
    this.configurationError = null;
    this.cachedAccessToken = null;
    this.accessTokenExpiresAt = 0;
    this.accessTokenPromise = null;
    this.activeSends = 0;
    this.queuedSends = [];
    this.maxObservedConcurrentSends = 0;
    this.#loadCredentials();
  }

  #loadCredentials() {
    if (!this.enabled) return;
    if (!this.serviceAccountFile || !fs.existsSync(this.serviceAccountFile)) {
      this.configurationError = `No se encontró ${this.serviceAccountFile ? path.basename(this.serviceAccountFile) : 'el archivo de cuenta de servicio'}`;
      return;
    }
    try {
      const credentials = JSON.parse(fs.readFileSync(this.serviceAccountFile, 'utf8'));
      if (!credentials.client_email || !credentials.private_key) throw new Error('faltan client_email o private_key');
      this.credentials = credentials;
      this.projectId ||= String(credentials.project_id || '').trim();
      if (!this.projectId) throw new Error('falta project_id');
    } catch (error) {
      this.configurationError = `Cuenta de servicio Firebase inválida: ${error.message}`;
      this.credentials = null;
    }
  }

  getStatus() {
    return {
      enabled: this.enabled,
      ready: this.enabled && Boolean(this.credentials) && !this.configurationError,
      projectId: this.projectId || null,
      serviceAccountFile: this.serviceAccountFile ? path.basename(this.serviceAccountFile) : null,
      error: this.configurationError,
      maxConcurrentSends: this.maxConcurrentSends,
      activeSends: this.activeSends,
      queuedSends: this.queuedSends.length,
      maxObservedConcurrentSends: this.maxObservedConcurrentSends
    };
  }

  async sendAlert(event, targets) {
    const originInstallationId = String(event?.actorInstallationId || '').trim();
    const isOwnLifeButtonEvent = String(event?.eventCode || '') === '640' &&
      String(event?.actionSource || '').trim().toUpperCase() === 'BOTON_VIDA' &&
      originInstallationId !== '';
    const filteredTargets = isOwnLifeButtonEvent && Array.isArray(targets)
      ? targets.filter((target) => String(target?.installationId || '').trim() !== originInstallationId)
      : targets;
    return this.#sendToTargets(event, filteredTargets, 'ALERT');
  }
  async sendDeviceCommand(command, targets) { return this.#sendToTargets(command, targets, 'DEVICE_COMMAND'); }
  async sendPanelState(panelState, targets) { return this.#sendToTargets(panelState, targets, 'PANEL_STATE'); }
  async sendLifeStatus(status, targets) { return this.#sendToTargets(status, targets, 'LIFE_STATUS'); }

  async #sendToTargets(item, targets, type) {
    const normalizedTargets = Array.isArray(targets)
      ? targets.filter((target) => target?.pushToken)
        .map((target) => ({ ...target, platform: target.platform === 'IOS' ? 'IOS' : 'ANDROID' }))
      : [];
    const status = this.getStatus();
    if (!status.ready || normalizedTargets.length === 0) {
      return { requested: normalizedTargets.length, sent: 0, errors: 0, skipped: normalizedTargets.length, invalidTargets: [] };
    }
    const results = await Promise.all(normalizedTargets.map(async (target) => {
      try {
        await this.#withSendSlot(() => this.#sendToToken(item, target.pushToken, true, type, target.platform));
        return { target, sent: true };
      } catch (error) {
        return { target, sent: false, error };
      }
    }));
    return {
      requested: results.length,
      sent: results.filter((result) => result.sent).length,
      errors: results.filter((result) => !result.sent).length,
      skipped: 0,
      invalidTargets: results.filter((result) => !result.sent && result.error?.invalidToken).map((result) => result.target),
      errorMessages: results.filter((result) => !result.sent).map((result) => result.error?.message || 'Error FCM desconocido')
    };
  }

  async #withSendSlot(callback) {
    if (this.activeSends >= this.maxConcurrentSends) await new Promise((resolve) => this.queuedSends.push(resolve));
    else this.activeSends += 1;
    this.maxObservedConcurrentSends = Math.max(this.maxObservedConcurrentSends, this.activeSends);
    try {
      return await callback();
    } finally {
      const next = this.queuedSends.shift();
      if (next) next();
      else this.activeSends = Math.max(0, this.activeSends - 1);
    }
  }

  async #sendToToken(item, pushToken, retryAuthentication = true, type = 'ALERT', platform = 'ANDROID') {
    const accessToken = await this.#getAccessToken();
    const endpoint = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.projectId)}/messages:send`;
    const isLifeStatus = type === 'LIFE_STATUS';
    const isPanelState = type === 'DEVICE_COMMAND' || type === 'PANEL_STATE';
    let title;
    let body;
    let data;

    if (isLifeStatus) {
      ({ title, body } = lifeStatusMessage(item));
      data = {
        type,
        title,
        body,
        imei: stringValue(item.imei),
        status: stringValue(item.status),
        actorName: stringValue(item.actorName),
        panelName: stringValue(item.panelName),
        buttonId: stringValue(item.buttonId),
        buttonName: stringValue(item.buttonName),
        buttonBattery: stringValue(item.buttonBattery),
        receivedAt: stringValue(item.receivedAt)
      };
    } else if (isPanelState) {
      const panelStatus = String(item.panelStatus || '').toLowerCase();
      const action = String(item.action || '').toLowerCase();
      const fromApp = item.actionSource === 'APP';
      body = item.alreadyInState
        ? (fromApp && item.actorName ? `${item.actorName} intentó ${action}, pero el panel ya estaba ${panelStatus}` : `El panel ya estaba ${panelStatus} · Teclado del panel`)
        : (fromApp && item.actorName ? `Panel ${panelStatus} correctamente por ${item.actorName}` : `Panel ${panelStatus} desde el teclado`);
      title = 'Estado NanoSmart';
      data = {
        type, title, body,
        commandId: stringValue(item.id), imei: stringValue(item.imei), action: stringValue(item.action),
        status: stringValue(item.status), panelStatus: stringValue(item.panelStatus), result: stringValue(item.result),
        resultCode: stringValue(item.resultCode), resultDescription: stringValue(item.resultDescription),
        actionSource: stringValue(item.actionSource), actorName: stringValue(item.actorName),
        alreadyInState: stringValue(item.alreadyInState === true), actionSentAt: stringValue(item.actionSentAt),
        confirmedAt: stringValue(item.confirmedAt), deliveredAt: stringValue(item.deliveredAt)
      };
    } else {
      title = item.eventCode === '640' ? 'Botón Vida' : 'Alerta NanoSmart';
      body = compactBody(item);
      data = {
        type: 'ALERT', title, body, alertId: stringValue(item.id), imei: stringValue(item.imei),
        eventCode: stringValue(item.eventCode), eventDescription: stringValue(item.eventDescription),
        partition: stringValue(item.partition), subject: stringValue(item.subject), subjectNumber: stringValue(item.subjectNumber),
        subjectKind: stringValue(item.subjectKind), zoneName: stringValue(item.zoneName), abonado: stringValue(item.abonado),
        actionSource: stringValue(item.actionSource), actorName: stringValue(item.actorName), receivedAt: stringValue(item.receivedAt),
        latitude: stringValue(item.latitude), longitude: stringValue(item.longitude),
        locationAccuracyMeters: stringValue(item.locationAccuracyMeters), locationCapturedAt: stringValue(item.locationCapturedAt),
        mapsUrl: mapsUrl(item)
      };
    }

    const message = { token: pushToken, data };
    if (platform === 'IOS') {
      message.notification = { title, body };
      message.apns = {
        headers: { 'apns-priority': '10' },
        payload: { aps: { sound: isLifeStatus ? 'default' : 'default', category: isLifeStatus ? 'NANOSMART_STATUS' : (isPanelState ? 'NANOSMART_STATE' : 'NANOSMART_ALERT'), 'content-available': 1 } }
      };
    } else {
      message.android = { priority: 'HIGH', restricted_package_name: 'com.nanocomm.nanosmart.eventos' };
    }

    const response = await this.#fetchWithTimeout(endpoint, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ message })
    });
    if (response.ok) return response.json();
    if (response.status === 401 && retryAuthentication) {
      this.cachedAccessToken = null;
      this.accessTokenExpiresAt = 0;
      return this.#sendToToken(item, pushToken, false, type, platform);
    }
    const responseText = await response.text();
    let parsed = null;
    try { parsed = JSON.parse(responseText); } catch { /* no JSON */ }
    const details = parsed?.error?.details || [];
    const fcmError = details.find((detail) => detail?.errorCode)?.errorCode || null;
    const invalidToken = response.status === 404 || fcmError === 'UNREGISTERED';
    throw new FcmSendError(parsed?.error?.message || `FCM respondió HTTP ${response.status}`, {
      statusCode: response.status, errorCode: fcmError, invalidToken
    });
  }

  async #getAccessToken() {
    if (this.cachedAccessToken && this.now() < this.accessTokenExpiresAt - 60000) return this.cachedAccessToken;
    if (this.accessTokenPromise) return this.accessTokenPromise;
    this.accessTokenPromise = this.#requestAccessToken();
    try { return await this.accessTokenPromise; }
    finally { this.accessTokenPromise = null; }
  }

  async #requestAccessToken() {
    const nowSeconds = Math.floor(this.now() / 1000);
    const tokenUri = this.credentials.token_uri || DEFAULT_TOKEN_URI;
    const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
    const claims = Buffer.from(JSON.stringify({
      iss: this.credentials.client_email, sub: this.credentials.client_email, aud: tokenUri,
      iat: nowSeconds, exp: nowSeconds + 3600, scope: FCM_SCOPE
    })).toString('base64url');
    const unsignedJwt = `${header}.${claims}`;
    const signature = crypto.sign('RSA-SHA256', Buffer.from(unsignedJwt), this.credentials.private_key).toString('base64url');
    const response = await this.#fetchWithTimeout(tokenUri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsignedJwt}.${signature}` }).toString()
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.access_token) throw new FcmSendError(body.error_description || `No se pudo autenticar con Firebase: HTTP ${response.status}`);
    this.cachedAccessToken = body.access_token;
    this.accessTokenExpiresAt = this.now() + Number.parseInt(body.expires_in || 3600, 10) * 1000;
    return this.cachedAccessToken;
  }

  async #fetchWithTimeout(url, options) {
    if (typeof this.fetchImpl !== 'function') throw new FcmSendError('Node.js no ofrece la función fetch');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await this.fetchImpl(url, { ...options, signal: controller.signal });
    } catch (error) {
      if (error?.name === 'AbortError') throw new FcmSendError('Tiempo de espera agotado al contactar Firebase');
      throw new FcmSendError(`No se pudo contactar Firebase: ${error.message}`);
    } finally {
      clearTimeout(timer);
    }
  }
}

module.exports = { FirebasePushService, FcmSendError, compactBody, lifeStatusMessage };
