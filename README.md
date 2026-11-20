# Libby

A runtime dependency management library for plugins running in Java-based Minecraft
server platforms.

Libraries can be downloaded from Maven repositories (or direct URLs) into a plugin's data
folder, verified, relocated and then loaded into the plugin's classpath at runtime.
Optionally, a library's transitive dependencies are resolved from its POM and loaded too.

### Why use runtime dependency management?

Due to file size constraints on plugin hosting services like SpigotMC, some plugins with
bundled dependencies become too large to be uploaded.

Using runtime dependency management, dependencies are downloaded and cached by the server
and don't need to be bundled with the plugin, which significantly reduces the size of the
plugin jar.

A smaller plugin jar also means shorter download times and less network strain for authors
who self-host their plugins on servers with limited bandwidth.

## Contents

- [Requirements and platforms](#requirements-and-platforms)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Describing a library](#describing-a-library)
- [Transitive dependencies](#transitive-dependencies)
- [Repositories](#repositories)
- [Security model](#security-model)
- [Classpath injection on Java 16+](#classpath-injection-on-java-16)
- [Configuration](#configuration)
- [Fabric and other platforms](#fabric-and-other-platforms)
- [Migrating from 0.0.x / 1.x](#migrating-from-00x--1x)
- [Building from source](#building-from-source)

## Requirements and platforms

Libby requires **Java 17 or newer** at runtime.

| Platform                                | Module           | Library manager                                                      |
|-----------------------------------------|------------------|----------------------------------------------------------------------|
| Bukkit / Spigot / Paper (`plugin.yml`)  | `libby-bukkit`   | `BukkitLibraryManager`                                               |
| Paper plugins (`paper-plugin.yml`)      | `libby-paper`    | `PaperLibraryManager`, used inside a Paper `PluginLoader`            |
| BungeeCord / Waterfall                  | `libby-bungee`   | `BungeeLibraryManager`                                               |
| Velocity                                | `libby-velocity` | `VelocityLibraryManager`                                             |
| Nukkit                                  | `libby-nukkit`   | `NukkitLibraryManager`                                               |
| Anything else                           | `libby-core`     | `loadLibrary(Library, IsolatedClassLoader)` or your own `LibraryManager` subclass |

`libby-slf4j` contains `SLF4JLogAdapter` and is pulled in by the Velocity and Paper modules.

## Installation

The coordinates are `net.byteflux:libby-<platform>:2.0.0-SNAPSHOT`, for example
`net.byteflux:libby-bukkit:2.0.0-SNAPSHOT`. Version 2.0.0 isn't published to a public
repository yet: build it from source and install it with `./gradlew publishToMavenLocal`
(see [Building from source](#building-from-source)), or publish it to your own repository.

Libby is meant to be **shaded into your plugin and relocated**, so that it never clashes
with another plugin's copy of libby.

**Gradle (Kotlin DSL)** with the [Shadow](https://github.com/GradleUp/shadow) plugin:

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("net.byteflux:libby-bukkit:2.0.0-SNAPSHOT")
}

tasks.shadowJar {
    relocate("net.byteflux.libby", "com.example.libs.libby")
}
```

**Maven** with the shade plugin:

```xml
<dependency>
    <groupId>net.byteflux</groupId>
    <artifactId>libby-bukkit</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>

<!-- in <build><plugins> -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.2</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <relocations>
                    <relocation>
                        <pattern>net.byteflux.libby</pattern>
                        <shadedPattern>com.example.libs.libby</shadedPattern>
                    </relocation>
                </relocations>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### The `{}` trick

Shading tools rewrite string literals that look like relocated package names. If your
plugin relocates `com.google.gson` and also contains `"com.google.code.gson"` as a group ID
string, the string may be rewritten too. Libby therefore accepts `{}` in place of `.` in
group IDs (`Library.Builder#groupId`, `excludeTransitiveDependency`) and in relocation
patterns:

```java
Library.builder()
    .groupId("com{}google{}code{}gson")      // becomes com.google.code.gson
    .relocate("com{}google{}gson", "com{}example{}libs{}gson")
```

## Quick start

Every platform follows the same pattern: create the manager, add at least one repository,
then load libraries. Libraries are downloaded into a `lib` directory in the plugin's data
folder and reused on the next start.

### Bukkit, Spigot and Paper (`plugin.yml`)

```java
public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        BukkitLibraryManager libraryManager = new BukkitLibraryManager(this);
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(Library.builder()
            .groupId("com{}google{}code{}gson")
            .artifactId("gson")
            .version("2.11.0")
            .relocate("com{}google{}gson", "com{}example{}libs{}gson")
            .build());
    }
}
```

Loading in `onLoad()` works too and makes the libraries available earlier.

### Paper plugins (`paper-plugin.yml`)

Paper plugins can't modify their class loader, but they can contribute jars through a
[`PluginLoader`](https://docs.papermc.io/paper/dev/getting-started/paper-plugins). Libby's
`PaperLibraryManager` hands every jar to Paper's `PluginClasspathBuilder`, without any
reflection:

```java
public final class MyPluginLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        PaperLibraryManager libraryManager = new PaperLibraryManager(classpathBuilder);
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(Library.builder()
            .groupId("com{}google{}code{}gson")
            .artifactId("gson")
            .version("2.11.0")
            .build());
    }
}
```

```yaml
# paper-plugin.yml
name: MyPlugin
version: 1.0.0
main: com.example.MyPlugin
loader: com.example.MyPluginLoader
api-version: '1.20'
```

Paper's plugin loader API is marked experimental and may change between Paper versions.

### Velocity

`VelocityLibraryManager` adds jars with Velocity's official
`PluginManager#addToClasspath`. Create it from your plugin's constructor:

```java
@Plugin(id = "myplugin", name = "MyPlugin", version = "1.0.0")
public final class MyPlugin {
    @Inject
    public MyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        VelocityLibraryManager<MyPlugin> libraryManager =
            new VelocityLibraryManager<>(this, logger, dataDirectory, server.getPluginManager());
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(Library.builder()
            .groupId("com{}google{}code{}gson")
            .artifactId("gson")
            .version("2.11.0")
            .build());
    }
}
```

Or let Guice inject it. The manager needs the plugin instance, so inject it into a field
(not a constructor parameter, which would be a circular dependency) and use it once the
plugin is constructed:

```java
@Plugin(id = "myplugin", name = "MyPlugin", version = "1.0.0")
public final class MyPlugin {
    @Inject
    private VelocityLibraryManager<MyPlugin> libraryManager;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(/* ... */);
    }
}
```

### BungeeCord

```java
public final class MyPlugin extends Plugin {
    @Override
    public void onEnable() {
        BungeeLibraryManager libraryManager = new BungeeLibraryManager(this);
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(Library.builder()
            .groupId("com{}google{}code{}gson")
            .artifactId("gson")
            .version("2.11.0")
            .build());
    }
}
```

Nukkit works the same way with `new NukkitLibraryManager(this)`.

## Describing a library

Libraries are immutable and built with `Library.builder()`:

| Builder method                                | Purpose                                                                      |
|-----------------------------------------------|------------------------------------------------------------------------------|
| `groupId(String)`                             | Maven group ID (required, `{}` accepted for `.`)                             |
| `artifactId(String)`                          | Maven artifact ID (required)                                                 |
| `version(String)`                             | Version (required)                                                           |
| `classifier(String)`                          | Classifier, e.g. `linux-x86_64`                                              |
| `checksum(String)` / `checksum(byte[])`       | Pinned SHA-256 of the jar, Base64-encoded or raw bytes                       |
| `relocate(String, String)` / `relocate(Relocation)` | Relocation rule applied to the jar before loading                     |
| `url(String)`                                 | Direct download URL, tried before the repositories                           |
| `resolveTransitiveDependencies(boolean)`      | Also load the library's dependencies, see below                              |
| `excludeTransitiveDependency(String, String)` | Never load a transitive dependency (`*` wildcards allowed)                   |

Coordinates may only contain letters, digits and `_ . + -`, so they can never point outside
the library directory. Invalid coordinates throw an `IllegalArgumentException` from
`build()`.

`Relocation.builder()` creates rules with `include(...)`/`exclude(...)` class patterns.
Relocated jars are saved next to the original as
`<artifact>-relocated-<hash>.jar`, where the hash covers the rules and the relocation
tooling, so changing either produces a new jar.

### Computing a checksum

Pinning the SHA-256 checksum is the strongest protection libby offers (see
[Security model](#security-model)). Compute it from a jar you trust:

```sh
openssl dgst -sha256 -binary gson-2.11.0.jar | base64
```

or, if the repository publishes a hexadecimal `.sha256` file next to the jar (Maven
Central does for most recent uploads), convert that:

```sh
curl -s https://repo.maven.apache.org/maven2/<group/path>/<artifact>/<version>/<artifact>-<version>.jar.sha256 | xxd -r -p | base64
```

## Transitive dependencies

By default only the library itself is loaded. With
`resolveTransitiveDependencies(true)`, libby reads the library's POM, follows its runtime
dependencies recursively and loads them before the library. This is what you need for
libraries like [JDA](https://github.com/discord-jda/JDA), which otherwise fail with
`ClassNotFoundException`s:

```java
libraryManager.addMavenCentral();
libraryManager.loadLibrary(Library.builder()
    .groupId("net{}dv8tion")
    .artifactId("JDA")
    .version("5.0.0")
    .resolveTransitiveDependencies(true)
    // JDA's audio natives aren't needed by bots that don't send audio
    .excludeTransitiveDependency("club{}minnced", "opus-java")
    .build());
```

- **Exclusions** remove a dependency together with everything only it depends on.
  `excludeTransitiveDependency("com{}example", "*")` excludes a whole group,
  `("*", "*")` every transitive dependency. An `ExcludedDependency` can be passed instead
  of two strings.
- **Relocations are inherited**: every transitive dependency gets the root library's
  relocation rules, so one rule set relocates the whole tree.
- **Checksums**: transitive dependencies have no pinned checksum and are verified against
  the repository's checksum files. To pin them, resolve the tree once and declare the
  libraries explicitly:

  ```java
  for (Library dependency : libraryManager.resolveTransitiveLibraries(jda)) {
      System.out.println(dependency); // groupId:artifactId:version[:classifier]
  }
  ```

  `resolveTransitiveLibraries(Library)` returns the resolved libraries in load order
  (dependencies first), without the root, whether or not the library enables resolution.
- **Explicit libraries win**: libby keeps the first version of each
  `groupId:artifactId[:classifier]` it loads. Loading a library explicitly before the one
  that depends on it pins its version; the transitive version is skipped with a warning.
- POMs, parent POMs and BOMs are downloaded from the manager's repositories (never from a
  library's direct URLs) and cached in the library directory.

The resolver is built into libby (no Maven Resolver download) and follows Maven's rules for
the POM features plugins commonly depend on:

| Supported                                                                                         |
|---------------------------------------------------------------------------------------------------|
| Parent POMs (`<relativePath>` is ignored) with inheritance of group ID, version, properties, dependency management and dependencies |
| `${project.groupId/artifactId/version}`, `${project.parent.*}`, the `pom.*` and `parent.*` aliases and `<properties>`, nested up to 16 levels |
| `<dependencyManagement>`, including BOMs imported with `<scope>import</scope>`; the root library's management also overrides transitive versions |
| `compile` and `runtime` scopes with Maven's transitive scope rules; `test`, `provided`, `system` and optional dependencies are skipped |
| Types `jar`, `bundle` and `pom` (POM-only aggregators such as `opus-java` contribute their dependencies); other types such as `test-jar` or `aar` are skipped |
| Classifiers, `<exclusions>` with `*` wildcards (inherited down the tree), nearest-wins version mediation, dependency cycles |
| One hop of `<distributionManagement><relocation>` (logged as a warning)                           |
| Profiles with `<activeByDefault>true</activeByDefault>`                                           |

| Not supported (throws `TransitiveDependencyException`)                                            |
|---------------------------------------------------------------------------------------------------|
| Version ranges such as `[1.0,2.0)`; an exact `[1.2.3]` is accepted                               |
| Dependencies without a version, or with a property that can't be resolved (`${env.*}` and system properties are never resolved) |
| `SNAPSHOT` versions of transitive dependencies                                                    |
| POMs that can't be downloaded or parsed; POMs larger than 1 MiB                                   |
| Trees deeper than 16 levels, more than 256 artifacts or parent/BOM chains longer than 64 POMs     |

Profiles activated by JDK, OS, property or file conditions are ignored. The exception message
names the dependency path from your library to the problem and the exact
`excludeTransitiveDependency(...)` call that skips it; exclude the dependency and, if you
need it, declare the version you want explicitly.

## Repositories

A new library manager has **no repositories**: add at least one.

| Method                     | Repository                                                                 |
|----------------------------|----------------------------------------------------------------------------|
| `addMavenCentral()`        | `https://repo.maven.apache.org/maven2/` (`LibraryManager.MAVEN_CENTRAL`)   |
| `addJitPack()`             | `https://jitpack.io/`                                                      |
| `addSonatypeSnapshots()`   | Sonatype Central Portal snapshots                                          |
| `addMavenLocal()`          | `~/.m2/repository`, useful during development                              |
| `addRepository(String)`    | Any `https:`, `http:` or `file:` repository URL                            |
| `addJCenter()` (deprecated)  | JCenter is shut down: logs a warning and adds Maven Central              |
| `addSonatype()` (deprecated) | OSSRH is shut down: logs a warning and adds the Central Portal snapshots |

Repositories are tried in the order they were added, after a library's direct URLs.
`getRepositories()` lists them and `resolveLibrary(Library)` shows every candidate URL for a
library.

## Security model

Libby downloads code that will run inside your server, so it is strict by default:

- **HTTPS only.** Downloads use `https:` or local `file:` URLs. Plain `http:` URLs are
  refused with a warning unless you call `setAllowInsecureHttp(true)`; only do that on
  trusted networks and pin checksums when you do.
- **Pinned checksums vs. repository checksums.** A library with
  `checksum(...)` is only accepted if its SHA-256 matches. Without one, libby verifies the
  download against the repository's `.sha256`, `.sha512` or `.sha1` file. Those checksum
  files come from the same repository as the jar, so they only protect against corruption
  and broken mirrors, **not against a malicious or compromised repository**. Pin checksums
  for anything that matters. A warning is logged when no checksum is available at all.
- **Verified cache.** Every downloaded file gets its SHA-256 recorded in a `.sha256` sidecar.
  Cached jars, relocated jars and POMs are re-verified on every start and downloaded again
  if they don't match.
- **Safe downloads.** Only HTTP 200 responses are accepted (error pages are never cached),
  downloads are streamed to a temporary file with a size limit and moved into place
  atomically, and coordinates are validated so paths can't escape the library directory.
- **Hardened POM parsing.** The transitive resolver rejects POMs with a `DOCTYPE` (so no
  external or internal entities are ever processed), never loads external DTDs, schemas
  or XIncludes, uses the JDK's built-in parser, limits POMs to 1 MiB and bounds the depth
  and size of the dependency tree.
- **No other code is executed.** The only third-party code libby downloads and runs itself
  is [jar-relocator](https://github.com/lucko/jar-relocator) and ASM, and only when a
  library has relocations. They are downloaded with pinned SHA-256 checksums and loaded
  into an isolated class loader.

## Classpath injection on Java 16+

`BukkitLibraryManager`, `BungeeLibraryManager` and `NukkitLibraryManager` add jars to the
plugin's own `URLClassLoader`, whose `addURL` method is protected. Libby tries these
strategies in order and uses the first that works:

1. a public `addURL(URL)` method on the plugin's class loader, if the platform provides one;
2. deep reflection on `URLClassLoader#addURL`, which works on Java 8 to 15, or on newer
   Java when the server is started with `--add-opens java.base/java.net=ALL-UNNAMED`;
3. a trusted method handle lookup obtained through `sun.misc.Unsafe`. On **JDK 24 and
   newer** this makes the JVM print a one-time warning about terminally deprecated
   `sun.misc.Unsafe` methods, and it may stop working in a future JDK. Disable it with
   `-Dlibby.classloaders.unsafeDisabled=true` or the environment variable
   `LIBBY_CLASSLOADERS_UNSAFE_DISABLED=true`.

If none works, loading fails with an `IllegalStateException` that explains how to fix it.
To avoid these workarounds entirely:

- on Paper, prefer a Paper plugin with `PaperLibraryManager`;
- on Velocity, `VelocityLibraryManager` already uses the official API;
- anywhere, `loadLibrary(Library, IsolatedClassLoader)` loads libraries into an
  `IsolatedClassLoader` you control, at the cost of only reaching them through that class
  loader.

## Configuration

| Setting                                   | Default        | Notes                                                        |
|-------------------------------------------|----------------|--------------------------------------------------------------|
| `setLogLevel(LogLevel)`                   | `INFO`         | `WARN` hides download messages but keeps checksum warnings; `DEBUG` shows the dependency tree |
| `setConnectTimeout(Duration)`             | 10 seconds     | Time to establish a connection                               |
| `setRequestTimeout(Duration)`             | 30 seconds     | Time to receive the response headers (body streaming isn't limited) |
| `setMaxDownloadSize(long)`                | 256 MiB        | Larger downloads are aborted and the next URL is tried       |
| `setAllowInsecureHttp(boolean)`           | `false`        | See [Security model](#security-model)                        |
| Library directory name                    | `lib`          | Pass it to the constructor, e.g. `new BukkitLibraryManager(this, "libraries")` or `new PaperLibraryManager(classpathBuilder, "libraries")` |

Library managers are thread-safe; concurrent requests for the same file download it once.
Failed downloads throw a `LibraryDownloadException` whose `getAttempts()` lists every URL
that was tried and why it failed.

## Fabric and other platforms

There is no Fabric module (upstream issue #3): Fabric's Knot class loader isn't a
`URLClassLoader` and can't be extended this way. Use Fabric's own jar-in-jar mechanism for
mods, or `libby-core` with `loadLibrary(Library, IsolatedClassLoader)` for code you load
reflectively.

For any other platform, either use an `IsolatedClassLoader`, or subclass `LibraryManager`
and implement `addToClasspath(Path)`:

```java
public final class MyLibraryManager extends LibraryManager {
    private final URLClassLoaderHelper classLoader;

    public MyLibraryManager(LogAdapter logAdapter, Path dataDirectory, URLClassLoader classLoader) {
        super(logAdapter, dataDirectory);
        this.classLoader = new URLClassLoaderHelper(classLoader);
    }

    @Override
    protected void addToClasspath(Path file) {
        classLoader.addToClasspath(file);
    }
}
```

`JDKLogAdapter` (for `java.util.logging`) and `SLF4JLogAdapter` are available as log
adapters.

## Migrating from 0.0.x / 1.x

- **Java 17 baseline.** Libby 2 requires Java 17 or newer.
- **Sponge module removed.** Use `libby-core` with an `IsolatedClassLoader` or a custom
  `LibraryManager` subclass.
- **New relocation tooling.** jar-relocator 1.7 and ASM 9.11.1 replace the old versions, so
  relocated jars are regenerated once under new `-relocated-<hash>.jar` names. Old
  relocated jars in the library directory can be deleted.
- **Repositories.** Maven Central now points at `repo.maven.apache.org`. `addJCenter()` and
  `addSonatype()` are deprecated because those services are shut down; use
  `addMavenCentral()` and `addSonatypeSnapshots()`.
- **Stricter downloads.** `http:` URLs are refused by default, downloads are verified
  against repository checksum files, and failures throw a `LibraryDownloadException`
  (still a `RuntimeException`) that lists every attempted URL and why it failed, instead
  of a plain `RuntimeException`.
- **Validated coordinates.** Invalid group IDs, artifact IDs, versions, classifiers or
  checksums now throw an `IllegalArgumentException` when the library is built.
- **One version per library.** Loading a different version of an already loaded
  `groupId:artifactId[:classifier]` is skipped with a warning.

## Building from source

```sh
./gradlew build                # compile, test and build every module (Java 21 toolchain)
./gradlew publishToMavenLocal  # install 2.0.0-SNAPSHOT into ~/.m2/repository
```

Libby is released under the [MIT License](LICENSE).

## Credits

Special thanks to:

* [Luck](https://github.com/lucko) for [LuckPerms](https://github.com/lucko/LuckPerms)
  and its dependency management system which was the original inspiration for this project
  and another thanks for [jar-relocator](https://github.com/lucko/jar-relocator) which is
  used by Libby to perform jar relocations.
* [Glare](https://github.com/darbyjack) for convincing me that I should publish this
  library instead of letting it sit around collecting dust :)
* [AlessioDP](https://github.com/AlessioDP) for [his libby fork](https://github.com/AlessioDP/libby),
  prior art in keeping libby maintained.
