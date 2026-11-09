# BeSided

Gradle plugin that builds separate client and server jars from a single codebase by stripping opposite-side bytecode.

Group `me.methodial`, plugin id `me.methodial.besided`. Sources live at [team-froglight/besided](https://github.com/team-froglight/besided).

## Quick start

```gradle
plugins {
    id 'java'
    id 'me.methodial.besided' version '0.1.0'
}
```

```java
public class MyBlock {
    @ClientSide public String texture;     // gone from the server jar
    @ServerSide public void saveToDb() { } // gone from the client jar
}
```

```
./gradlew buildClient    # build/libs/<name>-client.jar
./gradlew buildServer    # build/libs/<name>-server.jar
```

Intermediate stripped classes are written under `build/besided/client/` and `build/besided/server/`; the `buildClient`/`buildServer` jars are the
supported outputs. Strip-only tasks `stripClient`/`stripServer` populate those directories
without packaging.

### The annotations and `Sided`

`@ClientSide`, `@ServerSide` and the rest, and the `Sided` helper, are in a small jar of their
own, `me.methodial:besided-api`. The plugin carries a copy and adds it to every project that
applies it (with the `java` plugin) as an `implementation` file dependency, so there is nothing
to declare and no repository to add. A file dependency is never published, so your POM doesn't
gain it.

The side jars don't need it at runtime: every `Sided` call is rewritten while stripping, and the
annotations have `CLASS` retention. Untransformed classes do call `Sided` (dev runs, tests, the
plain `jar`): it is on the runtime classpath there, but if you ship the plain jar, ship
`besided-api` with it. A Minecraft loader's dev run may want it on its own classpath (for
example ModDevGradle's `additionalRuntimeClasspath`).

## DSL

```gradle
besided {
    clientPackages = ['com.example.mod.render']
    serverPackages = ['com.example.mod.db']

    clientResources = ['assets/**', 'models/**/*.json']
    serverResources = ['config/server-only.json']

    clientAnnotations = ['net.minecraftforge.fml.relauncher.SideOnly:value=CLIENT']
    serverAnnotations = ['net.minecraftforge.fml.relauncher.SideOnly:value=SERVER']
}
```

| Property | Type | Description |
|---|---|---|
| `stripFrom` | `StripSource` | Input source for stripping (auto-detected by default) |
| `clientPackages` | `Set<String>` | Packages holding client-only code, removed from the **server** jar |
| `serverPackages` | `Set<String>` | Packages holding server-only code, removed from the **client** jar |
| `clientResources` | `List<String>` | Glob patterns for client-only resources, removed from the **server** jar |
| `serverResources` | `List<String>` | Glob patterns for server-only resources, removed from the **client** jar |
| `clientAnnotations` | `List<String>` | Extra annotations marking client-only code (see below) |
| `serverAnnotations` | `List<String>` | Extra annotations marking server-only code (see below) |

Package names are prefixes: `com.example.mod.render` covers that package and all nested packages.
Resource patterns are `PathMatcher` globs matched against the output-relative path with `/` separators.

## Annotations

All annotations use `RetentionPolicy.CLASS`: they are gone from the built jars; the plugin removes
them itself during stripping.

### `@ClientSide` / `@ServerSide`

Place on a class, method, field, or enum constant.

```java
@ClientSide
public class RenderSystem { } // whole class missing from the server jar

public class Machine {
    @ClientSide private Renderer renderer;
    @ServerSide public void tickServer() { }
}
```

**`keep` (methods only).** When a method cannot be removed physically — it overrides a parent
method, implements an interface, or is called by surviving code — deleting it would throw
`AbstractMethodError` / `NoSuchMethodError`. `keep = true` keeps the signature but hollows the
body to `return` with a default value (`0`, `false`, `null`, or bare `return`).

```java
@Override
@ClientSide(keep = true)
public int getRenderColor() { return computeFromTexture(); }
// server jar: public int getRenderColor() { return 0; }
```

`keep = true` on a **field** is a build error. On a **class** it is currently ignored.

### `@NotSided`

Cancels inherited sidedness for a specific member. Needed when a base method is sided but a
particular override must survive.

```java
public class Base {
    @ClientSide public void render() { }
}
public class Child extends Base {
    @NotSided @Override public void render() { } // kept in Child
}
```

### Interfaces: `@ClientInterface` / `@ServerInterface`

Placed on a class; drop the listed interface from `implements` together with every method that
interface declares. Needed when the interface itself is external (for example from Minecraft)
and cannot be annotated.

```java
@ClientInterface(IItemRenderer.class)
public class MyItem extends Item implements IItemRenderer {
    @Override public void renderItem(...) { } // removed together with the interface
}
```

Repeatable: several such annotations may sit on one class.

### External annotations

The plugin can react to foreign annotations, most notably Forge `@SideOnly`. Format:

```
"pkg.AnnotationClass"                — marker annotation
"pkg.AnnotationClass:param=VALUE"    — annotation with a parameter
```

```gradle
besided {
    clientAnnotations = ['net.minecraftforge.fml.relauncher.SideOnly:value=CLIENT']
    serverAnnotations = ['net.minecraftforge.fml.relauncher.SideOnly:value=SERVER']
}
```

After that `@SideOnly(Side.CLIENT)` behaves exactly like `@ClientSide`, including enums.

## `Sided` helper

Write side-dependent code inside an ordinary method. Calls are inlined into bytecode, so after
stripping neither the check nor the call remains.

```java
public void onUpdate() {
    Sided.runClient(() -> spawnParticles()); // lambda and body gone from the server jar
    Sided.runServer(() -> saveToDisk());

    if (Sided.isClient()) {
        doRenderStuff(); // dead branch folded away on the server
    }

    int fps = Sided.getClient(() -> Minecraft.getMinecraft().fps, 0);
}
```

| Method | On its own side | On the opposite side |
|---|---|---|
| `isClient()` / `isServer()` | `true` | `false` (branch folded as dead) |
| `runClient(r)` / `runServer(r)` | lambda body runs inline | lambda and its synthetic method removed |
| `getClient(s)` / `getServer(s)` | supplier value | `null` |
| `getClient(s, def)` | supplier value | `def` |
| `getClient(s, defSupplier)` | supplier value | value from `defSupplier` |

Suppliers should be **inline lambdas at the call site**: only those are inlined, and only their
bodies are removed from the other jar. Anything else (a variable, a method or constructor
reference) falls back to a safe interface call, `Runnable.run()` / `Supplier.get()`, on its own
side, and is computed but not run on the other; what it points at stays in both jars. Either way
no call to `Sided` itself is left in the side jars.

## How it works

1. **Scan.** All classes are read with `SKIP_CODE`; the plugin collects the inheritance
   hierarchy, annotations, and declared members (plus `besided.json` metadata from dependencies).
2. **Resolve.** Sidedness propagates along the hierarchy to a fixpoint (sided parent methods make
   overrides sided, sided classes make subclasses sided, `keep = true` is inherited too).
3. **Enum scan.** Only for enums that lost a constant, `<clinit>` is re-read to learn constant
   order and which synthetic subclasses hold constant bodies; those subclasses are marked sided.
4. **Transform.** Per class: drop sided interfaces, fields, methods; inline `Sided.*` calls; fold
   dead branches; hollow `keep` methods; rebuild enums; delete the annotations themselves.
5. **Write.** Classes whose branches were folded or whose enums were rebuilt are written with
   `COMPUTE_FRAMES` (edits shift offsets that existing stack frames were computed against); the
   rest use `COMPUTE_MAXS`. Resources are filtered, then the `META-INF/besided-side` marker
   (`client` / `server`) is written last so no glob can delete it.

## Limitations

- **Enum ordinals differ per side.** After renumbering, a shared enum such as
  `A, @ClientSide B, C` has `C == 2` on the client but `C == 1` on the server. Never serialize
  sided enums by `ordinal()` (packets, NBT, configs, block metadata); use `name()`. Plain Java
  enum serialization is safe because it uses names.
- **Dangling references are not diagnosed.** The plugin removes what is marked but does not check
  whether surviving code still refers to it. A common class calling a removed member fails at
  runtime with `NoSuchFieldError` / `NoSuchMethodError`. Mark callers sided too.
- **Inline lambdas only.** `Sided.*` suppliers written as stored variables or method references are
  not inlined; the code they point at survives in the opposite jar (a method reference's target is
  an ordinary method, which other code may call). Pass the lambda directly at the call site, or
  mark the target method sided.

## Migration from sidedjar

- Gradle plugin id: `sidedjar` → `me.methodial.besided`.
- Extension block: `sidedJar { ... }` → `besided { ... }`.
- Java/Kotlin imports: `dev.feldsher.sidedjar.*` → `me.methodial.besided.*`
  (annotations, `Sided`, `StripSource`, tasks).
- Group: `dev.feldsher` → `me.methodial`.
- Metadata file in jars: `META-INF/sidedjar.json` → `META-INF/besided.json`.
- Side marker in jars: `META-INF/sidedjar-side` → `META-INF/besided-side`
  (contents unchanged: `client` / `server`).
- Task names: `stripClient`, `stripServer`, `buildClient` and `buildServer` are unchanged;
  `generateSidedMeta` → `generateBesidedMeta`.
