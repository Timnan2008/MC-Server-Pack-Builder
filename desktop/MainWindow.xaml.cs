using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows;
using System.Windows.Controls;

namespace McServerPackBuilder.Desktop;

public partial class MainWindow : Window
{
    private readonly HttpClient _client;
    private readonly int _backendPort;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private ScanResult? _result;
    private Process? _backend;

    public MainWindow()
    {
        using (var listener = new TcpListener(IPAddress.Loopback, 0))
        {
            listener.Start();
            _backendPort = ((IPEndPoint)listener.LocalEndpoint).Port;
        }
        _client = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{_backendPort}/"), Timeout = TimeSpan.FromMinutes(60) };
        InitializeComponent();
        if (Environment.GetCommandLineArgs() is { Length: > 1 } args) PathBox.Text = args[1];
        Closed += (_, _) => { if (_backend is { HasExited: false }) _backend.Kill(); _backend?.Dispose(); _client.Dispose(); };
    }

    private void ChooseFile_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "支持的文件 (*.jar;*.mrpack;*.zip)|*.jar;*.mrpack;*.zip|所有文件 (*.*)|*.*" };
        if (dialog.ShowDialog(this) == true) PathBox.Text = dialog.FileName;
    }

    private void ChooseFolder_Click(object sender, RoutedEventArgs e)
    {
        using var dialog = new System.Windows.Forms.FolderBrowserDialog { Description = "选择 mods 文件夹" };
        if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK) PathBox.Text = dialog.SelectedPath;
    }

    private async void Scan_Click(object sender, RoutedEventArgs e)
    {
        string path = PathBox.Text.Trim();
        if (!File.Exists(path) && !Directory.Exists(path))
        {
            StatusText.Text = "路径不存在，请选择文件或文件夹。";
            return;
        }
        ScanButton.IsEnabled = false;
        ExportButton.IsEnabled = false;
        StatusText.Text = "正在扫描…";
        DetailText.Text = "";
        try
        {
            await EnsureBackendAsync();
            using var response = await _client.PostAsJsonAsync("api/scan", new { path });
            if (!response.IsSuccessStatusCode)
            {
                using var error = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
                throw new IOException(error.RootElement.GetProperty("error").GetString());
            }
            _result = await response.Content.ReadFromJsonAsync<ScanResult>(JsonOptions)
                ?? throw new IOException("后端返回了空报告");
            ResultsGrid.ItemsSource = _result.Entries;
            StatusText.Text = $"扫描完成：{_result.Entries.Count} 项；仅客户端 {_result.Entries.Count(x => x.Side == "CLIENT_ONLY")}，兼容服务端 {_result.Entries.Count(x => x.Side == "SERVER_COMPATIBLE")}，建议保留 {_result.Entries.Count(x => x.Side == "KEEP_ON_SERVER")}，待确认 {_result.Entries.Count(x => x.Side == "UNKNOWN")}";
            ExportButton.IsEnabled = true;
            DetailText.Text = FormatDiagnostics(_result.Diagnostics);
        }
        catch (Exception ex)
        {
            StatusText.Text = "扫描失败";
            DetailText.Text = ex.Message;
        }
        finally { ScanButton.IsEnabled = true; }
    }

    private void ResultsGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResultsGrid.SelectedItem is ScanEntry entry)
            DetailText.Text = $"{entry.Path}\n{entry.Reason}\n\n" + FormatDiagnostics(entry.Diagnostics);
    }

    private async void Build_Click(object sender, RoutedEventArgs e)
    {
        string path = PathBox.Text.Trim();
        if (!File.Exists(path) || !(path.EndsWith(".mrpack", StringComparison.OrdinalIgnoreCase) ||
                                    path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)))
        {
            StatusText.Text = "请选择 .mrpack 或 .zip 整合包。";
            return;
        }
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "ZIP 压缩包 (*.zip)|*.zip",
            FileName = Path.GetFileNameWithoutExtension(path) + "-server.zip" };
        if (dialog.ShowDialog(this) != true) return;
        ScanButton.IsEnabled = false;
        BuildButton.IsEnabled = false;
        bool exportEnabled = ExportButton.IsEnabled;
        ExportButton.IsEnabled = false;
        StatusText.Text = "正在下载并校验模组、制作服务端 ZIP…";
        DetailText.Text = "这一步可能需要几分钟，取决于整合包大小和网络速度。";
        try
        {
            await EnsureBackendAsync();
            using var response = await _client.PostAsJsonAsync("api/build", new { path, output = dialog.FileName });
            if (!response.IsSuccessStatusCode)
            {
                using var error = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
                throw new IOException(error.RootElement.GetProperty("error").GetString());
            }
            var built = await response.Content.ReadFromJsonAsync<BuildResult>(JsonOptions)
                ?? throw new IOException("后端返回了空结果");
            StatusText.Text = $"已制作：保留 {built.KeptMods} 个模组，排除 {built.ExcludedMods} 个仅客户端模组";
            DetailText.Text = $"输出：{built.Output}\nMinecraft {built.MinecraftVersion} / NeoForge {built.NeoforgeVersion}\n" +
                "服务端核心和依赖已预装。解压后运行 start-server.bat。\n" +
                string.Join("\n", built.Warnings);
        }
        catch (Exception ex)
        {
            StatusText.Text = "制作失败";
            DetailText.Text = ex.Message;
        }
        finally
        {
            ScanButton.IsEnabled = true;
            BuildButton.IsEnabled = true;
            ExportButton.IsEnabled = exportEnabled;
        }
    }

    private void Export_Click(object sender, RoutedEventArgs e)
    {
        if (_result is null) return;
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "JSON 报告 (*.json)|*.json", FileName = "scan-report.json" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            File.WriteAllText(dialog.FileName, JsonSerializer.Serialize(_result, JsonOptions));
            StatusText.Text = $"报告已保存：{dialog.FileName}";
        }
        catch (Exception ex) { DetailText.Text = "导出失败：" + ex.Message; }
    }

    private static string FormatDiagnostics(IReadOnlyList<ScanDiagnostic> diagnostics) =>
        diagnostics.Count == 0 ? "没有诊断。UNKNOWN 需要人工确认；分类不代表服务端启动验证。" :
        string.Join(Environment.NewLine, diagnostics.Select(d => $"[{d.Severity}] {d.Code}: {d.Message}"));

    private async Task EnsureBackendAsync()
    {
        if (_backend is { HasExited: false }) return;
        string? lib = FindBackendLib();
        if (lib is null) throw new IOException("找不到 Java 后端。先运行 .\\gradlew.bat installDist，并安装 Java 21。详情见 README。");
        _backend = Process.Start(new ProcessStartInfo("java")
        {
            ArgumentList = { "-Djava.net.useSystemProxies=true", "-cp", Path.Combine(lib, "*"), "dev.mcspb.api.ApiServerMain", _backendPort.ToString() },
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true
        }) ?? throw new IOException("无法启动 Java 后端");
        for (int attempt = 0; attempt < 75; attempt++)
        {
            await Task.Delay(200);
            if (_backend.HasExited) throw new IOException("Java 后端启动失败，请检查 Java 21 和后端日志。");
            try { using var ready = await _client.GetAsync("api/health"); if (ready.IsSuccessStatusCode) return; }
            catch (HttpRequestException) { }
        }
        throw new IOException("Java 后端启动超时");
    }

    private static string? FindBackendLib()
    {
        DirectoryInfo? dir = new(AppContext.BaseDirectory);
        for (int i = 0; i < 6 && dir is not null; i++, dir = dir.Parent)
        {
            string[] candidates = [Path.Combine(dir.FullName, "backend", "lib"),
                Path.Combine(dir.FullName, "build", "install", "mc-server-pack-builder", "lib")];
            foreach (string candidate in candidates)
                if (Directory.Exists(candidate) && Directory.EnumerateFiles(candidate, "mc-server-pack-builder-*.jar").Any()) return candidate;
        }
        return null;
    }
}

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
