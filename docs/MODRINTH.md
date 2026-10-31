# Ficha de Modrinth

Texto publicado en [admintools-suite](https://modrinth.com/mod/admintools-suite) (proyecto
`Ii9JUmjd`). Es el documento canónico de la ficha: si cambia algo del plugin, se edita aquí y se
vuelve a subir con la receta de [SUBIDA-GITHUB-MODRINTH.md](SUBIDA-GITHUB-MODRINTH.md).

- **Resumen (≤256 caracteres):** la línea `description` de la API.
- **Cuerpo:** la parte en inglés va primero porque Modrinth valida el idioma; debajo va la traducción
  española, que la plataforma acepta sin avisos.

> Resumen: Paper 26.1+ administration suite: ranked punishments, jails, inventory rollback, reports,
> staff panels, vanish, spectator patrol and full Discord integration.

---

# AdminTools

**All-in-one administration suite for Minecraft Java servers running Paper 26.1 and newer.**

Built on Java 25 with Gradle, tested against Paper 26.1.2: 70+ commands, ~50 permission nodes with
inheritance, three staff ranks, an English/Spanish message system and a full reference manual.
SQLite out of the box, MySQL when you run a network of servers.

---

## What you get

### 1. Punishments

- `ban`, `tempban`, `ipban`, `mute`, `tempmute`, `ipmute`, `shadowmute`, `warn`, `tempwarn`, `kick`
- Full history with `/history`, staff notes with `/notes`, reversion with `/unpunish`
- **Templates** (`/punish hacking`, `xray`, `grief`, `insultos`, `spam`, `publicidad`, `evadir-staff`)
  and **escalation ladders** (`/ladder chat`, `publicidad`, `cheating`) that step up automatically
  while the player keeps misbehaving inside the window
- **Per-rank duration limits**: helper 1 day, moderator 30 days, admin permanent. Cross the limit and
  the plugin clamps it instead of letting a junior staff member overreach
- Punish **by name** players who have never joined: the sanction sticks to the name and is applied
  the first time that account connects
- Automatic expiry, join warnings, auto-escalation after 3 warnings in 30 days, announce to the
  server, silent mode (`-s`), protection for higher ranks and exempt nodes

### 2. Jails (based on xJail, rebuilt)

- Cells with position and a square return radius (`/setjail <name> [radius]`, 2 blocks = 5×5 by
  default), **random cell** on sentencing, `/jailtp`, `/jaillist`, `/jailtime`, `/unjail`
- The prisoner's **full inventory is confiscated and returned without duplicates** — armour,
  offhand included; if the sentence ends while offline, it is handed over on the next login
- Adventure mode during the sentence (no ghost blocks for Bedrock/Geyser players), block breaking,
  placing, interaction, damage, ender pearls and chorus fruit all blocked
- Instant teleport back the moment they cross the boundary — an open gate is not an escape
- Allowed commands configurable, staff notifications, escape handling, release to the original
  position and `/jailimport` to migrate cells and prisoners from xJail (with `--dry` to simulate)

### 3. Inventory rollback

Inspired by InventoryRollbackPlus and extended: complete snapshots of inventory, enderchest,
position, health, hunger, XP and game mode.

- Automatic copies on join, quit, death, world change and on an optional interval
- Menu-driven: players → snapshots → contents → enderchest → actions → confirmation
- **Selective restore** (`items`, `ender`, `position`, `stats`, `all`) and **single-slot restore**
- A safety copy is saved before every restore, so a wrong click is undone
- Per-reason retention (`rollback.keep`), `/rollback save`, `/rollback purge <days>`, read-only menus

### 4. Staff tools

- **Vanish** that survives reconnects and restarts, hides join messages, blocks mob targeting,
  stops item/XP pickup, opens **every container without animation or sound** (chests, barrels,
  shulkers, hoppers, furnaces, crafter, enderchest, anvils, carts…), silences doors and levers,
  and does not appear in death or advancement messages. Staff in vanish see each other with a
  **rank-coloured glow** (green / yellow / red)
- **Staff mode** with fly, god mode, invulnerability, a hotbar of tools (compass = player list and
  teleport, blaze rod = freeze, clock = vanish, book = profile) and the original inventory restored
  on exit
- **Spectator patrol** for real watching: 5 minutes active, 15 minutes cooldown, persisted
- `/freeze`, `/follow`, `/tp`, `/tphere`, `/tppos`, `/back`, `/heal`, `/feed`, `/fly`, `/god`,
  `/gamemode`, `/clear`, `/invsee`, `/enderchest`, `/spy`, `/cspy`, `/duty`, `/seen`, `/playtime`,
  `/staffchat`, `/stafflist`

### 5. Reports, chat and investigation

- `/report` with cooldown, open limit, staff notification, claim/resolve/reject flow with notes and
  delivery to a Discord channel
- `/reports` queue, `/chatlog`, `/case` (sanctions + notes + reports + chat + snapshots),
  `/alts` (accounts sharing an IP), `/check` (profile, active sanctions, last anticheat flag)
- Chat: anti-spam, anti-caps, regex filters, anti-advertising with automatic punishment after N
  detections, cooldown, slow mode, `/clearchat` and `/muteall`
- `/snapshot` and `/restore` for evidence, plus `/at inspect` and `/at rollback` through CoreProtect

### 6. Panels for every rank

`/helper`, `/moderator` and `/admin` open a panel with **only the actions that rank may perform** —
each button runs the real command as the player, so panels never bypass permissions, limits or
auditing. Three interchangeable interfaces:

| `panel.ui` | Java | Bedrock |
|---|---|---|
| `auto` (default) | native dialog (1.21.6+) | native Floodgate form when installed |
| `inventory` | classic chest menu | classic chest menu |
| `dialog` | native dialog | dialog rendered by Geyser |

Missing values (player, duration, reason) are asked in chat and never broadcast.

### 7. Discord integration

- **Sanction announcements** to a public channel: skin head, type, duration with a **live countdown**
  and the issuing staff member; when it expires, a second embed announces the end
- **A copy to the staff channel** signed by whoever issued the sanction
- **Staff action log**: every command and tool used by helpers, moderators and admins, batched every
  3 seconds; passwords and 2FA never logged, reasons stripped of `@everyone`
- Reports delivered to their own channel; alert webhooks for sanctions, performance and anticheats

### 8. Operations

- Watchdog for TPS/MSPT/RAM/disk with thresholds and alerts (`/watchdog`)
- Maintenance mode, scheduled restarts with countdown and cancel, automatic backups with rotation
- Full staff auditing (`/audit`), per-minute action limits, two-step confirmation on destructive
  commands, chat and IP retention purges, `/at reload`

---

## Ranks

| Rank | What it can do |
|---|---|
| **helper** | warnings, mutes up to 1 day, kick, jail use, vanish, freeze, teleport, follow, reports queue, `/chatlog`, `/check` |
| **moderator** | everything above + bans up to 30 days, templates, ladders, staff mode, spy, invsee/enderchest, `/case`, `/alts`, snapshots, chat moderation, rollback view/restore |
| **admin** | everything above + permanent bans, IP bans/mutes, reversion, muteall, maintenance, restarts, backups, audit, watchdog, reload, jails management import |

LuckPerms groups are wired for you (`admintools.helper`, `admintools.moderator`, `admintools.admin`
with inheritance already declared), so one permission grants the whole rank.

---

## Installation

1. Drop `AdminTools-x.y.z.jar` into `plugins/`.
2. Start the server: SQLite database, Spanish messages and default config are created automatically.
3. Edit `plugins/AdminTools/config.yml` (storage, jail, rollback, Discord, panel UI, chat rules).
4. Grant ranks with LuckPerms: `/lp group helper permission set admintools.helper true`.

Requires Paper **26.1 or newer** (Java 25). Nothing else is mandatory.

## Optional integrations

LuckPerms (ranks and prefixes), CoreProtect (block lookup and rollback), PlaceholderAPI, Vault
(economy in the staff menu), Floodgate/Geyser (native Bedrock forms), and an anticheat bridge
(`/at acalert`) that feeds Grim, Vulcan, Matrix, Spartan or Polar flags straight into `/check`.

## Documentation

Everything is written down in the repository: a full command and permission reference, a handbook
per rank with protocols for semi-anarchy servers, a function list, security audits and this
changelog. Messages ship in Spanish and English (`messages_es.yml`, `messages_en.yml`) and are fully
editable — including every colour and every screen (ban, jail, freeze, maintenance).

## License

**All rights reserved.** The repository is public for reading and compiling for your own server;
copying, modifying, redistributing or monetizing the code is not allowed without written permission.
Not affiliated with Mojang Studios, Microsoft or Modrinth.

---

## Español

**Suite integral de administración para servidores Minecraft Java en Paper 26.1 o superior.**

Java 25, compilado con Gradle y probado contra Paper 26.1.2: más de 70 comandos, unos 50 nodos de
permiso con herencia, tres rangos de staff, mensajes en español e inglés y documentación completa.
SQLite de fábrica, MySQL cuando llevas una red de servidores.

### Sanciones

`ban`, `tempban`, `ipban`, `mute`, `tempmute`, `ipmute`, `shadowmute`, `warn`, `tempwarn`, `kick`,
historial (`/history`), notas de staff (`/notes`) y reversión (`/unpunish`), **plantillas**
(`/punish hacking`, `xray`, `grief`, `insultos`, `spam`, `publicidad`, `evadir-staff`) y
**escaleras** (`/ladder chat`, `publicidad`, `cheating`) que suben solas mientras reincida.
Límite de duración por rango: helper 1 día, moderador 30 días, admin permanente; si te pasas, el
plugin te la corta. También se sanciona **por nombre** a quien todavía no ha entrado nunca.

### Cárcel

Celdas con posición y radio de retorno (`/setjail <nombre> [radio]`, 2 bloques = 5×5 por defecto),
**celda aleatoria** al encarcelar, inventario confiscado y devuelto **sin duplicar** (armadura y
mano secundaria incluidas), modo aventura durante la condena para evitar bloques fantasma en
Bedrock, bloqueo de construcción, interacción, daño, perlas y fruta del coro, retorno instantáneo
al salirse del área, comandos permitidos por configuración y `/jailimport` para migrar desde xJail.

### Rollback de inventarios

Inspirado en InventoryRollbackPlus y ampliado: copias de inventario, enderchest, posición, vida,
hambre, XP y modo de juego, automáticas al entrar, salir, morir y cambiar de mundo. Menús de
jugadores → copias → contenido, **restauración selectiva** y de un solo espacio, copia de seguridad
previa a cada restauración, retención por motivo y `/rollback purge`.

### Herramientas de staff

Vanish que sobrevive a reconexión y reinicio, oculta entradas, no genera foco de mobs, bloquea la
recogida, **abre todos los contenedores sin animación ni sonido** y da brillo con el color del rango.
Modo staff con vuelo, invulnerabilidad y hotbar de herramientas (brújula, vara de blaze, reloj,
libro). Espectador de vigilancia de 5 minutos con 15 de recarga. Además `/freeze`, `/follow`,
teletransportes, `/invsee`, `/enderchest`, `/spy`, `/cspy`, `/duty`, `/staffchat` y más.

### Reportes, chat e investigación

`/report` con cola, reclamo, resolución con nota y envío a Discord; `/case`, `/alts`, `/check`,
`/chatlog`, `/snapshot` y `/restore`. Chat con antispam, anti mayúsculas, filtros por expresión
regular, anti publicidad con castigo automático, modo lento, `/clearchat` y `/muteall`.

### Paneles por rango

`/helper`, `/moderator` y `/admin` abren un panel **con solo las acciones que tu rango permite**,
con tres interfaces intercambiables: menú de inventario, diálogo nativo de Java (1.21.6+) o
formulario nativo de Bedrock vía Floodgate.

### Discord

Anuncios de sanciones en un canal público con la cabeza del jugador, el tipo, la duración con
**cuenta atrás en vivo** y quien la emitió; cuando termina se anuncia el fin. Copia en el canal
privado del staff firmada por quien sancionó, registro de comandos del staff agrupado cada
3 segundos y reportes en su propio canal.

### Operación

Vigilancia de TPS/RAM/disco (`/watchdog`), modo mantenimiento, reinicio programado con avisos y
cancelación, copias automáticas con rotación, auditoría de todo el staff (`/audit`), límite de
acciones por minuto y confirmación en dos pasos para lo destructivo.

### Rangos

| Rango | Alcance |
|---|---|
| **helper** | warn, mute hasta 1 día, kick, cárcel, vanish, freeze, teletransportes, reportes, `/chatlog`, `/check` |
| **moderador** | lo anterior + ban hasta 30 días, plantillas, escaleras, modo staff, espías, invsee, `/case`, `/alts`, copias, moderación de chat |
| **admin** | lo anterior + permanente, IP, reversión, muteall, mantenimiento, reinicio, copias, auditoría, recarga, importación de cárceles |

### Instalación

Copia el jar en `plugins/`, arranca (crea SQLite y la config en español) y ajusta
`plugins/AdminTools/config.yml`. Con LuckPerms: `/lp group helper permission set admintools.helper true`.
Requisito: Paper **26.1 o superior**. Todo lo opcional (LuckPerms, CoreProtect, PlaceholderAPI,
Vault, Floodgate/Geyser) se detecta solo.

### Documentación

El repositorio contiene la referencia completa de comandos y permisos, el manual de cada rango con
protocolos de uso en servidores semi-anárquicos, la lista de funciones, las auditorías de seguridad
y el changelog. Los mensajes vienen en español e inglés y son editables al completo, incluidos
colores y pantallas (ban, cárcel, congelado, mantenimiento).

### Licencia

Todos los derechos reservados: el repositorio es público para leer y compilar en tu servidor
propio; no se permite copiar, modificar, redistribuir ni monetizar sin permiso escrito.
