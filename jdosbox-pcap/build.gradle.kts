plugins {
    id("java")
}

dependencies {
    // jdosbox-pcap-specific dependencies
    implementation(project(":jdosbox"))
    implementation("jnetpcap:jnetpcap:1.5.r1457-1i")
}
