plugins {
    id("dev.tamboui.demo-project")
}

description = "A simple UI built using Java 25 and Compact Source File"

demo {
    tags = setOf("toolkit", "panel", "row", "column", "text", "focus", "draggable", "mouse")
    internal = true
}

dependencies {
    implementation(projects.tambouiToolkit)
}

application {
    mainClass.set("CompactDemo")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}