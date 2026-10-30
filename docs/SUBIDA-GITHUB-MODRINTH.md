# Subida a GitHub y Modrinth — checklist preparada

Todo lo que hace falta para publicar **AdminTools**, en el orden en que se hace. **No está publicado
aún**: este documento es el preparado para que la subida sea un solo repaso.

---

## 0. Estado actual

| Ítem | Estado |
|---|---|
| Repositorio git inicializado (`main`) | ✅ |
| `.gitignore` (Gradle, IDE, `run/`, logs, base de datos, secretos) | ✅ |
| Integración continua (`build.yml`: compila + pruebas en cada push) | ✅ |
| `LICENSE` (todos los derechos reservados: público, no código abierto) | ✅ |
| `CHANGELOG.md` | ✅ |
| `README.md` con instalación, rangos y configuración | ✅ |
| Manual de rangos (`docs/ROL-*.md`) y comandos (`docs/COMANDOS-Y-PERMISOS.md`) | ✅ |
| Tests (`./gradlew build`) | ✅ 43 pruebas |
| Remoto de GitHub | ✅ `github.com/amigosdemito-hue/AdminTools` (público), subido y CI en verde |
| `release.yml` (jar adjunto en cada etiqueta) | ✅ listo, se dispara con `git tag` |
| Proyecto publicado en Modrinth | ⏳ pendiente: slug `admintools` ocupado → se usa **`admintools-suite`** y el `MODRINTH_TOKEN` actual devuelve 401 (hay que generar otro PAT) |
| Release `v0.5.1` con el jar | ⏳ pendiente de la etiqueta (`git tag v0.5.1 && git push origin v0.5.1`) |

---

## 1. Antes de publicar (comprobación en 2 minutos)

```bash
./gradlew clean build        # compila y pasa los tests
ls build/libs/               # debe salir AdminTools-<versión>.jar y un -plain.jar
```

- ✅ El jar «gordo» es el que se sube; el `-plain.jar` **nunca**.
- ✅ No quedan secretos: `grep -rE "discord.com/api/webhooks" src/` debe dar vacío (los webhooks
  viven en `config.yml` **del servidor**, que está en `run/` y fuera del repo).
- ✅ El `plugin.yml` y `gradle.properties` llevan la misma versión que `CHANGELOG.md`.
- ✅ Prueba en un servidor limpio: borrar `plugins/AdminTools/`, arrancar con el jar nuevo y revisar
  el letrero de consola, `/at help` y un `/ban` de prueba.

## 2. GitHub

```bash
# 1) primer push (una sola vez) — GitHub CLI ya autenticado como amigosdemito-hue
gh repo create AdminTools --public --source . --remote origin --push

# 2) etiqueta de versión (dispara release.yml y publica la release con el jar)
git tag v0.5.1
git push origin v0.5.1
```

- El workflow `.github/workflows/release.yml` compila con Java 25, sube el jar como artefacto de la
  release `v0.5.1` y pone en el cuerpo el bloque `## [0.5.1]` del `CHANGELOG.md`.
- Los enlaces del `CHANGELOG.md` apuntan a `github.com/amigosdemito-hue/AdminTools`.
- Rama por defecto: `main`. La CI de `build.yml` corre en cada push y pull request.

## 3. Modrinth

**Requisitos:** cuenta en Modrinth y un token de acceso personal con permiso *create versions*
(<https://modrinth.com/settings> → PAT). O subida manual desde el panel.

### 3.1 Metadatos del proyecto (una sola vez, si aún no existe)

| Campo | Valor |
|---|---|
| Slug | `admintools-suite` (título visible: **AdminTools**; `admintools` está ocupado por otro autor) |
| Categoría | `utility` · `management` (secundarias: `admin-tools`) |
| Carga (loader) | `paper` |
| Versiones de juego | `26.1` y superiores |
| Licencia | **All Rights Reserved** (`arr` en la API; archivo `LICENSE`: público para consulta, sin permiso de redistribución) |
| Fuente | URL del repositorio de GitHub |
| Resumen (≤256) | Suite de administración para servidores Paper 26.1+: sanciones, celdas, rollback, reportes y paneles por rango (helper, moderador, admin) con interfaz de inventario, diálogos y formularios de Bedrock. |
| Dependencias | Ninguna obligatoria. Opcionales: LuckPerms, CoreProtect, PlaceholderAPI, Floodgate/Geyser, Vault. |

### 3.2 Publicar la versión (API)

```bash
export MODRINTH_TOKEN="***"        # nunca en el repositorio: - MODRINTH_TOKEN, en el entorno

curl -X POST "https://api.modrinth.com/v2/version" \
  -H "Authorization: $MODRINTH_TOKEN" \
  -H "Content-Type: multipart/form-data" \
  -F "data='{
        "project_id": "<id-del-proyecto>",
        "name": "AdminTools 0.5.1",
        "version_number": "0.5.1",
        "game_versions": ["26.1"],
        "loaders": ["paper"],
        "version_type": "release",
        "changelog": "$(sed -n '/^## \[0.5.1\]/,/^---$/p' CHANGELOG.md | sed 's/"/\\"/g')",
        "dependencies": []
      }' \
  -F "file=@build/libs/AdminTools-0.5.1.jar"
```

Manual: Modrinth → *Dashboard* → *Create project* → *Upload version*, elegir el jar y pegar el
bloque de la versión del `CHANGELOG.md` en la descripción.

### 3.3 Reglas de Modrinth

- Subir **el jar sin el sufijo `-plain`** y con el `plugin.yml` dentro (el jar de Gradle ya lo lleva).
- Cada versión necesita `game_versions` y `loaders` correctos: el plugin es de `paper` (no fabric
  ni bukkit genérico).
- El changelog de la versión debe ser el bloque de `CHANGELOG.md`, sin enlaces rotos.
- Si el *review* de Modrinth pide aclaraciones: es un plugin de Paper, se instala en `plugins/` y no
  requiere dependencias obligatorias.

## 4. Después de publicar

1. Comprobar que el enlace de la release de GitHub entrega el jar correcto (tamaño y nombre).
2. Probar la descarga de Modrinth en un servidor limpio.
3. Enlazar Modrinth en la descripción de la release de GitHub (y viceversa).
4. Cerrar el ciclo: `docs/PLAN-0-y-10.md` apunta a *release engineering* (semver, changelog,
   compatibilidad); esta lista es ese punto.

---

## Notas

- **Nunca** subir `run/` (servidor de pruebas con mundos, base de datos y webhooks reales): ya está
  en `.gitignore`, comprobado antes del primer push.
- El token de Modrinth va en el entorno local o en los *secrets* de GitHub (`MODRINTH_TOKEN`),
  nunca en un archivo del repositorio.
- Si el proyecto pasa a tener organización en GitHub, actualizar los enlaces del `CHANGELOG.md`.
