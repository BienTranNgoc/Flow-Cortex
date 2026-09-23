// Root of the multi-module build. Per-project build logic lives in the
// buildSrc convention plugins (flowcortex.spring-module-conventions,
// flowcortex.spring-boot-app-conventions), not here.
allprojects {
    group = "com.flowcortex"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
