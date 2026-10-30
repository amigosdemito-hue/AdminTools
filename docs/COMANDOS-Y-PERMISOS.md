# AdminTools — comandos y permisos

Referencia generada a partir de `plugin.yml` y del registro de comandos del plugin.
Los tres rangos están definidos con herencia: **helper → moderador → admin**.

El autocompletado (Tab) sugiere jugadores online, duraciones (`15m`, `1h`, `1d`, `permanent`),
subcomandos (`/reports claim`, `/snapshot save`...) y el flag `-s`, según el argumento en el que estés.

## Comandos

### Paneles por rango

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/helper` | `/helper` | `admintools.helper` | helper |
| `/moderator` | `/moderator` | `admintools.moderator` | moderador |
| `/admin` | `/admin` | `admintools.admin` | admin |

Los tres abren un panel con **todas las opciones que permite el rango** (acumulativo: moderador
incluye lo del helper y admin lo de los dos). Cada botón ejecuta el comando real como el propio
jugador, así que el panel no salta permisos, límites de acciones ni auditoría, y si un permiso
cambia el botón aparece o desaparece solo.

Tres interfaces, elegidas en `panel.ui`:

| Valor | Java | Bedrock |
|---|---|---|
| `auto` (por defecto) | diálogo nativo 1.21.6+ | formulario nativo de Floodgate si está instalado; si no, diálogo Java |
| `dialog` | diálogo nativo | diálogo Java (Geyser lo dibuja) |
| `inventory` | menú de inventario | menú de inventario |

Con la interfaz de inventario, los datos que falten (jugador, duración, motivo…) se piden por chat;
la respuesta no se difunde y caduca a los `panel.chat-input-seconds` segundos. Escribe `cancelar`
para dejarlo.

### Sanciones

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/ban` | `/ban <jugador> [duración] [motivo]` | `admintools.sanction.ban` | moderador |
| `/tempban` | `/tempban <jugador> <duración> [motivo]` | `admintools.sanction.ban` | moderador |
| `/ipban` | `/ipban <jugador\|ip> [motivo]` | `admintools.sanction.ipban` | admin |
| `/unban` | `/unban <jugador|ip> [motivo]` | `admintools.sanction.unban` | moderador |
| `/mute` | `/mute <jugador> [motivo]` | `admintools.sanction.mute` | helper* |
| `/tempmute` | `/tempmute <jugador> <duración> [motivo]` | `admintools.sanction.mute` | helper* |
| `/ipmute` | `/ipmute <jugador\|ip> [duración] [motivo]` | `admintools.sanction.ipmute` | admin |
| `/shadowmute` | `/shadowmute <jugador> [duración] [motivo]` | `admintools.sanction.shadowmute` | moderador |
| `/unmute` | `/unmute <jugador\|ip> [motivo]` | `admintools.sanction.unmute` | helper |
| `/warn` | `/warn <jugador> [motivo]` | `admintools.sanction.warn` | helper |
| `/tempwarn` | `/tempwarn <jugador> <duración> [motivo]` | `admintools.sanction.warn` | helper |
| `/kick` | `/kick <jugador> [motivo]` | `admintools.sanction.kick` | helper |
| `/history` | `/history <jugador> [página]` | `admintools.sanction.history` | helper |
| `/notes` | `/notes add <jugador> <texto>` · `/notes remove <id>` · `/notes <jugador>` | `admintools.sanction.notes` (+ `.add`) | helper / moderador |
| `/punish` | `/punish <plantilla> <jugador> [motivo]` | `admintools.sanction.templates` | moderador |
| `/ladder` | `/ladder <escalera> <jugador> [motivo]` | `admintools.sanction.ladder` | moderador |
| `/unpunish` | `/unpunish <jugador\|staff> <nombre> [motivo]` | `admintools.sanction.rollback` | admin |

\* El helper puede mutear, pero el límite de duración de su rango (`sanctions.duration-limits`) decide cuánto.

> Cualquier comando de sanción acepta el flag `-s` para aplicarla en silencio (solo la ve el staff).
> Una sanción es permanente solo si el rango tiene `admintools.limit.admin` (o el nodo de límite que
> corresponda con valor `permanent` en la config); si no, se aplica la duración máxima del rango.
>
> **Sancionar a quien nunca ha entrado:** si el nombre no tiene ficha en el servidor, la sanción se crea
> igualmente sobre el nombre y se aplica en cuanto esa cuenta conecte (y se asocia a su UUID real en ese
> momento). `history`, `unban` y `unmute` funcionan sobre esos nombres también.

### Herramientas de staff

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/vanish` | `/vanish [jugador]` | `admintools.staff.vanish` (+`.see`; `.others` para otros) | helper |
| `/staffmode` | `/staffmode [jugador\|on\|off]` | `admintools.staff.mode` (+`.others`) | moderador |
| `/spectate` | `/spectate [jugador]` | `admintools.staff.mode` (+`.others` para cortar la de otro) | moderador |
| `/follow` | `/follow <jugador\|stop>` | `admintools.staff.follow` | helper |
| `/playtime` | `/playtime [jugador]` | `admintools.staff.seen` | helper |
| `/freeze` | `/freeze <jugador> [motivo]` | `admintools.staff.freeze` | helper |
| `/staffchat` | `/staffchat [mensaje]` (sin argumentos alterna el canal; la consola puede escribir) | `admintools.staff.staffchat` | helper |
| `/spy` | `/spy` (mensajes privados) | `admintools.staff.spy` | moderador |
| `/cspy` | `/cspy` (comandos ejecutados; nunca muestra `/login`, `/register`, correo ni 2FA) | `admintools.staff.spy.command` | moderador |
| `/stafflist` | `/stafflist` | `admintools.staff.list` | helper |
| `/duty` | `/duty` | `admintools.staff.duty` | moderador |
| `/seen` | `/seen <jugador>` | `admintools.staff.seen` | helper |
| `/invsee` | `/invsee <jugador>` | `admintools.staff.invsee` (+`.modify`) | moderador |
| `/enderchest` | `/enderchest <jugador>` | `admintools.staff.enderchest` (+`.modify`) | moderador |
| `/back` | `/back` (también vuelve al punto de muerte) | `admintools.staff.teleport` | helper |
| `/heal` `/feed` `/fly` `/god` `/gamemode` `/clear` | `/<comando> [jugador]` | `admintools.staff.utility` | moderador |
| `/tp` `/tphere` `/tppos` `/back` | ver `/at help` | `admintools.staff.teleport` | helper |

**Barra rápida del modo staff** (se devuelve tu inventario al salir):

| Herramienta | Clic derecho en el aire o un bloque | Clic derecho sobre un jugador |
|---|---|---|
| Brújula | Abre la lista de jugadores online (por páginas) | Te teletransporta a ese jugador |
| Vara de blaze | Congela al que estés mirando | Congela o descongela a ese jugador |
| Reloj | Activa o desactiva tu vanish | Activa o desactiva tu vanish |
| Libro | Abre la ficha del que estés mirando | Abre la ficha de ese jugador |

Al pasar el ratón por una cabeza del menú se ve su ficha en cinco líneas: edición (Java o Bedrock), ping
y modo de juego; ubicación; vida, comida y dinero (si hay Vault con un plugin de economía); tiempo jugado
y sesiones; y las sanciones activas. Las insignias `VANISH`, `STAFF` y `DUTY` y la línea de sanciones sólo
aparecen cuando aplican, así que la ficha de un jugador limpio queda en cuatro líneas.

Clic en la cabeza te teletransporta por el mismo camino que `/tp`, así que respeta la exención y deja
`/back` disponible.

### Reportes, chat e investigación

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/report` | `/report <jugador> <motivo>` (además del aviso en el juego, llega a `reports.discord-webhook`) | `admintools.report` (**todos**) | jugador |
| `/reports` | `/reports [claim\|resolve\|reject\|info] [id] [nota]` | `admintools.report.staff` | helper |
| `/chatlog` | `/chatlog <jugador> [cantidad]` | `admintools.chat.log` | helper |
| `/clearchat` | `/clearchat [líneas]` | `admintools.chat.clear` | moderador |
| `/slowmode` | `/slowmode <segundos\|off>` | `admintools.chat.slowmode` | moderador |
| `/muteall` | `/muteall [on\|off]` | `admintools.chat.muteall` | admin |
| `/check` | `/check <jugador>` | `admintools.investigate.check` | helper |
| `/alts` | `/alts <jugador\|ip> [ip]` | `admintools.investigate.alts` (+`.ip`) | moderador / admin (IP) |
| `/case` | `/case <jugador>` | `admintools.investigate.case` | moderador |
| `/snapshot` | `/snapshot <jugador> [save\|list\|view\|restore] [id]` | `admintools.investigate.snapshot` | moderador |
| `/restore` | `/restore <jugador> <id>` | `admintools.investigate.restore` | admin |

### Cárcel

Cárcel con varias celdas y radio de fuga, basada en xJail pero con historial de condenas,
devolución a la posición original y tiempo que sólo corre mientras el preso está conectado.

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/jail` | `/jail <jugador> [duración] [cárcel] [motivo]` | `admintools.jail.use` | helper |
| `/unjail` | `/unjail <jugador> [motivo]` | `admintools.jail.use` | helper |
| `/jailtime` | `/jailtime [jugador]` (sin argumentos: la tuya, sin permiso) | `admintools.jail.view` | todos / helper |
| `/jaillist` | `/jaillist` | `admintools.jail.view` | helper |
| `/setjail` | `/setjail <nombre> [radio]` | `admintools.jail.manage` | moderador |
| `/deljail` | `/deljail <nombre>` | `admintools.jail.manage` | moderador |
| `/jails` | `/jails` | `admintools.jail.view` | helper |
| `/jailtp` | `/jailtp <jugador>` | `admintools.jail.use` | helper |
| `/jailimport` | `/jailimport [ruta\|xjail] [--dry]` | `admintools.jail.import` | admin |

- **Celda al azar:** `/jail` sin celda manda al preso a una celda al azar entre las que tienen mundo válido,
  así que no acaba siempre en la misma. El mensaje de confirmación dice en cuál quedó. Con
  `jail.random-cell: false` vuelve a usarse siempre `jail.default`; esa celda también es la reserva si no
  hay ninguna válida entre las que elegir.
- Un preso no rompe ni coloca bloques, no interactúa, no recibe ni hace daño, no usa perlas ni fruta del
  coro y sólo puede escribir en los comandos de `jail.allowed-commands` (más reportes y mensajes privados).
- **Inventario**: al encarcelar se le guarda el inventario completo (36 espacios, armadura y mano secundaria) y
  se le quita; al salir se le devuelve exacto. Si sale de la cárcel estando desconectado, se le entrega en cuanto
  entra. Lo que tenga encima en ese momento se suelta al suelo antes de poner la copia, así que no hay duplicados.
  Se desactiva con `jail.confiscate-inventory: false`.
- **Radio**: `/setjail` usa `jail.default-radius` (2 bloques por defecto) cuando no se indica otro. El área es
  un cuadrado horizontal centrado en el punto de la celda (2 = 5x5), con margen vertical: las esquinas también
  cuentan, al contrario que una esfera.
- **Fugas**: en cuanto el preso cruza un bloque y sale del área, se le devuelve al centro de la celda al instante
  (no espera al tic de cada segundo), así que una puerta abierta no sirve para escapar. El staff recibe el aviso.
- **Modo aventura** (`jail.adventure-mode: true`): mientras dura la condena el preso va en modo aventura, así que
  su propio cliente deja de romper y colocar bloques. Esto evita el bloque fantasma de Bedrock/Geyser (el jugador
  veía el bloque roto y se quedaba trabado dentro). Al salir se le devuelve su modo de juego original, también si
  la condena terminó mientras estaba desconectado. Al picar se le avisa de que no puede.
- Al cumplir la condena vuelve a donde estaba; al morir reaparece en la celda.
- `admintools.jail.bypass` ignora las restricciones y `admintools.jail.exempt` protege a un jugador de ser
  encarcelado.
- **`/jailimport`** lee la carpeta `plugins/xJail` (SQLite o YAML): descubre las tablas por sus columnas en
  vez de depender de un esquema fijo, importa celdas y presos, omite lo que ya existe y acepta `--dry` para
  simular sin escribir nada.

### Rollback de inventarios

Copias completas al estilo de InventoryRollbackPlus, mejoradas: inventario, enderchest, posición, vida,
comida, XP y modo de juego, con menús de inventario, restauración **selectiva**, restauración de un solo
espacio y borrado de copias.

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/rollback` | `/rollback` (menú de jugadores) · `/rollback <jugador> [página]` | `admintools.rollback.view` | moderador |
| `/rollback` | `/rollback <jugador> #id` (menú de la copia) | `admintools.rollback.view` | moderador |
| `/rollback` | `/rollback <jugador> #id confirm [items\|ender\|position\|stats\|all]` | `admintools.rollback.restore` | moderador |
| `/rollback` | `/rollback <jugador> #id delete` · borrar desde el menú | `admintools.rollback.restore` | moderador |
| `/rollback save` | `/rollback save <jugador>` | `admintools.rollback.save` | moderador |
| `/rollback purge` | `/rollback purge <días>` | `admintools.rollback.purge` | admin |

- Copia automática al entrar, salir, morir y cambiar de mundo (`rollback.events`), más un intervalo opcional.
- Antes de cada restauración se guarda el estado actual con motivo `seguridad`, así que un error del staff
  se deshace restaurando esa copia.
- Retención independiente por motivo (`rollback.keep`): las copias viejas se borran solas.
- Menús: jugadores con copias → copias del jugador → contenido → enderchest → acciones → confirmación.
  El contenido se muestra con el mismo orden que el inventario del jugador (inventario, armadura y mano
  secundaria) y un clic en un ítem restaura sólo ese espacio.
- Los menús son de sólo lectura: nada se coge ni se mueve, y toda restauración o borrado se confirma en un
  menú aparte. `#id` distingue la copia del número de página.

### Operación y núcleo

| Comando | Uso | Permiso | Rango |
|---|---|---|---|
| `/admintools` | `/at <help\|status\|reload\|inspect\|rollback\|purge\|acalert>` | según subcomando | — |
| `/maintenance` | `/maintenance [on\|off\|status]` | `admintools.ops.maintenance` (+`.bypass`) | admin |
| `/restart` | `/restart [cancel\|<segundos>\|now]` | `admintools.ops.restart` | admin |
| `/backup` | `/backup [list\|now]` | `admintools.ops.backup` | admin |
| `/audit` | `/audit [jugador] [página]` | `admintools.ops.audit` | admin |
| `/watchdog` | `/watchdog [status\|test]` | `admintools.ops.watchdog` | admin |

`/at inspect` y `/at rollback` son atajos a CoreProtect (requieren CoreProtect instalado y sus permisos).
`/at inspect` pide además `admintools.investigate.inspect`, como el resto de subcomandos de `/at`.

`/at acalert <jugador> <anticheat> <check> <vl> [detalles]` es el puente de anticheats: cualquier
plugin que sepa ejecutar un comando al detectar un flag (Grim, Vulcan, Matrix, Spartan, Polar…) puede
enviarlo y la alerta llega al staff con permiso `admintools.investigate.check`. El último flag recibido
aparece en `/check`, así que no hace falta copiar y pegar de la consola.

```yaml
# Ejemplo de enganche en un anticheat que permita "comando al alertar":
# /at acalert %player% Vulcan KillAura %vl%
```

> **Nota sobre `/restart`:** Spigot registra su propio `/restart` antes que los plugins, así que el plugin
> recupera esa etiqueta al arrancar (se ve en el log: *«Comando /restart recuperado»*) y la redirige a su
> gestor, con cuenta atrás y avisos. Si no pudiera recuperarla, seguiría disponible como `/admintools:restart`.

---

### Cómo se comportan las herramientas delicadas

- **`/vanish`** oculta al staff también para los jugadores que entren después, suprime su mensaje de entrada,
  hace que los mobs no lo fijen como objetivo, impide recoger ítems del suelo (`staff.vanish-pickup: true`
  para permitirlo; también bloquea los orbes de experiencia), no aparece en los mensajes de muerte ni
  de logros, abre los contenedores **sin animación ni sonido** para los demás
  (`staff.vanish-silent-containers: false` para el comportamiento normal) y lo devuelve al mundo real
  si ataca a alguien.
  - **Sobrevive a la reconexión y al reinicio:** el estado se guarda en `plugins/AdminTools/vanish.yml`,
    así que si el staff sale con el vanish activo y vuelve a entrar sigue oculto y los contenedores
    siguen abriéndose sin animación ni sonido, sin tener que escribir `/vanish` otra vez (al entrar le
    avisa por el chat de que el vanish sigue activo). También aguanta un reinicio del servidor o una
    recarga del plugin. Para apagarlo basta con `/vanish`.
  - **Sólo los invisibles se ven entre sí:** al activar el vanish el staff desaparece también para el
    resto del staff visible, así que un moderador que no está en vanish no se entera de que hay alguien
    mirando. Dos miembros en vanish sí se ven (con el brillo del apartado siguiente) para coordinarse.
    Con `staff.vanish-see-requires-vanish: false` vuelve el modelo antiguo, donde el permiso
    `admintools.staff.vanish.see` basta aunque no estés invisible.
  - **Brillo con el color del rango:** en vanish se aplica el efecto `GLOWING` y el color lo pone un
    equipo del marcador de cada observador (el efecto siempre brilla en blanco): verde para helper,
    amarillo para moderador y rojo para admin, ajustable en `staff.vanish-glow`. También sirve para
    distinguir a un miembro del staff invisible de un jugador normal. Se reaplica al entrar y al
    reaparecer, y se quita del marcador al salir del vanish o del servidor.
  - **Acciones silenciosas:** en vanish las puertas, trampillas y vallas con puerta se abren sin sonido
    (el bloque se acciona por API, sin sonido, y el staff sigue pudiendo pasar); botones y palancas no se
    accionan, y no se puede comer ni beber, porque el sonido de comer lo genera el cliente y no hay forma
    de silenciarlo desde el servidor. Las puertas y trampillas de hierro no se abren con esto. Se apaga
    con `staff.vanish-silent-actions: false`.
  - **Sin invisibilidad fantasma:** al entrar, la visibilidad se reconcilia en los dos sentidos. Si el
    estado dice vanish, se vuelve a ocultar; si no, se vuelve a mostrar a todos. Evita el caso en el que
    el staff queda invisible para los clientes (que recuerdan por UUID a quién tienen oculto) mientras el
    plugin lo trata como visible, con contenedores animados y sonido.
  - La apertura se hace con el jugador marcado como espectador solo durante esa llamada, que es lo que
    usa el servidor para no mover la tapa ni reproducir el sonido; el cliente no recibe ningún paquete
    de cambio de modo y el staff sigue con su modo de juego, su vuelo y sus efectos intactos.

- **Espectador de vigilancia (`/spectate` o el visor de la hotbar del modo staff)** pone al staff en el
  espectador **de verdad**: atraviesa bloques, no puede tocar nada y los demás no lo ven. No es una
  imitación del vanish, así que no se le aplican los contenedores silenciosos ni el brillo por rango, y
  mientras dura **pierde la barra rápida** (el espectador no la muestra). Por eso el tiempo es corto:
  - **Duración y recarga:** `staff.spectate.seconds` (300 s = 5 min) y `staff.spectate.cooldown-seconds`
    (900 s = 15 min). Al agotarse el tiempo se sale solo y avisa; `/spectate` sale antes de tiempo.
  - **Persistencia:** la sesión y la recarga se guardan en `plugins/AdminTools/spectate.yml`. Si el
    servidor se cae con alguien dentro, al volver a entrar se le devuelve su modo de juego y la recarga
    se respeta igual.
  - **No sobrevive al modo staff:** al salir de `/staffmode` se cierra la sesión y se devuelve el modo.
  - **Limitación del cliente:** en el espectador vanilla el cuerpo no se mantiene. Para quien no es
    espectador eres invisible; para otro espectador apareces como una cabeza flotante. No hay paquete
    que separe «colisión de espectador» de «modelo visible»: es una regla del cliente, no del plugin.

- **Registro de acciones del staff en Discord** (`staff.action-log.discord: true`, con el webhook de
  `staff.action-log.webhook`, o `announcements.staff-webhook`, o el general de `integrations.discord`, en
  ese orden; `/at status` dice de cuál se está tomando): anota cada comando y cada herramienta que usen
  los jugadores con rango
  `helper`, `moderator` o `admin`, en un mensaje agrupado cada 3 segundos para no chocar con el límite de
  envíos de Discord. Las contraseñas, correos y dobles factores se registran sin argumentos, y los
  motivos se limpian de menciones (`@everyone`) y de marcado. La consola no entra en este registro.
  - **No sólo los comandos del plugin.** Con `staff.action-log.all-commands: true` (por defecto) también se
    anotan los que ejecuta el staff desde cualquier otro sitio: `/give`, `/gamemode`, `/tp`, `/msg`, los de
    EssentialsX, WorldEdit, etc. Se escucha el comando antes de que lo reciba nadie, así que no hay que
    envolver ni parchear los otros plugins, y los alias (`/at`, `/gm`) cuentan como propios para no
    duplicar líneas. Todo lo interceptado se guarda además en la auditoría de la base, así que aparece en
    `/audit` aunque no haya Discord configurado.
  - Se abren así **todos** los contenedores con interfaz: cofre, cofre trampa, barril, todos los shulkers,
    horno, alto horno, ahumador, tolva, dispensador, dropper, alambique, crafter, mesa de libros, estantería
    tallada, estantes, lectern, enderchest, mesa de trabajo, encantamientos, los tres yunques, afiladora,
    cartografía, telar, herrería, cortapiedras, barcas con cofre y vagonetas con cofre o tolva.
  - En **clientes de Bedrock** (Geyser/Floodgate) los shulkers se abren a través de una copia en un cofre
    virtual: ese cliente anima la caja que esté en la posición del contenedor aunque el servidor no envíe
    el evento del bloque, así que abrir el shulker real seguiría moviendo la tapa. Cada cambio de la copia
    se escribe en el shulker real en el mismo instante y, si el bloque desaparece mientras la vista está
    abierta, lo que quede en la copia vuelve al jugador en lugar de perderse.
  - **Las cajas de shulker nunca se abren en su propia posición.** Se abre una copia de 27 huecos con el
    mismo nombre y contenido, y cada clic, arrastre o cierre se escribe en el shulker de verdad. Es la
    única forma de que un cliente de Bedrock no anime la tapa: Geyser abre el contenedor en la posición
    del bloque tocado y el cliente de Bedrock dibuja abierto el shulker que haya ahí, sin importar el tipo
    de menú que mande el servidor. En Java el resultado es idéntico al inventario real, que nunca animó
    la tapa. El motivo de que se abra por copia también en Java es que así no depende de reconocer el
    cliente; la señal detectada queda anotada en la consola.
  - **No** se interceptan caballos, burros, mulas, llamas, camellos, aldeanos ni allays aunque también
    tengan inventario: abrirlos rompería montar o comerciar. Tampoco el tocadiscos ni la maceta decorada,
    que no tienen interfaz propia y se usan con un clic normal.
- **`/freeze`** bloquea el movimiento pero **deja girar la cámara**, para que el staff pueda hablar con el
  jugador congelado; también bloquea clics de inventario, dropeo de objetos y comandos (salvo `/report`).
  El motivo es opcional: se anota en la auditoría y viaja al anuncio de Discord.
- **`/invsee` y `/enderchest`** se abren en modo lectura salvo que se tenga el permiso `.modify`
  correspondiente a esa vista concreta.
- **`/cspy`** omite comandos sensibles (`/login`, `/register`, `/email`, 2FA...) para no filtrar contraseñas.

## Permisos adicionales (no son comandos)

| Nodo | Para qué | Defecto |
|---|---|---|
| `admintools.helper` / `.moderator` / `.admin` | rangos completos, con todos sus hijos | op |
| `admintools.limit.new` / `.mod` / `.admin` | límite de duración de sanciones (valor en `sanctions.duration-limits`) | op (admin) |
| `admintools.exempt.sanctions` | inmune a sanciones de otros | false |
| `admintools.sanction.force` | puede sancionar a un rango igual o superior | false |
| `admintools.sanction.ipmute` | silenciar IPs | op |
| `admintools.staff.follow` | seguir a un jugador con `/follow` | op |
| `admintools.staff.mode` / `.others` | modo staff propio / de otros | op |
| `admintools.staff.vanish.others` | activar o quitar el vanish a otro miembro del staff | op |
| `admintools.staff.spectate.others` | cortar la sesión de espectador de otro jugador | op |
| `admintools.investigate.inspect` | usar `/at inspect` | op |
| `admintools.staff.limit.bypass` | ignora el límite de acciones por minuto | false |
| `admintools.anticheat.report` | enviar flags por `/at acalert` | op |
| `admintools.exempt.freeze` | no se puede congelar | false |
| `admintools.staff.teleport.exempt` | no se puede teletransportar | false |
| `admintools.chat.filter.bypass` | ignora filtros, antispam y antripublicidad | false |
| `admintools.chat.cooldown.bypass` | ignora el cooldown de chat | false |
| `admintools.report.bypass-cooldown` | ignora el cooldown de reportes | false |
| `admintools.ops.maintenance.bypass` | puede entrar con el servidor en mantenimiento | op |
| `admintools.jail.bypass` | ignora las restricciones de la cárcel | false |
| `admintools.jail.exempt` | no se le puede encarcelar | false |
| `admintools.rollback.restore` | restaurar copias de inventario | op |
| `admintools.rollback.save` | crear copias manuales | op |
| `admintools.rollback.purge` | borrar copias antiguas | op |

### Protecciones contra el mal uso

- **Jerarquía de rangos** (`sanctions.protect-higher-ranks: true`): un miembro del staff no puede
  sancionar a alguien de su mismo rango o superior. La consola y quien tenga
  `admintools.sanction.force` quedan fuera. El escalado automático nunca sanciona a alguien con
  `admintools.helper`.
- **Límite de acciones por minuto** (`staff.action-limit`): las sanciones y los comandos que mueven
  datos (clear, freeze, restore, mantenimiento, reinicio, backups, clearchat, muteall, slowmode…)
  cuentan para un tope por minuto y por staff. Frena un ban masivo desde una cuenta comprometida sin
  molestar en el uso normal; `admintools.staff.limit.bypass` lo desactiva para un usuario.
- **Confirmación en dos pasos** para lo destructivo: `/at purge`, `/restore`, `/clear` y
  `/unpunish staff` piden repetir el comando en 15 segundos. Así no se borra nada por un dedazo.
- **Decaimiento de advertencias** (`sanctions.warn-decay-days`): con 7, una advertencia deja de contar
  para el escalado y para `/history` a los 7 días; con 0 (por defecto) no caduca nunca. `/tempwarn`
  permite además una advertencia con fecha de caducidad propia.

### Límites de duración por rango

En `config.yml`, la duración máxima la decide el **permiso más alto** que tenga el staff:

```yaml
sanctions:
  duration-limits:
    admintools.limit.new: 1d
    admintools.limit.mod: 30d
    admintools.limit.admin: permanent
```

Si un staff intenta una duración mayor a la permitida, el plugin aplica su límite máximo y lo avisa
(o rechaza la sanción si no tiene ningún límite asignado).

---

## Placeholders (PlaceholderAPI)

`%admintools_sanciones%`, `%admintools_baneados%`, `%admintools_silenciados%`,
`%admintools_reportes%`, `%admintools_staff_online%`, `%admintools_tps%`, `%admintools_mspt%`,
`%admintools_rango%`.

---

## API para otros plugins

```java
AdminToolsAPI api = AdminToolsAPI.get();          // null si AdminTools no está instalado
if (api.isMuted(uuid)) { /* ... */ }
api.punish(PunishmentType.BAN, uuid, "Ana", "Cheating", TimeUtil.PERMANENT, "AnticheatX");
api.anticheatAlert(player, "Vulcan", "KillAura", 12, "detalles internos");
```

Las alertas de anticheat se envían al staff con permiso `admintools.investigate.check`.
