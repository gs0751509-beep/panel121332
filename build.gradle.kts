plugins {
    java
}

group = "com.shulfur"
version = "1.2.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pin an exact build for production, e.g. "26.3.build.<number>", once you've picked one.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("ShulfurHunt-${project.version}.jar")
}
