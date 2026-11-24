import java.util.*

val props = Properties().apply {
    file("gradle.properties").inputStream().use(::load)
}

rootProject.name = props.getProperty("mod.name").toString()