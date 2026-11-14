using System.IO;
using System.IO.Compression;
using System.Text.Json;

namespace McServerPackBuilder.Desktop;

internal sealed record InputInspection(bool CanScan, bool CanBuild, string Description,
    string? PackType = null, string? Loader = null, string? Version = null);

internal static class PackInputInspector
{
    public static InputInspection Inspect(string path)
    {
        if (Directory.Exists(path)) return new(true, false, "已识别为文件夹，可扫描其中的 JAR。文件夹不能直接制作服务端 ZIP。", "MODS 文件夹");
        if (!File.Exists(path)) return new(false, false, "路径不存在，请选择本地文件或文件夹。");
        string ext = Path.GetExtension(path);
        if (ext.Equals(".jar", StringComparison.OrdinalIgnoreCase))
            return new(true, false, "已识别为单个模组 JAR，可扫描；这不是整合包。", "模组 JAR");
        if (!ext.Equals(".zip", StringComparison.OrdinalIgnoreCase) && !ext.Equals(".mrpack", StringComparison.OrdinalIgnoreCase))
            return new(false, false, "不支持此文件。请选择 .mrpack、.zip、.jar 或文件夹。");

        try
        {
            using var archive = ZipFile.OpenRead(path);
            if (archive.Entries.Count > 50_000) return new(false, false, "压缩包条目过多，无法识别。");
            var index = archive.GetEntry("modrinth.index.json");
            if (index is not null && TryReadJson(index, out var modrinth))
            {
                using (modrinth)
                {
                    var root = modrinth.RootElement;
                    if (root.TryGetProperty("files", out var files) && files.ValueKind == JsonValueKind.Array)
                    {
                        bool canBuild = root.TryGetProperty("dependencies", out var deps)
                            && HasString(deps, "minecraft") && HasString(deps, "neoforge");
                        string? version = root.TryGetProperty("dependencies", out deps) && HasString(deps, "minecraft")
                            ? "MC " + deps.GetProperty("minecraft").GetString() : null;
                        string? loader = root.TryGetProperty("dependencies", out deps) && HasString(deps, "neoforge")
                            ? "NeoForge " + deps.GetProperty("neoforge").GetString() : null;
                        return new(true, canBuild, canBuild
                            ? "已识别为 Modrinth 整合包（NeoForge），可扫描并制作服务端 ZIP。"
                            : "已识别为 Modrinth 整合包，可扫描；制作服务端 ZIP 需要 Minecraft 和 NeoForge 版本。",
                            "Modrinth 整合包", loader, version);
                    }
                }
            }
            if (ext.Equals(".mrpack", StringComparison.OrdinalIgnoreCase))
                return new(false, false, "MRPACK 缺少有效的 modrinth.index.json，无法识别为 Modrinth 整合包。");
            var manifest = archive.GetEntry("manifest.json");
            if (manifest is not null && TryReadJson(manifest, out var curseforge))
            {
                using (curseforge)
                {
                    if (curseforge.RootElement.TryGetProperty("files", out var files) && files.ValueKind == JsonValueKind.Array)
                        return new(true, false, "已识别为 CurseForge 整合包，可扫描；当前不能直接制作服务端 ZIP。", "CurseForge 整合包");
                }
            }
            if (archive.Entries.Any(e => e.FullName.Replace('\\', '/').Contains("mods/", StringComparison.OrdinalIgnoreCase)
                && e.FullName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)))
                return new(true, false, "已识别为包含 mods 的 ZIP，可扫描；制作服务端 ZIP 需要 Modrinth 索引。", "MODS ZIP");
            return new(false, false, "压缩包中未找到有效整合包索引或 mods 文件夹，无法识别为 MC 整合包。");
        }
        catch (InvalidDataException) { return new(false, false, "文件不是有效的 ZIP 压缩包。"); }
        catch (IOException ex) { return new(false, false, "读取文件失败：" + ex.Message); }
        catch (UnauthorizedAccessException) { return new(false, false, "没有权限读取此文件。"); }
    }

    private static bool HasString(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(value.GetString());

    private static bool TryReadJson(ZipArchiveEntry entry, out JsonDocument document)
    {
        document = null!;
        if (entry.Length > 2_000_000) return false;
        try
        {
            using var stream = entry.Open();
            document = JsonDocument.Parse(stream);
            if (document.RootElement.ValueKind == JsonValueKind.Object) return true;
            document.Dispose();
            return false;
        }
        catch (JsonException) { document?.Dispose(); return false; }
        catch (InvalidDataException) { document?.Dispose(); return false; }
    }
}
