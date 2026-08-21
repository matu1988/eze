'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const { readSupportClients } = require('../src/support-db');

test('lee la tabla clients de support.db sin modificarla', (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nanosmart-support-db-'));
  const databasePath = path.join(directory, 'support.db');
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const database = new DatabaseSync(databasePath);
  database.exec(`
    CREATE TABLE clients (
      id INTEGER PRIMARY KEY,
      cliente TEXT,
      descripcion TEXT,
      ca TEXT,
      pais TEXT,
      tipo_receptor TEXT,
      contacto TEXT,
      telefono TEXT,
      direccion TEXT,
      cuit TEXT,
      ciudad TEXT,
      provincia TEXT,
      telefono2 TEXT,
      vendedor TEXT
    )
  `);
  database.prepare(`
    INSERT INTO clients
      (id, cliente, descripcion, ca, pais, tipo_receptor, contacto, telefono,
       direccion, cuit, ciudad, provincia, telefono2, vendedor)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(10, 'Empresa Prueba', 'Central', 'CA-1001', 'Argentina', 'IP',
    'Laura', '111', 'Calle 1', '30-1', 'Buenos Aires', 'Buenos Aires', '222', 'Ana');
  database.close();

  const before = fs.statSync(databasePath);
  const clients = readSupportClients(databasePath);
  const after = fs.statSync(databasePath);

  assert.equal(clients.length, 1);
  assert.equal(clients[0].externalId, '10');
  assert.equal(clients[0].name, 'Empresa Prueba');
  assert.equal(clients[0].account, 'CA-1001');
  assert.equal(clients[0].city, 'Buenos Aires');
  assert.equal(after.size, before.size);
  assert.equal(after.mtimeMs, before.mtimeMs);
});
