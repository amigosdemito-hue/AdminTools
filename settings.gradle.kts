// ============================================================
//  AdminTools — configuración raíz del proyecto
//  Paper 26.1+ · Java 25 · Gradle 9.7.1
// ============================================================

pluginManagement {
    repositories {
        gradlePluginPortal() // de aquí salen run-paper y shadow
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral() // bStats, drivers JDBC, PlaceholderAPI
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC" // API de Paper (paper-api)
        }
        maven("https://repo.extendedclip.com/releases/") {
            name = "ExtendedClip" // PlaceholderAPI (integración opcional)
        }
        maven("https://repo.opencollab.dev/main/") {
            name = "OpenCollab" // Floodgate y Cumulus (formularios Bedrock, opcional)
        }
    }
}

rootProject.name = "AdminTools"
