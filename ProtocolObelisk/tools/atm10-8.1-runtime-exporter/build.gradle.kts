plugins {
    java
}

group = "br.com.atmbrasil.protocolobelisk"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val runtimeRootText = providers.gradleProperty("atm10RuntimeRoot").orNull
val runtimeRoot = runtimeRootText?.let(::file)

if (runtimeRoot != null) {
    val minecraftSrg = runtimeRoot.resolve(
        "libraries/net/minecraft/server/1.21.1-20240808.144430/" +
            "server-1.21.1-20240808.144430-srg.jar"
    )
    val neoforge = runtimeRoot.resolve(
        "libraries/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar"
    )
    val loader = runtimeRoot.resolve(
        "libraries/net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44.jar"
    )
    dependencies {
        compileOnly(files(minecraftSrg, neoforge, loader))
        compileOnly(fileTree(runtimeRoot.resolve("libraries")) { include("**/*.jar") })
    }
    tasks.compileJava {
        doFirst {
            listOf(minecraftSrg, neoforge, loader).forEach { required ->
                check(required.isFile) { "Missing exact compile input: $required" }
            }
        }
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-proc:none"))
    doFirst {
        check(runtimeRoot != null) {
            "Pass -Patm10RuntimeRoot=/absolute/path/to/pure/ATM10-8.1-runtime"
        }
    }
}

tasks.jar {
    archiveFileName.set("protocolobelisk-atm10-8.1-runtime-exporter-1.0.0.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to "ProtocolObelisk ATM10 8.1 Runtime Exporter",
            "Implementation-Version" to project.version
        )
    }
}
