# MC Server Pack Builder

Java 21 scanner for Forge, NeoForge, and Fabric mod metadata, with a Windows WPF desktop interface and a local Java API.

## Structure

- `core.model`: physical JAR results, logical mod declarations, dependencies, diagnostics
- `core.metadata`: loader-specific TOML and JSON adapters
- `core.scan`: bounded, read-only JAR scanning, NeoForge entry-point inspection, and Modrinth project-side lookup
- `cli`: conservative boolean server retention results; optional detailed JSON report
- `api`: loopback-only scan API shared by the desktop interface
- `desktop`: .NET 8 WPF Windows interface, with separate window, input-inspection, API-client, and result-model layers

## Windows desktop app

Install Java 21 and the .NET 8 Desktop Runtime. From the project root, build the Java distribution and start the desktop app:

```powershell
.\gradlew.bat installDist
dotnet run --project desktop/McServerPackBuilder.Desktop.csproj
```

The desktop app starts its own Java API on an available loopback port and closes that backend when the window closes. This prevents a running desktop instance from silently reusing an outdated API process. It finds the backend under `build/install/mc-server-pack-builder/lib` in a development checkout, or under `backend/lib` beside a packaged desktop app. To run the API separately, use `.\gradlew.bat runServer` (default port `38241`).

Choose or drag one JAR, a mods folder, `.mrpack`, or `.zip` into the desktop window. The import area identifies Modrinth packs, CurseForge exports, ZIP files containing `mods/*.jar`, single JARs, and folders. Only a Modrinth pack with Minecraft and NeoForge versions enables **制作服务端 ZIP**. The task bar shows the current operation and uses an indeterminate progress indicator while the synchronous Java API is working; it does not claim a percentage that the backend cannot report. The table shows `CLIENT_ONLY`, `SERVER_COMPATIBLE`, `KEEP_ON_SERVER`, or `UNKNOWN` and any scan diagnostics; **export JSON** is optional. `KEEP_ON_SERVER` means retain by default; it does not prove that a dedicated server can start with the mod. **制作服务端 ZIP** downloads and verifies Modrinth-pack JARs, filters confirmed client-only mods, installs the matching NeoForge server core in a staging directory, and writes a ZIP. The original pack is unchanged.

The WPF desktop theme lives in `desktop/Theme.xaml`. It provides the light blue palette, buttons, badges, cards, and table styles used by `MainWindow.xaml`. A compact top bar combines navigation and window controls. The workbench handles import and building; the scan results page shows counts, search, and diagnostics. The initial window is limited to the usable screen area, and page changes use a short transition. Future features are labeled as coming soon.

The scan results page keeps the overview visible, while task status remains visible on both pages. Its search field filters paths, reasons, and side labels as you type; **清除筛选** restores all rows. Use `Ctrl+O` to choose a file, `Ctrl+F` to open and focus result search, and `Esc` to clear a focused search. The desktop entry point is `desktop/McServerPackBuilder.Desktop.csproj`; run the two PowerShell commands above from the repository root.

For Modrinth packs whose JARs are only listed as downloads, the scanner sends the public Modrinth **project IDs** from the pack's CDN URLs to the Modrinth `/v2/projects` API in batches. It does not upload the pack, JAR bytes, file hashes, paths, or file names. The project-side declaration can distinguish, for example, Sodium (`CLIENT_ONLY`) from Applied Energistics 2 (`SERVER_COMPATIBLE`) even though neither JAR is embedded in the `.mrpack`. Project declarations summarize versions; mixed or missing declarations stay in `KEEP_ON_SERVER`, and network failures keep the indexed mods with a warning. The Java backend uses the Windows system proxy when one is configured.

For physical NeoForge JARs, ASM inspects `@Mod` entry points without executing classes. A JAR is `CLIENT_ONLY` by this evidence only when every declared mod ID has entry points and **all** are explicitly restricted to `Dist.CLIENT`. A common or dedicated-server entry point, an unmatched mod ID, or incomplete analysis prevents that result. This entry-point check cannot prove successful dedicated-server startup.

API: `GET /api/health`, `POST /api/scan` with JSON `{ "path": "C:\\path\\to\\mods" }`, and `POST /api/build` with `{ "path": "C:\\path\\to\\pack.mrpack", "output": "C:\\path\\to\\server.zip" }`. The API accepts local paths and listens only on loopback, so do not expose it through a proxy.

The server ZIP includes retained `mods/`, server-side configuration and data files such as `kubejs/` and `defaultconfigs/`, server-side overrides, the official NeoForge installer, installed `libraries/`, `run.bat`/`run.sh`, and `start-server.bat`/`start-server.sh`. Files listed in the Modrinth index are downloaded or read from the pack and verified with their SHA-512 hash before copying. Embedded pack files and overrides are also retained unless they are known client-only content such as resource packs, shader packs, screenshots, saves, or client options; index entries with `env.server=unsupported` are excluded. The installer is verified with its Maven SHA-512. The installer may retry once if a dependency download fails its own checksum check. Extract the ZIP and start the server with the Java version required by that Minecraft release. The server will create `eula.txt` on first launch; review and accept it yourself. A successful build does not guarantee every retained mod will start on a dedicated server.

## Run

Use Java 21. On Windows, run the included Gradle Wrapper (its first run downloads Gradle 8.14.4):

```powershell
.\gradlew.bat test
.\gradlew.bat run --args='"C:\path\to\minecraft\mods"'
.\gradlew.bat run --args='"C:\path\to\minecraft\mods\example.jar"'
.\gradlew.bat run --args='"C:\path\to\minecraft\mods\example.jar" --crash-report "C:\path\to\crash-...-fml.txt"'
.\gradlew.bat run --args='"C:\path\to\client.mrpack"'
.\gradlew.bat run --args='"C:\path\to\client.zip" --output "C:\path\to\pack-report.json"'
.\gradlew.bat run --args='"C:\path\to\client.mrpack" --server-zip "C:\path\to\server.zip"'
```

When running `dev.mcspb.cli.Main` directly from IntelliJ IDEA, put a JAR path or mods folder in **Program arguments**. A single JAR prints only `true` or `false`. A folder prints `<filename><tab><boolean>` per JAR. `true` means **keep on the server**. Add `--crash-report <Forge crash report>` to use a saved startup failure report. No JSON is written by default. Add `--output <report.json>` after a folder path to request a detailed scan report.

Only `.jar` files directly in the specified folder are scanned. Source JARs are read only. Without a crash report, the result defaults to `true`: metadata alone does not prove a JAR will crash a dedicated server. With a Forge crash report, `false` requires an explicit mod section naming the exact JAR filename and an `invalid dist DEDICATED_SERVER` failure. A report from a different JAR version must not be applied to another version. A successful server startup can contain `ERROR` lines, so those lines alone never trigger removal. Fabric `environment: "client"` is skipped on dedicated servers; Forge `displayTest` is only a network compatibility declaration.

For `.mrpack` and `.zip` input, only `CLIENT_ONLY` is a candidate to omit from a server pack. `KEEP_ON_SERVER` is the conservative default when side evidence is missing; it does not claim the downloaded JAR was inspected during a quick scan. The build step downloads and inspects every indexed mod JAR before packaging. It retains conflicting or unknown results and any client-only JAR required by a retained mod's declared dependencies. `SERVER_COMPATIBLE` reflects a loader or Modrinth declaration, not a successful server startup. Explicit Modrinth `env.server=unsupported` is `CLIENT_ONLY`. Modrinth `client-overrides` and `server-overrides` are classified by their installation side. The build step currently requires a Modrinth index with Minecraft and NeoForge versions; CurseForge ZIP exports with only project/file IDs cannot be converted without a download source.

## Limits

- This build path supports NeoForge packs with `modrinth.index.json`. It cannot automatically build a Forge or Fabric core, or resolve CurseForge manifest IDs without direct download URLs.
- Download URLs are restricted to Modrinth and Forge CDN hosts. Other hosts need explicit support.
- The dependency safeguard reads required dependencies from JAR metadata. It does not resolve all runtime-only relationships or prove the server will boot.
- Loader metadata may contain unresolved `${...}` placeholders. They are reported as literal values; version-specific interpolation is deferred.
- Forge and NeoForge dependency fields differ across versions. The parser reads `mandatory` and `type = "required"` where present and does not infer absent fields as required.
- Fabric nested JARs, mixins, entry points, and `breaks`/`conflicts` are not analyzed.
- Archive limits: 1 GB per JAR, 20,000 entries, 2 GB declared uncompressed size, and 2 MB per metadata file. Scanning never executes mod JAR code; building runs the checksum-verified official NeoForge installer in a temporary directory. ZIP names with traversal components are reported.
- A report describes metadata only. It makes no claim that any pack will start on a server.
