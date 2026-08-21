'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const KEY_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const AUTOMONITORING_CLIENT_ID = 'automonitoreo';
const LIFE_PURPOSE = 'LIFE_BUTTON';
const ROBBERY_EVENT_CODES = new Set(['130', '131', '132', '133', '134', '135', '136', '137', '138', '139']);

class RegistryError extends Error {
  constructor(message, statusCode = 400, code = 'REGISTRY_ERROR') {
    super(message);
    this.name = 'RegistryError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

function validateImei(imei) {
  const value = String(imei || '').trim();
  if (!/^\d{15}$/.test(value)) {
    throw new RegistryError('El IMEI debe contener exactamente 15 dígitos', 400, 'INVALID_IMEI');
  }
  return value;
}

function normalizeAccessKey(value) {
  return String(value || '').trim().toUpperCase();
}

function normalizeInstallationName(value) {
  const name = String(value || '').trim().replace(/\s+/g, ' ').slice(0, 80);
  if (!name) {
    throw new RegistryError('El nombre del celular es obligatorio', 400, 'INVALID_INSTALLATION_NAME');
  }
  return name;
}

function normalizePlatform(value, fallback = 'ANDROID') {
  const platform = String(value || fallback).trim().toUpperCase();
  if (!['ANDROID', 'IOS'].includes(platform)) {
    throw new RegistryError('La plataforma debe ser ANDROID o IOS', 400, 'INVALID_PLATFORM');
  }
  return platform;
}

function normalizeZoneNames(value) {
  if (value === null || value === undefined) return {};
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw new RegistryError(
      'Los nombres de zonas deben enviarse como un objeto',
      400,
      'INVALID_ZONE_NAMES'
    );
  }

  const normalized = {};
  for (let zone = 1; zone <= 16; zone += 1) {
    const name = String(value[zone] ?? value[String(zone)] ?? '')
      .trim()
      .replace(/\s+/g, ' ')
      .slice(0, 60);
    if (name) normalized[String(zone)] = name;
  }
  return normalized;
}

function normalizeClientId(value) {
  return String(value || '').trim().toLowerCase().slice(0, 120);
}

function normalizeClientText(value, maxLength = 160) {
  return String(value ?? '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

function automonitoringClient(previous = null) {
  return {
    id: AUTOMONITORING_CLIENT_ID,
    externalId: '',
    name: 'Automonitoreo',
    description: 'Equipos de automonitoreo NanoSmart',
    account: '',
    country: '',
    receiverType: '',
    contact: '',
    phone: '',
    address: '',
    cuit: '',
    city: '',
    province: '',
    phone2: '',
    seller: '',
    source: 'SYSTEM',
    forwardRobberyToApp: true,
    locked: true,
    createdAt: previous?.createdAt || new Date().toISOString(),
    updatedAt: previous?.updatedAt || new Date().toISOString()
  };
}

function normalizeImportedClient(value = {}) {
  const externalId = normalizeClientText(value.externalId ?? value.id, 80);
  const name = normalizeClientText(value.name ?? value.cliente, 160);
  if (!externalId || !name) {
    throw new RegistryError('Cada cliente debe incluir id y nombre', 400, 'INVALID_CLIENT');
  }
  return {
    id: `reparaciones:${externalId.toLowerCase()}`,
    externalId,
    name,
    description: normalizeClientText(value.description ?? value.descripcion, 240),
    account: normalizeClientText(value.account ?? value.ca, 80),
    country: normalizeClientText(value.country ?? value.pais, 80),
    receiverType: normalizeClientText(value.receiverType ?? value.tipo_receptor, 80),
    contact: normalizeClientText(value.contact ?? value.contacto, 160),
    phone: normalizeClientText(value.phone ?? value.telefono, 80),
    address: normalizeClientText(value.address ?? value.direccion, 240),
    cuit: normalizeClientText(value.cuit, 40),
    city: normalizeClientText(value.city ?? value.ciudad, 100),
    province: normalizeClientText(value.province ?? value.provincia, 100),
    phone2: normalizeClientText(value.phone2 ?? value.telefono2, 80),
    seller: normalizeClientText(value.seller ?? value.vendedor, 120),
    source: 'REPARACIONES'
  };
}

function randomAccessKey() {
  const bytes = crypto.randomBytes(16);
  let value = '';
  for (const byte of bytes) value += KEY_ALPHABET[byte % KEY_ALPHABET.length];
  return `NS-${value.slice(0, 4)}-${value.slice(4, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}`;
}

function scryptHash(secret, salt) {
  return crypto.scryptSync(secret, salt, 32).toString('hex');
}

function tokenHash(token) {
  return crypto.createHash('sha256').update(String(token || '')).digest('hex');
}

function safeEqualHex(left, right) {
  if (!left || !right || left.length !== right.length) return false;
  return crypto.timingSafeEqual(Buffer.from(left, 'hex'), Buffer.from(right, 'hex'));
}

class DeviceRegistry {
  constructor(filePath, options = {}) {
    this.filePath = path.resolve(filePath);
    this.persistDebounceMs = Math.max(
      0,
      Math.min(5000, Number.parseInt(options.persistDebounceMs || 0, 10) || 0)
    );
    this.persistTimer = null;
    this.dirty = false;
    this.data = { version: 4, devices: {}, installations: {}, pairingSlots: {}, clients: {} };
    this.data.clients[AUTOMONITORING_CLIENT_ID] = automonitoringClient();
    this.installationByTokenHash = new Map();
    this.installationIdsByImei = new Map();
    this.pairingSlotIdsByImei = new Map();
    this.pushTokenOwnerByDevice = new Map();
    this.enabledInstallationCountByImei = new Map();
    this.enabledPairingCountByImei = new Map();
    this.deviceCountByClient = new Map();
    this.pushTokenCount = 0;
  }

  initialize() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    if (!fs.existsSync(this.filePath)) {
      this.#rebuildIndexes();
      this.#save(true);
      return;
    }

    const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
    this.data = {
      version: 4,
      devices: parsed.devices || {},
      installations: parsed.installations || {},
      pairingSlots: parsed.pairingSlots || {},
      clients: parsed.clients || {}
    };
    this.data.clients[AUTOMONITORING_CLIENT_ID] = automonitoringClient(
      this.data.clients[AUTOMONITORING_CLIENT_ID]
    );
    for (const device of Object.values(this.data.devices)) {
      if (!device.clientId) device.clientId = AUTOMONITORING_CLIENT_ID;
    }
    for (const installation of Object.values(this.data.installations)) {
      if (!installation.purpose) installation.purpose = 'MOBILE';
    }
    this.#rebuildIndexes();
    this.#save(true);
  }

  #save(immediate = false) {
    this.dirty = true;
    if (immediate || this.persistDebounceMs === 0) {
      this.flush();
      return;
    }
    if (this.persistTimer) return;
    this.persistTimer = setTimeout(() => this.flush(), this.persistDebounceMs);
    this.persistTimer.unref?.();
  }

  flush() {
    if (this.persistTimer) {
      clearTimeout(this.persistTimer);
      this.persistTimer = null;
    }
    if (!this.dirty) return;
    if (fs.existsSync(this.filePath)) {
      fs.copyFileSync(this.filePath, `${this.filePath}.bak`);
    }
    fs.writeFileSync(this.filePath, `${JSON.stringify(this.data)}\n`, 'utf8');
    this.dirty = false;
  }

  close() {
    this.flush();
  }

  #addToSetIndex(index, key, value) {
    let values = index.get(key);
    if (!values) {
      values = new Set();
      index.set(key, values);
    }
    values.add(value);
  }

  #removeFromSetIndex(index, key, value) {
    const values = index.get(key);
    if (!values) return;
    values.delete(value);
    if (values.size === 0) index.delete(key);
  }

  #changeCount(index, key, delta) {
    const next = (index.get(key) || 0) + delta;
    if (next > 0) index.set(key, next);
    else index.delete(key);
  }

  #pushOwnerKey(imei, pushToken) {
    return `${imei}\u0000${pushToken}`;
  }

  #indexDevice(device) {
    if (!device) return;
    this.#changeCount(
      this.deviceCountByClient,
      device.clientId || AUTOMONITORING_CLIENT_ID,
      1
    );
  }

  #unindexDevice(device) {
    if (!device) return;
    this.#changeCount(
      this.deviceCountByClient,
      device.clientId || AUTOMONITORING_CLIENT_ID,
      -1
    );
  }

  #indexInstallation(installation) {
    if (!installation) return;
    this.#addToSetIndex(
      this.installationIdsByImei,
      installation.imei,
      installation.installationId
    );
    if (!installation.enabled) return;
    if (installation.tokenHash) {
      this.installationByTokenHash.set(installation.tokenHash, installation);
    }
    this.#changeCount(this.enabledInstallationCountByImei, installation.imei, 1);
    if (installation.pushToken && installation.purpose !== LIFE_PURPOSE) {
      this.pushTokenOwnerByDevice.set(
        this.#pushOwnerKey(installation.imei, installation.pushToken),
        installation.installationId
      );
      this.pushTokenCount += 1;
    }
  }

  #unindexInstallation(installation) {
    if (!installation) return;
    this.#removeFromSetIndex(
      this.installationIdsByImei,
      installation.imei,
      installation.installationId
    );
    if (!installation.enabled) return;
    if (installation.tokenHash &&
        this.installationByTokenHash.get(installation.tokenHash)?.installationId === installation.installationId) {
      this.installationByTokenHash.delete(installation.tokenHash);
    }
    this.#changeCount(this.enabledInstallationCountByImei, installation.imei, -1);
    if (installation.pushToken && installation.purpose !== LIFE_PURPOSE) {
      const ownerKey = this.#pushOwnerKey(installation.imei, installation.pushToken);
      if (this.pushTokenOwnerByDevice.get(ownerKey) === installation.installationId) {
        this.pushTokenOwnerByDevice.delete(ownerKey);
      }
      this.pushTokenCount = Math.max(0, this.pushTokenCount - 1);
    }
  }

  #indexPairing(slot) {
    if (!slot) return;
    this.#addToSetIndex(this.pairingSlotIdsByImei, slot.imei, slot.slotId);
    if (slot.enabled) this.#changeCount(this.enabledPairingCountByImei, slot.imei, 1);
  }

  #unindexPairing(slot) {
    if (!slot) return;
    this.#removeFromSetIndex(this.pairingSlotIdsByImei, slot.imei, slot.slotId);
    if (slot.enabled) this.#changeCount(this.enabledPairingCountByImei, slot.imei, -1);
  }

  #rebuildIndexes() {
    this.installationByTokenHash.clear();
    this.installationIdsByImei.clear();
    this.pairingSlotIdsByImei.clear();
    this.pushTokenOwnerByDevice.clear();
    this.enabledInstallationCountByImei.clear();
    this.enabledPairingCountByImei.clear();
    this.deviceCountByClient.clear();
    this.pushTokenCount = 0;
    for (const device of Object.values(this.data.devices)) this.#indexDevice(device);
    for (const installation of Object.values(this.data.installations)) {
      this.#indexInstallation(installation);
    }
    for (const slot of Object.values(this.data.pairingSlots)) this.#indexPairing(slot);
  }

  provisionDevice(imei) {
    const normalizedImei = validateImei(imei);
    const now = new Date().toISOString();
    const accessKey = randomAccessKey();
    const salt = crypto.randomBytes(16).toString('hex');
    const previous = this.data.devices[normalizedImei];

    this.#unindexDevice(previous);
    this.data.devices[normalizedImei] = {
      imei: normalizedImei,
      enabled: previous?.enabled ?? true,
      createdAt: previous?.createdAt || now,
      updatedAt: now,
      zoneNames: previous?.zoneNames || {},
      clientId: previous?.clientId || AUTOMONITORING_CLIENT_ID,
      accessKeySalt: salt,
      accessKeyHash: scryptHash(normalizeAccessKey(accessKey), salt)
    };
    this.#indexDevice(this.data.devices[normalizedImei]);
    this.#save();

    return {
      imei: normalizedImei,
      accessKey,
      createdAt: this.data.devices[normalizedImei].createdAt,
      updatedAt: now,
      warning: 'La clave se muestra una sola vez. Guárdela para vincular los celulares.'
    };
  }

  verifyAccessKey(imei, accessKey) {
    const normalizedImei = validateImei(imei);
    const device = this.data.devices[normalizedImei];
    if (!device || !device.enabled) {
      throw new RegistryError('El IMEI no está habilitado para vinculación', 404, 'DEVICE_NOT_PROVISIONED');
    }

    const candidate = scryptHash(normalizeAccessKey(accessKey), device.accessKeySalt);
    if (!safeEqualHex(candidate, device.accessKeyHash)) {
      throw new RegistryError('IMEI o clave de acceso incorrectos', 401, 'INVALID_ACCESS_KEY');
    }
    return device;
  }

  registerInstallation(input = {}) {
    const imei = validateImei(input.imei);
    const pairingSlot = this.#findPairingSlot(imei, input.accessKey);
    const purpose = String(input.purpose || 'MOBILE').trim().toUpperCase();

    if (purpose === LIFE_PURPOSE) {
      if (pairingSlot) {
        pairingSlot.lastUsedAt = new Date().toISOString();
      } else {
        this.verifyAccessKey(imei, input.accessKey);
      }
      const deviceIdentifier = String(input.deviceIdentifier || '').trim().slice(0, 160);
      if (deviceIdentifier.length < 6) {
        throw new RegistryError(
          'Falta la identificación del dispositivo Botón Vida',
          400,
          'MISSING_LIFE_DEVICE_ID'
        );
      }
      const suffix = crypto.createHash('sha256')
        .update(`${imei}\u0000${deviceIdentifier}`)
        .digest('hex')
        .slice(0, 32);
      return this.#createInstallation(imei, {
        ...input,
        installationId: `life:${suffix}`,
        name: input.name || `Botón Vida ${imei.slice(-4)}`,
        purpose: LIFE_PURPOSE,
        pushToken: null
      });
    }

    if (pairingSlot) {
      pairingSlot.lastUsedAt = new Date().toISOString();
      return this.#createInstallation(imei, {
        ...input,
        installationId: pairingSlot.installationId,
        name: input.name || pairingSlot.label
      });
    }
    this.verifyAccessKey(imei, input.accessKey);
    return this.#createInstallation(imei, input);
  }

  provisionMobilePairings(imeiValue, labelsValue = null, options = {}) {
    const imei = validateImei(imeiValue);
    const existingDevice = this.data.devices[imei];
    if (!existingDevice) {
      this.provisionDevice(imei);
    } else if (!existingDevice.enabled) {
      throw new RegistryError('El IMEI está deshabilitado', 409, 'DEVICE_DISABLED');
    }

    const device = this.data.devices[imei];
    const clientId = normalizeClientId(options.clientId || device.clientId || AUTOMONITORING_CLIENT_ID);
    const client = this.data.clients[clientId];
    if (!client) {
      throw new RegistryError('Seleccione un cliente valido', 400, 'CLIENT_NOT_FOUND');
    }

    const requestedLabels = Array.isArray(labelsValue) ? labelsValue : ['Mobile 1', 'Mobile 2'];
    if (requestedLabels.length !== 2) {
      throw new RegistryError(
        'Producción debe generar exactamente dos usuarios móviles',
        400,
        'INVALID_MOBILE_PAIRING_COUNT'
      );
    }

    const existingSlots = [...(this.pairingSlotIdsByImei.get(imei) || [])]
      .map((slotId) => this.data.pairingSlots[slotId])
      .filter((slot) => slot?.enabled);
    if (existingSlots.length > 0 && options.reissue !== true) {
      throw new RegistryError(
        'El IMEI ya tiene dos QR de producción. Confirme la reimpresión para renovarlos.',
        409,
        'PRODUCTION_PAIRINGS_EXIST'
      );
    }

    const now = new Date().toISOString();
    const pairings = requestedLabels.map((labelValue, index) => {
      const number = index + 1;
      const slotId = `${imei}:mobile-${number}`;
      const previous = this.data.pairingSlots[slotId];
      const accessKey = randomAccessKey();
      const salt = crypto.randomBytes(16).toString('hex');
      const label = normalizeInstallationName(labelValue || `Mobile ${number}`);
      const installationId = previous?.installationId || `prod:${imei}:mobile-${number}`;

      this.#unindexPairing(previous);
      this.data.pairingSlots[slotId] = {
        slotId,
        slotNumber: number,
        imei,
        label,
        installationId,
        enabled: true,
        accessKeySalt: salt,
        accessKeyHash: scryptHash(normalizeAccessKey(accessKey), salt),
        createdAt: previous?.createdAt || now,
        updatedAt: now,
        lastUsedAt: previous?.lastUsedAt || null
      };
      this.#indexPairing(this.data.pairingSlots[slotId]);

      return {
        slotId,
        slotNumber: number,
        imei,
        label,
        installationId,
        accessKey,
        createdAt: this.data.pairingSlots[slotId].createdAt,
        updatedAt: now
      };
    });
    const previousDevice = { ...device };
    this.#unindexDevice(previousDevice);
    device.clientId = client.id;
    device.updatedAt = now;
    this.#indexDevice(device);
    this.#save();
    return {
      imei,
      client: this.#publicClient(client),
      pairings,
      generatedAt: now,
      reissued: existingSlots.length > 0
    };
  }

  importClients(values) {
    if (!Array.isArray(values) || values.length === 0) {
      throw new RegistryError('La base de clientes esta vacia', 400, 'EMPTY_CLIENT_LIST');
    }
    if (values.length > 5000) {
      throw new RegistryError('La base supera el maximo de 5000 clientes', 400, 'CLIENT_LIMIT_EXCEEDED');
    }

    const now = new Date().toISOString();
    let inserted = 0;
    let updated = 0;
    for (const value of values) {
      const normalized = normalizeImportedClient(value);
      const previous = this.data.clients[normalized.id];
      this.data.clients[normalized.id] = {
        ...normalized,
        forwardRobberyToApp: previous?.forwardRobberyToApp !== false,
        locked: false,
        createdAt: previous?.createdAt || now,
        updatedAt: now
      };
      if (previous) updated += 1;
      else inserted += 1;
    }
    this.data.clients[AUTOMONITORING_CLIENT_ID] = automonitoringClient(
      this.data.clients[AUTOMONITORING_CLIENT_ID]
    );
    this.#save();
    return {
      inserted,
      updated,
      total: Object.keys(this.data.clients).length,
      clients: this.listClients()
    };
  }

  listClients() {
    return Object.values(this.data.clients)
      .map((client) => this.#publicClient(client))
      .sort((left, right) => {
        if (left.id === AUTOMONITORING_CLIENT_ID) return -1;
        if (right.id === AUTOMONITORING_CLIENT_ID) return 1;
        return left.name.localeCompare(right.name, 'es', { sensitivity: 'base' });
      });
  }

  setClientRobberyForwarding(clientIdValue, enabledValue) {
    const clientId = normalizeClientId(clientIdValue);
    const client = this.data.clients[clientId];
    if (!client) {
      throw new RegistryError('Cliente no encontrado', 404, 'CLIENT_NOT_FOUND');
    }
    if (clientId === AUTOMONITORING_CLIENT_ID && enabledValue !== true) {
      throw new RegistryError(
        'Automonitoreo siempre debe recibir los eventos de robo',
        409,
        'AUTOMONITORING_ALWAYS_ENABLED'
      );
    }
    client.forwardRobberyToApp = enabledValue === true;
    client.updatedAt = new Date().toISOString();
    this.#save();
    return this.#publicClient(client);
  }

  shouldDeliverEventToApp(imeiValue, eventCodeValue) {
    const eventCode = String(eventCodeValue || '').trim();
    if (!ROBBERY_EVENT_CODES.has(eventCode)) return true;
    const imei = String(imeiValue || '').trim();
    const device = this.data.devices[imei];
    if (!device) return true;
    const client = this.data.clients[normalizeClientId(device.clientId)];
    if (!client || client.id === AUTOMONITORING_CLIENT_ID) return true;
    return client.forwardRobberyToApp !== false;
  }

  #findPairingSlot(imei, accessKeyValue) {
    const accessKey = normalizeAccessKey(accessKeyValue);
    if (!accessKey) return null;
    const slotIds = this.pairingSlotIdsByImei.get(imei) || [];
    for (const slotId of slotIds) {
      const slot = this.data.pairingSlots[slotId];
      if (!slot?.enabled || slot.imei !== imei) continue;
      const candidate = scryptHash(accessKey, slot.accessKeySalt);
      if (safeEqualHex(candidate, slot.accessKeyHash)) return slot;
    }
    return null;
  }

  registerInstallationFromAdmin(input = {}) {
    const imei = validateImei(input.imei);
    const existingDevice = this.data.devices[imei];
    let provisioned = false;

    if (!existingDevice) {
      this.provisionDevice(imei);
      provisioned = true;
    } else if (!existingDevice.enabled) {
      throw new RegistryError('El IMEI está deshabilitado', 409, 'DEVICE_DISABLED');
    }

    return {
      ...this.#createInstallation(imei, input),
      deviceProvisioned: provisioned
    };
  }

  #createInstallation(imei, input) {
    const requestedId = String(input.installationId || '').trim();
    if (requestedId && !/^[A-Za-z0-9._:-]{8,100}$/.test(requestedId)) {
      throw new RegistryError('Identificador de instalación inválido', 400, 'INVALID_INSTALLATION_ID');
    }

    const installationId = requestedId || crypto.randomUUID();
    const previous = this.data.installations[installationId];
    if (previous && previous.imei !== imei) {
      throw new RegistryError(
        'Esta instalación ya está vinculada a otro IMEI',
        409,
        'INSTALLATION_ALREADY_LINKED'
      );
    }

    const now = new Date().toISOString();
    const accessToken = crypto.randomBytes(32).toString('base64url');
    const name = String(input.name || previous?.name || 'Celular sin nombre').trim().slice(0, 80);
    const purpose = String(input.purpose || previous?.purpose || 'MOBILE').trim().toUpperCase();
    const pushToken = purpose === LIFE_PURPOSE
      ? null
      : (input.pushToken ? String(input.pushToken).trim().slice(0, 4096) : (previous?.pushToken || null));
    const platform = normalizePlatform(input.platform, previous?.platform || 'ANDROID');

    this.#unindexInstallation(previous);
    this.data.installations[installationId] = {
      installationId,
      imei,
      name,
      platform,
      purpose,
      enabled: true,
      pushToken,
      tokenHash: tokenHash(accessToken),
      createdAt: previous?.createdAt || now,
      updatedAt: now,
      lastRegisteredAt: now
    };
    this.#indexInstallation(this.data.installations[installationId]);
    this.#save();

    return {
      installationId,
      imei,
      name,
      platform,
      purpose,
      accessToken,
      tokenType: 'Bearer',
      createdAt: this.data.installations[installationId].createdAt
    };
  }

  authenticate(accessToken) {
    if (!accessToken) {
      throw new RegistryError('Falta el token de autorización', 401, 'MISSING_TOKEN');
    }
    const candidate = tokenHash(accessToken);
    const installation = this.installationByTokenHash.get(candidate);
    if (!installation || !installation.enabled || !safeEqualHex(installation.tokenHash, candidate)) {
      throw new RegistryError('Token inválido o revocado', 401, 'INVALID_TOKEN');
    }
    return {
      ...installation,
      platform: installation.platform || 'ANDROID',
      purpose: installation.purpose || 'MOBILE',
      tokenHash: undefined
    };
  }

  updatePushToken(installationId, pushToken, platformValue = null) {
    const installation = this.data.installations[installationId];
    if (!installation || !installation.enabled) {
      throw new RegistryError('Instalación no encontrada', 404, 'INSTALLATION_NOT_FOUND');
    }
    const normalizedPushToken = String(pushToken || '').trim().slice(0, 4096) || null;
    if ((installation.purpose || 'MOBILE') === LIFE_PURPOSE && normalizedPushToken) {
      throw new RegistryError(
        'La instalación Botón Vida no recibe notificaciones push',
        409,
        'LIFE_PUSH_NOT_ALLOWED'
      );
    }
    const now = new Date().toISOString();
    this.#unindexInstallation(installation);
    if (normalizedPushToken) {
      const ownerId = this.pushTokenOwnerByDevice.get(
        this.#pushOwnerKey(installation.imei, normalizedPushToken)
      );
      const other = ownerId ? this.data.installations[ownerId] : null;
      if (other && other.installationId !== installationId) {
        this.#unindexInstallation(other);
        other.pushToken = null;
        other.updatedAt = now;
        this.#indexInstallation(other);
      }
    }
    installation.pushToken = normalizedPushToken;
    installation.platform = normalizePlatform(platformValue, installation.platform || 'ANDROID');
    installation.updatedAt = now;
    this.#indexInstallation(installation);
    this.#save();
    return this.#publicInstallation(installation);
  }

  updateInstallationName(installationId, nameValue) {
    const installation = this.data.installations[installationId];
    if (!installation || !installation.enabled) {
      throw new RegistryError('Instalación no encontrada', 404, 'INSTALLATION_NOT_FOUND');
    }
    const name = normalizeInstallationName(nameValue);
    if (installation.name !== name) {
      installation.name = name;
      installation.updatedAt = new Date().toISOString();
      this.#save();
    }
    return this.#publicInstallation(installation);
  }

  getZoneNames(imei) {
    const normalizedImei = validateImei(imei);
    const device = this.data.devices[normalizedImei];
    if (!device || !device.enabled) {
      throw new RegistryError('El IMEI no está habilitado', 404, 'DEVICE_NOT_PROVISIONED');
    }
    const stored = normalizeZoneNames(device.zoneNames || {});
    return Object.fromEntries(
      Array.from({ length: 16 }, (_, index) => {
        const zone = String(index + 1);
        return [zone, stored[zone] || ''];
      })
    );
  }

  getZoneName(imei, zoneValue) {
    const normalizedImei = String(imei || '').trim();
    if (!/^\d{15}$/.test(normalizedImei)) return null;
    const zone = Number.parseInt(zoneValue, 10);
    if (!Number.isFinite(zone) || zone < 1 || zone > 16) return null;
    const device = this.data.devices[normalizedImei];
    if (!device || !device.enabled) return null;
    return normalizeZoneNames(device.zoneNames || {})[String(zone)] || null;
  }

  updateZoneNames(imei, value) {
    const normalizedImei = validateImei(imei);
    const device = this.data.devices[normalizedImei];
    if (!device || !device.enabled) {
      throw new RegistryError('El IMEI no está habilitado', 404, 'DEVICE_NOT_PROVISIONED');
    }
    device.zoneNames = normalizeZoneNames(value);
    device.updatedAt = new Date().toISOString();
    this.#save();
    return this.getZoneNames(normalizedImei);
  }

  clearPushToken(installationId, expectedPushToken = null) {
    const installation = this.data.installations[installationId];
    if (!installation) return false;
    if (expectedPushToken && installation.pushToken !== expectedPushToken) return false;
    this.#unindexInstallation(installation);
    installation.pushToken = null;
    installation.updatedAt = new Date().toISOString();
    this.#indexInstallation(installation);
    this.#save();
    return true;
  }

  clearPushTokens(targets) {
    if (!Array.isArray(targets) || targets.length === 0) return 0;
    let removed = 0;
    const now = new Date().toISOString();
    for (const target of targets) {
      const installation = this.data.installations[String(target?.installationId || '')];
      if (!installation || !installation.pushToken) continue;
      if (target?.pushToken && installation.pushToken !== target.pushToken) continue;
      this.#unindexInstallation(installation);
      installation.pushToken = null;
      installation.updatedAt = now;
      this.#indexInstallation(installation);
      removed += 1;
    }
    if (removed > 0) this.#save();
    return removed;
  }

  listPushTargets(imei) {
    const normalizedImei = validateImei(imei);
    return [...(this.installationIdsByImei.get(normalizedImei) || [])]
      .map((installationId) => this.data.installations[installationId])
      .filter((item) => item?.enabled && item.pushToken && (item.purpose || 'MOBILE') !== LIFE_PURPOSE)
      .map((item) => ({
        installationId: item.installationId,
        pushToken: item.pushToken,
        platform: item.platform || 'ANDROID'
      }));
  }

  countPushTokens() {
    return this.pushTokenCount;
  }

  revokeInstallation(installationId) {
    const installation = this.data.installations[String(installationId || '')];
    if (!installation) {
      throw new RegistryError('Instalación no encontrada', 404, 'INSTALLATION_NOT_FOUND');
    }
    this.#unindexInstallation(installation);
    installation.enabled = false;
    installation.updatedAt = new Date().toISOString();
    installation.tokenHash = null;
    installation.pushToken = null;
    this.#indexInstallation(installation);
    this.#save();
    return this.#publicInstallation(installation);
  }

  listInstallations(imei) {
    const normalizedImei = validateImei(imei);
    return [...(this.installationIdsByImei.get(normalizedImei) || [])]
      .map((installationId) => this.data.installations[installationId])
      .filter(Boolean)
      .map((item) => this.#publicInstallation(item))
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  }

  listProvisionedDevices() {
    return Object.values(this.data.devices).map((device) => ({
      imei: device.imei,
      clientId: device.clientId || AUTOMONITORING_CLIENT_ID,
      clientName: this.data.clients[device.clientId || AUTOMONITORING_CLIENT_ID]?.name || 'Automonitoreo',
      enabled: device.enabled,
      createdAt: device.createdAt,
      updatedAt: device.updatedAt,
      installations: this.enabledInstallationCountByImei.get(device.imei) || 0,
      productionPairings: this.enabledPairingCountByImei.get(device.imei) || 0
    }));
  }

  #publicInstallation(installation) {
    return {
      installationId: installation.installationId,
      imei: installation.imei,
      name: installation.name,
      platform: installation.platform || 'ANDROID',
      purpose: installation.purpose || 'MOBILE',
      enabled: installation.enabled,
      hasPushToken: Boolean(installation.pushToken),
      createdAt: installation.createdAt,
      updatedAt: installation.updatedAt,
      lastRegisteredAt: installation.lastRegisteredAt
    };
  }

  #publicClient(client) {
    return {
      id: client.id,
      externalId: client.externalId || '',
      name: client.name,
      description: client.description || '',
      account: client.account || '',
      country: client.country || '',
      receiverType: client.receiverType || '',
      contact: client.contact || '',
      phone: client.phone || '',
      address: client.address || '',
      cuit: client.cuit || '',
      city: client.city || '',
      province: client.province || '',
      phone2: client.phone2 || '',
      seller: client.seller || '',
      source: client.source || 'REPARACIONES',
      assignedDevices: this.deviceCountByClient.get(client.id) || 0,
      forwardRobberyToApp: client.id === AUTOMONITORING_CLIENT_ID
        ? true
        : client.forwardRobberyToApp !== false,
      locked: client.id === AUTOMONITORING_CLIENT_ID || client.locked === true,
      createdAt: client.createdAt,
      updatedAt: client.updatedAt
    };
  }
}

module.exports = {
  DeviceRegistry,
  RegistryError,
  normalizeAccessKey,
  normalizeClientId,
  normalizeInstallationName,
  normalizePlatform,
  normalizeZoneNames,
  validateImei
};
