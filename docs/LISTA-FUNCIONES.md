# Lista de funciones — AdminTools

**Herramienta de administración general para Paper 26.1+** con 3 rangos: `helper`, `moderador`, `admin`.

Basada en el estado del arte 2026 (LiteBans, LibertyBans, BanManager, PunisherX, Staff++ / StaffPlus Plus,
InvSee++, EssentialsX, CMI, Warden, CoreProtect, LuckPerms, WorldGuard, Grim, Vulcan, Matrix, Spartan, Polar,
ChatSentinel, DiscordSRV, spark). Solo lo mejor de cada uno.

## Cómo leer la lista

| Símbolo | Significado |
|---|---|
| **Rango** | Rango mínimo para usar la función: `H` = helper, `M` = moderador, `A` = admin |
| **N** | Lo implementamos nosotros (es el valor del plugin) |
| **I** | Integración: lo hace otro plugin/servicio y nosotros nos conectamos |
| **P** | Ya lo trae Paper: no lo reinventamos, solo lo exponemos/enlazamos |
| **P0 / P1 / P2** | Prioridad: P0 = base del MVP, P1 = importante, P2 = extra |

---

## 1. Núcleo de sanciones (el corazón del plugin)

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| `/ban` permanente (online u offline, por nombre/UUID) | M | N | P0 | Nunca depender de que el jugador esté conectado |
| `/tempban <dur>` con parser de duración (`1h30m`, `7d`, `perm`) | M | N | P0 | Duraciones predecibles, sin ambigüedad |
| `/kick <razón>` | H | N | P0 | — |
| `/mute` y `/tempmute` (bloquea chat, `/msg` y comandos seleccionados) | H | N | P0 | Helper: solo mutes temporales y cortos |
| `/warn` y `/tempwarn` | H | N | P0 | Es la sanción blanda que usa el helper |
| `/ipban`, `/ipmute` (IP y rango de IP `/24`) | A | N | P1 | Solo admin: castiga a terceros que comparten IP |
| `/unban`, `/unmute`, `/unwarn`, `/pardon` (revocación con motivo obligatorio) | M | N | P0 | Sin motivo no se revoca |
| **Historial por jugador** (`/history`): activas, expiradas, quién, cuándo, por qué, cuánto | H | N | P0 | Requisito #1 de cualquier servidor serio |
| **Notas de staff** (`/note`, `/notes`) separadas del motivo de la sanción | M | N | P0 | Contexto, no sanción (ej.: "ya se le avisó 2 veces") |
| **Aviso al entrar**: mute/ban activo, alt detectada, notas pendientes | H | N | P0 | + notificación al staff que lo sancionó |
| **IDs de sanción** + editar/borrar (`-m` / `-d`) | A | N | P1 | Trazabilidad total |
| **Plantillas de sanción** (motivo → tipo + duración + mensaje) | A | N | P1 | Reduce decisiones inconsistentes del staff |
| **Escalado automático (ladder)**: 1º warn → 2º mute 1h → 3º ban 1d → ... | A | N | P1 | Con `expire_ladder`: el conteo se reinicia tras el período |
| **Decaimiento de warnings** (configurable; LiteBans usa 7 días) | A | N | P1 | Opcional: por defecto los warns no expiran si no se configura |
| **Acciones automáticas por umbral** de warnings | A | N | P1 | Ej.: al 3º warn, mute automático |
| **Límite de duración por rango** (helper ≤1h, mod ≤7d, admin permanente) | A | N | P1 | Se aplica por permiso, no por código del comando |
| **No se puede sancionar a un rango igual o superior** | A | N | P0 | Protección contra abuso interno |
| **Rollback de sanciones de un miembro del staff** (`/staffhistory`) | A | N | P1 | Para cuenta comprometida o abuso masivo |
| **Evidencia adjunta** (link/ID de caso/replay del anticheat) | M | N | P1 | No subimos archivos a la base de datos: solo referencias |
| **Persistencia**: H2/SQLite local y MySQL/MariaDB para red | A | N | P0 | Mismo esquema en ambas |
| Apelaciones (web/Discord) | A | I | P2 | Fuera del juego; decisión pendiente |

## 2. Herramientas de staff (modo staff)

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| `/vanish` con mensajes falsos de join/quit, oculto en tab-list | H | N | P0 | Estandarte de Staff++/SuperVanish |
| Apertura silenciosa de cofres estando en vanish | H | N | P1 | Sin abrir la animación para el jugador |
| `/staffmode` (modo staff): volar, invulnerable, no recoger ítems, herramientas | H | N | P1 | Herramientas: compass, follow, freeze, vanish, examine, CPS |
| `/freeze` con bloqueo de movimiento/interacción + **política de desconexión** | H | N | P0 | Clave: comando al desconectarse estando congelado (evita escape) |
| `/invsee` en vivo y **offline** (ver / editar con permisos separados) | M | N | P1 | Editar inventario puede destruir evidencia: permiso aparte |
| `/enderchest` ver/editar (online y offline) | M | N | P1 | Igual que arriba |
| `/heal`, `/feed`, `/fly`, `/god`, `/clear` (con confirmación) | M | N | P0 | `/gm` con permiso por modo |
| `/tp`, `/tphere`, `/tppos`, `/back`, `/spawn` | H | N | P0 | — |
| **TP a la víctima del reporte** y `/follow` | H | N | P1 | Flujo de investigación fluido |
| `/seen` (última conexión) y `/playtime` | H | N | P0 | — |
| **Registro de sesiones** (join/quit, duración, IP al entrar) | M | N | P1 | Base para alts e investigación |
| `/afk` | H | N | P2 | — |
| **Staff chat** (`/sc`) y chat de admin (`/ac`) | H | N | P0 | Canales separados por rango |
| **Social spy** (`/ss`) y **command spy** | M | N | P1 | — |
| **Duty toggle** (`/duty`) y `/staff list` (quién está en servicio) | H | N | P1 | — |
| `/jail` con celdas + jail offline + conteo de tiempo offline configurable | M | N | P2 | EssentialsX/CMI lo tienen; decidir si propio o integración |
| **Confirmación + cooldown** en comandos destructivos | — | N | P1 | Aplica a todos los rangos |
| **Límite de acciones por minuto** del staff | A | N | P1 | Anti-abuso interno (estilo StaffLens) |

## 3. Reportes y casos

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| `/report` (jugador) y `/report staff` | H | N | P0 | Destino: online u offline |
| `/reports`: cola con **estados** `OPEN → IN_PROGRESS → RESOLVED/REJECTED` | H | N | P0 | Motivo obligatorio al cerrar |
| **Reclamar/asignar** el reporte (evita que dos staff lo atiendan a la vez) | H | N | P0 | El estándar de Staff++ |
| **Cooldown y antiabuso** de reportes (bloqueo por spam) | H | N | P0 | — |
| Notificación al jugador que reportó cuando cambia el estado | H | N | P1 | Mejora la confianza en el sistema |
| **Adjuntar al caso**: notas, historial de chat, sanciones previas, evidencia | M | N | P0 | Un solo `/case <id>` con todo |
| Prioridad/severidad y filtros de la cola | M | N | P1 | — |
| Reasignar/cerrar reportes de otro staff | A | N | P1 | — |

## 4. Moderación de chat

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **Anti-flood/antispam**: cooldown, mensajes repetidos/similares, flood de símbolos | H | N | P0 | — |
| **Anti-caps** con umbral y longitud mínima configurables | H | N | P0 | — |
| Límite de longitud de mensaje | H | N | P0 | — |
| **Anti-publicidad**: IP, dominios, URLs, puertos, invites de Discord | H | N | P0 | Normalizar disfraces (`punto`, espacios, unicode) + allowlist legítima |
| **Filtro de insultos** con regex + acciones (bloquear/censurar/avisar/sancionar) | H | N | P1 | Regex acotadas y con comando de prueba |
| **Slowmode** global y por jugador | M | N | P1 | — |
| `/chat clear` (borrando mensajes firmados con la API de Paper) y **mute-all** | M | N | P1 | Borrar > spamear líneas en blanco |
| **Shadow mute** (el jugador cree que habla) | A | N | P2 | Solo con política clara y auditado |
| **Log de chat reciente** (`/chatlog`) para revisar contexto | M | N | P1 | — |
| Bloqueo/inspección de comandos con texto (`/msg`, `/r`) | M | N | P1 | — |
| **Chat firmado (1.19+)**: filtrar el **texto original**, cancelar sin reescribir la firma | — | N | P0 | Requisito técnico, no una función visible |

## 5. Integración con anticheat (no reimplementamos detección)

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **Adaptadores**: Grim, Vulcan, Matrix, Spartan, Polar (se activan si están presentes) | M | I | P1 | Cada uno con su API: no la misma para todos |
| **Alertas de flag** a staff: check, VL, ping, TPS, mundo | H | N | P1 | — |
| **`/check` unificado**: VL + historial de sanciones + notas + alts + replay | M | N | P1 | El comando estrella de investigación |
| **No sancionar por cada flag**: umbral conservador + revisión humana | A | N | P1 | Ping, lag y mecánicas legítimas dan falsos positivos |
| Escalado automático opcional por VL/repetición, registrando la fuente | A | N | P2 | — |
| Callbacks del anticheat en hilo Netty → re-agendar en el scheduler | — | N | P0 | Detalle técnico de correctitud |

## 6. Anti-abuso y detección

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **`/alts` / dupeIP**: cuentas por IP compartida | M | N | P1 | Nunca como prueba única |
| **Retención limitada** del historial de IP (ej. 60 días) y expiración | A | N | P1 | Privacidad: guardar solo lo necesario |
| **Exenciones** para IPs compartidas legítimas (familias, CGNAT, ej. Starlink) | A | N | P1 | Evita falsos positivos |
| **VPN/proxy**: consulta asíncrona a API externa (ej. IPHub), con caché, timeout y rate-limit | A | I | P2 | Nivel 1 = hosting/proxy (bloquear), nivel 2 = revisión, no ban |
| **Anti-Xray** | A | P | — | Ya viene en Paper (config por mundo): no lo tocamos |
| **Alertas de grief** y reconstrucción | A | I | P1 | Vía CoreProtect/BlockLedger, no propios |

## 7. Investigación y evidencia

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **Lookup / rollback / restore** de CoreProtect desde nuestros menús | A | I | P1 | Con preview antes de aplicar |
| **Historial de acciones**: bloques, cofres, inventario, kills, chat, comandos | M | I | P1 | CoreProtect ya lo graba y tiene API |
| **Restauración de inventario** (muerte, lag, grief) | M | I | P1 | InventoryRollback Plus / AxInventoryRestore |
| **Caso completo** (`/case <id>`): sanción + notas + chat + acciones + evidencia | H | N | P1 | Une todos los módulos |
| **Replay del anticheat** (AntiCheatReplay/BetterReplay) enlazado al caso | M | I | P2 | Nunca como prueba única |

## 8. Administración y operación del servidor

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **Modo mantenimiento**: bloqueo de acceso, bypass de staff, mensaje configurable | A | N | P1 | — |
| **Gestión de whitelist** desde el juego (añadir/quitar/ver) | A | N | P1 | — |
| **TPS/MSPT** + alerta a staff cuando baja del umbral | H | N | P1 | Basado en el spark que ya trae Paper |
| **Reinicio programado con avisos** escalonados (5m/1m/10s) | A | N | P1 | Paper solo tiene `restart-on-crash`; el scheduler lo aportamos |
| **Backup programado** (mundos + base de datos) con retención | A | I/P | P1 | Vía panel/host; nosotros avisamos y ejecutamos el comando |
| RAM y disco del host | A | I | P2 | Lo razonable desde la JVM (`/paper heap`, File API) |
| MOTD y slots dinámicos | A | N | P2 | — |
| `/paper` (chunkinfo, entity list, mobcaps, heap) envuelto en menús | A | P | P2 | Ya existe: solo más cómodo |
| Enlace al `/spark` del momento | H | I | P2 | — |
| Límites de entidades/tick, rangos de activación, límites por jugador | A | P | — | Config de Paper/Spigot: no duplicamos |

## 9. Rangos, permisos y auditoría

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **3 grupos con herencia**: `moderador` hereda `helper`, `admin` hereda `moderador` | A | I | P0 | LuckPerms es la fuente de verdad |
| **Nodos** `admintools.<módulo>.<acción>` y `.others`; default `notop` | A | N | P0 | Se declaran en `plugin.yml`/`paper` y los reparte LuckPerms |
| **Matriz de permisos** documentada (abajo) | A | N | P0 | Nadie recibe un wildcard por accidente |
| **Audit log** con cadena de hashes (estilo StaffLens) | A | N | P2 | Detecta manipulación del log |
| **Log de acciones de staff**: quién, a quién, qué, cuándo, resultado | M | N | P0 | También de las acciones que fallan |
| Nunca depender de **OP** como rango de staff | A | N | P0 | OP = infraestructura, no moderación |
| Eximir a los admins de las sanciones de rangos menores | A | N | P0 | — |

### Matriz de rangos (resumen)

| Módulo | Helper | Moderador | Admin |
|---|---|---|---|
| Sanciones | warn, mute temporal corto, kick | + ban temporal, unmute/unban, historial, notas, revocar con motivo | + ban permanente, IP-ban, plantillas, escalado, límites, rollback de staff |
| Staff tools | vanish, freeze, tp, seen, staff chat, reports | + staffmode completo, invsee/enderchest, heal/feed/fly/gm, spies, sesiones | + jail config, límites de acciones, gestión de staff |
| Reportes | crear, tomar y resolver | + prioridad, adjuntar historial/evidencia | + reasignar y cerrar los de otros |
| Chat | todo el filtrado automático, chatlog | + slowmode, chat clear, mute-all, bloqueo de comandos | + shadow mute |
| Anticheat | recibir alertas | + `/check`, revisión de casos | + escalado automático, configuración de umbrales |
| Investigación | `/case` en modo lectura | + rollback CoreProtect, restaurar inventario | + purgas y configuración |
| Servidor | ver TPS | + `/admintools info` detallado | + mantenimiento, whitelist, reinicio, backups |
| Permisos/auditoría | — | ver audit log propio | + todo el audit log, config del plugin, permisos |

## 10. Integraciones y puentes

| Función | Rango | Cómo | Prio | Notas |
|---|:--:|:--:|:--:|---|
| **Discord webhooks** para sanciones, reportes y alertas | A | I | P1 | Filtrar IPs y datos sensibles; limitar menciones |
| **DiscordSRV** (bridge de chat / relay de consola) | A | I | P2 | El canal de consola es consola remota: privado y restringido |
| **PlaceholderAPI** (sanción activa, reportes pendientes, staff en servicio) | M | I | P1 | Para scoreboards y menús |
| **Vault** (permisos) | A | I | P2 | Solo si hace falta compatibilidad |
| **i18n**: todos los mensajes en archivos (es/en) con MiniMessage | — | N | P0 | Nada de textos hardcodeados |
| **Base de datos**: H2/SQLite por defecto + MySQL/MariaDB para red, con migraciones versionadas | A | N | P0 | Mismo esquema para sanciones, notas, reportes, casos y auditoría |

---

## Lo que NO vamos a construir (y por qué)

| Área | Por qué no | Qué hacemos |
|---|---|---|
| Anticheat propio | Grim/Vulcan/Matrix/Spartan llevan años de I+D y se actualizan en cada versión de Minecraft | Adaptadores por API/eventos |
| Rollback de bloques propio | CoreProtect/Prism lo hacen y ya tienen API | Integración |
| Regiones y protección de terreno | WorldGuard/GriefPrevention ya lo resuelven y se integran con todo | Integración (solo jail propio si hace falta) |
| Gestor de permisos propio | LuckPerms es el estándar y todos lo leen | Nodos nuestros + LuckPerms |
| Panel web del servidor | Pterodactyl/Crafty ya lo hacen mejor (consola, archivos, backups) | Solo alertas y avisos |
| Economía, kits, warps, homes | No es administración ni moderación | Fuera del alcance |

## Ladder de sanciones propuesto (configurable)

| Reincidencia | Chat (insultos/spam) | Publicidad | Cheating (revisado) |
|---|---|---|---|
| 1ª vez | Warn + borrar mensaje | Warn + borrar mensaje | Ban temporal 3d |
| 2ª vez | Mute 1h | Mute 6h | Ban temporal 14d |
| 3ª vez | Mute 24h | Ban 7d | Ban permanente |
| 4ª vez | Ban 3d | Ban permanente | — |
| Expiración del ladder | 30 días sin sanciones reinicia el conteo | 30 días | 90 días |

Los warns decaen a los 7 días por defecto (configurable). Todo el ladder es editable sin recompilar.

## Fases de desarrollo

| Fase | Contenido |
|---|---|
| **v0.1 — MVP** | Permisos de los 3 rangos + núcleo de sanciones (ban/mute/warn/kick + historial + notas) + chat básico + staff básico (vanish, freeze, staff chat, reports) + persistencia local + i18n |
| **v0.2** | Anticheat adapters + `/check` + CoreProtect + inventario offline + auditoría + Discord webhooks + escalado/templates + jail |
| **v0.3** | VPN/alt API + panel de casos + modo staff con herramientas + mantenimiento/reinicio/backup programados + PlaceholderAPI + apelaciones |

## Fuentes principales consultadas

- Sanciones: [LiteBans](https://gitlab.com/ruany/LiteBans/-/wikis/Commands.md) · [LibertyBans](https://github.com/A248/LibertyBans) · [BanManager](https://banmanagement.com/docs/banmanager/commands) · [PunisherX](https://hangar.papermc.io/SyntaxDevTeam/PunisherX) · [LightBans](https://modrinth.com/project/DVuSteyb)
- Staff: [Staff++](https://wiki.staffplusplus.org/features/staff-mode) · [InvSee++](https://github.com/Jannyboy11/InvSee-plus-plus) · [EssentialsX](https://essentialsx.net/wiki/introduction) · [CMI](https://www.zrips.net/cmi/commands/) · [Warden](https://hangar.papermc.io/CSM/WardenManagementSystem) · [ReportSystem](https://modrinth.com/project/UELBYZcj) · [StaffLens](https://modrinth.com/project/U42khYED)
- Logs y permisos: [CoreProtect](https://docs.coreprotect.net/commands/) · [Prism 4](https://docs.prism-mc.org/) · [LuckPerms](https://luckperms.net/wiki/Usage) · [WorldGuard](https://worldguard.enginehub.org/en/latest/regions/flags/) · [InventoryRollback Plus](https://www.spigotmc.org/resources/inventoryrollback-plus-1-8-26-2.85811/)
- Anticheat y chat: [Grim API](https://github.com/GrimAnticheat/Grim/wiki/Developer-API) · [Matrix API](https://matrix.rip/docs/developer/api) · [Spartan API](https://github.com/Vagdedes/Spartan-AntiCheat) · [Vulcan](https://docs.bloom.host/plugins/vulcan) · [Polar](https://polar.top/) · [ChatSentinel](https://modrinth.com/project/d1aRSTsI) · [Chat firmado en Paper](https://docs.papermc.io/paper/dev/component-api/signed-messages/)
- Operación: [Comandos de Paper](https://docs.papermc.io/paper/reference/commands/) · [Configuración de Paper](https://docs.papermc.io/paper/reference/configuration/) · [DiscordSRV](https://docs.discordsrv.com/) · [plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/)
