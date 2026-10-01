plugins {
    java
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.layout.projectDirectory.dir("necro-common/src/main/java"))
    }
}

dependencies {
    val offlineApiDir = providers.gradleProperty("offlineVelocityApiDir").orNull
        ?: providers.gradleProperty("offlineApiDir").orNull
    if (offlineApiDir != null) {
        compileOnly(fileTree(offlineApiDir) { include("*.jar") })
        testImplementation(fileTree(offlineApiDir) { include("*.jar") })
    } else {
        compileOnly("com.velocitypowered:velocity-api:3.5.1")
        compileOnly("io.netty:netty-buffer:4.1.97.Final")
        compileOnly("io.netty:netty-transport:4.1.97.Final")
        testImplementation("com.velocitypowered:velocity-api:3.5.1")
        testImplementation("io.netty:netty-buffer:4.1.97.Final")
        testImplementation("io.netty:netty-transport:4.1.97.Final")
        testImplementation(platform("org.junit:junit-bom:5.13.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("velocity-plugin.json") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveFileName.set("ProtocolObelisk-Velocity-${project.version}.jar")
    // Avoid an intermittent native-deflater truncation observed while archiving the large,
    // immutable registry fixtures on container filesystems. The outer release ZIP still
    // compresses this JAR, while the deployable JAR itself remains deterministic and auditable.
    entryCompression = ZipEntryCompression.STORED
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE.txt" }
    }
    manifest {
        attributes(
            "Implementation-Title" to "ProtocolObelisk - Velocity",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "br.com.atmbrasil.lobby.velocity"
        )
    }
}
