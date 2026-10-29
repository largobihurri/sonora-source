# Política de privacidad de Sonora

Fecha de entrada en vigor: 29 de octubre de 2026

Sonora es un reproductor de música para los archivos de audio de tu dispositivo. No tiene cuentas, ni anuncios, ni
analítica, ni seguimiento. El desarrollador no tiene ningún servidor para Sonora y no recibe ningún dato de la app.

Esta política cubre dos versiones:

- **La versión de Google Play** (`io.sonora.player`), la que se vende en Google Play.
- **La versión completa** (`io.sonora.player.full`, que aparece como "Sonora Full"), compilada desde el mismo código
  para uso personal. No se distribuye en Google Play. Añade Google Cast y la app del reloj Wear OS. Los apartados
  marcados **Solo versión completa** se aplican solo a ella.

## Qué guarda Sonora en tu dispositivo

Sonora guarda sus datos en el almacenamiento privado de la app, en tu dispositivo:

- las carpetas que elegiste con el selector de carpetas del sistema, y un índice de sus canciones (etiquetas y
  carátulas);
- tus listas de reproducción, favoritos y elementos fijados y ocultos;
- los recuentos de reproducciones y tu historial de escucha (qué canción, cuándo y durante cuánto tiempo), para las
  estadísticas de la app;
- las letras que descargaste y los análisis de DJ (rejillas de tempo, tonalidades, puntos cue);
- tus ajustes y la cola de reproducción.

Sonora solo lee música de las carpetas que eliges, y solo modifica tus archivos cuando se lo pides (por ejemplo, al
editar etiquetas o convertir archivos). Borrar los datos de la app o desinstalar Sonora elimina todo lo que guardaba.

## Qué sale de tu dispositivo, y cuándo

**Búsqueda de letras (LRCLIB), solo en Sonora Full.** La versión de Google Play no tiene búsqueda de letras ni acceso
a internet. En Sonora Full, solo cuando pulsas "Buscar letra en internet" en una canción, Sonora envía el título,
el artista, el álbum y la duración de esa canción a [LRCLIB](https://lrclib.net), un servicio de letras gratuito de
terceros, por una conexión cifrada (HTTPS). Como cualquier petición web, lleva también tu dirección IP y el nombre y la
versión de la app. La respuesta se guarda en tu dispositivo, así que la canción no se vuelve a buscar salvo que lo
pidas. Sonora nunca busca por su cuenta. LRCLIB trata la petición según sus propias condiciones.

**Otras apps de tu dispositivo.** Sin pasar por la red:

- Las apps del desarrollador de Sonora firmadas con la misma clave pueden leer tu biblioteca, el estado de la
  reproducción, el historial de escucha y las carátulas, y controlar la reproducción, mediante la comunicación entre
  apps de Android dentro del dispositivo.
- El **control externo** está desactivado hasta que lo activas en los ajustes de Sonora. Mientras está activado, la
  consola de depuración de Android (`adb`, desde un ordenador que conectaste con la depuración USB) y las apps a las
  que concedas el permiso "Controlar Sonora" pueden hacer lo mismo. Al desactivarlo se les vuelve a denegar.
- Cuando compartes canciones o un archivo comprimido, Sonora los entrega a la app que eliges en el menú de compartir
  del sistema.

**Copias y exportaciones que haces tú.** La copia de seguridad de Sonora, los archivos comprimidos, los archivos
convertidos y las exportaciones a un pendrive USB van solo al lugar que eliges. Lo que pase allí depende de ese lugar.

**Copia de seguridad de Android.** Si activaste la copia de seguridad en los ajustes de Android, Android incluye los
ajustes y los datos de la biblioteca de Sonora (salvo la base de datos de análisis de DJ) en la copia de tu
dispositivo y en el traspaso a un dispositivo nuevo. La gestiona tu proveedor de copias, no el desarrollador. Puedes
desactivarla en los ajustes de Android.

**Los controles multimedia de Android.** Sonora muestra lo que suena (título, artista, carátula) a los controles
multimedia de Android, para que la notificación, la pantalla de bloqueo, los dispositivos Bluetooth, Android Auto y el
asistente de voz puedan mostrarlo y controlarlo.

**Solo versión completa: Google Cast.** Cuando envías música a un dispositivo Cast, Sonora le manda el título, el
artista, el álbum y la carátula de la canción, y el dispositivo reproduce el audio desde un pequeño servidor en tu
teléfono. Ese servidor solo escucha en tu red wifi, solo responde a peticiones que llevan un código creado para esa
sesión y se detiene al terminar. Cast lo proporciona Google mediante los servicios de Google Play, según la política
de privacidad de Google.

**Solo versión completa: reloj Wear OS.** Cuando envías música al reloj o controlas Sonora desde él, Sonora usa la Data
Layer de Wear OS de Google (servicios de Google Play) para pasar copias de las canciones y las órdenes de control
remoto entre tu teléfono y tu reloj.

Nada más sale de tu dispositivo.

## Qué no hace Sonora

- No hay cuentas ni inicio de sesión.
- No hay anuncios, ni analítica, ni servicio de informes de fallos, ni seguimiento entre apps o sitios web.
- No accede a tu ubicación, contactos, cámara ni micrófono.
- No vende ni comparte tus datos: el desarrollador nunca los recibe.

## Comprar Sonora en Google Play

Google procesa tu compra según su propia política de privacidad. El desarrollador solo ve la información del pedido
que Google Play da a los vendedores, y la usa solo para gestionar ese pedido.

Si permitiste que Android comparta datos de uso y diagnóstico, Google puede mostrar al desarrollador informes anónimos
de fallos y rendimiento de Sonora en Google Play Console.

## Permisos

| Permiso | Para qué |
|---|---|
| Internet | Solo en la versión completa: la búsqueda de letras que pides, y Google Cast. La versión de Google Play no lo tiene. |
| Estado de la red | Lo usan las bibliotecas del reproductor de audio y del trabajo en segundo plano. Versión completa: también la dirección wifi para Cast. |
| Notificaciones | Controles de reproducción y progreso de conversiones, búsquedas de duplicados y exportaciones. |
| Servicios en primer plano (reproducción, procesamiento multimedia, sincronización de datos) | Mantener la reproducción, la conversión de archivos y las exportaciones (a un pendrive USB y, en la versión completa, al reloj) cuando sales de la app. |
| Notificaciones destacadas | Mostrar el progreso de la conversión como actualización en directo en Android 16. |
| Mantener activo el dispositivo | Seguir reproduciendo con la pantalla apagada. |
| Cambiar ajustes de audio | Salida bit-perfect a un dispositivo de audio USB. |
| Ejecutar al iniciar | Que Android reanude el trabajo en segundo plano programado (como una conversión) tras reiniciar. |
| Dispositivos USB (se pide al conectar uno) | Leer y escribir el pendrive USB que elijas para exportar. |

Sonora define además su propio permiso "Controlar Sonora", que tú concedes a otras apps (ver arriba).

## Menores

Sonora no está dirigida a menores. No recoge datos personales de nadie, tampoco de menores.

## Lo que puedes hacer

- En Sonora Full, borrar la letra descargada de una canción ("Eliminar letra descargada"); después se puede volver a buscar.
- Dejar el control externo desactivado, o desactivarlo cuando quieras.
- Desactivar la copia de seguridad del dispositivo en los ajustes de Android.
- Borrar los datos de Sonora o desinstalarla para eliminar todo lo que guardaba.

Como el desarrollador no tiene datos tuyos, no hay nada que solicitarle. Para una petición sobre letras
buscadas desde Sonora Full, contacta con LRCLIB.

## Código fuente

Sonora es software libre bajo la licencia GNU GPL versión 3. Su código fuente está en
<https://github.com/largobihurri/sonora-source>, así que cualquiera puede comprobar esta política con el código.

## Cambios

Los cambios de esta política se publican en este archivo, con una nueva fecha de entrada en vigor.

## Contacto

bihurridev@gmail.com
