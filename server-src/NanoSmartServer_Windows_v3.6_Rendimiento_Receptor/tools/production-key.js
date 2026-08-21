'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const configPath = path.resolve(process.env.NANOSMART_CONFIG || path.join(root, 'config.json'));
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const storageDirectory = path.resolve(root, config.storage?.directory || 'data');
const keyFile = path.resolve(
  root,
  config.production?.keyFile || path.join(storageDirectory, 'production-key.txt')
);

fs.mkdirSync(path.dirname(keyFile), { recursive: true });
let key = fs.existsSync(keyFile) ? fs.readFileSync(keyFile, 'utf8').trim() : '';
if (!key) {
  key = `NSP-${crypto.randomBytes(24).toString('base64url')}`;
  fs.writeFileSync(keyFile, `${key}\n`, 'utf8');
}

console.log('============================================================');
console.log(' CLAVE PARA NANOSMART PRODUCCIÓN');
console.log('============================================================');
console.log(key);
console.log('');
console.log(`Servicio de producción: http://54.232.115.106:${config.production?.port || 18083}`);
console.log('La aplicación v1.2 ya tiene esta dirección protegida internamente. Copie solamente la clave.');
