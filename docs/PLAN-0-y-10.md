# Plan 0 → 10: cómo clasifico AdminTools

> Pregunta del usuario: *"del 0/10 como lo clasificas"* — esta nota responde eso con criterio,
> no con autohalago. Nota corta: **hoy es un 7.5/10 en funcionalidad, un 6.5/10 como
> producto publicable**. Es un MVP sólido y probado, no una suite de nivel LiteBans todavía.

---

## 1. Criterio de puntuación (10 puntos)

| Punto | Qué mide |
|:--:|---|
| 1 | Compila, empaqueta y carga en Paper 26.1+ sin errores |
| 2 | Estructura modular real (storage / service / command / listener / config / api) |
| 3 | Persistencia con migraciones versionadas (SQLite y MySQL con el mismo esquema) |
| 4 | Sistema de sanciones completo: ban, mute, warn, kick, historial, reversión con motivo |
| 5 | Roles con herencia (helper → moderador → admin) y 57+ nodos de permiso declarados |
| 6 | Calidad de software: manejo de errores, auditoría, i18n (es/en) con MiniMessage, docs escritas |
| 7 | Verificado en servidor real: carga, 52 comandos, suite de comandos ejecutada, sin excepciones, datos persistentes |
| 8 | Requiere: **pruebas unitarias automatizadas** (hoy 0 tests) y cobertura mínima razonable |
| 9 | Requiere: **pruebas de integración end-to-end** (multi-servidor, player simulado, reconciliación de migraciones, chaos/restart) y endurecimiento de seguridad |
| 10 | Requiere: **release engineering** — pipeline, semver, changelog, plan de compatibilidad, ligar versión y rollback operativo |

**Fecha de evaluación:** hoy. Con esto como línea base se mide toda evolución futura.

| Escala | Rango | Nota actual |
|---|---|:--:|
| Prototipo | 0–3 | |
| Espina dorsal funcional | 4–5 | |
| MVP sólido | 6–7 | → **7.5** |
| Producto publicable | 8–9 | |
| Proyecto pulido y mantenible a largo plazo | 10 | |

---

## 2. Evaluación por área

| Área | Hoy | Ubicación | Subida |
|---|:--:|---|---|
| Compilación y empaquetado | 9 | build limpio, jar funcional | mantener CI |
| Arquitectura y desacoplamiento | 7 | capas claras, algo de acoplamiento en `AdminToolsPlugin` | test asincrónicos |
| Almacenamiento (SQLite + MySQL, migraciones) | 8 | migraciones versionadas, mismo esquema | tests de reconciliación |
| Sanciones (ban, mute, warn, kick, historial, reversión) | 8 | completo y funcional | casos horario/límite de precisión |
| Roles y permisos (herencia) | 8 | 3 roles en cascada, 57+ nodos | test de matriz |
| Calidad (errores, auditoría, i18n, docs) | 7 | auditoría completa, es/en, docs escritas | singular/plural i18n, advertencias de compilación |
| **Verificación (tests)** | **3** | suite de comandos manual, 0 tests unitarios | **prioridad #1** |
| Seguridad | 6 | permisos CHECK, auditoría en cada operación sensible | duración de sesión, rate limiting de comandos, signatures de auditoría |
| Operación | 6 | backups con retención, watchdog TPS/MSPT/RAM/disco, mantenimiento | alertas reales (webhook), restauración probada |
| Observabilidad (errores, logs, métricas) | 5 | bStats inactivo por defecto | logs estructurados, métricas activas bajo opt-in |

---

## 3. Fases pendientes por puntaje

| De → a | Paso | Coste |
|---|---|:--:|
| 7.5 → **8** | Tests unitarios de la lógica crítica: parser de duraciones (`/ban 1d`), matriz de roles, prioridad de migraciones, expiración de sanciones, límites por permiso. Integrar `gradle check` al pipeline. | bajo |
| 7.5 → **8** | i18n: arreglar singular/plural de mensajes (hoy dice "1 advertencias"), completar faltantes, añadir tercer idioma si hace falta. | bajo |
| 8 → **8.5** | Tests de integración end-to-end: arranque in-process del plugin sobre mock de servidor, reconciliación de migraciones en ambos dialectos, simulación de 2 servidores sobre la misma base, reinicio/crash. | medio |
| 8.5 → **9** | Endurecer seguridad: expiración de sesión de staff, rate limiting por permiso, callbacks/alertas firmadas (hash-chain) en auditoría para detectar edición. | medio |
| 9 → **9.5** | Observabilidad: logs estructurados (JSON único por evento), métricas de negocio (sanciones/hora, tasa de rechazo de reportes, latencia de migración), activar bStats bajo opt-in explícito. | medio |
| 9.5 → **10** | Release engineering: pipeline que corre `check`, publica jar semver, changelog por releases, matriz de compatibilidad (Paper 26.1 → 27.x), plan de rollback por migración, API de versión estable (`AdminToolsAPI.getVersion()`). | alto |

---

## 4. Nota consolidada

**7.5 / 10.**

Justificación en una línea: existe y funciona de punta a punta sobre un servidor real con
52 comandos, pero **cero automatización de pruebas** y la verificación aún depende de la
suite manual. Eso lo define como MVP funcional y probado, no como producto maduro.

---

## 5. Siguiente paso recomendado

Empezar por el paso 7.5 → 8: tests unitarios de `TimeUtil` y la matriz de roles con
`gradle check` en CI. Es el cuello de botella que separa "funciona" de "sé que sigue
funcionando mañana".