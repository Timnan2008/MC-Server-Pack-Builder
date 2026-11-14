using System.Text.Json.Serialization;

namespace McServerPackBuilder.Desktop;

public sealed record ScanResult(string Source, string Kind, List<ScanEntry> Entries, List<ScanDiagnostic> Diagnostics);
public sealed record ScanEntry(string Path, string Side, string Reason, List<ScanDiagnostic> Diagnostics)
{
    [JsonIgnore] public string SideLabel => Side switch
    {
        "CLIENT_ONLY" => "仅客户端",
        "SERVER_COMPATIBLE" => "兼容服务端",
        "KEEP_ON_SERVER" => "建议保留",
        _ => "待确认"
    };
    [JsonIgnore] public int IssueCount => Diagnostics.Count;
}
public sealed record ScanDiagnostic(string Severity, string Code, string Message);
public sealed record BuildResult(string Output, string MinecraftVersion, string NeoforgeVersion,
    int DownloadedMods, int KeptMods, int ExcludedMods, List<string> Warnings);
public sealed record BuildJob(string Id, string State, string Stage, long DownloadedBytes, long TotalBytes,
    string CurrentFile, BuildResult? Result, string? Error);
