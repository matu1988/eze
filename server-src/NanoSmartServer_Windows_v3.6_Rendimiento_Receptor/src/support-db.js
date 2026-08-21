'use strict';

const { DatabaseSync } = require('node:sqlite');

const CLIENT_QUERY = `
  SELECT
    CAST(id AS TEXT) AS externalId,
    COALESCE(cliente, '') AS name,
    COALESCE(descripcion, '') AS description,
    COALESCE(ca, '') AS account,
    COALESCE(pais, '') AS country,
    COALESCE(tipo_receptor, '') AS receiverType,
    COALESCE(contacto, '') AS contact,
    COALESCE(telefono, '') AS phone,
    COALESCE(direccion, '') AS address,
    COALESCE(cuit, '') AS cuit,
    COALESCE(ciudad, '') AS city,
    COALESCE(provincia, '') AS province,
    COALESCE(telefono2, '') AS phone2,
    COALESCE(vendedor, '') AS seller
  FROM clients
  WHERE TRIM(COALESCE(cliente, '')) <> ''
  ORDER BY cliente COLLATE NOCASE, id
`;

function readSupportClients(databasePath) {
  const database = new DatabaseSync(databasePath, { readOnly: true });
  try {
    return database.prepare(CLIENT_QUERY).all().map((row) => ({
      externalId: String(row.externalId || ''),
      name: String(row.name || ''),
      description: String(row.description || ''),
      account: String(row.account || ''),
      country: String(row.country || ''),
      receiverType: String(row.receiverType || ''),
      contact: String(row.contact || ''),
      phone: String(row.phone || ''),
      address: String(row.address || ''),
      cuit: String(row.cuit || ''),
      city: String(row.city || ''),
      province: String(row.province || ''),
      phone2: String(row.phone2 || ''),
      seller: String(row.seller || '')
    }));
  } finally {
    database.close();
  }
}

module.exports = { readSupportClients };
