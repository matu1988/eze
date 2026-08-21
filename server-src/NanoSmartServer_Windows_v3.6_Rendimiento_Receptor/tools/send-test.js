'use strict';

const dgram = require('node:dgram');

const host = process.argv[2] || '127.0.0.1';
const port = Number.parseInt(process.argv[3] || '7050', 10);
const packet = process.argv.slice(4).join(' ') ||
  '$B,TM10,15,25/11/2022-15:27,01,621118340100040B,18,0,0,1234,30,2_1.23AR,1,0,1,0,0,2,0,000001278272947,133,0,186.191.130.232,0,999,$E';

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('Puerto inválido. Uso: node tools/send-test.js [IP] [PUERTO] [PAQUETE]');
  process.exit(1);
}

const socket = dgram.createSocket('udp4');
const data = Buffer.from(packet, 'utf8');
let receivedAck = false;

socket.on('message', (message, remote) => {
  receivedAck = true;
  console.log(`ACK recibido desde ${remote.address}:${remote.port}`);
  console.log(message.toString('utf8'));
  socket.close();
});

socket.on('error', (error) => {
  console.error(`Error UDP: ${error.message}`);
  socket.close();
  process.exitCode = 1;
});

socket.bind(0, '0.0.0.0', () => {
  socket.send(data, port, host, (error) => {
    if (error) {
      console.error(`No se pudo enviar el paquete: ${error.message}`);
      socket.close();
      process.exitCode = 1;
      return;
    }

    console.log(`Paquete UDP enviado a ${host}:${port}`);
    console.log(packet);
    console.log('Esperando ACK...');
  });
});

setTimeout(() => {
  if (receivedAck) return;
  console.error('No se recibió el ACK dentro de 3 segundos.');
  socket.close();
  process.exitCode = 2;
}, 3000).unref();
