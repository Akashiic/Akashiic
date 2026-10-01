# Compile-only Paper API descriptors

These sources expose only the external descriptors required by the Paper production and test
sources when the official dependency graph is unavailable. They contain no server behavior and
must never be packaged in a ProtocolObelisk runtime JAR.

The reviewed offline build compiles these descriptors together with the Velocity descriptors into
one compile-only JAR. In 1.9.15 that JAR is supplied to the reviewed manual `javac`/JUnit build
alongside real JUnit and Netty artifacts; Gradle is not executed. See `../MANUAL-BUILD.md` and
`../build-offline-api.sh` for exact exclusions and commands. Both main and test sources are compiled with Java 21,
`-Xlint:all -Werror`, and the complete Velocity/Paper JUnit suites are executed. The release
packager rejects API namespaces such as `org/bukkit/`, `net/kyori/`, `io/netty/` and
`com/velocitypowered/` if they leak into a runtime JAR.

The exact dependency hashes and test counts for 1.9.14 are recorded under
`build-evidence/1.9.15-EVOLUTION/`. This bounded offline gate does not replace the required smoke
test on the deployed Paper/Purpur and Velocity binaries.
