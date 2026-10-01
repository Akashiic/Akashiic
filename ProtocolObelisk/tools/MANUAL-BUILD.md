# Reviewed manual build — 1.9.16-EVOLUTION

This independent build path uses Java 21 `javac` and the real JUnit console.
It does not execute Gradle and must not be described as a Gradle build. The
Gradle build definitions and wrapper remain available for environments with
the complete distribution and dependency graph. The manual path exists because
the audited environment could obtain Java and Maven inputs normally, but its
Gradle distribution download was blocked; that restriction was not bypassed.

## Inputs

- Complete Java 21 JDK, including `java`, `javac`, and `jar`.
- Python 3 (standard library only), Bash, `rg`, `realpath`, `sort`, `sed`, `sha256sum`.
- Real Maven Central artifacts in a dedicated external `offline-api-inputs` directory:
  - `io.netty:netty-common:4.1.97.Final`
  - `io.netty:netty-buffer:4.1.97.Final`
  - `io.netty:netty-transport:4.1.97.Final`
  - `io.netty:netty-resolver:4.1.97.Final`
  - `org.junit.platform:junit-platform-console-standalone:1.13.4`

Retain the Maven filenames exactly. No dependency JAR is bundled into the
ProtocolObelisk source or runtime artifacts. The audited input hashes and
actual test counts belong in the release build evidence.

## Compile-only API preparation

From the source root:

```bash
bash tools/build-offline-api.sh /path/to/java21-jdk /path/to/offline-api-inputs
```

This builds `obelisk-compile-only-api.jar` from the checked-in descriptor
sources. It excludes **all** `io/netty/` descriptor sources so compilation and
tests use real Netty behavior. It also excludes the Velocity descriptor tree's
`net/kyori/` sources, selecting the Paper Adventure superset once. These are
compile/test substitutes for platform descriptors, not implementation code or
live-platform proof. The script refuses to overwrite an existing API JAR and
prints the retained temporary classes directory and output digest.

The descriptors have a fixed JAR timestamp for reproducibility. The script
performs no downloads and changes no production source. Its output must never
be installed in Velocity/Paper or put into the release's runtime JARs.

## Independent source builds

Freeze the source and release documents before invoking either build. Select
two fresh output directories outside the source tree:

```bash
python3 tools/build-manual-reviewed.py /absolute/source-root /absolute/build-one \
  --jdk /path/to/java21-jdk --api-dir /path/to/offline-api-inputs
python3 tools/build-manual-reviewed.py /absolute/source-root /absolute/build-two \
  --jdk /path/to/java21-jdk --api-dir /path/to/offline-api-inputs
```

Each run:

1. Pins the packager's canonical source-tree digest before compilation.
2. Independently compiles Velocity, Paper, and their shared source with Java 21,
   `-g -Xlint:all -Werror`; platform APIs remain classpath-only.
3. Compiles the tests, processes release-version placeholders, and runs all
   discovered module tests under real JUnit with `-Xverify:all`.
4. Builds fresh deterministic JARs from production classes and resources only,
   using fixed `1980-02-01` timestamps, sorted entries, and stable regular-file
   modes. This epoch matches the existing JAR validator's archive format; it
   is not a claim that Gradle ran.
5. Invokes `make-evolution-release.py --validate-jars-only` to enforce the
   two-component inventory, Java65 bytecode, metadata, checksums, and platform
   namespace exclusions.
6. Rechecks the source digest and fails if sources changed during the build.

Each output retains compile/test logs, exact command arguments as JSON, JUnit
XML, source digest, and `jars/CHECKSUMS.sha256`. Compare the two JAR files with
`cmp` or compare the two checksum files before publication. Identical JARs from
these runs establish manual-build reproducibility, not Gradle execution.

Use the existing release packager to create and validate the distribution from
one verified `jars/` directory. Run its Python tests too. Never rename a prior
release JAR to pass as a new build.

## Limits

Descriptor compilation plus offline tests does not prove linkage against the
exact deployed Velocity/Paper binaries, real-client rendering, movement,
inventory behavior, or a backend round-trip. Those remain live homologation
gates. Test pass counts must come from the current run, not historical evidence.
