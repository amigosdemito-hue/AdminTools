# Rango MODERADOR — permisos y protocolo de uso

> Manual de AdminTools · servidor **semi-anárquico** · rango de decisión y de investigación.
> Hereda todo lo de [ROL-HELPER.md](ROL-HELPER.md).
> Referencia técnica: [COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md).

---

## 1. Qué es el rol

El moderador ya castiga de verdad: **bans hasta 30 días, plantillas, escaleras, investigación y
recuperación de inventarios**. Es el que resuelve los casos que el helper no puede cerrar y el que
prepara la documentación para que un admin tome una decisión definitiva.

En semi-anárquico su criterio principal es distinguir **jugada de trampa**: lo gris se resuelve con
pruebas, no con intuición.

---

## 2. Cómo se otorga

```bash
/lp creategroup moderador
/lp group moderador permission set admintools.moderator true
/lp user <jugador> parent set moderador
```

`admintools.moderator` incluye `admintools.helper`, así que hereda todo el rango inferior sin repetir nada.

---

## 3. Permisos nuevos respecto al helper

| Nodo | Qué abre |
|---|---|
| `admintools.sanction.ban` | `/ban`, `/tempban` |
| `admintools.sanction.unban` | `/unban` |
| `admintools.sanction.templates` | `/punish <plantilla>` |
| `admintools.sanction.ladder` | `/ladder <escalera>` |
| `admintools.sanction.shadowmute` | `/shadowmute` (el jugador escribe y no lo ve nadie) |
| `admintools.sanction.notes.add` | `/notes add` y `/notes remove` |
| `admintools.limit.mod` | Duración máxima de sanción: **30 días** |
| `admintools.staff.mode` | `/staffmode` y `/spectate` |
| `admintools.staff.spy` (+ `.command`) | `/spy` (privados) y `/cspy` (comandos) |
| `admintools.staff.duty` | `/duty` (aviso y contadores de servicio) |
| `admintools.staff.invsee` (+ `.modify`) | `/invsee` |
| `admintools.staff.enderchest` (+ `.modify`) | `/enderchest` |
| `admintools.staff.utility` | `/heal`, `/feed`, `/fly`, `/god`, `/gamemode`, `/clear` |
| `admintools.report.manage` | Cierre de reportes: `resolve` / `reject` con nota (node declarado en `plugin.yml` como «resolver, rechazar o anotar reportes») |
| `admintools.chat.clear` / `.slowmode` | `/clearchat`, `/slowmode` |
| `admintools.investigate.alts` | `/alts` (sin IP ajena: eso es `.ip` y es de admin) |
| `admintools.investigate.case` | `/case` (expediente completo) |
| `admintools.investigate.inspect` | `/at inspect` (CoreProtect) |
| `admintools.investigate.snapshot` | `/snapshot save\|list\|view` |
| `admintools.jail.manage` | `/setjail`, `/deljail` (crear y borrar celdas) |
| `admintools.rollback.view` / `.restore` / `.save` | `/rollback` ver, **restaurar** y `/rollback save` |

**No tiene:** `ipban`, `ipmute`, `permanent` (bans permanentes), `unpunish`, `muteall`, `restore`
(copia de seguridad), `rollback.purge`, `maintenance`, `restart`, `backup`, `audit`, `watchdog`,
`reload`, `jail.import`, `alts.ip`, `spectate.others`, `vanish.others`, `mode.others`.

---

## 4. Protocolo de uso

### 4.1 Tu techo de sanción

`admintools.limit.mod` = **30 días**. Un ban permanente o un IP-ban **nunca** los pones tú: escalan a
admin (o se dejan en `#staff` con el caso abierto para que él decida). El límite no es decorativo:
si lo intentas, el plugin te corta la duración y lo deja en el histórico.

### 4.2 Casos cerrados con plantilla, no a mano

Las plantillas existen para que dos moderadores no penalicen distinto lo mismo:

```bash
/punish hacking  <jugador> [motivo]   # tipo IP_BAN — a tu techo llega a 30d
/punish xray     <jugador>            # BAN 30d
/punish grief    <jugador>            # BAN 7d
/punish insultos <jugador>            # MUTE 1d
/punish spam     <jugador>            # MUTE 1h
/punish publicidad <jugador>          # MUTE 1d
/punish evadir-staff <jugador>        # BAN 14d
```

Y las escaleras suben solas cuando reincide dentro de su ventana:

```bash
/ladder chat        <jugador>   # warn → 1h → 1d → 7d → ban 7d
/ladder publicidad  <jugador>   # warn → 1d → ban 7d → ipban permanente
/ladder cheating    <jugador>   # ban 7d → ipban permanente
```

Regla: **si hay plantilla, se usa la plantilla**. Sin excepciones ni «pequeños ajustes» a mano.

Duraciones: cada plantilla trae la suya, pero tu techo (`admintools.limit.mod` = 30 días) manda:
`/punish hacking` está pensado como IP-ban permanente y con tu rango **se aplica a 30 días**; el
IP-ban permanente lo deja admin. Si escalera te sube a un paso que se pasa de tu techo, lo mismo:
se corta a 30 días y queda anotado, no se salta el límite.

### 4.3 Flujo de investigación (caso serio)

1. **`/reports claim`** y fija el caso.
2. **`/check <jugador>`** — sanciones activas y último flag del anticheat.
3. **`/alts <jugador>`** — cuentas asociadas por IP/sesión (la búsqueda por IP ajena es de admin).
4. **`/at inspect <jugador> [tiempo]`** — qué bloques tocó (CoreProtect).
5. **`/chatlog <jugador> <n>`** — historial de chat relevante.
6. **`/snapshot <jugador> save`** — fotografía de su inventario **antes** de tocar nada.
7. Decide con plantilla/escalera y **`/case <jugador>`** queda como expediente con lo que anotes.

### 4.4 `/invsee` y `/enderchest` (herramienta, no robo)

- Son de **solo lectura** hasta que habilitas la modificación, y **todo queda auditado**.
- Úsalos para comprobar objetos robados o no permitidos, **no** para quedarte nada.
- Si sacas un objeto de prueba, déjalo registrado en `/notes` y en el canal del staff.

### 4.5 `/rollback` (recuperación)

- `/rollback` → menú de jugadores y copias; `/rollback <jugador> #id` → contenido.
- **Restaurar** (`confirm items|ender|position|stats|all`) está permitido para tu rango: antes de
  cada restauración el plugin guarda el estado actual con motivo `seguridad`, así que si te equivocas
  lo deshaces con `/restore` (eso ya es de admin).
- `/rollback save <jugador>` — hazlo siempre **antes** de investigar un caso con sospecha de robo.
- `/rollback purge` (borrar copias viejas) es de **admin**: no lo toques.

### 4.6 Cárceles

- `/jail <jugador> [duración] [celda] [motivo]` — sin celda, elige una **al azar**; el confirmación
  dice en cuál cayó. `/unjail` devuelve inventario y modo de juego sin duplicar nada.
- `/setjail <nombre> [radio]` y `/deljail <nombre>` los tienes tú: cuidado al borrar, las condenas
  activas apuntan a esa celda. Radio 2 = área de 5×5.
- `/jaillist` y `/jailtime` para consultar sin comandos.

### 4.7 Chat masivo

- `/clearchat` cuando el chat se vuelva injugable; **precedido de un aviso** en el chat (el plugin
  borra de verdad, no hay «puedes volver a leer»).
- `/slowmode 3` en olas de spam y `off` en cuanto pase.

### 4.8 Nota técnica sobre el cierre de reportes

`admintools.report.manage` sólo decide quién recibe la notificación en el chat (el moderador va a la
cola, el helper es avisado en directo); la comprobación de cierre todavía no la exige el código, así
que un helper que reclamó también puede cerrar. Si se quiere que cerrar sea exclusivo del moderador,
hay que añadir esa comprobación en `ReportCommands.close`.

### 4.9 Reglas de la casa (además de las del helper)

1. **Nunca ban permanente ni IP tú.** Se deja la evidencia escrita y se escala.
2. **No manipules inventarios ajenos** más allá de comprobar: el abuso está en el log.
3. **Guarda antes de restaurar** (`/rollback save`) en cualquier caso dudoso.
4. **Duty y registro**: `/duty` al entrar y al salir; todo tu trabajo queda en el canal de Discord
   (`staff.action-log`) — el silencioso `-s` también se registra.
5. **Casos sensibles → admin:** donante, familiar de un staff, amenaza legal, robo de cuenta,
   doxxing, discord roto por un caso, o si el propio acusado es staff.
6. **No reveles métodos de detección** (qué mide el anticheat, cómo se ven las pruebas): los casos
   se comunican con el motivo de la plantilla, no con el tutorial.

### 4.10 Checklist de turno

```
□ /duty on y aviso en staff-chat
□ /reports: reclamé los que voy a resolver
□ Guardé /rollback save antes de investigar
□ Cada sanción con plantilla, motivo y /notes
□ Escaladas a admin con evidencia y caso abierto
□ /reports resolve + notas en /case
□ /duty off y traspaso de lo pendiente
```

---

## 5. Errores típicos de este rango

| Error | Por qué duele |
|---|---|
| Ban permanente «porque es obvio» | Es el salto de rango; el admin lo revierte y pierdes credibilidad |
| Restaurar sin copia de seguridad | Puede arruinar el inventario de un jugador que no tiene culpa; `rollback save` antes, siempre |
| Invsee para «mirar» sin registrar | Auditoría + Discord lo dejan escrito; si no lo notas, alguien te lo recordará |
| Repartir duraciones distintas para lo mismo | Por eso están las plantillas y escaleras: coherencia entre todos |
| Cerrar el reporte sin nota | El siguiente turno repite tu trabajo y el caso se diluye |

---

**Documentación relacionada:** [ROL-HELPER.md](ROL-HELPER.md) ·
[ROL-ADMIN.md](ROL-ADMIN.md) · [LISTA-FUNCIONES.md](LISTA-FUNCIONES.md) ·
[COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md)
