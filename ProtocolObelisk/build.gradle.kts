import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat

plugins {
    base
}

val protocolObeliskVersion = providers.gradleProperty("protocolObeliskVersion").get().also {
    require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-EVOLUTION"))) {
        "protocolObeliskVersion must use the bounded form X.Y.Z-EVOLUTION"
    }
}
val velocityArchiveName = "ProtocolObelisk-Velocity-$protocolObeliskVersion.jar"
val paperArchiveName = "ProtocolObelisk-Paper-$protocolObeliskVersion.jar"
val sourceArchiveName = "ProtocolObelisk-$protocolObeliskVersion-source.zip"
val releaseArchiveName = "ProtocolObelisk-$protocolObeliskVersion-release.zip"
val releaseChecksumName = "$releaseArchiveName.sha256"
val publicationNames = setOf(
    velocityArchiveName,
    paperArchiveName,
    sourceArchiveName,
    releaseArchiveName,
    releaseChecksumName,
    "CHECKSUMS.sha256"
)

fun requireNoSymlinkComponents(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current: Path = requireNotNull(absolute.root) { "absolute path has no root: $absolute" }
    for (component in absolute) {
        current = current.resolve(component)
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(current)) {
            "symlinked path component is forbidden: $current"
        }
    }
}

fun sha256Hex(path: Path): String {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path)) {
        "not a regular non-symlink file: $path"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

fun exactLeafFiles(directory: Path): Map<String, Path> {
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(directory)) {
        "not a regular publication directory: $directory"
    }
    return Files.list(directory).use { children ->
        children.iterator().asSequence().associateBy { child ->
            require(Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(child)) {
                "publication contains a non-regular leaf: $child"
            }
            child.fileName.toString()
        }
    }
}

fun verifyExactPublication(directory: Path) {
    val files = exactLeafFiles(directory)
    require(files.keys == publicationNames) {
        "publication tree mismatch: expected=$publicationNames, actual=${files.keys}"
    }
}

fun deleteOwnedTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(root)) {
        "refusing to delete non-directory or symlink: $root"
    }
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

fun copyExactPublication(source: Path, target: Path) {
    verifyExactPublication(source)
    Files.createDirectory(target)
    for ((name, path) in exactLeafFiles(source).toSortedMap()) {
        Files.copy(path, target.resolve(name))
    }
    verifyExactPublication(target)
}

fun requireByteIdentical(first: Path, second: Path) {
    verifyExactPublication(first)
    verifyExactPublication(second)
    for (name in publicationNames) {
        require(sha256Hex(first.resolve(name)) == sha256Hex(second.resolve(name))) {
            "immutable publication differs for $name"
        }
    }
}

abstract class ProtocolObeliskReleaseLock :
        BuildService<ProtocolObeliskReleaseLock.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val lockFile: RegularFileProperty
    }

    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock

    init {
        val lockPath = parameters.lockFile.get().asFile.toPath()
        requireNoSymlinkComponents(lockPath.parent)
        Files.createDirectories(lockPath.parent)
        channel = FileChannel.open(
            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = channel.lock()
    }

    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }
}

val isolatedBuildRoot = providers.gradleProperty("protocolObeliskBuildRoot").orNull
    ?.let { value ->
        val candidate = file(value).toPath().toAbsolutePath().normalize()
        val projectRoot = layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
        require(candidate.root != candidate && candidate != projectRoot
                && !candidate.startsWith(projectRoot)
                && !projectRoot.startsWith(candidate)) {
            "protocolObeliskBuildRoot must be an absolute/disjoint dedicated directory: $candidate"
        }
        requireNoSymlinkComponents(candidate)
        candidate
    }

allprojects {
    group = "br.com.atmbrasil"
    version = protocolObeliskVersion
    if (isolatedBuildRoot != null) {
        val leaf = if (this == rootProject) "root" else name
        layout.buildDirectory.set(isolatedBuildRoot.resolve(leaf).toFile())
    }
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }
    extensions.configure<SourceSetContainer> {
        named("main") {
            java.srcDir(rootProject.layout.projectDirectory.dir("common/src/main/java"))
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("protocolObeliskVersion", project.version.toString())
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        includeEmptyDirs = false
    }
}

val releaseLock = gradle.sharedServices.registerIfAbsent(
    "protocolObeliskReleaseLock", ProtocolObeliskReleaseLock::class) {
    parameters.lockFile.set(layout.projectDirectory.file(".gradle/protocolobelisk-release.lock"))
    maxParallelUsages.set(1)
}

tasks.named("check") {
    dependsOn(":velocity-plugin:check", ":paper-plugin:check")
}

val releaseInputs = layout.buildDirectory.dir("release-inputs")
val prepareReleaseInputs by tasks.registering(Sync::class) {
    dependsOn(":velocity-plugin:jar", ":paper-plugin:jar")
    usesService(releaseLock)
    outputs.upToDateWhen { false }
    includeEmptyDirs = false
    from(project(":velocity-plugin").layout.buildDirectory.file("libs/$velocityArchiveName"))
    from(project(":paper-plugin").layout.buildDirectory.file("libs/$paperArchiveName"))
    into(releaseInputs)
    doLast {
        val directory = releaseInputs.get().asFile.toPath()
        val jarNames = sortedSetOf(velocityArchiveName, paperArchiveName)
        val actual = exactLeafFiles(directory)
        require(actual.keys == jarNames) {
            "release-input tree mismatch before checksums: expected=$jarNames, actual=${actual.keys}"
        }
        val checksums = jarNames.joinToString(separator = "\n", postfix = "\n") { name ->
            "${sha256Hex(directory.resolve(name))}  $name"
        }
        Files.writeString(
            directory.resolve("CHECKSUMS.sha256"), checksums,
            Charsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

val releaseCandidate = layout.buildDirectory.dir("release-candidate/$protocolObeliskVersion")
val pythonExecutable = providers.environmentVariable("PYTHON").orElse("python3")
val packageReleaseCandidate by tasks.registering(Exec::class) {
    dependsOn(prepareReleaseInputs, "check")
    usesService(releaseLock)
    outputs.upToDateWhen { false }
    val candidate = releaseCandidate.get().asFile.toPath()
    doFirst {
        val buildRoot = layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val normalized = candidate.toAbsolutePath().normalize()
        require(normalized.startsWith(buildRoot) && normalized != buildRoot) {
            "release candidate is outside the owned build tree: $normalized"
        }
        requireNoSymlinkComponents(normalized)
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            deleteOwnedTree(normalized)
        }
        Files.createDirectories(normalized.parent)
    }
    commandLine(
        pythonExecutable.get(),
        layout.projectDirectory.file("tools/make-evolution-release.py").asFile,
        layout.projectDirectory.asFile,
        releaseInputs.get().asFile,
        releaseCandidate.get().asFile
    )
    environment("LC_ALL", "C")
    environment("TZ", "UTC")
    environment("PYTHONDONTWRITEBYTECODE", "1")
}

val verifyReleaseCandidate by tasks.registering(Exec::class) {
    dependsOn(packageReleaseCandidate)
    usesService(releaseLock)
    outputs.upToDateWhen { false }
    commandLine(
        pythonExecutable.get(),
        layout.projectDirectory.file("tools/make-evolution-release.py").asFile,
        "--validate-publication",
        releaseCandidate.get().asFile
    )
    environment("LC_ALL", "C")
    environment("TZ", "UTC")
    environment("PYTHONDONTWRITEBYTECODE", "1")
}

val releaseOutput = layout.projectDirectory.dir("dist/$protocolObeliskVersion")
val publishRelease by tasks.registering {
    dependsOn(verifyReleaseCandidate)
    usesService(releaseLock)
    outputs.upToDateWhen { false }
    doLast {
        val source = releaseCandidate.get().asFile.toPath()
        val target = releaseOutput.asFile.toPath()
        val parent = target.parent
        verifyExactPublication(source)
        requireNoSymlinkComponents(target)
        Files.createDirectories(parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireByteIdentical(source, target)
            return@doLast
        }
        val temporary = Files.createTempDirectory(
            parent, ".ProtocolObelisk-$protocolObeliskVersion.publish-")
        try {
            Files.delete(temporary)
            copyExactPublication(source, temporary)
            for (path in exactLeafFiles(temporary).values) {
                FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                    channel.force(true)
                }
            }
            FileChannel.open(temporary, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(parent, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            requireByteIdentical(source, target)
        } finally {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                deleteOwnedTree(temporary)
            }
        }
    }
}

tasks.register("dist") {
    dependsOn(publishRelease)
    description = "Builds and atomically publishes the two-component lobby-first release."
}
