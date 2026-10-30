# Changelog

Todas las notas de versión de **AdminTools**. El formato sigue [Keep a Changelog](https://keepachangelog.com/)
y el proyecto se versiona con [SemVer](https://semver.org/lang/es/).

---

## [0.5.1] — 2026-10-30

### Añadido

- **Letrero de arranque en consola** (`console.banner` / `console.color: auto|always|never`): bloque
  con versión, servidor, almacenamiento, módulos, integraciones y estado, en un solo evento de log
  para que el marco quede alineado. `latest.log` sale sin códigos de escape.
- **Canal propio del registro de staff** (`staff.action-log.webhook`): el registro de comandos y
  herramientas ya no depende de las alertas (`discord.enabled`); resuelve canal propio →
  `announcements.staff-webhook` → webhook general, y avisa en consola si falta.
- **Documentación de rangos**: [docs/ROL-HELPER.md](docs/ROL-HELPER.md),
  [docs/ROL-MODERADOR.md](docs/ROL-MODERADOR.md) y [docs/ROL-ADMIN.md](docs/ROL-ADMIN.md), con
  permisos y protocolo de uso en servidor semi-anárquico.
- **Configuración documentada**: tabla de canales de Discord y, en cada ajuste con webhook, qué
  hace y de dónde sale la URL.

### Corregido

- El registro de staff a Discord no enviaba nada si se activaba sólo con
  `staff.action-log.discord: true`.
- La columna «Rango» de `/setjail`, `/deljail` y `/rollback … confirm` en
  [docs/COMANDOS-Y-PERMISOS.md](docs/COMANDOS-Y-PERMISOS.md) no coincidía con los permisos reales
  (moderador, no admin).

---

## [0.5.0] — 2026-10-29

### Añadido

- **Anuncios de sanciones en Discord**: canal público con embed (icono, tipo, sanción, motivo,
  duración con cuenta atrás y quien la emitió) y copia firmada en el canal del staff.
- **`/report` a Discord** con canal propio.
- **Registro de uso del staff** agrupado cada 3 s, con contraseñas y 2FA sin argumentos y motivos
  sin menciones.
- **Espectador de vigilancia** con límite de 5 minutos y recarga de 15 minutos (`/spectate`).
- **Paneles por rango** `/helper`, `/moderator`, `/admin` con tres interfaces: inventario Bukkit,
  diálogo nativo de Java 1.21.6+ y formularios Bedrock vía Floodgate.
- **Cárcel aleatoria** (celda al azar al encarcelar) y `/jailimport` con `--dry`.

---

## [0.4.5] — 2026-10-29

### Añadido

- Auditoría completa de código y simulación de ataque, con informe en
  [docs/AUDITORIA-0.4.5.md](docs/AUDITORIA-0.4.5.md): 13 hallazgos (1 alto, 1 medio-alto, 7 medios
  y bajos) con su estado.

### Corregido

- Inyección de MiniMessage en el nombre sancionado, jerarquía de rangos sobre cárcel y congelación,
  confirmaciones ligadas a sus argumentos, etiquetas robadas a otros plugins y lecturas asíncronas
  de la API de Bukkit.

---

## [0.4.0] — 2026-10-28

### Añadido

- **Cárcel** basada en xJail con historial, radio de retorno, modo aventura y confiscación de
  inventario sin duplicados.
- **Rollback de inventarios** al estilo InventoryRollbackPlus: copias automáticas, menús,
  restauración selectiva y de un solo espacio.
- **`/jailimport`**: migración de datos de xJail.

---

## [0.3.3] — 2026-10-27

### Añadido

- Primer entregable funcional completo sobre Paper 26.1: sanciones con herencia de rangos,
  herramientas de staff, reportes, chat, investigación y auditoría.

---

[0.5.1]: https://github.com/USUARIO/AdminTools/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/USUARIO/AdminTools/compare/v0.4.5...v0.5.0
[0.4.5]: https://github.com/USUARIO/AdminTools/compare/v0.4.0...v0.4.5
[0.4.0]: https://github.com/USUARIO/AdminTools/compare/v0.3.3...v0.4.0
[0.3.3]: https://github.com/USUARIO/AdminTools/releases/tag/v0.3.3
