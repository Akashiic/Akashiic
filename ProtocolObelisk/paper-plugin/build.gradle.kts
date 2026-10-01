plugins {
    java
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.layout.projectDirectory.dir("necro-common/src/main/java"))
    }
}

dependencies {
    val offlineApiDir = providers.gradleProperty("offlinePaperApiDir").orNull
        ?: providers.gradleProperty("offlineApiDir").orNull
    if (offlineApiDir != null) {
        compileOnly(fileTree(offlineApiDir) { include("*.jar") })
        testImplementation(fileTree(offlineApiDir) { include("*.jar") })
    } else {
        compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
        compileOnly("io.netty:netty-transport:4.1.97.Final")
        // Tests exercise the plugin's Bukkit-facing helpers, so the API is needed on their
        // classpath as well (it is never packaged into the plugin JAR).
        testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
        testImplementation("io.netty:netty-transport:4.1.97.Final")
        testImplementation(platform("org.junit:junit-bom:5.13.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveFileName.set("ProtocolObelisk-Paper-${project.version}.jar")
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE.txt" }
    }
    manifest {
        attributes(
            "Implementation-Title" to "ProtocolObelisk - Paper",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "br.com.atmbrasil.lobby.paper"
        )
    }
}
