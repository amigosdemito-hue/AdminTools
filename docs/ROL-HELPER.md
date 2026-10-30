# Rango HELPER — permisos y protocolo de uso

> Manual de AdminTools · servidor **semi-anárquico** · rango inicial del equipo de staff.
> Referencia técnica de comandos: [COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md).

---

## 1. Qué es el rol

El helper es la primera línea: **ve y resuelve**, pero no castiga de gravedad. Su trabajo es
recibir reportes, comprobar pruebas, contener la situación (congelar, mutear, echar) y dejar todo
documentado para que un moderador decida si hace falta escalar.

En un servidor **semi-anárquico** el límite es doble:

| Es jugada legítima (no se sanciona) | Es falta (sí se sanciona) |
|---|---|
| Robar, saquear bases sin protección, PvP fuera de la spawn, guerras de clanes, romper lo ajeno en terreno libre | Hacks/clientes (killaura, xray, fly, reach…), robo de cuentas, publicidad, insultos/gore/doxxing, uso de bugs de dupe, crashers, reportes falsos y acoso |

Si dudas de si algo cae en la primera columna, **no lo sanciones**: deja el caso en manos del moderador.

---

## 2. Cómo se otorga

```bash
/lp creategroup helper
/lp group helper permission set admintools.helper true
/lp user <jugador> parent set helper
```

`admintools.helper` trae todos los hijos montados en `plugin.yml`, así que con un solo nodo el rango
está completo. Está pensado con herencia: `admin → moderador → helper`.

---

## 3. Permisos

| Nodo | Qué abre |
|---|---|
| `admintools.sanction.warn` | `/warn`, `/tempwarn` |
| `admintools.sanction.mute` | `/mute`, `/tempmute` (limitado por `admintools.limit.new`) |
| `admintools.sanction.unmute` | `/unmute` |
| `admintools.sanction.kick` | `/kick` |
| `admintools.sanction.history` | `/history` |
| `admintools.sanction.notes` | `/notes` (leer); escribir requiere `.add` (moderador) |
| `admintools.limit.new` | Duración máxima de sanción: **1 día** |
| `admintools.staff.vanish` (+ `.see`) | `/vanish` y ver a otros en vanish |
| `admintools.staff.freeze` | `/freeze` |
| `admintools.staff.staffchat` | `/staffchat` |
| `admintools.staff.seen` | `/seen`, `/playtime` |
| `admintools.staff.list` | `/stafflist` |
| `admintools.staff.teleport` | `/tp`, `/tphere`, `/tppos`, `/back` |
| `admintools.staff.follow` | `/follow` |
| `admintools.jail.use` | `/jail`, `/unjail`, `/jailtp` |
| `admintools.jail.view` | `/jaillist`, `/jails`, `/jailtime` |
| `admintools.report.staff` | `/reports` (cola) |
| `admintools.report.claim` | `/reports claim` (y `resolve` / `reject` / `info`)
| `admintools.chat.log` | `/chatlog` |
| `admintools.investigate.check` | `/check` (ficha y último flag de anticheat) |

**No tiene:** `ban`, `tempban`, `punish`, `ladder`, `invsee`, `spy`, `cspy`, `duty`, `staffmode`,
`spectate`, `clearchat`, `alts`, `case`, `snapshot`, `rollback`, `setjail`, `audit`, `restart`, `backup`,
`maintenance`, `muteall`, `restore`.

---

## 4. Panel y herramientas

`/helper` abre el panel con **solo** los botones que su permiso permite (interfaz configurada en
`panel.ui`): inventario, diálogo nativo (Java 1.21.6+) o formulario de Bedrock vía Floodgate.

Herramientas de turno:

- **`/vanish`** — invisibilidad para los jugadores; aguanta reconexión y reinicio, los contenedores se
  abren sin animación ni sonido, los demás staff en vanish se ven entre sí con brillo por rango
  (verde = helper).
- **`/freeze <jugador> [motivo]`** — contención inmediata mientras investigas; se explica al jugador.
- **`/follow <jugador>`** — seguir sin delatarte; **`/tp`/`/back`** — moverte y volver.
- **`/check <jugador>`** — ficha, sanciones activas y último flag del anticheat.

---

## 5. Protocolo de uso

### 5.1 Al recibir un reporte (`/report` → cola)

1. **`/reports claim <id>`** en cuanto lo veas: nadie más lo toca mientras esté reclamado.
2. **Comprueba antes de actuar:** `/check <jugador>` (sanciones activas, últimos flags) y
   `/chatlog <jugador> <n>` si es de chat. Para ver **bloques tocados** hace falta `/at inspect`
   (CoreProtect) y ese permiso es de moderador: pídeselo con el ID del reporte y las coordenadas.
   Capturas con F2 + coordenadas y el chat visible valen; un «es que sí» no.
3. **Contén** si hace falta: `/freeze` mientras miras, no castigues en caliente.
4. **Resuelve con la escala de tu rango** (apartado 5.3), deja constancia en la cola con
   `/reports resolve <id> [nota]` y una línea en el canal del staff con el ID del reporte y lo que
   hiciste. Las notas en `/notes` son de moderador en adelante: con `resolve [nota]` te vale.

### 5.2 Evidencia mínima antes de sancionar

- Nombre del jugador, fecha, coordenadas/dimensión y qué pasó.
- Comprobación directa: `/check`, `/chatlog`, o tu propia pantalla grabada; los bloques tocados,
  pídelos al moderador con `/at inspect`.
- Si es *chat*: captura con el mensaje completo, no recortada.

### 5.3 Tu escala de sanciones (solo hasta 1 día)

| Caso | Acción |
|---|---|
| Primera falta de chat (insultos, spam, publicidad) | `/warn <jugador> <motivo>` — plantillas y escaleras no son tuyas |
| Chat reincidente o falto grave | `/tempmute <jugador> <1h\|1d> <motivo>` |
| Jugador molestando en directo / rompiendo una pelea | `/freeze <jugador> <motivo>` |
| Trampa de chat, publicidad | avisa en `#staff` → lo escalan a moderador con plantilla |
| Desconectarse para evitar la sanción | avisa a moderador (eso es `evadir-staff`, tiene plantilla propia) |
| Cualquier sanción de más de 1 día, ban o ban permanente | **escala** |
| Hacker con prueba (xray, killaura, fly) | escala de inmediato con la captura y `/check` |
| Sospecha de alt account o de alguien con nombre falso | escala a moderador con `/alts` (lo hace él) |

`admintools.limit.new` limita la duración a **1 día**: si pones más, el plugin te la corta. Ese límite
existe para que un helper nunca deje a nadie castigado más de un turno.

### 5.4 A quién escalar y cómo

Escribe en el canal privado del staff con este formato, así nadie repite trabajo:

```
[ESCALADA] <jugador> — <qué pasó, con fecha y coordenadas>
Evidencia: /check, captura en <enlace> · Reporte #<id>
Pedí: <lo que crees que corresponde>
```

Escalas cuando: toca más de 1 día · hay ban o IP · hay más de 2 implicados · hay alt accounts ·
es un donante, un conocido o alguien con amigos que harán ruido · el caso es gris (semi-anárquico,
¿era jugada o trampa?).

### 5.5 Reglas de la casa

1. **Neutralidad.** No sanciones a quien te ganó una pelea, a quien te robó o a tu enemigo de clan.
   Usa `/vanish` para vigilar, no para espiar bases privadas sin motivo.
2. **Sin sanciones fantasma.** Toda sanción lleva motivo. El plugin la registra en la auditoría y en
   Discord (`staff.action-log`), incluido el `-s` (silencioso): **no sirve para ocultar nada**.
3. **Nunca des** objetos, gamemode, vuelo ni información de la spawn a nadie. Tus comandos útiles
   (`/heal`, `/fly`, `/god`…) son de moderador y sólo en modo staff.
4. **No enseñes comandos de consola ni capacidades del servidor** a jugadores. No compartas tu
   `/login` ni datos de otras cuentas: si alguien te pide un «favor con un amigo», es una estafa.
5. **Cumple tu turno:** `/duty` (de moderador en adelante) antes de empezar y al terminar; en
   `#staff-chat` avisa si te vas sin cubrir.
6. **No respondas a la comunidad con malos modos.** Eres el servidor. Si un caso te supera, escala
   en lugar de improvisar.

### 5.6 Checklist de turno

```
□ Entré al turno y avisé en staff-chat
□ /reports: nada reclamado sin resolver
□ /vanish activo si voy a observar; /freeze solo para contener
□ Cada sanción con motivo y el reporte cerrado con nota
□ Escaladas publicadas con evidencia
□ /reports resolve de lo que resolví
□ Salí del turno y dejé lo pendiente escrito
```

---

## 6. Errores típicos de este rango

| Error | Por qué duele |
|---|---|
| Mutear 1 día «para enseñar» | El límite ya existe: `1d` es tu techo, y saltárselo para castigar más es abuso |
| Banear sin comprobar | En semi-anárquico casi todo es jugada; un ban sin prueba se revierte y desprestigia al staff |
| Castigar en el chat público | Los casos se resuelven en privado y con nota; el chat es para avisar, no para discutir |
| Ignorar los reportes con ID | Si nadie hace `claim`, los casos se atascan: reclama o di en `#staff` que no puedes |

---

**Documentación relacionada:** [ROL-MODERADOR.md](ROL-MODERADOR.md) ·
[ROL-ADMIN.md](ROL-ADMIN.md) · [LISTA-FUNCIONES.md](LISTA-FUNCIONES.md) ·
[COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md)
