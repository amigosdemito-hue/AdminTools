# Auditoría de código y simulación de ataque — AdminTools 0.4.5

**Alcance:** todo el proyecto (`src/main/java` + `src/main/resources`): 96 archivos, ~18 000 líneas.
**Fecha:** 30 de octubre de 2026.
**Método:** revisión manual dirigida por frentes de ataque, comprobaciones automatizadas de
coherencia (claves de idioma, ajustes de config, permisos, clases y métodos huérfanos), prueba
de inyección ejecutada con JUnit y ataque en vivo contra Paper 26.1.2 real con el jar 0.4.5.

> No se ha modificado ni una línea de código de producción durante esta auditoría. Los arreglos
> propuestos están descritos, no aplicados.

---

## Resumen

| # | Gravedad | Hallazgo | Estado |
|---|---|---|---|
| H1 | **Alta** | Inyección de MiniMessage en el nombre sancionado: el aviso público se puede convertir en un enlace o un texto clicable falso | Demostrado (PoC) |
| H2 | **Media-alta** | La protección de jerarquía sólo cubre sanciones: un helper puede encarcelar/congelar a un admin y confiscarle el inventario | Confirmado por código y permisos |
| H3 | **Media** | La confirmación en dos pasos no está ligada a los argumentos (`/clear A` habilita `/clear B`) | Confirmado |
| H4 | **Media** | `CommandOwnership` roba etiquetas globales (`/v`, `/ec`, `/gm`, `/ci`, `/inv`…) a otros plugins | Confirmado |
| H5 | **Media-baja** | `/admintools inspect` no comprueba permiso propio (el resto de subacciones sí) | Confirmado |
| H6 | **Baja** | Copias de inventario corruptas: `decode()` confía en la longitud leída de la base (OOM / excepción) | Confirmado |
| H7 | **Baja** | Contador de publicidad que nunca se reinicia: tras el umbral, cualquier anuncio castiga | Confirmado |
| H8 | **Baja** | Uso de la API de Bukkit desde hilos async (`Bukkit.getPlayer` en el mapeo de IPs, `Bukkit.getOperators`) | Confirmado |
| H9 | **Baja** | `/tppos` sin límites de coordenadas (carga de chunks a millones de bloques) | Confirmado |
| H10 | **Baja** | `/vanish <jugador>` permite a un helper cambiar el vanish de otro miembro del staff | Confirmado |
| H11 | **Baja** | Mapa `PlayerService.cache` sin límite y `ChatService.recent/lastMessage` sin limpiar al salir | Confirmado |
| H12 | **Informativa** | `Placeholders.staff_online` cuenta al staff en vanish | Confirmado |
| H13 | **Informativa** | Desajuste conocido al apagar con una condena guardándose (carrera del guardado asíncrono) | Ya documentado |

**Veredicto:** el núcleo de datos es sólido (no hay inyección SQL, ni ejecución de comandos en
consola con datos del usuario, ni path traversal en la importación). Los problemas reales están
en la **capa de presentación** (H1) y en la **coherencia de permisos/jerarquía** (H2, H3, H10),
que es donde este tipo de plugin se rompe en producción.

---

## H1 (Alta) — Inyección de MiniMessage en el nombre sancionado

**Dónde:** [SanctionService.java:420-426](../src/main/java/com/admintools/service/SanctionService.java#L420-L426)
(alimentado por [CommandSupport.java:39-47](../src/main/java/com/admintools/command/CommandSupport.java#L39-L47)
y [SanctionCommands.java:86](../src/main/java/com/admintools/command/SanctionCommands.java#L86)).

`/ban` acepta cualquier cadena como objetivo. Si el jugador no existe, el UUID se deriva del
nombre y **el nombre se guarda y se publica tal cual**:

```java
Component message = plugin.getMessages().get("sanctions.broadcast", ...,
        "player", sanction.targetName(),          // <-- sin Text.safe()
        "reason", Text.safe(sanction.reason()));  // el motivo sí se escapa
```

`Messages.get()` termina en `Text.mm(...)`, es decir, MiniMessage interpreta el valor. El motivo
está escapado; el **nombre no**. Resultado: el nombre desaparece como texto y se convierte en
formato/eventos del cliente.

**Prueba ejecutada (JUnit, camino real de `announce`):**

```java
nombre = "<click:open_url:'https://ejemplo.invalid/verifica'>Soporte</click>";
Text.mm(Text.substitute(plantilla, "player", nombre, ...));
```

Salida:

```
CLICK ENCONTRADO: ClickEvent{action=open_url, payload=TextImpl{value="https://ejemplo.invalid/verifica"}}
TEXTO PLANO: [[Sanción] Gabi172l aplicó Ban a Soporte: prueba (permanente)]
```

Y en el servidor real:

```
[09:13:53] [Sanción] CONSOLE aplicó Ban a Soporte: Sin motivo especificado (permanente)
[09:13:53] [AdminTools] Sanción #41 aplicada a Soporte (Ban, permanente).
```

El nombre visible pasó a ser «Soporte»: la etiqueta se consumió como marcado.

**Impacto:** cualquier staff con `admintools.sanction.ban/mute/warn` puede publicar en el chat
de **todos** (los ban son «loud») un mensaje con aspecto oficial que contiene un enlace externo,
texto con hover, fuente personalizada o colores de otro rango. Es el vector clásico de phishing
dentro del servidor y también permite suplantar el formato del propio plugin.

**Mismo patrón, menor alcance:**
- `/setjail <nombre>` no valida el nombre ([JailCommands.java:215](../src/main/java/com/admintools/command/JailCommands.java#L215)),
  y ese nombre se pinta en `/jails`, `/jaillist` y el aviso `jail.applied` al staff.
- `InvestigationCommands`/`/check` sí escapan lo que muestran; el patrón está bien aplicado en
  el 95 % del plugin. El fallo es la excepción, no la norma.

**Arreglo recomendado:**
1. Escapar siempre el nombre y el nombre de celda: `"player", Text.safe(sanction.targetName())`,
   `"jail", Text.safe(jail.name())`.
2. Validar el objetivo de `/ban` con `Util.isPlayerName(name)` cuando no exista ficha; hoy se
   puede crear una sanción sobre `"<red>cualquier cosa"`, que además nunca se podrá adoptar al
   entrar un jugador real.
3. Endurecer `Messages.get()` para que escape por defecto todos los `%placeholders%` y dejar un
   `getRaw()` explícito para los pocos casos que inyectan marcado a propósito (colores de rango,
   `sanctions.join-notice` con `<green>online`, etc.). Así el fallo no puede repetirse al añadir
   mensajes nuevos.

---

## H2 (Media-alta) — La jerarquía de rangos no protege cárcel, congelar ni utilidades

**Dónde:** `checkRank()` se invoca **sólo** desde `checkExempt()`
([CommandSupport.java:109-133](../src/main/java/com/admintools/command/CommandSupport.java#L109-L133)),
que a su vez sólo usan las sanciones. El resto de acciones destructivas sólo mira su nodo
`exempt`:

| Comando | Permiso | Comprobación actual | Consecuencia |
|---|---|---|---|
| `/jail` | `admintools.jail.use` (helper) | sólo `admintools.jail.exempt` ([JailCommands.java:85](../src/main/java/com/admintools/command/JailCommands.java#L85)) | un helper **encarcela a un moderador/admin** y le confisca el inventario |
| `/freeze` | `admintools.staff.freeze` (helper) | sólo `exempt.freeze` ([StaffCommands.java:52](../src/main/java/com/admintools/command/StaffCommands.java#L52)) | un helper deja bloqueado a un admin |
| `/vanish <otro>` | `admintools.staff.vanish` (helper) | ninguna ([StaffCommands.java:37-44](../src/main/java/com/admintools/command/StaffCommands.java#L37-L44)) | un helper saca del vanish a un admin y lo delata |
| `/gamemode`, `/clear`, `/god`, `/fly`, `/heal` | `admintools.staff.utility` (moderador) | ninguna ([UtilityCommands.java:77-101](../src/main/java/com/admintools/command/UtilityCommands.java#L77-L101)) | un moderador cambia el modo de juego o **vacía el inventario de un admin** |

`plugin.yml` confirma la herencia (`admintools.moderator` incluye `admintools.helper`, y
`admintools.admin` incluye al moderador), así que el rango inferior siempre tiene el nodo.

**Mitigación existente:** `/clear` guarda una copia antes de borrar y `/jail` también guarda el
inventario, así que el daño es reversible con `/restore` o `/unjail`. Aun así, es una
inconsistencia de diseño: el plugin protege la jerarquía para sancionar pero no para encerrar.

**Arreglo recomendado:** llamar a `checkRank(sender, target)` en `/jail`, `/freeze`,
`/vanish <otro>`, `/gamemode`, `/clear`, `/god`, `/fly`, `/heal` y `/tphere`. Coste: una línea
por comando; `checkRank` ya contempla consola, uno mismo y `admintools.sanction.force`.

---

## H3 (Media) — Confirmaciones que no están ligadas a los argumentos

**Dónde:** [ConfirmationService.java:44-52](../src/main/java/com/admintools/service/ConfirmationService.java#L44-L52)
compara sólo la cadena `action`. Dos llamadas usan una acción constante aunque el efecto dependa
del argumento:

- [UtilityCommands.java:93](../src/main/java/com/admintools/command/UtilityCommands.java#L93) →
  `"clear"`.
- [SanctionCommands.java:650](../src/main/java/com/admintools/command/SanctionCommands.java#L650) →
  `"unpunish-staff"`.

**Escenario:** `/clear Ana` (pide confirmación) y a continuación `/clear Beto` dentro de los 15 s
→ **Beto es vaciado sin confirmación**. Igual con `/unpunish staff Ana` seguido de
`/unpunish staff Beto`: revierte todas las sanciones de Beto sin el segundo paso.

**Arreglo recomendado:** incluir el objetivo en la clave, como ya hacen `rollback:uuid:id:parts`
y `restore:id`: `"clear:" + target.getUniqueId()` y `"unpunish-staff:" + staffName.toLowerCase()`.

---

## H4 (Media) — `CommandOwnership` se apropia de etiquetas de otros plugins

**Dónde:** [CommandOwnership.java:77-86](../src/main/java/com/admintools/command/CommandOwnership.java#L77-L86)
(`map.getKnownCommands().put(label, ours)`), con los alias de `plugin.yml`: `v`, `sc`, `inv`,
`ec`, `gm`, `ci`, `cl`, `cc`, `sm`, `hist`, `note`, `ptime`, `snap`, `rq`, `at`.

La intención es legítima (recuperar `/restart` de Spigot), pero el mecanismo no distingue «lo
tenía el servidor» de «lo tenía otro plugin»: si el servidor tiene EssentialsX/CMI, AdminTools
le quita `/ec`, `/gm`, `/ci`, `/v`… globalmente. Los jugadores de esos plugins pasan a recibir
`No tienes permiso` de AdminTools en lugar de su comando de siempre.

**Arreglo recomendado:** limitar la recuperación a una lista blanca (`restart`) y, para el
resto, dejar la etiqueta a quien la registró antes (o registrar el alias sólo si está libre:
`map.getCommand(label) == null`). El comentario del propio código ya dice que el caso real es
`/restart`; no hace falta más.

---

## H5 (Media-baja) — `/admintools inspect` sin comprobación de permiso

**Dónde:** [RootCommand.java:154-170](../src/main/java/com/admintools/command/RootCommand.java#L154-L170).
`status`, `reload`, `rollback`, `purge` y `acalert` llaman a `requirePermission(...)`; `inspect`
no. El comando raíz `/admintools` se registra sin permiso, así que **cualquier jugador** puede
ejecutar `/at inspect <algo>`.

No hay fuga de datos: el puente ejecuta `co lookup` **como el propio remitente** y CoreProtect
aplica sus permisos. El efecto es una llamada no autorizada más un `You do not have permission`
por intento, y la posibilidad de inyectar parámetros de CoreProtect dentro del propio `lookup`
(el prefijo `u:` está fijo, así que el alcance es limitado).

**Arreglo recomendado:** `requirePermission(sender, "admintools.investigate.check")` al inicio,
y validar el nombre con `Util.isPlayerName()`.

---

## H6 (Baja) — `decode()` confía en la longitud almacenada

**Dónde:** [SnapshotService.java:173](../src/main/java/com/admintools/service/SnapshotService.java#L173)
`byte[] items = new byte[in.readInt()];`.

Con una fila corrupta o manipulada (restauración de un `data.db` viejo, copia a medias, edición
manual) el entero puede ser negativo (`NegativeArraySizeException`) o enorme (`OutOfMemoryError`).
`readInventory()` sólo captura `IOException`, y `/invsee` de un jugador offline llama a este
método en el hilo principal: un OOM ahí mata el servidor.

**Arreglo recomendado:** comprobar `length > 0 && length <= MAX_SNAPSHOT_BYTES` (p. ej. 4 MiB),
capturar `RuntimeException` en `readInventory()` y devolver copia vacía.

---

## H7 (Baja) — El contador de publicidad nunca se reinicia

**Dónde:** [ChatService.java:169-174](../src/main/java/com/admintools/service/ChatService.java#L169-L174).
`advertising.merge(uuid, 1, Integer::sum)` es acumulativo y **no se resetea** al castigar ni con
el tiempo. Con `punish-after: 3`, el tercer anuncio de la vida de esa cuenta castiga, y **todos
los siguientes también**, aunque pasen meses. Tampoco se limpia al salir.

**Arreglo recomendado:** reiniciar el contador al castigar y aplicar la misma ventana temporal
que el antispam (`anti-spam.window-seconds`).

---

## H8 (Baja) — API de Bukkit desde hilos async

**Dónde:**
- [PlayerService.java:236-241](../src/main/java/com/admintools/service/PlayerService.java#L236-L241):
  el mapeo de `accountsOnIp()` llama a `Bukkit.getPlayer(id)`. Ese mapeo se usa desde
  `AsyncPlayerPreLoginEvent` (hilo de login) y desde la tarea async de aviso de alts.
- [ConnectionListener.java:81](../src/main/java/com/admintools/listener/ConnectionListener.java#L81):
  `Bukkit.getOperators()` dentro de `onPreLogin`.

Bukkit no garantiza sus estructuras fuera del hilo principal. En la práctica no ha fallado en las
pruebas, pero es una fuente clásica de `ConcurrentModificationException` bajo carga.

**Arreglo recomendado:** que el mapeo devuelva sólo datos y resolver «online» en el hilo
principal (`Bukkit.getScheduler().runTask`), y cachear la lista de operadores en memoria.

---

## H9 (Baja) — `/tppos` sin límites

**Dónde:** [UtilityCommands.java:131-157](../src/main/java/com/admintools/command/UtilityCommands.java#L131-L157).
Acepta `double` sin acotar: `/tppos 1e8 1e8 1e8` fuerza carga/generación de chunks a 100 millones
de bloques y puede tumbar el TPS o el servidor. Sólo lo puede usar staff con
`admintools.staff.teleport`, pero un helper lo tiene.

**Arreglo recomendado:** acotar a ±30 000 000 (límite del mundo) y a la altura válida del mundo,
y exigir `admintools.staff.teleport.worlds` si se indica otro mundo.

---

## H10 (Baja) — `/vanish <jugador>` sin jerarquía

Ya incluido en H2, se detalla aparte porque el efecto es de privacidad: un helper puede escribir
`/vanish Admin` y **sacar** al admin de vanish (o meterlo), sin que éste lo haya pedido. El
`/staffmode <otros>` ya exige `admintools.staff.mode.others`; el vanish debería seguir el mismo
patrón.

---

## H11 (Baja) — Cachés que crecen sin límite

- `PlayerService.cache` guarda un `PlayerRecord` por UUID para siempre.
- `ChatService.recent` y `ChatService.lastMessage` no se limpian en `PlayerQuitEvent`
  (`ChatService` no escucha la salida; sólo `ConnectionListener` limpia otros servicios).

En un servidor con miles de cuentas únicas, la memoria crece de forma monótona. Impacto real bajo
(unos cientos de bytes por jugador), pero es una fuga estructural.

---

## H12 (Informativa) — `staff_online` cuenta a los invisibles

[Placeholders.java:57-59](../src/main/java/com/admintools/integration/Placeholders.java#L57-L59)
cuenta a todo el que tenga `admintools.staff.staffchat`, incluidos los que están en vanish. Si se
usa en un marcador público, delata el número de staff conectado. Es una decisión de producto, no
un fallo técnico.

---

## Código muerto y desalineaciones

1. **`StaffListener.plain(Component)`** ([línea 742](../src/main/java/com/admintools/listener/StaffListener.java#L742)):
   método público sin una sola llamada. Se puede borrar.
2. **`BackupService.zip`** ([línea 140](../src/main/java/com/admintools/service/BackupService.java#L140)):
   `String base = sourceFile.getParentFile() == null ? "" : "";` — siempre `""`. Se pasa a
   `relativize(source, file, base)`, cuyo parámetro `base` **no se usa** ([línea 180](../src/main/java/com/admintools/service/BackupService.java#L180)).
   Quedó de una versión anterior; borrar los dos.
3. **Claves de idioma:** 527 en ES y 527 en EN, **idénticas y todas referenciadas** (las que
   parecen «sin usar» se componen dinámicamente: plurales `-one`, `rollback.reason-*`,
   `panel.action.*`, `discord.*-title`). Sin trabajo pendiente aquí.
4. **Ajustes de config:** 150 claves, todas leídas por `Settings` (las de `duration-limits` son
   permisos y se resuelven dinámicamente). Sin claves muertas.
5. **Clases:** 95 de 96 referenciadas; la única «suelta» es `AdminToolsAPI`, que es la API
   pública a propósito.
6. **Permisos:** los 79 nodos declarados en `plugin.yml` existen todos como permiso real y todo
   `hasPermission("admintools.…")` del código apunta a un nodo declarado. No hay nodos fantasma.

---

## Lo que se probó y está bien

| Prueba | Resultado |
|---|---|
| Inyección SQL por nombre/motivo/IP | `ban test_sqli' OR '1'='1` se guarda **literal**; todas las consultas usan `PreparedStatement` y `bind()` con `setObject` |
| Inyección de comandos a consola | El único `dispatchCommand(console, …)` usa un comando **de la config**, no entrada del usuario |
| Path traversal en `/jailimport` | Valida `getCanonicalFile()` dentro de `plugins/` ([JailCommands.java:317-330](../src/main/java/com/admintools/command/JailCommands.java#L317-L330)) |
| Permisos por comando | El registro central comprueba permiso, mínimo de argumentos (ignorando `-s`), límite de acciones y auditoría antes de ejecutar |
| Menús de inventario | Cancelan clic **y** arrastre; las copias se abren con holder propio y son de sólo lectura |
| Inventario de la cárcel | Se guarda antes de quitarlo y se marca `returned = 1` de forma síncrona: no hay doble entrega |
| Pool de base de datos | `permits.release()` siempre en `finally`; `insert` hace `commit`/`rollback` |
| Arranque y apagado | Paper 26.1.2 real: `AdminTools v0.4.5`, 2 celdas / 1 preso, sin errores, apagado limpio |
| `/ban` sin argumentos | Devuelve el uso correcto, sin `ArrayIndexOutOfBounds` |
| Pruebas | **42 pruebas, 0 fallos** |

---

## Cómo reproducir

```bash
# Pruebas unitarias
./gradlew cleanTest test

# Servidor de pruebas con el plugin
./gradlew runServer
```

El ataque en vivo se hizo arrancando `run/paper-26.1.2-74.jar` con el jar 0.4.5 en
`run/plugins/` y enviando por consola:

```
ban test_sqli' OR '1'='1
ban <click:open_url:'https://ejemplo.invalid'>Soporte
ban
jail
admintools inspect Notch
history test_sqli
jails
jaillist
stop
```

> Nota de método: si se envían comandos por consola antes de que Paper imprima `Done (…)`, el
> servidor responde `NullPointerException: … getLevel() is null`. Es un comportamiento de Paper
> durante el arranque, **no** del plugin; hay que esperar al `Done`.

---

## Orden de arreglo sugerido

1. **H1** (una línea en `announce` + validación del objetivo + escape por defecto en `Messages`).
2. **H2 + H10** (jerarquía en cárcel, freeze, vanish y utilidades).
3. **H3** (claves de confirmación con argumentos).
4. **H5** y **H4** (permiso de `inspect`; limitar el robo de etiquetas).
5. **H6, H7, H8, H9, H11** en una sola pasada de endurecimiento.
6. Limpieza de código muerto (dos sitios).
