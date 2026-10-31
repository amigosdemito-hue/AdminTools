# AdminTools

Herramienta integral de **administración y moderación** para servidores **Minecraft Java (Paper 26.1+)**.
Proyecto sobre **Java 25** y **Gradle 9.7.1** (wrapper incluido, no hace falta Gradle global).

Estado: **plugin funcional y verificado** (compila, carga en Paper 26.1.2 y responde comandos reales).

---

## Qué trae

| Módulo | Funciones |
|---|---|
| **Sanciones** | ban / tempban / ipban / mute / tempmute / ipmute / shadowmute / warn / tempwarn / kick, historial completo, notas de staff, plantillas (`/punish`), escaleras de escalado (`/ladder`), reversión de sanciones (`/unpunish`), límites de duración por rango, avisos al entrar y expiración automática. También se sanciona **por nombre** a jugadores que todavía no han entrado nunca |
| **Herramientas de staff** | vanish con mensajes falsos, modo staff con vuelo/invulnerabilidad/herramientas, `/follow`, freeze persistente (no se escapa reconectándose), staff chat, espía social y de comandos, `/seen`, `/playtime`, `/stafflist`, duty, invsee y enderchest (en vivo y de jugadores offline), heal/feed/fly/god/gamemode/clear, tp/tphere/tppos/back |
| **Reportes** | `/report`, cola con `/reports`, reclamo de reportes (`claim`), resolución/rechazo con nota, cooldown y límite de reportes abiertos, aviso al reportero |
| **Chat** | antispam, antimayúsculas, filtros por regex, anti publicidad, cooldown por jugador, modo lento, `/clearchat` (borra de verdad los mensajes firmados con la API de Paper), silencio global, mute fantasma, `/chatlog`, formato de chat configurable con prefijo de LuckPerms |
| **Investigación** | `/check` (ficha unificada), `/alts` por IP, `/case` (expediente con sanciones + notas + reportes + chat + copias), copias de inventario (`/snapshot`, `/restore`) |
| **Cárcel** | celdas con posición y radio cuadrado (5x5 por defecto), retorno instantáneo al salirse, modo aventura mientras dura la condena (sin bloques fantasma en Bedrock), condenas con expiración, inventario retirado al entrar y devuelto sin duplicados al salir, bloqueo de bloques, contenedores, daño, recogida y teletransportes, comandos permitidos por config e importación desde xJail |
| **Rollback** | copias automáticas (entrada, salida, muerte, cambio de mundo e intervalo), menús de jugadores → copias → contenido con armadura y mano secundaria, restauración selectiva, restauración de un solo espacio y borrado de copias con confirmación |
| **Operación** | watchdog de TPS/MSPT/RAM/disco con alertas, modo mantenimiento, reinicio programado con avisos, copias de seguridad con rotación, auditoría de todo lo que hace el staff |
| **Integraciones** | LuckPerms (rangos y prefijos), CoreProtect (lookup y rollback), Discord (alertas por webhook), PlaceholderAPI, Vault (saldo en la ficha del menú de staff, con o sin Vault instalado), puente de anticheats (`/at acalert`, con el último flag visible en `/check`) |
| **Paneles por rango** | `/helper`, `/moderator` y `/admin` abren un panel con todas las opciones que permite el rango, con tres interfaces configurables: menú de inventario Bukkit, diálogo nativo de Minecraft (1.21.6+) y formularios nativos de Bedrock vía Floodgate |
| **Anti-abuso** | jerarquía de rangos, límite de acciones por minuto, confirmación en comandos destructivos, decaimiento de warns |

Detalle completo de cada función en [docs/LISTA-FUNCIONES.md](docs/LISTA-FUNCIONES.md) y la
referencia de comandos y permisos en [docs/COMANDOS-Y-PERMISOS.md](docs/COMANDOS-Y-PERMISOS.md).
El historial de versiones está en [CHANGELOG.md](CHANGELOG.md) y el material para publicar en
[docs/SUBIDA-GITHUB-MODRINTH.md](docs/SUBIDA-GITHUB-MODRINTH.md) (GitHub + Modrinth) y
[docs/CURSEFORGE.md](docs/CURSEFORGE.md) (CurseForge, con las plantillas de los tres proyectos).

---

## Instalación rápida

```bash
./gradlew build                       # compila y arma el jar final
cp build/libs/AdminTools-0.5.0.jar servidor/plugins/   # el jar "gordo", no el -plain
```

Al arrancar el servidor se crean solos:

```
plugins/AdminTools/config.yml        # toda la configuración
plugins/AdminTools/messages_es.yml   # mensajes en español
plugins/AdminTools/messages_en.yml   # mensajes en inglés
plugins/AdminTools/data.db           # base de datos SQLite
```

Después de editar la config: `/at reload` (o reinicia el servidor si cambias el almacenamiento).

### Pruebas

```bash
./gradlew test    # pruebas de la lógica que no necesita servidor
```

Cubren duraciones de sanción (parseo, ambigüedades y desbordes), texto y escape de MiniMessage,
geometría del área de la cárcel, lectura del tipo de sanción guardado y el **formato de las pantallas**
que ve el jugador (ban, expulsión, silencio, cárcel, congelado, mantenimiento): se renderizan con
MiniMessage en los dos idiomas y no pueden quedar con placeholders sin valor ni etiquetas sueltas.
Las integraciones con Paper (inventarios, menús, eventos) se siguen verificando arrancando un servidor real.

### Si vienes de la versión anterior (ServerAdmin)

```bash
# el plugin se llama ahora AdminTools: un solo jar, la carpeta de datos cambió de nombre
rm plugins/ServerAdmin-*.jar
mv plugins/ServerAdmin plugins/AdminTools      # conserva sanciones, reportes, notas y copias
```

- Los permisos pasaron de `serveradmin.*` a `admintools.*` (vuelve a conceder `admintools.helper`,
  `.moderator` o `.admin` en LuckPerms).
- El comando raíz es ahora `/admintools` (`/at`, `/atool`); `/sa` ya no existe.
- En `sanctions.duration-limits`, los nodos `serveradmin.limit.*` se siguen leyendo: el plugin los
  convierte a `admintools.limit.*` y te avisa en el log para que los renombres.

---

## Rangos (helper → moderador → admin)

Los permisos vienen declarados en `plugin.yml` con sus hijos ya armados:

```bash
# Con LuckPerms
/lp creategroup helper      && /lp group helper      permission set admintools.helper true
/lp creategroup moderador   && /lp group moderador   permission set admintools.moderator true
/lp creategroup admin       && /lp group admin       permission set admintools.admin true
```

- **helper** — warn/tempwarn, mute temporal, kick, vanish, freeze, tp, follow, playtime, staff chat, reportes, `/chatlog`, `/check`
- **moderador** — todo lo del helper + bans temporales, mute permanente, modo staff, plantillas, escaleras, espías, invsee/enderchest, utilidades, `/alts`, copias de inventario
- **admin** — todo + IP-ban, IP-mute, bans/mutes permanentes, reversión de sanciones, muteall, mantenimiento, reinicio, backups, auditoría, recarga, restauración de inventarios, vanish y espectador sobre otros miembros del staff

Cada rango tiene además su panel: `/helper`, `/moderator` y `/admin` abren una interfaz con **todas
las acciones que su permiso permite**, sin botones que no pueda usar. La interfaz se elige en
`panel.ui` (`auto`, `dialog` o `inventory`): `auto` usa el diálogo nativo de Java y los formularios
nativos de Bedrock cuando Floodgate está instalado. Con el inventario, los datos que falten se
piden por chat y el mensaje no se difunde.

`/at help` muestra exactamente los comandos que puede usar cada rango.

Manual de cada rango, con sus permisos y su **protocolo de uso en un servidor semi-anárquico**:

- [Helper — permisos y protocolo](docs/ROL-HELPER.md)
- [Moderador — permisos y protocolo](docs/ROL-MODERADOR.md)
- [Admin — permisos y protocolo](docs/ROL-ADMIN.md)

---

## Configuración destacada

| Ajuste | Para qué |
|---|---|
| `storage.type: sqlite` / `mysql` | un servidor (**SQLite**) o una red de servidores (**MySQL**), sin cambiar el código |
| `sanctions.duration-limits` | duración máxima por rango (ej. `admintools.limit.mod: 30d`) |
| `sanctions.templates` | plantillas tipo `/punish hacking Ana` |
| `sanctions.ladders` | escaleras automáticas: chat, publicidad, cheating (`/ladder publicidad Ana`) |
| `sanctions.auto-escalate` | N advertencias → mute automático |
| `chat.*` | filtros, antispam, cooldown, modo lento, bloqueo de comandos (`/plugins`, `/ver`...) |
| `reports.*`, `alts.*` | cooldown y límites de reportes, retención de IPs y bloqueo de multicuenta |
| `watchdog.*` | umbrales de TPS/MSPT/RAM/disco y alertas (también a Discord) |
| `restart.*`, `backups.*` | reinicios por horario y copias automáticas |
| `discord.*` | webhook para alertas de sanciones, reportes y rendimiento |
| `announcements.*` | anuncios públicos de sanciones (sin contar el registro de staff), con cuenta atrás |
| `integrations.*` | activar/desactivar LuckPerms, CoreProtect, PlaceholderAPI y alertas de anticheat |

El webhook de Discord es un secreto: déjalo en `config.yml` del servidor, no en el repositorio.

### Si MySQL no conecta

El plugin imprime en consola el motivo real y una pista, por ejemplo:

```
[AdminTools] Conectando a MySQL jdbc:mysql://localhost:3306/midb?... (usuario midb)
[AdminTools] No pude conectar a la base de datos: Access denied for user 'midb'@'localhost' [SQLState 28000] [código 1045]
  -> Acceso denegado: revisa usuario y contraseña, y que ese usuario tenga permisos sobre la base.
```

Causas habituales en un hosting: la base lleva el **prefijo del usuario** (`usuario_admintools`),
el host debe ser el **interno** (`localhost`) porque el 3306 remoto suele estar cerrado, y el usuariodebe estar autorizado para conectarse desde la IP del servidor. Con TLS desactivado el plugin añade
solo `allowPublicKeyRetrieval=true` a los parámetros, que MySQL 8+ necesita con `caching_sha2_password`.
Pon `debug: true` para ver la traza completa del fallo.

### Anuncios de sanciones

Es un sistema aparte del **registro de staff**: aquel audita lo que hace cada rango en un canal privado,
este publica la moderación en un canal abierto. Se configuran dos webhooks distintos (se pueden usar los
dos a la vez):

- `announcements.public-webhook` — la comunidad. El embed va **firmado por el jugador sancionado**, con
  su nombre y la cabeza de su skin, y muestra el tipo (ban permanente, ban temporal, encarcelamiento,
  silencio, congelamiento), la duración, el motivo, quién la emitió y el número de sanción.
- `announcements.staff-webhook` — copia al canal del staff, **firmada por quien emitió la sanción** (su
  nombre y su cabeza), para ver de un vistazo quién actuó.

Si la sanción es temporal, el embed lleva un campo `Termina` con la fecha y una **cuenta atrás viva**
(`<t:...:R>`, que Discord actualiza sola sin que el servidor envíe nada). Cuando llega a cero se publica
un segundo embed, en los dos canales, anunciando el fin. Como los avisos de fin cuelgan de los mismos
caminos que ya expiran sanciones y condenas (idempotentes), no se duplican tras un reinicio.

Las cabezas se piden a `announcements.head-url` (`https://mc-heads.net/avatar/{uuid}/128` por defecto);
admite `{uuid}` y `{name}`, así que funciona también con nombres sancionados antes de entrar y con
jugadores de Bedrock.

### Reportes en Discord

`/report` avisa en el juego y además manda un embed al canal privado del staff, con la cabeza del
reportado, la de quien reporta (salvo con `reports.anonymous`), el motivo, la fecha y el ID. El destino
se toma, en este orden, de `reports.discord-webhook`, del canal de staff de los anuncios y del webhook
general; así sale de fábrica sin configurar nada nuevo si ya tienes canal de staff.

### Dónde va cada webhook

Cada canal de Discord necesita **su propio** webhook (no se puede publicar en un canal con el enlace de
otro). Se sacan igual: ajustes del canal → Integraciones → Webhooks → Nuevo webhook → Copiar URL.

| Ajuste | Para qué | Si se deja vacío |
|---|---|---|
| `discord.webhook-url` | alertas del plugin: sanciones, reportes y rendimiento | no se envía ninguna alerta |
| `reports.discord-webhook` | cola de `/report` (canal privado del staff) | usa `announcements.staff-webhook` y, si no, el general |
| `announcements.public-webhook` | anuncios de sanciones para la comunidad | no se publica nada |
| `announcements.staff-webhook` | copia de esos anuncios, firmada por quien sancionó | no se manda copia |
| `staff.action-log.webhook` | registro de comandos del staff (helpers, mods y admins) | usa `announcements.staff-webhook` y, si no, el general |

El registro cubre **todos** los comandos del staff, no sólo los del plugin: con `staff.action-log.all-commands`
(activado por defecto) también se anotan `/give`, `/gamemode` o cualquier comando de EssentialsX y demás
plugins. Lo interceptado se guarda además en la auditoría, así que se consulta con `/audit`.

El registro de staff **no** depende de `discord.enabled` (ese interruptor es solo para las alertas):
con su webhook puesto funciona aunque las alertas estén apagadas. Si activas el registro sin ningún
webhook, el arranque lo dice en consola y `/at status` muestra «activado pero sin webhook».

### ¿Webhooks o un bot único?

Cada canal de Discord necesita su propio webhook: no existe forma de publicar en un canal con el
enlace de otro, ni con un bot (un bot tendría que estar en el servidor con los *intents* activados,
conectado al *gateway* y cargando una librería de varios megas dentro del jar). Por eso el plugin usa
webhooks: cada canal cuesta un campo de texto y no añade dependencias ni conexiones permanentes. Los
embeds llevan la identidad (nombre y cabeza) en el propio mensaje, así que el aspecto es el mismo que
con un bot.

### Letrero de arranque

Al terminar de cargar, el plugin pinta un bloque con versión, servidor, almacenamiento, módulos,
integraciones y estado. Se ve de un vistazo entre las líneas del servidor porque todo el marco sale en
un solo evento de log (el prefijo `[AdminTools]` aparece una vez y el dibujo queda alineado):

```
  ╔══════════════════════════════════════════════════════════════╗
  ║                                                              ║
  ║     ▄▀█ █▀▄ █▀▄▀█ █ █▄░█ ▀█▀ █▀█ █▀█ █░░ █▀                  ║
  ║     █▀█ █▄▀ █░▀░█ █ █░▀█ ░█░ █▄█ █▄█ █▄▄ ▄█                  ║
  ║                                                              ║
  ║          ADMINISTRACIÓN · SANCIONES · CÁRCEL · STAFF         ║
  ╟──────────────────────────────────────────────────────────────╢
  ║  Versión        0.5.2 · Paper 26.1.2                         ║
  ║  Servidor       MiServidor                                   ║
  ║  Datos          SQLite · data.db                             ║
  ║  Idioma         es · messages_es.yml                         ║
  ║  Comandos       71 disponibles                               ║
  ║  Paneles        3 rangos · 148 acciones · auto               ║
  ║  Cárcel         2 celdas · 1 preso                           ║
  ║  Integraciones  LuckPerms, Discord (webhook)                 ║
  ║  Discord        alertas OFF · anuncios ON · staff-log ON     ║
  ║  Estado         operativo                                    ║
  ║  Arranque       1204 ms                                      ║
  ║  Ayuda          /at help · /helper · /moderator · /admin     ║
  ╚══════════════════════════════════════════════════════════════╝
```

El logo lleva un degradado cian → verde con colores ANSI de 256 colores, que Paper y las consolas de
los paneles de hosting interpretan directamente. Dos opciones en `config.yml`:

```yml
console:
  banner: true      # false = arranque discreto
  color: auto       # auto | always | never
```

`auto` usa color salvo que la consola no lo soporte (`NO_COLOR`, `terminal.ansi=false` o una consola de
Windows sin JLine). El archivo `logs/latest.log` sale siempre sin códigos de escape: Paper los recorta al
escribir el archivo, así que el log queda legible para `grep`.

### Mensajes y pantallas

El diseño de los mensajes vive en `messages_es.yml` / `messages_en.yml` (MiniMessage), así que se puede
cambiar todo —colores, textos, incluso el aspecto de la pantalla de ban— sin tocar el jar. La identidad
visual es una barra `▏` con el canal (`SANCIÓN`, `CÁRCEL`, `SANCIONES`, `FIN`…) y columnas separadas por
`│`; las pantallas completas (ban, expulsión, cárcel, congelado, mantenimiento) van enmarcadas y con los
datos alineados:

```
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
ACCESO DENEGADO
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
Sanción    » #42
Tipo       » Ban
Motivo     » Uso de clientes no permitidos (cheating)
Expira     » 30 días
Servidor   » MiServidor

Si crees que es un error, apela la sanción
en nuestro Discord.
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
```

Cada archivo lleva `messages-version`. Cuando una actualización cambia el formato, el archivo de tu
carpeta de datos se **regenera** con el diseño nuevo y tus textos quedan guardados al lado como
`messages_es.yml.v1.bak`; si lo habías personalizado y quieres conservarlo, sube `messages-version`
a mano y el plugin dejará de tocarlo.

### Seguridad de los mensajes

Todo texto que viene del juego (chat, motivos de reporte, notas, motivos de sanción) se **escapa antes de
pasar por MiniMessage**: un jugador no puede inyectar etiquetas como `<click:run_command:...>` en el chat
del staff ni en los avisos de Discord.

---

## Toolchain del proyecto

| Herramienta | Versión | Para qué |
|---|---|---|
| JDK | 25.0.4.1 LTS | lo exige Paper 26.1+ |
| Gradle | 9.7.1 (wrapper) | build y servidor de pruebas |
| paper-api | `26.1.2.build.74-stable` (compileOnly) | API del plugin |
| Paper server | `26.1.2` build 74 (STABLE) | servidor de pruebas en `run/` |
| shadow | 9.6.1 | jar final con bStats relocalizado |
| run-paper | 3.1.0 | tarea `runServer` |
| sqlite-jdbc / mysql-connector-j | los provee Paper | sin engordar el jar |
| bStats | 3.2.1 | métricas anónimas (desactivadas por defecto) |

Todas las versiones se cambian en un solo archivo: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## Comandos de desarrollo

```bash
./gradlew build                        # compila + arma build/libs/AdminTools-<version>.jar
./gradlew runServer                    # arranca Paper con el plugin (datos en ./run/)
cd run && ./start.sh                   # arranca el servidor de pruebas ya descargado
cd run && ./plugin-test.sh             # prueba rápida: arranca, envía comandos y apaga
cd run && ./plugin-test-full.sh        # batería completa: los 52 comandos, en run/plugin-test-full.log
```

`run/plugin-test.sh` deja el resultado en `run/plugin-test.log` (carga del plugin, respuestas de cada
comando y cualquier error).

---

## Estructura del código

```
src/main/java/com/admintools/
├── AdminToolsPlugin.java      arranque, servicios, tareas periódicas
├── api/AdminToolsAPI.java     API pública para otros plugins
├── command/                    52 comandos + framework con permisos, uso y autocompletado
├── config/                     Settings (config.yml tipado) y Messages (i18n MiniMessage)
├── integration/                Discord, LuckPerms, CoreProtect, anticheats, PlaceholderAPI, Vault
├── listener/                   conexiones (bans, mantenimiento, alts), chat y protecciones de staff
├── model/                      sanciones, notas, reportes, fichas, copias, auditoría
├── service/                    sanciones, jugadores, chat, staff, reportes, watchdog, backups...
├── storage/                    Database (SQLite/MySQL + migraciones + pool + tareas async)
└── util/                       Text (MiniMessage), TimeUtil (duraciones), Util
src/main/resources/             plugin.yml, config.yml, messages_es.yml, messages_en.yml
```

## Notas del entorno de pruebas

- `run/eula.txt` tiene `eula=true`: al arrancar ese servidor aceptás el [EULA de Minecraft](https://aka.ms/MinecraftEULA).
- `run/server.properties` usa `online-mode=false`: es **solo para desarrollo local**, nunca lo subas a un servidor real.
- El reinicio automático usa `Bukkit.shutdown()` salvo que definas `restart.command` en la config
  (por ejemplo un script del panel o del sistema que vuelva a arrancar el servidor).
- Spigot trae su propio `/restart` y lo registra antes que los plugins: `CommandOwnership` lo detecta al
  arrancar, recupera la etiqueta para el plugin (y sus alias) y, si hiciera falta, redirige la ejecución
  desde los eventos de comando de jugador y de consola.
