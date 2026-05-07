plugins {
    id("java")
}

dependencies {
    implementation("com.github.umjammer:jlayer:1.0.3")

    // jdosbox-specific dependencies
    implementation("org.javassist:javassist:3.29.2-GA")

    implementation("com.github.umjammer:vavi-commons:1.1.16")
}
