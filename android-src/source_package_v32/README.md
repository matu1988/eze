# NanoSmart Eventos v32 — rendimiento multipanel

## Optimización v32 (versión 3.8.0)

- Mis paneles recicla tarjetas y crea solamente los elementos visibles.
- Los paneles quedan indexados en memoria por IMEI para que cada push no vuelva a convertir y recorrer todo el JSON.
- Las ráfagas de avisos se agrupan antes de refrescar la interfaz.
- Mientras la aplicación está abierta no se generan notificaciones redundantes en la barra de Android.
- La consulta de estado se realiza sólo para paneles visibles, con un máximo de tres consultas simultáneas.
- Al editar un panel, el registro push y la actualización del nombre afectan solamente a ese panel.

La versión estable anterior 3.7.4 permanece separada en `source_package_v31`.

## Corrección v31

- Restablece un contorno visible y respuesta de pulsación en los botones inferiores.
- Conserva el centrado conjunto de cada ícono con su texto.

## Corrección v30

- Usa controles con centrado específico para mantener cada ícono junto a su texto en equipos Samsung.

## Ajuste v29

- Los íconos de Mis paneles e Histórico quedan centrados junto a sus textos.

## Corrección v28

- Los botones inferiores respetan automáticamente la barra de navegación del teléfono.
- La separación se aplica a todas las pantallas y se adapta al girar el dispositivo.

## Novedades v27

- Pantalla principal renovada con tarjetas, estados destacados e iconografía.
- Animación e iluminación al presionar controles.
- Vibración y sonido diferenciados para navegación, armado/desarmado y emergencias.
- Lista de paneles e histórico alineados con el nuevo diseño.
- Conserva GPRS, pánicos con ubicación, push, sirena persistente, QR y seguridad de acceso.

Variante independiente de la aplicación NanoSmart RN41, preparada para convivir
con la aplicación original en el mismo teléfono.

## Identidad Android

- Nombre visible: `NanoSmart Eventos`
- Application ID: `com.nanocomm.nanosmart.eventos`
- Preferencias: `nanosmart_eventos_prefs`

La aplicación original utiliza `com.nanocomm.m41sms`, por lo que Android trata
ambas instalaciones como aplicaciones diferentes. Cada una conserva su propia
configuración, permisos y estado.

## Abrir y ejecutar

1. Abrir esta carpeta con Android Studio.
2. Esperar la sincronización de Gradle.
3. Conectar el teléfono y seleccionar **Run app**.
4. Android instalará `NanoSmart Eventos` sin reemplazar `NanoSmart RN41`.

Esta variante elimina el armado/desarmado por SMS dentro de NanoSmart Eventos.
La aplicación RN41 original sigue instalada y conserva su funcionamiento
independiente.

## Varios paneles

- La pantalla inicial muestra todos los paneles vinculados al celular.
- Cada panel conserva nombre, abonado, IMEI, token, estado y configuración UDP.
- Al pulsar **Abrir** se reutiliza el menú completo de armado, desarmado,
  emergencias y alertas para el panel seleccionado.
- **Agregar panel** incorpora otro RN41 sin borrar el anterior.
- La configuración existente de v17 se migra automáticamente como primer panel.
- Un mismo token FCM se registra con todos los paneles. Las notificaciones incluyen
  el IMEI y abren directamente el panel que produjo el evento.

## Modo de servicio

- Cada panel permite elegir **Automonitoreo** o **Monitoreo** desde un desplegable.
- En Automonitoreo se ocultan abonado, IP, puerto, ID y clave M41. Médica, Pánico e Incendio
  se envían solamente a NanoSmart Server y a los celulares vinculados.
- En Monitoreo se muestran los campos UDP y se conserva el envío al software de
  monitoreo, además del registro y las notificaciones del servidor.
- Los paneles guardados por versiones anteriores se mantienen en modo Monitoreo.
- La pantalla de edición usa un único bloque UDP para mostrar u ocultar los campos,
  manteniendo compatibilidad con configuraciones guardadas por versiones anteriores.

## Vinculación mediante QR

- Al agregar un panel, el cliente completa el nombre del panel y el nombre de la persona.
- **Escanear QR del equipo** lee la etiqueta generada por NanoSmart Server y carga
  automáticamente el IMEI y el token individual de ese celular.
- El mismo QR sirve para Automonitoreo y Monitoreo. En Monitoreo permanecen los
  campos abonado, IP, puerto, ID y clave M41.
- La carga manual de IMEI y token continúa disponible como respaldo.
- Cada escaneo crea un token diferente, por lo que cada persona conserva su
  identificación y puede revocarse sin afectar a los demás celulares.

## Protección de acceso

- Al abrir la app se solicita huella, reconocimiento facial o el PIN, patrón o
  contraseña configurados en el teléfono.
- La sesión se bloquea después de permanecer 30 segundos en segundo plano.
- **Desarmar** siempre solicita una nueva verificación de identidad.
- Armar y los botones de emergencia utilizan la sesión que ya fue desbloqueada.
- La pantalla queda excluida de capturas y de la vista previa de aplicaciones recientes.
- Si el teléfono no tiene un bloqueo seguro configurado, la app no permite el acceso
  y ofrece abrir la configuración de seguridad de Android.

## Ubicación en emergencias

- Al pulsar **Médica**, **Pánico** o **Incendio**, la app adjunta la última
  ubicación disponible del teléfono si fue obtenida durante los últimos 10 minutos.
- El permiso de ubicación es opcional: si fue rechazado, el GPS está apagado o
  no existe una posición reciente, la emergencia se envía igualmente.
- El receptor guarda coordenadas, precisión y hora junto con el evento y las
  reenvía a todos los celulares vinculados al mismo IMEI.
- El push y la tarjeta de la alerta muestran **Ver ubicación**, que abre Google Maps.
- La app no rastrea a la persona en segundo plano ni solicita ubicación permanente.

## Personalización de zonas

- Al editar un panel aparecen las solapas **Configuración del panel** y
  **Personalización de zonas**.
- Se puede asignar un nombre opcional a cada zona del 1 al 16.
- Los nombres se guardan en NanoSmart Server por IMEI, no dentro de un solo
  teléfono. Cualquier celular vinculado al mismo equipo ve la misma configuración.
- Las alertas de robo muestran el nombre y conservan el número como referencia,
  por ejemplo: `Puerta principal (Zona 1)`.

## Alertas e histórico

- La pantalla principal muestra solamente las 5 alertas más recientes del panel.
- El botón **Histórico** reemplaza a **Configuración** dentro del panel y permite
  consultar hasta las últimas 30 alertas.
- La configuración continúa disponible desde **Mis paneles > Editar**.
- El histórico conserva los nombres de zonas, quién ejecutó la acción y el botón
  para abrir Google Maps cuando la emergencia incluye ubicación.

## Armado y desarmado

- Los botones **Armar** y **Desarmar** llaman al servidor mediante el token del
  celular.
- El equipo mantiene disponible su IP y puerto enviando solamente su IMEI cada
  minuto. La app no recibe ni genera ese heartbeat.
- Si el receptor todavía no conoce una IP y un puerto recientes, conserva la
  orden y espera el siguiente heartbeat del equipo.
- El servidor obtiene el IMEI asociado al token, envía la consulta DI01 al
  equipo y agrega `ARMAR` o `DESARMAR` en el ACK del siguiente reporte numerado.
- La app espera hasta 90 segundos la entrega. La orden permanece hasta 180
  segundos en el servidor antes de vencer, permitiendo varios heartbeats.
- Después de incluir la acción en el ACK, la app espera la confirmación real del
  panel: evento `45` armado, `46` desarmado, `43` ya estaba armado o `44` ya
  estaba desarmado.
- Recién al recibir uno de esos eventos se actualiza el estado A/D y el servidor
  envía por FCM el resultado exacto a todos los celulares vinculados al IMEI.
- Los mismos eventos actualizan la pantalla aunque la acción se haya realizado
  directamente desde el teclado del panel y no exista una orden iniciada por la app.
- Con la app abierta, el estado también se consulta cada 30 segundos como respaldo
  si la notificación push se demora o Firebase no está disponible.
- La configuración solicita el nombre de la persona o del celular. Ese nombre
  queda asociado al token individual y aparece en armado, desarmado, Médica,
  Pánico e Incendio.
- Los tres botones de emergencia conservan el paquete UDP original enviado al
  software de monitoreo usando ID, clave, IP y puerto configurados.
- En paralelo, la app informa la acción a la API autenticada para registrar el
  nombre del celular que ejecutó Médica, Pánico o Incendio.
- La app no contiene receptor SMS ni solicita permisos de SMS.

## Servidor público configurado

La aplicación consulta internamente:

```text
http://54.232.115.106:18082
```

El host y el puerto no se muestran en la interfaz. Esta versión mantiene HTTP
solamente para la prueba inicial en AWS; antes de la operación comercial debe
migrarse a un dominio con HTTPS por el puerto 443.

## Alertas del servidor

- La API está configurada internamente para la IP pública de AWS en el puerto
  HTTP `18082`; no se muestra ni se modifica desde la interfaz. Los
  puertos `8081` y `8082` quedan fuera de NanoSmart para evitar el servicio
  Jetty detectado.
- En **Configuración** se debe ingresar el token individual generado para ese
  celular.
- El IMEI configurado corresponde al equipo NanoSmart que reporta los paquetes,
  no al teléfono.
- La pantalla principal consulta `GET /api/app/alerts` cada 30 segundos mientras
  la aplicación está abierta.
- Firebase asigna automáticamente un token push único a cada instalación y la
  app lo registra en `POST /api/app/push-token` usando el token NanoSmart ya
  configurado.
- Con la app minimizada o cerrada no se realizan consultas periódicas: el
  servidor envía la alerta mediante FCM y Android muestra una notificación.
- En Android 13 o superior se debe aceptar el permiso **Notificaciones**.
- Los eventos `100` (Médica), `110` (Incendio), `120` (Pánico) y todos los
  robos Contact ID `130` a `139` activan una alarma sonora y vibración
  repetitivas cuando NanoSmart está minimizada, cerrada o el teléfono está
  bloqueado.
- La alarma utiliza la sirena personalizada incluida en la aplicación y la
  reproduce en bucle. Si un teléfono no pudiera reproducirla, usa el tono de
  alarma del sistema como respaldo.
- La alarma permanece activa hasta que el usuario abre NanoSmart. Las
  confirmaciones de armado/desarmado y los restantes eventos sólo generan la
  notificación normal.
- La primera consulta obtiene el historial reciente y las siguientes envían
  `afterId`, por lo que solamente descargan alertas nuevas.
- El servidor entrega una respuesta compacta sin paquete crudo ni campos que la
  pantalla no utiliza, reduciendo el consumo de datos móviles.
- Las alertas se muestran en **Alertas recibidas**. Las nuevas alertas generan
  una notificación. En las cuatro categorías críticas anteriores, la app inicia
  además la alarma persistente. Si Android fue detenido mediante **Forzar
  detención**, hay que abrir nuevamente la app para que el sistema reactive la
  recepción.

Para esta prueba, AWS Security Groups y el firewall de Windows deben permitir
el puerto TCP `18082`. El teléfono puede utilizar Wi-Fi o datos móviles.

Los botones Médica, Pánico e Incendio envían el paquete al destino UDP del
software de monitoreo y registran por HTTP quién lo ejecutó. Si una de las dos
vías falla, la app informa claramente cuál de ellas no pudo completarse.
