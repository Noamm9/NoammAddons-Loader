plugins { java }

version = property("mod.version").toString()
val loaderVersion = property("fabric.loader").toString()

base { archivesName = rootProject.name }

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:$loaderVersion")
}

tasks.processResources {
    val props = mapOf("version" to version, "loader_version" to loaderVersion)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.jar { from("LICENSE") }