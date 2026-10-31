# Publicación en CurseForge — guía de campos

Material listo para crear los tres proyectos en el panel de CurseForge y para que después las
versiones se suban solas. CurseForge **no tiene API para crear proyectos**: eso se hace a mano
(5 minutos por proyecto); lo que sí se automatiza es la subida de versiones, y sólo a partir de que
el proyecto ya existe.

---

## 1. Las dos claves (no se confunden)

| Para qué | Clave | Cabecera | Dónde se crea |
|---|---|---|---|
| **Subir archivos** a tus proyectos | Token de autor | `X-Api-Token` | <https://www.curseforge.com/account/api-tokens> |
| Leer datos (versiones, categorías, IDs) | `x-api-key` de Studios | `x-api-key` | Consola *CurseForge for Studios* |

El `x-api-key` **no sube nada**. El token de autor **sólo** sirve contra
`https://minecraft.curseforge.com/api/projects/{projectId}/upload-file`.

Guárdalo en `.env` (ignorado por git):

```
CURSEFORGE_TOKEN=…
```

**Comprobado el 2026-10-30:** con el token actual, `GET /api/game/versions?token=…` y la cabecera
`X-Api-Token` devuelven **200**, mientras que `api.curseforge.com/v1/games` con `x-api-key`
devuelve **403**: es decir, el token que tienes **sí es el de autor** (el que sube) y no sirve para
la API de lectura de Studios. Justo lo que hace falta.

### IDs de versión de juego (necesarios para subir)

Leídos con el propio token (`GET /api/game/versions`). Los que interesan a nuestros proyectos:

| Versión | ID (tipo 1) | ID (tipo de la propia versión) |
|---|---|---|
| 26.1 | 16083 | 15933 (tipo 83806 «Minecraft 26.1») |
| 26.1.1 | 16084 | 16021 (tipo 83806) |
| 26.1.2 | 16085 | 16082 (tipo 83806) |
| 26.2 | 16500 | 16498 (tipo 86297) |
| 26.3 | 17046 | 17045 (tipo 88556) |

Al subir se prueban primero los del tipo 1 (la lista general de Minecraft); si CurseForge rechaza el
par, se usan los de la tabla derecha.

## 2. Crear los proyectos (manual)

<https://authors.curseforge.com> → **Create project** → juego **Minecraft** → rellenar.

Valores comunes a los tres:

- **Class:** `Bukkit Plugins` (es la clase de plugin de Minecraft; influye en los tipos de archivo
  admitidos y en el launcher).
- **Project License:** AdminTools y BloodMoonPlus → *All Rights Reserved*; CrownArena → *MIT*.
- **Logo:** archivo de `assets/` (PNG).
- **Description:** abrir el HTML indicado, `Ctrl+A`, copiar y **pegar en el editor**. CurseForge no
  entiende Markdown, por eso está convertido a HTML.
- **Summary:** una o dos frases en inglés (valor de la tabla).

| Campo | **AdminTools** | **BloodMoonPlus** | **CrownArena** |
|---|---|---|---|
| Name | `AdminTools` | `BloodMoonPlus` | `CrownArena` |
| Slug sugerido | `admintools` | `bloodmoonplus` | `crownarena` |
| Summary | *Paper 26.1+ administration suite: ranked punishments, jails, inventory rollback, reports, staff panels, vanish, spectator patrol and full Discord integration.* | *Blood Moon event plugin for Paper: fixed schedules (Mexico time), 9 bosses, 45 items by rarity, enchantments up to level 7, progressive waves, PlaceholderAPI support, full immersion and rare loot delivered to inventory.* | *Automated scheduled PvP event for Paper: native invite, private lobby and rotating arena worlds, identical kit, separate inventory, kill feed, streaks, rankings and Bedrock/Geyser support.* |
| Main category | Administration | Fun / Mechanics (la que ofrezca la lista) | Fun / PvP |
| Licencia | All Rights Reserved | All Rights Reserved | MIT |
| Logo | `assets/admintools-icon.png` (512×512) | `assets/bloodmoon-plus.png` (256×256) | `assets/crownarena.png` (512×512) |
| Descripción (HTML) | `docs/curseforge/admintools-suite.html` | `docs/curseforge/bloodmoon-plus.html` | `docs/curseforge/crownarena.html` |
| Versiones de juego | `26.1`, `26.1.1`, `26.1.2`, `26.2`, `26.3` | `26.2`, `26.3` | `26.2` |
| Repositorio | `github.com/amigosdemito-hue/AdminTools` | `github.com/amigosdemito-hue/BloodMoonPlus` | `github.com/amigosdemito-hue/CrownArena` |

> Si el nombre o el slug ya está ocupado en CurseForge, añade el sufijo `-plugin` o `-paper` y
> anótalo: el `projectId` sale de la URL del proyecto.

## 3. Primer archivo (obligatorio)

Sin un archivo el proyecto **no entra en moderación**. Pestaña **Files → Upload file**:

- **Archivo:** el jar «gordo» (`AdminTools-0.5.2.jar`, sin el `-plain`).
- **Display name:** `AdminTools 0.5.2`.
- **Release type:** `Release`.
- **Changelog:** bloque de esa versión de `CHANGELOG.md`.
- **Supported version:** las de la tabla de arriba.
- Queda en *Under Review* hasta que la moderación lo apruebe.

## 4. Lo que viene después (automatizable)

1. Deja el token en `.env` como `CURSEFORGE_TOKEN`.
2. Pásame los tres `projectId` (están en la URL: `authors.curseforge.com/#/projects/<id>`).
3. Yo subo versiones con:

```bash
curl -X POST "https://minecraft.curseforge.com/api/projects/<projectId>/upload-file" \
  -H "X-Api-Token: $CURSEFORGE_TOKEN" \
  -F 'metadata={"displayName":"AdminTools 0.5.2","releaseType":"release",
                "gameVersions":["<id de versión>","<id de versión>"],
                "changelog":{"type":"text/plain","content":"…"}}' \
  -F "file=@build/libs/AdminTools-0.5.2.jar"
```

Los IDs de juego se leen con
`https://minecraft.curseforge.com/api/game/versions?token=$CURSEFORGE_TOKEN`.

Y lo que lo deja molido: una **GitHub Action** que en cada etiqueta `v*` suba a Modrinth y a
CurseForge a la vez, con el changelog del `CHANGELOG.md`.

## 5. Comprobaciones

- El proyecto aparece en `curseforge.com/minecraft/bukkit-plugins/<slug>` cuando está aprobado.
- Un archivo **Release** aprobado es lo que hace que aparezca en el launcher.
- CurseForge exige la **descripción en inglés y con gramática correcta**: por eso los HTML están en
  inglés aunque el repositorio y Modrinth lleven también la versión en español.
