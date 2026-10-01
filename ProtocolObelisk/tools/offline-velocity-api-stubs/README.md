# Compile-only Velocity API descriptors

These minimal sources exist only to compile and JVM-verify the Velocity production and test
sources when the official dependency graph is unavailable. They are never packaged in the runtime
JAR and do not implement proxy behavior.

For 1.9.15 they are compiled with the Paper descriptors into one bounded compile-only artifact.
The reviewed manual `javac`/JUnit path receives that artifact alongside real JUnit and Netty JARs,
compiles with Java 21 and `-Xlint:all -Werror`, and runs both module suites. It does not execute
Gradle. See `../MANUAL-BUILD.md` and `../build-offline-api.sh` for exact exclusions and commands.
The release packager independently rejects
Velocity, Netty, Adventure and backend API namespaces if a descriptor leaks into a published JAR.

Exact dependency hashes, class counts and test results are recorded under
`build-evidence/1.9.15-EVOLUTION/`. Compatibility with the exact deployed Velocity binary remains
a smoke-test gate; compile-only descriptors are not presented as runtime proof.
