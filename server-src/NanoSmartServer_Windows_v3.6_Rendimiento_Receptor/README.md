# NanoSmart Server para Windows v3.6 — rendimiento optimizado

## Nuevo en v3.6

- La autenticación de la app y la búsqueda de celulares por IMEI ahora usan
  índices directos: no recorren toda la base en cada consulta.
- El registro de equipos agrupa escrituras cercanas y conserva una copia
  `registry.json.bak` antes de guardar.
- Los eventos se escriben en segundo plano para no frenar la recepción UDP ni
  el envío del ACK.
- El historial mantiene índices separados por IMEI y abonado.
- Firebase limita globalmente los envíos simultáneos (20 por defecto), evitando
  una avalancha de conexiones si muchos equipos reportan al mismo tiempo.
- Los tokens FCM vencidos se retiran en un solo guardado agrupado.
- Se agregaron `/api/app/batch/status` y `/api/app/batch/push-token` para que una
  app multipanel pueda consultar o registrar muchos paneles en una llamada.
- La limpieza de paquetes duplicados dejó de recorrer la tabla completa con
  cada datagrama.

Los puertos, el protocolo GPRS, los ACK, la administración, Producción y las
rutas anteriores no cambian. El APK existente sigue siendo compatible.

Los valores recomendados ya están incluidos en `config.json`:

```json
{
  "http": { "maxBatchItems": 500 },
  "storage": { "registryFlushMs": 250, "asyncEventPersistence": true },
  "firebase": { "maxConcurrentSends": 20 }
}
```

La prueba totalmente simulada se puede repetir con:

```text
node tools\stress-simulated.js
```

No utiliza Firebase real ni envía notificaciones a celulares.

## Nuevo en v3.5

- Cada IMEI de producción queda asociado a un cliente.
- La administración local del receptor permite cargar directamente la tabla
  `clients` de `support.db` desde `http://localhost:18082/admin`.
- Todos los clientes importados comienzan habilitados para recibir en la app
  los eventos Contact-ID de robo `130` a `139`.
- Al destildar un cliente, esos robos no se envían por push ni aparecen en el
  historial de la app para sus equipos. El receptor conserva los eventos.
- `Automonitoreo` está incluido siempre y no puede deshabilitarse.
- Una nueva importación actualiza los datos sin volver a habilitar clientes que
  ya fueron destildados.
- NanoSmart Producción solamente consulta esta base y asocia Cliente → IMEI.

Receptor para paquetes NanoSmart/M41 `$B ... $E`, heartbeat IMEI sin ACK,
armado/desarmado por GPRS, respuesta ACK,
interpretación Contact-ID, separación de equipos por IMEI, historial y una cola
de alertas, vinculación segura de varios celulares a cada IMEI y envío de
notificaciones push mediante Firebase Cloud Messaging (FCM), tanto para
Android como para iPhone a través de APNs.

La versión 3.3 separa la aplicación externa del sector de producción:
al escanear un IMEI crea dos vinculaciones móviles independientes, genera dos
QR distintos y permite imprimir dos etiquetas de 50 × 25 mm. Esta API escucha
únicamente en TCP `18083`; las apps móviles permanecen en TCP `18082`.

Esta variante está preparada para la IP pública `54.232.115.106` en una
instancia Windows de AWS. El servidor continúa escuchando en `0.0.0.0`, por lo
que no es necesario escribir la IP pública dentro de `config.json`.

## Obtener el token del celular en un solo paso

Desde RDP abrir `http://localhost:18082/admin`. En la sección **Crear celular y
obtener token** ingresar:

- nombre del celular;
- IMEI de 15 dígitos del equipo NanoSmart.

Al pulsar **Generar token**, el servidor habilita el IMEI si es nuevo, crea la
instalación y muestra directamente el token largo que debe pegarse en el campo
**Token** de la app. Ya no hace falta generar primero una clave `NS-...` ni
ejecutar comandos `curl`.

## Aplicación externa de producción

1. Iniciar esta versión del receptor.
2. Ejecutar `ABRIR_SOLO_PUERTO_PRODUCCION_18083_ADMIN.bat` como administrador.
3. En AWS crear la regla TCP `18083` con origen limitado a la IP pública de la
   fábrica en formato `IP/32`. Nunca usar `0.0.0.0/0` para este puerto.
4. Ejecutar `MOSTRAR_CLAVE_PRODUCCION.bat` en la computadora del receptor.
5. Copiar únicamente la clave `NSP-...` en **NanoSmart Producción v1.2**.
6. Seleccionar la impresora de etiquetas instalada en Windows.
7. Escanear el IMEI de 15 dígitos y pulsar **Generar dos QR**.

El receptor guarda dos cupos fijos, **Mobile 1** y **Mobile 2**, cada uno con
una clave y un QR diferentes. La aplicación NanoSmart Eventos conserva el mismo
formato QR `NS1|IMEI|CLAVE`; por eso no hace falta actualizar Android para usar
esta función.

Si un mismo IMEI se intenta preparar nuevamente, el servidor responde con una
advertencia y sólo renueva los QR cuando el operario lo confirma. Las claves
reales de los QR no quedan guardadas en texto legible: el receptor conserva
únicamente sus hashes. Cada uso de un QR renueva el token de ese cupo sin tocar
el otro usuario móvil.

## Puertos en AWS

En el Security Group de la instancia se deben agregar estas entradas:

| Tipo | Protocolo | Puerto | Origen inicial |
| --- | --- | ---: | --- |
| UDP personalizado | UDP | 7050 | `0.0.0.0/0` |
| TCP personalizado | TCP | 18082 | `0.0.0.0/0` para las apps móviles durante esta fase |
| TCP personalizado | TCP | 18083 | `IP_PUBLICA_FABRICA/32` exclusivamente |

Después, dentro de Windows, ejecutar como administrador
`ABRIR_PUERTOS_FIREWALL_AWS_ADMIN.bat`.

Con `restrictAdministrationToPrivateNetworks` activo, desde Internet solamente
se permiten `/api/app/*`, `/api/app/register` y una respuesta reducida de
`/api/health` en TCP `18082`. Las rutas `/api/production/*` fueron retiradas de
ese puerto y solamente existen en TCP `18083`, donde requieren la clave
dedicada `X-NanoSmart-Production-Key`. El panel, el historial y la administración se utilizan mediante
RDP abriendo `http://localhost:18082` dentro del servidor.

Las APIs todavía utilizan HTTP. La restricción `IP/32` evita que otras
direcciones lleguen al servicio de producción, pero la etapa definitiva debe
usar además HTTPS o VPN. Las apps móviles también deberán publicarse mediante
HTTPS por TCP `443` y luego cerrar el acceso público directo a TCP `18082`.

El armado y desarmado se realizan desde la app por la API del receptor. El
receptor conserva la orden, localiza al equipo mediante su heartbeat de 15
dígitos, envía `DI01` y agrega `ARMAR` o `DESARMAR` al ACK de la respuesta.
La orden queda esperando hasta que el panel informa `45` (armado), `46`
(desarmado), `43` (ya estaba armado) o `44` (ya estaba desarmado).
Esos eventos también actualizan el último estado conocido y notifican a los
celulares cuando la acción fue realizada directamente desde el teclado.

Cada token conserva el nombre de su teléfono o usuario. Las órdenes de armado y
desarmado confirmadas indican quién las solicitó; cuando no hay una orden de app
asociada, el origen se informa como **Teclado del panel**. Los botones Médica,
Pánico e Incendio utilizan la API autenticada y también registran el nombre del
celular que los ejecutó.

En las emergencias iniciadas desde la app, el receptor acepta opcionalmente
latitud, longitud, precisión y hora de captura. Esos datos se conservan con el
evento y se incluyen en la API y en Firebase para que los celulares vinculados
puedan abrir la posición directamente en Google Maps. Una ubicación ausente o
inválida nunca impide registrar ni distribuir la emergencia.

## Qué incluye

- Receptor UDP en el puerto `7050`, separado de la API HTTP.
- Recepción del heartbeat compuesto solamente por el IMEI de 15 dígitos, sin ACK.
- Registro en memoria del último IP y puerto UDP observado para cada IMEI.
- Cola de una orden activa por equipo, con espera automática del siguiente heartbeat.
- Consulta `$B,DI01,MIC=0,$E` y entrega mediante ACK con `ARMAR` o `DESARMAR`.
- Confirmación real por eventos 43–46, diferenciando ejecución y estado previo.
- Reintento de DI01 en un heartbeat posterior si el equipo no respondió.
- Panel de diagnóstico en vivo con IMEI, IP, puerto, cantidad de heartbeats,
  última recepción, antigüedad, conectividad y estado de la última orden.
- API y panel web en el puerto TCP `18082`; los puertos `8081` y `8082`,
  utilizados por Jetty, no son utilizados por NanoSmart.
- Servicio HTTP independiente en TCP `18083` que sólo expone las dos rutas de
  producción y no ofrece rutas móviles ni administrativas.
- ACK automático devuelto a la IP y puerto de origen de cada paquete.
- Fecha del ACK en la zona horaria `America/Argentina/Buenos_Aires`.
- Parser Contact-ID `SSSS 18 Q XYZ GG CCC K`.
- Identificación independiente de cada equipo mediante su IMEI de 15 dígitos.
- Interpretación de abonado, calificador, evento, partición y zona/usuario.
- Registro persistente en archivos JSONL diarios dentro de `data`.
- Detección de retransmisiones: vuelve a responder el ACK, pero no guarda ni
  publica dos veces el mismo evento.
- Reenvío a las apps de `100`, `110`, `120` y todos los eventos de robo
  `130` a `139`, solamente con calificador `1`.
- Los códigos `130` a `139` se incorporan automáticamente aunque se conserve un
  `config.json` anterior, para mantener puertos y credenciales sin perder eventos.
- Un mismo token FCM puede quedar asociado a instalaciones de distintos IMEI. Esto
  permite que un celular reciba eventos de todos sus paneles sin duplicar alertas
  dentro de un mismo panel.
- Panel web en vivo y API HTTP por IMEI.
- Simulador UDP que muestra el ACK recibido.
- Clave aleatoria independiente para cada IMEI; en disco sólo se guarda su hash.
- Token de autorización individual para cada celular vinculado.
- Varios celulares pueden consultar las alertas del mismo IMEI.
- Un celular no puede consultar eventos pertenecientes a otro IMEI.
- Respuesta móvil compacta, sin paquete crudo, payload ni datos internos.
- Consulta incremental mediante `afterId` para no descargar alertas repetidas.
- Registro automático del token FCM individual de cada instalación Android.
- Registro de la plataforma de cada instalación (`ANDROID` o `IOS`).
- Notificaciones para iPhone mediante Firebase Cloud Messaging y APNs.
- Mensajes Android de alta prioridad y sólo datos, para que la aplicación pueda
  activar en segundo plano la alarma sonora de Médica, Incendio, Pánico y Robo.
- Envío push a todos los celulares activos vinculados al IMEI del evento.
- Eliminación automática de tokens FCM vencidos o desinstalados.
- Pantalla de administración y simulador web de celulares.
- API de producción protegida por una clave dedicada, separada de los tokens de
  los celulares y de Firebase.
- Bloqueo temporal por IP después de diez intentos incorrectos dentro de cinco
  minutos, con auditoría de rechazos y tiempos de espera HTTP reducidos.
- Alta atómica de dos cupos móviles independientes por IMEI, con auditoría en
  `data\production-audit.jsonl`.
- Generación de dos imágenes QR listas para etiquetas de 50 × 25 mm.

## Requisito

Instalar **Node.js 24 LTS** desde <https://nodejs.org/>. No hay que ejecutar
`npm install`: el proyecto utiliza únicamente módulos incluidos en Node.js.

Para activar FCM también se necesita una clave privada de cuenta de servicio del
proyecto Firebase `nanosmart-eventos`. Esa clave se guarda sólo en la PC Windows
y nunca debe copiarse a la APK.

## Activar Firebase en Windows

1. En Firebase Console abrir **Configuración del proyecto**.
2. Entrar en **Cuentas de servicio**.
3. Pulsar **Generar nueva clave privada** y descargar el JSON.
4. Ejecutar `CONFIGURAR_FIREBASE.bat` y arrastrar ese archivo a la ventana.
5. Reiniciar `INICIAR_SERVIDOR.bat`.
6. Abrir <http://localhost:18082/api/health> y comprobar:

   ```json
   "firebasePush":{"enabled":true,"ready":true}
   ```

El JSON privado termina guardado como
`secrets\firebase-service-account.json`. La carpeta `secrets` no debe
publicarse ni compartirse.

Después de instalar Node.js, cerrar y volver a abrir las ventanas de comandos.

## Primera prueba en la misma PC

1. Descomprimir el ZIP en una ruta corta, por ejemplo:

   ```text
   C:\NanoSmartServer
   ```

2. Ejecutar `INICIAR_SERVIDOR.bat`.
3. Se abrirá el panel en <http://localhost:18082>.
4. Sin cerrar el servidor, ejecutar `PROBAR_RECEPCION.bat`.
5. La prueba debe mostrar un ACK con este formato:

   ```text
   $B,TM10,TI=DD/MM/AAAA-HH:mm,ACK=15,$E
   ```

6. El evento debe aparecer en el panel con IMEI, abonado, código `401`,
   partición `00` y usuario `040`.

## Vincular celulares de prueba

1. Abrir <http://localhost:18082/admin>.
2. Ingresar el IMEI de 15 dígitos y pulsar **Generar clave**.
3. Guardar la clave `NS-XXXX-XXXX-XXXX-XXXX`; el servidor no puede volver a
   mostrarla porque almacena únicamente su hash.
4. Abrir <http://localhost:18082/app-simulator>.
5. Ingresar nombre del celular, IMEI y clave.
6. Repetir el proceso con otro nombre y la misma combinación IMEI + clave para
   simular un segundo celular.
7. Al seleccionar cualquiera de los dos perfiles, ambos deben ver las alertas
   correspondientes a ese IMEI.

Desde Administración se pueden consultar y revocar celulares individualmente.
Renovar la clave evita nuevas vinculaciones con la anterior, pero no desconecta
los celulares que ya estaban autorizados.

## Recibir desde equipos de la red local

1. En la PC receptora ejecutar `ipconfig` y anotar la dirección IPv4.
2. Ejecutar `ABRIR_PUERTOS_FIREWALL_ADMIN.bat` con clic derecho y
   **Ejecutar como administrador**.
3. Configurar el equipo para enviar UDP a la IPv4 de la PC, puerto `7050`.
4. El servidor responderá el ACK al puerto de origen de cada equipo.

El heartbeat especial debe contener únicamente el IMEI, por ejemplo:

```text
000001278272947
```

Este mensaje actualiza el IP y puerto del equipo y deliberadamente no recibe
ACK. Si existe una orden pendiente, el servidor envía inmediatamente:

```text
$B,DI01,MIC=0,$E
```

Cuando el equipo responde con su paquete numerado, el ACK toma una de estas
formas:

```text
$B,2010,TI=DD/MM/AAAA-HH:mm,ACK=04,ARMAR,$E
$B,2010,TI=DD/MM/AAAA-HH:mm,ACK=05,DESARMAR,$E
```

Desde otra PC de la misma red, el panel se abre con:

```text
http://IP-DE-LA-PC:18082
```

Las reglas incluidas se aplican solamente al perfil de red **Privado** de
Windows.

## Interpretación Contact-ID

Ejemplo recibido:

```text
621118340100040B
```

Se interpreta como:

```text
6211 | 18 | 3 | 401 | 00 | 040 | B
```

| Campo | Valor |
| --- | --- |
| Abonado | `6211` |
| Formato | `18` |
| Calificador | `3` — restauración o cierre |
| Evento | `401` — apertura/cierre por usuario |
| Partición | `00` |
| Usuario | `040` |
| Checksum recibido | `B` |

El checksum se extrae y almacena, pero todavía no se valida matemáticamente.

## Configuración del control, ACK, alertas y Firebase

En `config.json`:

```json
"commands": {
  "enabled": true,
  "inquiryPayload": "$B,DI01,MIC=0,$E",
  "endpointMaxAgeSeconds": 90,
  "inquiryRetrySeconds": 45,
  "commandTtlSeconds": 180
},
"ack": {
  "enabled": true,
  "timeZone": "America/Argentina/Buenos_Aires",
  "lineEnding": ""
},
"appForwarding": {
  "enabled": true,
  "qualifiers": ["1"],
  "eventCodes": [
    "100", "110", "120",
    "130", "131", "132", "133", "134",
    "135", "136", "137", "138", "139"
  ]
}
```

La sección Firebase incluida es:

```json
"firebase": {
  "enabled": true,
  "projectId": "nanosmart-eventos",
  "serviceAccountFile": "secrets/firebase-service-account.json",
  "timeoutMs": 10000
}
```

Significado inicial:

- `100`: Médica.
- `110`: Incendio.
- `120`: Pánico.
- `130`: Robo.
- `131`: Perimetral.
- `132`: Interior.
- `133`: 24 horas.
- `134`: Entrada/Salida.
- `135`: Día/Noche.
- `136`: Exterior.
- `137`: Tamper (sabotaje).
- `138`: Alarma de proximidad.
- `139`: Intrusión verificada.

Los códigos se pueden ampliar en el archivo de configuración. Cada evento
seleccionado se guarda y se envía por FCM únicamente a las instalaciones
vinculadas al IMEI que originó el paquete.

## API disponible

| Ruta | Función |
| --- | --- |
| `GET /api/health` | Estado UDP/HTTP, ACK y contadores |
| `GET /api/gprs/endpoints` | Diagnóstico local de heartbeat, IP, puerto y órdenes por IMEI |
| `GET /api/events?take=100` | Últimos eventos de todos los equipos |
| `GET /api/events?imei=...` | Eventos filtrados por IMEI |
| `GET /api/devices` | Equipos detectados por IMEI |
| `GET /api/devices/{IMEI}/status` | Último estado de comunicación del equipo |
| `GET /api/devices/{IMEI}/events` | Historial completo del equipo |
| `GET /api/devices/{IMEI}/alerts` | Eventos seleccionados para su futura app |
| `GET /api/stream` | Eventos en vivo para el panel |
| `POST /api/admin/devices/{IMEI}/access-key` | Generar o renovar la clave del equipo |
| `POST /api/admin/installations` | Crear un celular y devolver directamente su token |
| `GET /api/admin/devices/{IMEI}/installations` | Celulares vinculados |
| `POST /api/admin/installations/{ID}/revoke` | Revocar un celular |
| `GET /api/production/health` | Probar la clave en el servicio separado TCP `18083` |
| `POST /api/production/pairings` | Crear o reemitir los dos QR móviles mediante TCP `18083` |
| `POST /api/app/register` | Vincular una instalación con IMEI + clave |
| `GET /api/app/me` | Datos de la instalación autenticada |
| `GET /api/app/device/status` | Estado del equipo autorizado |
| `GET /api/app/device/zones` | Nombres compartidos de las zonas 1 a 16 del IMEI autorizado |
| `PUT /api/app/device/zones` | Guardar los nombres de zonas para todos los celulares del mismo IMEI |
| `POST /api/app/device/command` | Crear una orden `ARMAR` o `DESARMAR` |
| `GET /api/app/device/commands/{ID}` | Consultar entrega o vencimiento de la orden |
| `GET /api/app/alerts` | Alertas del IMEI autorizado |
| `POST /api/app/push-token` | Registrar o actualizar el token FCM y su plataforma (`ANDROID`/`IOS`) |

Las rutas antiguas por abonado continúan disponibles para diagnóstico, pero el
identificador principal es el IMEI.

## Etiqueta QR de vinculación

- Desde `/admin` se genera o renueva la clave del IMEI y se obtiene una etiqueta
  lista para imprimir en 50 × 25 mm.
- El QR contiene la versión del formato, el IMEI y la clave de vinculación del
  equipo. No contiene el token final de ningún celular.
- La app canjea esa clave mediante `POST /api/app/register`; el servidor crea un
  token independiente para cada teléfono.
- Renovar la etiqueta impide nuevas altas con el QR anterior, sin revocar los
  teléfonos que ya estaban vinculados.
- En Automonitoreo, las emergencias enviadas desde la app utilizan el último
  abonado aprendido por el receptor desde los paquetes del panel.

## Datos guardados

Los registros se crean automáticamente así:

```text
data\events-AAAA-MM-DD.jsonl
```

Cada línea contiene el paquete original, Contact-ID interpretado, IMEI, ACK y la
decisión de si corresponde generar una alerta para la app.

Las vinculaciones se guardan en:

```text
data\registry.json
```

El archivo conserva hashes de claves y tokens, no las credenciales originales.
Se crea también `registry.json.bak` como respaldo de la versión anterior.

La clave exclusiva de la estación de producción se genera automáticamente en:

```text
data\production-key.txt
```

No debe colocarse esa clave en un QR ni entregarse al cliente final. Las altas y
reimpresiones quedan registradas sin guardar las claves QR originales en:

```text
data\production-audit.jsonl
```

## Seguridad de esta fase

Las rutas móviles requieren un token individual y la estación de producción usa
una clave dedicada. Producción está aislada en TCP `18083`, debe restringirse a
la IP pública de la fábrica y bloquea intentos repetidos. La pantalla y API de
administración siguen limitadas a conexiones privadas o locales.

La comunicación continúa siendo HTTP: la separación de puertos y el Security
Group reducen fuertemente la exposición, pero no reemplazan el cifrado. Para la
operación definitiva se recomienda HTTPS por TCP `443` o una VPN entre fábrica
y AWS. La regla TCP `18083` nunca debe utilizar origen `0.0.0.0/0`.

## Pruebas del código

Desde una terminal abierta dentro de la carpeta:

```text
npm test
```

Las pruebas comprueban el parser, ACK exacto, registro por IMEI, duplicados,
claves protegidas, varios celulares por equipo, revocación y aislamiento entre
IMEIs. No descargan dependencias.
