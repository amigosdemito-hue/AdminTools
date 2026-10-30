/*
 * ============================================================
 *  AdminTools — build del plugin
 *  Objetivo: Paper 26.1+ · Java 25 · Gradle 9.7.1
 * ============================================================
 *
 *  Tareas útiles:
 *    ./gradlew build      -> compila + arma el jar final (shadowJar)
 *    ./gradlew runServer  -> descarga Paper y arranca un servidor de pruebas con este plugin
 *    ./gradlew tasks      -> lista todas las tareas disponibles
 *
 *  plugin.yml se escribe a mano en src/main/resources/ y sólo se le
 *  inyectan ${version}, ${description} y ${author} desde gradle.properties.
 */

plugins {
    java
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = providers.gradleProperty("description").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // API de Paper: la provee el servidor en tiempo de ejecución -> compileOnly.
    // (Adventure y MiniMessage ya vienen dentro de Paper: no se shaddean.)
    compileOnly(libs.paper.api)

    // Drivers de base de datos: Paper YA los trae en su carpeta libraries/
    // (org.xerial:sqlite-jdbc y com.mysql:mysql-connector-j), así que se
    // compilan como compileOnly y no engordan el jar.
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.mysql.connector)

    // PlaceholderAPI: integración opcional (softdepend en plugin.yml).
    compileOnly(libs.placeholderapi)

    // Floodgate + Cumulus: formularios nativos para clientes Bedrock. También
    // compileOnly; si Floodgate no está instalado el panel usa diálogos Java y
    // estas clases nunca se cargan (ver BedrockPanel).
    compileOnly(libs.floodgate.api)
    compileOnly(libs.cumulus)

    // Métricas anónimas de bStats (opcional: si se quita, borrar también
    // la línea de relocation de shadowJar y la clase de métricas).
    implementation(libs.bstats.bukkit)

    // Pruebas: sólo se usan en `./gradlew test`, nunca dentro del jar. La API de
    // Paper entra también en el classpath de test para poder tocar Adventure
    // (MiniMessage) y los tipos de Bukkit que aparecen en las firmas.
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Xlint:-this-escape"))
    }

    // El jar publicable es el "gordo" (shadowJar, sin classifier);
    // el jar normal queda aparte como "-plain" para inspecciones.
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        // Relocalizamos las librerías empaquetadas para no chocar con
        // las de otros plugins que usen las mismas clases.
        relocate("org.bstats", "com.admintools.libs.bstats")
    }

    build {
        dependsOn(named("shadowJar"))
    }

    processResources {
        val tokens = mapOf(
            "version" to project.version.toString(),
            "description" to project.description.toString(),
            "author" to providers.gradleProperty("author").get(),
        )
        inputs.properties(tokens)
        filesMatching("plugin.yml") { expand(tokens) }
    }

    // Servidor Paper de pruebas: descarga la versión de gradle.properties,
    // le copia el plugin compilado y lo arranca. Datos en ./run/
    runServer {
        minecraftVersion(providers.gradleProperty("paperServerVersion").get())
        jvmArgs("-Xms1G", "-Xmx2G")
    }
}
