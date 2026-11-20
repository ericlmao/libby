rootProject.name = "libby"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            name = "spigotmc"
            mavenContent { snapshotsOnly() }
            content {
                includeGroup("org.spigotmc")
                includeGroup("org.bukkit")
            }
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroupAndSubgroups("io.papermc")
                includeGroup("com.velocitypowered")
                // Paper API depends on a Paper-built bungeecord-chat that only lives here.
                includeVersionByRegex("net\\.md-5", "bungeecord-chat", ".*-deprecated\\+build.*")
            }
        }
        maven("https://repo.opencollab.dev/maven-snapshots/") {
            name = "opencollab"
            mavenContent { snapshotsOnly() }
            content {
                includeGroup("cn.nukkit")
            }
        }
    }
}

// Every module is published as `libby-<directory>`.
listOf("core", "slf4j", "bukkit", "bungee", "velocity", "nukkit", "paper").forEach { module ->
    include("libby-$module")
    project(":libby-$module").projectDir = file(module)
}
