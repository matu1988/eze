'use strict';

const PACKET_TYPE_DESCRIPTIONS = Object.freeze({
  '00': 'Test GPRS/LAN',
  '01': 'Evento Contact-ID',
  '05': 'Test SMS',
  '10': 'Encendido del comunicador',
  '34': 'Falta de alimentación',
  '35': 'Restablecimiento de alimentación',
  '36': 'Batería baja',
  '37': 'Restablecimiento de batería',
  '42': 'Batería baja de la aplicación',
  '43': 'Intento de armado GPRS: el panel ya estaba armado',
  '44': 'Intento de desarmado GPRS: el panel ya estaba desarmado',
  '45': 'Armado GPRS confirmado',
  '46': 'Desarmado GPRS confirmado'
});

const GPRS_PANEL_RESULTS = Object.freeze({
  '43': Object.freeze({
    action: 'ARMAR',
    panelStatus: 'ARMADO',
    result: 'ALREADY_IN_STATE',
    alreadyInState: true,
    description: 'El panel ya estaba armado'
  }),
  '44': Object.freeze({
    action: 'DESARMAR',
    panelStatus: 'DESARMADO',
    result: 'ALREADY_IN_STATE',
    alreadyInState: true,
    description: 'El panel ya estaba desarmado'
  }),
  '45': Object.freeze({
    action: 'ARMAR',
    panelStatus: 'ARMADO',
    result: 'EXECUTED',
    alreadyInState: false,
    description: 'Panel armado correctamente'
  }),
  '46': Object.freeze({
    action: 'DESARMAR',
    panelStatus: 'DESARMADO',
    result: 'EXECUTED',
    alreadyInState: false,
    description: 'Panel desarmado correctamente'
  })
});

const CONTACT_ID_EVENTS = Object.freeze({
  '100': { description: 'Médica', category: 'ALARMA', subjectKind: 'ZONA' },
  '101': { description: 'Emergencia personal', category: 'ALARMA', subjectKind: 'ZONA' },
  '102': { description: 'Falta de reporte médico', category: 'ALARMA', subjectKind: 'ZONA' },
  '110': { description: 'Incendio', category: 'ALARMA', subjectKind: 'ZONA' },
  '111': { description: 'Humo', category: 'ALARMA', subjectKind: 'ZONA' },
  '112': { description: 'Combustión', category: 'ALARMA', subjectKind: 'ZONA' },
  '113': { description: 'Flujo de agua', category: 'ALARMA', subjectKind: 'ZONA' },
  '114': { description: 'Temperatura', category: 'ALARMA', subjectKind: 'ZONA' },
  '115': { description: 'Pulsador de incendio', category: 'ALARMA', subjectKind: 'ZONA' },
  '116': { description: 'Ducto', category: 'ALARMA', subjectKind: 'ZONA' },
  '117': { description: 'Llama', category: 'ALARMA', subjectKind: 'ZONA' },
  '118': { description: 'Alarma de proximidad', category: 'ALARMA', subjectKind: 'ZONA' },
  '120': { description: 'Pánico', category: 'ALARMA', subjectKind: 'ZONA' },
  '121': { description: 'Asalto (Duress)', category: 'ALARMA', subjectKind: 'USUARIO' },
  '122': { description: 'Pánico silencioso', category: 'ALARMA', subjectKind: 'ZONA' },
  '123': { description: 'Pánico audible', category: 'ALARMA', subjectKind: 'ZONA' },
  '124': { description: 'Desactivación por asalto', category: 'ALARMA', subjectKind: 'ZONA' },
  '125': { description: 'Activación por asalto', category: 'ALARMA', subjectKind: 'ZONA' },
  '130': { description: 'Robo', category: 'ALARMA', subjectKind: 'ZONA' },
  '131': { description: 'Perimetral', category: 'ALARMA', subjectKind: 'ZONA' },
  '132': { description: 'Interior', category: 'ALARMA', subjectKind: 'ZONA' },
  '133': { description: '24 horas', category: 'ALARMA', subjectKind: 'ZONA' },
  '134': { description: 'Entrada/Salida', category: 'ALARMA', subjectKind: 'ZONA' },
  '135': { description: 'Día/Noche', category: 'ALARMA', subjectKind: 'ZONA' },
  '136': { description: 'Exterior', category: 'ALARMA', subjectKind: 'ZONA' },
  '137': { description: 'Tamper (sabotaje)', category: 'ALARMA', subjectKind: 'ZONA' },
  '138': { description: 'Alarma de proximidad', category: 'ALARMA', subjectKind: 'ZONA' },
  '139': { description: 'Intrusión verificada', category: 'ALARMA', subjectKind: 'ZONA' },
  '140': { description: 'Alarma general', category: 'ALARMA', subjectKind: 'ZONA' },
  '141': { description: 'Circuito abierto', category: 'ALARMA', subjectKind: 'ZONA' },
  '142': { description: 'Circuito en corto', category: 'ALARMA', subjectKind: 'ZONA' },
  '143': { description: 'Fallo en módulo expansor', category: 'ALARMA', subjectKind: 'ZONA' },
  '144': { description: 'Tamper de sensor', category: 'ALARMA', subjectKind: 'ZONA' },
  '145': { description: 'Tamper de módulo expansor', category: 'ALARMA', subjectKind: 'ZONA' },
  '146': { description: 'Robo silencioso', category: 'ALARMA', subjectKind: 'ZONA' },
  '147': { description: 'Fallo de supervisión de zona', category: 'ALARMA', subjectKind: 'ZONA' },
  '150': { description: '24 hs no robo', category: 'ALARMA', subjectKind: 'ZONA' },
  '151': { description: 'Gas', category: 'ALARMA', subjectKind: 'ZONA' },
  '152': { description: 'Refrigeración', category: 'ALARMA', subjectKind: 'ZONA' },
  '153': { description: 'Pérdida de temperatura', category: 'ALARMA', subjectKind: 'ZONA' },
  '154': { description: 'Gotera', category: 'ALARMA', subjectKind: 'ZONA' },
  '155': { description: 'Rotura de cinta metálica', category: 'ALARMA', subjectKind: 'ZONA' },
  '156': { description: 'Fallo de día', category: 'ALARMA', subjectKind: 'ZONA' },
  '157': { description: 'Bajo nivel de gas/combustible', category: 'ALARMA', subjectKind: 'ZONA' },
  '158': { description: 'Alta temperatura', category: 'ALARMA', subjectKind: 'ZONA' },
  '159': { description: 'Baja temperatura', category: 'ALARMA', subjectKind: 'ZONA' },
  '161': { description: 'Pérdida de corriente de aire', category: 'ALARMA', subjectKind: 'ZONA' },
  '162': { description: 'Monóxido de carbono', category: 'ALARMA', subjectKind: 'ZONA' },
  '163': { description: 'Nivel de tanque', category: 'ALARMA', subjectKind: 'ZONA' },
  '300': { description: 'Fallo del sistema', category: 'FALLO', subjectKind: 'ZONA' },
  '301': { description: 'Fallo de 220 V', category: 'FALLO', subjectKind: 'ZONA' },
  '302': { description: 'Batería baja', category: 'FALLO', subjectKind: 'ZONA' },
  '305': { description: 'Reset del sistema', category: 'FALLO', subjectKind: 'ZONA' },
  '309': { description: 'Fallo en test de batería', category: 'FALLO', subjectKind: 'ZONA' },
  '311': { description: 'Batería muerta o en falla', category: 'FALLO', subjectKind: 'ZONA' },
  '400': { description: 'Apertura/cierre especial', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '401': { description: 'Apertura/cierre por usuario', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '402': { description: 'Apertura/cierre especial', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '403': { description: 'Apertura/cierre automático', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '407': { description: 'Apertura/cierre remoto', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '408': { description: 'Armado rápido', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '409': { description: 'Apertura/cierre por keyswitch', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '441': { description: 'Armado STAY', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '456': { description: 'Armado parcial', category: 'APERTURA_CIERRE', subjectKind: 'USUARIO' },
  '601': { description: 'Test manual', category: 'TEST', subjectKind: 'ZONA' },
  '602': { description: 'Test automático', category: 'TEST', subjectKind: 'ZONA' },
  '603': { description: 'Test automático de radio', category: 'TEST', subjectKind: 'ZONA' },
  '608': { description: 'Test automático con falla', category: 'TEST', subjectKind: 'ZONA' },
  '640': { description: 'Botón Vida', category: 'ALARMA', subjectKind: 'USUARIO' }
});

const QUALIFIER_DESCRIPTIONS = Object.freeze({
  '1': 'Evento nuevo/disparo o desactivación',
  '3': 'Restauración o cierre',
  '6': 'Evento anterior'
});

function cleanPacket(input) {
  return String(input ?? '')
    .replace(/\0/g, '')
    .replace(/^\uFEFF/, '')
    .trim();
}

function parseHeartbeat(input) {
  const raw = cleanPacket(input);
  return /^\d{15}$/.test(raw) ? { imei: raw, raw } : null;
}

function normalizeCode(value) {
  const text = String(value ?? '').trim();
  if (/^\d$/.test(text)) return `0${text}`;
  return text.toUpperCase();
}

function parseDisplayNumber(value) {
  return /^\d+$/.test(value || '') ? Number.parseInt(value, 10) : null;
}

function parseContactId(input, options = {}) {
  const raw = String(input || '').trim().toUpperCase();
  const errors = [];

  if (!/^[0-9A-F]{16}$/.test(raw)) {
    errors.push('Debe contener exactamente 16 caracteres hexadecimales');
  }

  const account = raw.slice(0, 4) || null;
  const format = raw.slice(4, 6) || null;
  const qualifier = raw.slice(6, 7) || null;
  const eventCode = raw.slice(7, 10) || null;
  const partition = raw.slice(10, 12) || null;
  const subject = raw.slice(12, 15) || null;
  const checksum = raw.slice(15, 16) || null;

  if (raw.length === 16 && format !== '18') errors.push('El identificador de formato no es 18');
  if (raw.length === 16 && !Object.hasOwn(QUALIFIER_DESCRIPTIONS, qualifier)) {
    errors.push(`Calificador Contact-ID desconocido: ${qualifier || '(vacío)'}`);
  }

  const custom = options.contactIdEventDescriptions || {};
  const known = CONTACT_ID_EVENTS[eventCode] || null;
  const eventDescription = custom[eventCode] || known?.description || `Código Contact-ID ${eventCode || 'desconocido'}`;
  const category = known?.category || 'SIN_CLASIFICAR';
  const subjectKind = known?.subjectKind || 'ZONA_USUARIO';
  const qualifierDescription = QUALIFIER_DESCRIPTIONS[qualifier] || 'Calificador desconocido';

  return {
    valid: errors.length === 0,
    errors,
    raw,
    account,
    format,
    qualifier,
    qualifierDescription,
    eventCode,
    eventDescription,
    qualifiedEventDescription: `${qualifierDescription}: ${eventDescription}`,
    category,
    partition,
    partitionNumber: parseDisplayNumber(partition),
    subject,
    subjectNumber: parseDisplayNumber(subject),
    subjectKind,
    checksum,
    checksumValidated: false
  };
}

function shouldForwardContactEvent(contactId, options = {}) {
  const forwarding = options.appForwarding || {};
  if (forwarding.enabled === false || !contactId.valid) return false;

  const requiredBurglaryEventCodes = [
    '130', '131', '132', '133', '134',
    '135', '136', '137', '138', '139'
  ];
  const eventCodes = [
    ...(forwarding.eventCodes || ['100', '110', '120', '640']),
    ...requiredBurglaryEventCodes,
    '640'
  ]
    .map((value) => String(value).toUpperCase());
  const qualifiers = (forwarding.qualifiers || ['1'])
    .map((value) => String(value).toUpperCase());

  return eventCodes.includes(contactId.eventCode) && qualifiers.includes(contactId.qualifier);
}

function parsePacket(input, options = {}) {
  const raw = cleanPacket(input);
  const startMarker = String(options.startMarker || '$B');
  const endMarker = String(options.endMarker || '$E');
  const errors = [];

  if (!raw) errors.push('Paquete vacío');

  const fields = raw ? raw.split(',').map((value) => value.trim()) : [];

  if (fields[0] !== startMarker) errors.push(`Falta marcador inicial ${startMarker}`);
  if (fields.at(-1) !== endMarker) errors.push(`Falta marcador final ${endMarker}`);
  if (fields.length < 6) errors.push('Cantidad insuficiente de campos');

  const transmitterId = fields[1] || null;
  const sequence = fields[2] || null;
  const deviceTimestampRaw = fields[3] || null;
  const packetType = normalizeCode(fields[4] || '');
  const contactId = parseContactId(fields[5], options);

  if (packetType === '01' && !contactId.valid) {
    errors.push(`Contact-ID inválido: ${contactId.errors.join('; ')}`);
  }

  const imeiCandidates = fields.filter((value) => /^\d{15}$/.test(value));
  const imei = imeiCandidates.at(-1) || null;
  const firmware = fields.find((value) =>
    /^[A-Z0-9]+_[A-Z0-9._-]+$/i.test(value) && !/^\d+$/.test(value)
  ) || null;

  const knownChannels = new Set(['2G', '3G', '4G', '5G', 'GPRS', 'LAN', 'ETH', 'WIFI', 'SMS']);
  const channel = fields.findLast((value) => knownChannels.has(String(value).toUpperCase())) || null;
  const packetTypes = { ...PACKET_TYPE_DESCRIPTIONS, ...(options.packetTypeDescriptions || {}) };
  const appForwarding = shouldForwardContactEvent(contactId, options);
  const panelResult = GPRS_PANEL_RESULTS[packetType] || null;

  return {
    valid: errors.length === 0,
    errors,
    raw,
    fields,
    transmitterId,
    protocol: transmitterId,
    sequence,
    deviceTimestampRaw,
    packetType: packetType || null,
    packetTypeDescription: packetTypes[packetType] || `Tipo de paquete ${packetType || 'desconocido'}`,
    contactId,
    eventCode: contactId.valid ? contactId.eventCode : (packetType || null),
    eventDescription: contactId.valid
      ? contactId.qualifiedEventDescription
      : (packetTypes[packetType] || `Tipo de paquete ${packetType || 'desconocido'}`),
    abonado: contactId.valid ? contactId.account : null,
    imei,
    firmware,
    channel,
    gprsPanelResult: panelResult ? { code: packetType, ...panelResult } : null,
    shouldForwardToApp: appForwarding
  };
}

function formatAckTimestamp(date = new Date(), timeZone = 'America/Argentina/Buenos_Aires') {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date);
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.day}/${value.month}/${value.year}-${value.hour}:${value.minute}`;
}

function buildAck(packet, options = {}) {
  if (!packet?.transmitterId || !packet?.sequence) return null;
  const startMarker = String(options.startMarker || '$B');
  const endMarker = String(options.endMarker || '$E');
  const timestamp = formatAckTimestamp(options.now || new Date(), options.timeZone);
  const lineEnding = options.lineEnding || '';
  const action = String(options.action || '').trim().toUpperCase();
  const actionField = action ? `${action},` : '';
  return `${startMarker},${packet.transmitterId},TI=${timestamp},ACK=${packet.sequence},${actionField}${endMarker}${lineEnding}`;
}

module.exports = {
  CONTACT_ID_EVENTS,
  GPRS_PANEL_RESULTS,
  PACKET_TYPE_DESCRIPTIONS,
  QUALIFIER_DESCRIPTIONS,
  buildAck,
  cleanPacket,
  formatAckTimestamp,
  parseHeartbeat,
  parseContactId,
  parsePacket,
  shouldForwardContactEvent
};
