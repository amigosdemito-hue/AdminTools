# Rango ADMIN — permisos y protocolo de uso

> Manual de AdminTools · servidor **semi-anárquico** · control total y operación del servidor.
> Hereda todo lo de [ROL-MODERADOR.md](ROL-MODERADOR.md) y de [ROL-HELPER.md](ROL-HELPER.md).
> Referencia técnica: [COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md).

---

## 1. Qué es el rol

El admin cierra los casos definitivos (bans permanentes, IP, reversión de sanciones) y **opera el
servidor**: mantenimiento, reinicios, copias, auditoría y configuración. Es el único rango que puede
detener una tormenta de la que nadie sale limpio — y el único que puede romper el servidor sin
querer, así que aquí la disciplina es más importante que en los otros dos.

---

## 2. Cómo se otorga

```bash
/lp creategroup admin
/lp group admin permission set admintools.admin true
/lp user <jugador> parent set admin
```

`admintools.admin` incluye `admintools.moderator` (y por herencia el helper completo).

---

## 3. Permisos nuevos respecto al moderador

| Nodo | Qué abre |
|---|---|
| `admintools.limit.admin` | Duración máxima: **permanente** |
| `admintools.sanction.ipban` | `/ipban` |
| `admintools.sanction.ipmute` | `/ipmute` |
| `admintools.sanction.rollback` | `/unpunish` (revocar sanciones, incluidas las de otros staff) |
| `admintools.chat.muteall` | `/muteall` |
| `admintools.chat.filter.bypass` / `.cooldown.bypass` | saltarte filtros y cooldown del chat |
| `admintools.staff.mode.others` | `/staffmode <jugador>` (apagar el modo de otro) |
| `admintools.staff.vanish.others` | `/vanish <jugador>` |
| `admintools.staff.spectate.others` | cortar el espectador de otro staff |
| `admintools.investigate.alts.ip` | `/alts <ip>` con IP ajena |
| `admintools.investigate.restore` | `/restore <jugador> <id>` (deshacer una restauración) |
| `admintools.ops.maintenance` (+ `.bypass`) | `/maintenance` |
| `admintools.ops.restart` | `/restart [cancel\|<segundos>\|now]` |
| `admintools.ops.backup` | `/backup [list\|now]` |
| `admintools.ops.audit` | `/audit [jugador] [página]` |
| `admintools.ops.watchdog` | `/watchdog [status\|test]` |
| `admintools.ops.reload` | `/at reload` |
| `admintools.rollback.purge` | `/rollback purge <días>` |
| `admintools.jail.import` | `/jailimport [ruta\|xjail] [--dry]` |

---

## 4. Protocolo de uso

### 4.1 Decisiones definitivas

| Caso | Herramienta | Nota |
|---|---|---|
| Ban permanente | `/ban <jugador> [motivo]` sin duración | Máximo una por semana: es la carta pesada |
| IP-ban / IP-mute | `/ipban`, `/ipmute` | Comprueba `/alts <ip>` antes: la IP de un cibercafé o una NAT compartida (`alts.cgnat-exempt`) no es culpable |
| Revocar un error | `/unpunish <jugador\|staff> <nombre> [motivo]` | Si la sanción estuvo mal, anúncialo en público: se revierte y se dice |
| Reincidencia grave | `/ladder cheating` o plantilla `hacking` | La escalera ya sube sola dentro de su ventana |

Los casos sensibles se deciden con evidencia en mano y con el expediente (`/case`) delante: donante,
familiar de staff, amenaza legal, robo de cuenta, doxxing, duplicación de objetos, u otro staff.

### 4.2 Operación del servidor

- **`/at status`** antes de tocar nada: TPS, memoria, disco, integraciones y estado.
- **`/watchdog status|test`** — umbrales de alerta; `test` comprueba que las alarmas salen.
- **`/backup now`** antes de cambios de configuración o migraciones; `/backup list` revisa rotación.
- **`/maintenance on`** para trabajos; el `.bypass` deja entrar al staff. Recuerda `off` (error típico
  de madrugada: el servidor queda en mantenimiento toda la noche).
- **`/restart [segundos]`** con cuenta atrás y avisos; `cancel` si se cae el asunto.
- **`/at reload`** tras editar `config.yml` o los mensajes. Ojo: con cambios de `storage` hace falta
  reiniciar, no recargar.
- **`/rollback purge <días>`** — con moderación: borra copias viejas y **no se deshace**.

### 4.3 Revisión del equipo

- **`/audit [jugador]`** — qué hizo cada miembro del staff. Úsalo cuando un caso no cuadre o cuando
  alguien reporte abuso; no como vigilancia diaria.
- Todo lo que hace el staff también va al canal privado de Discord (`staff.action-log`) y a la
  auditoría interna: **el flag `-s` no oculta nada**, sólo quita el aviso público.
- Si un miembro del equipo comete un error grave: se revisa con `/audit`, se habla primero en privado
  y la sanción al propio staff también va documentada en `/case`.

### 4.4 Cadena de mando y atención al servidor

1. **Helper** → reporte + contención + evidencia.
2. **Moderador** → investigación + sanción hasta 30 días + expediente.
3. **Admin** → permanente, IP, reversión, operación.

Casos que sólo un admin cierra: ban permanente, IP, reversión de sanciones de otro staff, escalado
cuando hay implicados varios o el caso es viral (cordadas grandes, discusiones en redes).

### 4.5 Emergencias (semi-anárquico)

| Escenario | Orden de actuación |
|---|---|
| Objetos duplicados por bug | `/maintenance on` → `/backup now` → fijar versión del bug → comunicar en público con la hora → limpiar con `/case` y `/rollback restore` cuando se entienda |
| Ola de cheats o raids | `/slowmode`/`/muteall` para controlar el chat → `/alts` y escaleras → anuncia en público que el staff está actuando |
| Servidor con TPS bajo | `/watchdog status` → cerrar lo que estorbe (`/maintenance`) → `/backup` → reinicio con cuenta atrás |
| Un admin es sancionado en otra tienda/consola | él mismo no se revierte: que lo haga otro admin o que lo haga fuera del servidor y quede en el log |

### 4.6 Reglas del cargo

1. **Tus comandos no son para ventaja propia:** `gamemode`, `fly`, `god`, `heal`, `clear` y
   `/teleport` en modo staff. El inventario y las acciones quedan auditados.
2. **No borres evidencias:** `/rollback purge` sólo por retención, nunca «para no dejar rastro».
3. **No cambies permisos de otros rangos sin decírselo** (por ejemplo quitar `jail.manage`): afecta
   al protocolo de todos.
4. **Toda decisión reversible se documenta igual:** un ban mal puesto se revierte y se anuncia,
   no se borra el histórico.
5. **Config, mensajes y backups son tu responsabilidad:** revisa `docs/` y `README` antes de editar
   `config.yml` y ten copia del archivo antes de tocarlo.

### 4.7 Checklist de arranque y de cierre

```
Arranque del turno
□ /at status + /watchdog status
□ /backup list (última copia reciente)
□ /maintenance apagado
□ Aviso en staff-chat: admin de servicio

Cierre del turno
□ /audit sin entradas raras
□ /reports: sin casos abiertos
□ Mantenimiento apagado, /restart cancelado si tocaba
□ Copia extra si hubo cambios importantes
□ Estado del servidor en el canal de staff
```

---

## 5. Errores típicos de este rango

| Error | Por qué duele |
|---|---|
| `/maintenance on` olvidado | Se queda el servidor cerrado para todo el mundo |
| `/restart` sin cuenta atrás | Desconecta gente en mitad de una partida y suena a caída |
| Revisar `/alts <ip>` sin comprobar NAT | Un ban por IP compartida abarca a jugadores sin culpa |
| `/rollback purge` con un número a ojo | Las copias se borran y no se recuperan |
| Revocar sin avisar | El jugador sigue creyendo que tiene la sanción puesta y abre discusión |

---

## 6. Qué NO hacer nunca

- Banear por reputación, por queja de un amigo o por «ya sabemos cómo es».
- Usar `/invsee`, `/enderchest` o `/restore` para lucrarte o para ayudar a alguien del clan.
- Borrar o recortar la auditoría (no se puede desde el plugin; si aparece tocada, es un problema mayor).
- Dejar el servidor en mantenimiento, un `/delayed restart` anunciado o la base sin copia.

---

**Documentación relacionada:** [ROL-HELPER.md](ROL-HELPER.md) ·
[ROL-MODERADOR.md](ROL-MODERADOR.md) · [LISTA-FUNCIONES.md](LISTA-FUNCIONES.md) ·
[COMANDOS-Y-PERMISOS.md](COMANDOS-Y-PERMISOS.md) · [AUDITORIA-0.4.5.md](AUDITORIA-0.4.5.md)
