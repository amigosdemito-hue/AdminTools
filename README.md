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
| **Anti-abuso** | jerarquía de rangos, límite de acciones por minuto, confirmación en comandos destructivos, decaimiento de warns |

Detalle completo de cada función en [docs/LISTA-FUNCIONES.md](docs/LISTA-FUNCIONES.md) y la
referencia de comandos y permisos en [docs/COMANDOS-Y-PERMISOS.md](docs/COMANDOS-Y-PERMISOS.md).

---

## Instalación rápida

```bash
./gradlew build                       # compila y arma el jar final
cp build/libs/AdminTools-0.3.3.jar servidor/plugins/   # el jar "gordo", no el -plain
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
geometría del área de la cárcel y lectura del tipo de sanción guardado. Las integraciones con Paper
(inventarios, menús, eventos) se siguen verificando arrancando un servidor real.

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
- **admin** — todo + IP-ban, IP-mute, bans/mutes permanentes, reversión de sanciones, muteall, mantenimiento, reinicio, backups, auditoría, recarga, restauración de inventarios

`/at help` muestra exactamente los comandos que puede usar cada rango.

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
| `integrations.*` | activar/desactivar LuckPerms, CoreProtect, PlaceholderAPI y alertas de anticheat |

El webhook de Discord es un secreto: déjalo en `config.yml` del servidor, no en el repositorio.

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
