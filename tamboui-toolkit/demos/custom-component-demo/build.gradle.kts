plugins {
    id("dev.tamboui.demo-project")
}

description = "Demo showcasing custom CSS-styled components with @OnAction annotations"

demo {
    tags = setOf("toolkit", "custom-component", "css", "panel", "gauge", "mouse")
}

dependencies {
    implementation(projects.tambouiToolkit)
    implementation(projects.tambouiAnnotations)
    annotationProcessor(projects.tambouiProcessor)
}

application {
    mainClass.set("dev.tamboui.demo.CustomComponentDemo")
}
