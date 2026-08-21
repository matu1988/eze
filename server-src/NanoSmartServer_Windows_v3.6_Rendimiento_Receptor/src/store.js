'use strict';

const fs = require('node:fs');
const path = require('node:path');

function boundedInteger(value, fallback, min, max) {
  const number = Number.parseInt(value, 10);
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, number));
}

class EventStore {
  constructor(options = {}) {
    this.directory = path.resolve(options.directory || 'data');
    this.memoryLimit = boundedInteger(options.memoryLimit, 5000, 100, 100000);
    this.trimBatch = boundedInteger(options.trimBatch, 256, 16, 5000);
    this.asyncPersistence = options.asyncPersistence === true;
    this.events = [];
    this.eventsByImei = new Map();
    this.eventsByAccount = new Map();
    this.devices = new Map();
    this.accounts = new Map();
    this.nextId = 1;
    this.pendingWrites = 0;
    this.writeStreams = new Map();
    this.flushWaiters = [];
    this.lastWriteError = null;
    this.stats = {
      totalReceived: 0,
      eventsStored: 0,
      validPackets: 0,
      invalidPackets: 0,
      duplicatePackets: 0,
      appAlertsGenerated: 0
    };
  }

  initialize() {
    fs.mkdirSync(this.directory, { recursive: true });
    const files = fs.readdirSync(this.directory)
      .filter((name) => /^events-\d{4}-\d{2}-\d{2}\.jsonl$/.test(name))
      .sort();

    const recent = [];
    for (const name of files.reverse()) {
      const fullPath = path.join(this.directory, name);
      const lines = fs.readFileSync(fullPath, 'utf8').split(/\r?\n/).filter(Boolean);
      for (let index = lines.length - 1; index >= 0; index -= 1) {
        try {
          recent.push(JSON.parse(lines[index]));
          if (recent.length >= this.memoryLimit) break;
        } catch (error) {
          console.warn(`[ALMACENAMIENTO] Línea inválida ignorada en ${name}: ${error.message}`);
        }
      }
      if (recent.length >= this.memoryLimit) break;
    }

    recent.reverse();
    for (const event of recent) this.#remember(event, false);
  }

  append(event) {
    const persisted = { ...event, id: this.nextId++ };
    this.#remember(persisted, true);

    const date = persisted.receivedAt.slice(0, 10);
    const filePath = path.join(this.directory, `events-${date}.jsonl`);
    const line = `${JSON.stringify(persisted)}\n`;
    if (this.asyncPersistence) {
      this.#appendAsync(filePath, line);
    } else {
      fs.appendFileSync(filePath, line, 'utf8');
    }
    return persisted;
  }

  async flush() {
    if (this.pendingWrites > 0) {
      await new Promise((resolve) => this.flushWaiters.push(resolve));
    }
    if (this.lastWriteError) {
      const error = this.lastWriteError;
      this.lastWriteError = null;
      throw error;
    }
  }

  async close() {
    await this.flush();
    const streams = [...this.writeStreams.values()];
    this.writeStreams.clear();
    await Promise.all(streams.map((stream) => new Promise((resolve) => {
      if (stream.closed || stream.destroyed) {
        resolve();
        return;
      }
      stream.once('close', resolve);
      stream.end();
    })));
  }

  #appendAsync(filePath, line) {
    let stream = this.writeStreams.get(filePath);
    if (!stream || stream.destroyed) {
      stream = fs.createWriteStream(filePath, { flags: 'a', encoding: 'utf8' });
      stream.on('error', (error) => {
        this.lastWriteError = error;
        console.error(`[ALMACENAMIENTO] No se pudo guardar un evento: ${error.message}`);
      });
      this.writeStreams.set(filePath, stream);
    }

    this.pendingWrites += 1;
    stream.write(line, 'utf8', (error) => {
      if (error) this.lastWriteError = error;
      this.pendingWrites = Math.max(0, this.pendingWrites - 1);
      if (this.pendingWrites === 0) {
        const waiters = this.flushWaiters.splice(0);
        for (const resolve of waiters) resolve();
      }
    });
  }

  recordDuplicate(valid = true) {
    this.stats.totalReceived += 1;
    this.stats.duplicatePackets += 1;
    if (valid) this.stats.validPackets += 1;
    else this.stats.invalidPackets += 1;
  }

  #remember(event, trimMemory) {
    const numericId = Number.parseInt(event.id, 10);
    if (Number.isFinite(numericId)) this.nextId = Math.max(this.nextId, numericId + 1);

    this.events.push(event);
    this.#indexEvent(event);
    this.stats.totalReceived += 1;
    this.stats.eventsStored += 1;
    if (event.valid) this.stats.validPackets += 1;
    else this.stats.invalidPackets += 1;
    if (event.shouldForwardToApp) this.stats.appAlertsGenerated += 1;

    this.#updateDevice(event);
    this.#updateAccount(event);

    if (trimMemory && this.events.length > this.memoryLimit + this.trimBatch) {
      this.events = this.events.slice(-this.memoryLimit);
      this.#rebuildQueryIndexes();
    }
  }

  #indexEvent(event) {
    if (event.imei) {
      const imei = String(event.imei);
      const values = this.eventsByImei.get(imei) || [];
      values.push(event);
      this.eventsByImei.set(imei, values);
    }
    if (event.abonado) {
      const abonado = String(event.abonado).toUpperCase();
      const values = this.eventsByAccount.get(abonado) || [];
      values.push(event);
      this.eventsByAccount.set(abonado, values);
    }
  }

  #updateDevice(event) {
    if (!event.imei) return;

    const previous = this.devices.get(event.imei) || {
      imei: event.imei,
      eventCount: 0,
      appAlertCount: 0,
      panelStateSource: 'GPRS'
    };

    const state = {
      ...previous,
      transmitterId: event.transmitterId || previous.transmitterId || null,
      abonado: event.abonado || previous.abonado || null,
      firmware: event.firmware || previous.firmware || null,
      channel: event.channel || previous.channel || null,
      lastCommunicationAt: event.receivedAt,
      lastEventId: event.id,
      lastEventCode: event.eventCode,
      lastEventDescription: event.eventDescription,
      eventCount: previous.eventCount + 1,
      appAlertCount: previous.appAlertCount + (event.shouldForwardToApp ? 1 : 0)
    };

    this.devices.set(event.imei, state);
  }

  #updateAccount(event) {
    if (!event.abonado) return;
    const previous = this.accounts.get(event.abonado) || {
      abonado: event.abonado,
      eventCount: 0
    };
    this.accounts.set(event.abonado, {
      ...previous,
      imei: event.imei || previous.imei || null,
      lastCommunicationAt: event.receivedAt,
      lastEventId: event.id,
      lastEventCode: event.eventCode,
      lastEventDescription: event.eventDescription,
      eventCount: previous.eventCount + 1
    });
  }

  #rebuildQueryIndexes() {
    this.eventsByImei.clear();
    this.eventsByAccount.clear();
    for (const event of this.events) this.#indexEvent(event);
  }

  getEvents(options = {}) {
    const take = boundedInteger(options.take, 100, 1, 1000);
    const afterId = boundedInteger(options.afterId, 0, 0, Number.MAX_SAFE_INTEGER);
    const abonado = options.abonado ? String(options.abonado).toUpperCase() : null;
    const imei = options.imei ? String(options.imei) : null;
    const forwardOnly = options.forwardOnly === true || options.forwardOnly === 'true';

    let result = this.events;
    if (imei) result = this.eventsByImei.get(imei) || [];
    if (abonado) {
      const accountEvents = this.eventsByAccount.get(abonado) || [];
      if (!imei || accountEvents.length < result.length) result = accountEvents;
    }
    if (abonado && imei) {
      result = result.filter((event) => event.abonado === abonado && event.imei === imei);
    }
    if (forwardOnly) result = result.filter((event) => event.shouldForwardToApp === true);

    if (afterId > 0) {
      return result.filter((event) => event.id > afterId).slice(0, take);
    }

    return result.slice(-take).reverse();
  }

  getDevice(imei) {
    return this.devices.get(String(imei || '')) || null;
  }

  getDevices() {
    return [...this.devices.values()]
      .sort((a, b) => String(b.lastCommunicationAt).localeCompare(String(a.lastCommunicationAt)));
  }

  getAccount(abonado) {
    return this.accounts.get(String(abonado || '').toUpperCase()) || null;
  }

  getAccounts() {
    return [...this.accounts.values()]
      .sort((a, b) => String(b.lastCommunicationAt).localeCompare(String(a.lastCommunicationAt)));
  }

  getStats() {
    return {
      ...this.stats,
      devicesDetected: this.devices.size,
      accountsDetected: this.accounts.size,
      eventsInMemory: this.events.length,
      pendingWrites: this.pendingWrites,
      asyncPersistence: this.asyncPersistence
    };
  }
}

module.exports = { EventStore };
