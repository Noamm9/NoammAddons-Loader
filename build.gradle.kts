plugins { java }

val desc = property("mod.desc").toString()
val modId = property("mod.id").toString()
version = property("mod.version").toString()

val loaderVersion = property("fabric.loader").toString()

repositories { maven("https://maven.fabricmc.net") }
dependencies { compileOnly("net.fabricmc:fabric-loader:$loaderVersion") }

base { archivesName = rootProject.name.replace(" ", "-") }

tasks.processResources {
    val props = mapOf(
        "name" to rootProject.name,
        "desc" to desc,
        "version" to version,
        "id" to modId,
        "loader_version" to loaderVersion
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

val stubJar = tasks.register<Jar>("stubJar") {
    description = "bundles dummy mod jar"
    archiveFileName = "noammaddons-stub.jar"
    destinationDirectory = layout.buildDirectory.dir("stub")
    from("src/stub/resources")
}

tasks.jar {
    from(stubJar) { into("META-INF/jars") }
    from("LICENSE")
}