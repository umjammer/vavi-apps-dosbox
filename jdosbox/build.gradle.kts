plugins {
    id("java")
}

dependencies {
    implementation("com.github.umjammer:jlayer:1.0.3")

    // jdosbox-specific dependencies
    implementation("org.javassist:javassist:3.32.0-GA")

    implementation("com.github.umjammer:vavi-commons:1.1.19")

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testImplementation("org.junit.platform:junit-platform-commons:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")

    testImplementation(project(":jdosbox-pcap"))
    testImplementation(project(":jdosbox-win"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    systemProperty("java.util.logging.config.file", "jdosbox/src/test/resources/logging.properties")
    // hand the -D's on the gradle command line to the test jvm
    // -Djdos.novideo=true runs a win32 guest without drawing anything, which for a program wanted
    // only for its sound gives most of the machine's time back
    for (key in listOf("vavi.test", "cycles", "timeout", "mmftool.path", "jdosbox.volume", "freeDrain", "turbo", "repeats", "mmf", "rate", "mmfs",
            "jdos.novideo", "jdos.compile.sse", "jdos.registry", "memory", "fmp7.path", "songs", "paced", "fmp7.song", "threshold", "min_block_size", "queue", "prime", "chunk", "runs", "pace", "snapshot", "stall", "stallevery", "jdos.trail", "gap")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // -Dtest.jdk=<java home> runs the tests on another jdk, to compare how they carry the emulation
    System.getProperty("test.jdk")?.let { executable = "$it/bin/java" }
    System.getProperty("test.jvmargs")?.let { jvmArgs(it.split(" ")) }
    testLogging { showStandardStreams = true }
}
