'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const childProcess = require('node:child_process');
const path = require('node:path');
const { parseContactId, shouldForwardContactEvent } = require('../src/protocol');

test('server.js conserva sintaxis JavaScript valida', () => {
  const serverPath = path.resolve(__dirname, '..', 'server.js');
  childProcess.execFileSync(process.execPath, ['--check', serverPath], { stdio: 'pipe' });
});

test('Contact ID 640 se reconoce como Botón Vida y genera alerta app', () => {
  const contact = parseContactId('1234181640010000');
  assert.equal(contact.valid, true);
  assert.equal(contact.eventCode, '640');
  assert.equal(contact.eventDescription, 'Botón Vida');
  assert.equal(contact.subjectKind, 'USUARIO');
  assert.equal(
    shouldForwardContactEvent(contact, {
      appForwarding: { enabled: true, qualifiers: ['1'], eventCodes: ['100', '110', '120'] }
    }),
    true
  );
});
