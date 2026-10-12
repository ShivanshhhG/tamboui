plugins {
    id("dev.tamboui.demo-project")
}

description = "Emoji list displayed using Columns (similar to Textual's Emoji demo)"

demo {
    tags = setOf("toolkit", "columns", "emoji", "inline", "text")
    // Inline print-and-exit demo: its recording is too short for the site's looping player
    internal = true
}

dependencies {
    implementation(projects.tambouiToolkit)
}

application {
    mainClass.set("EmojiListDemo")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
