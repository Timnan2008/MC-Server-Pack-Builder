using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text.Json;

namespace McServerPackBuilder.Desktop;

internal sealed class BackendClient : IDisposable
{
    private readonly HttpClient _client;
    private readonly int _port;
    private Process? _backend;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public BackendClient()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        _port = ((IPEndPoint)listener.LocalEndpoint).Port;
        _client = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{_port}/"), Timeout = TimeSpan.FromMinutes(60) };
    }

    public async Task<ScanResult> ScanAsync(string path)
    {
        await EnsureStartedAsync();
        using var response = await _client.PostAsJsonAsync("api/scan", new { path });
        await ThrowIfError(response);
        return await response.Content.ReadFromJsonAsync<ScanResult>(JsonOptions)
            ?? throw new IOException("后端返回了空报告");
    }

    public async Task<BuildResult> BuildAsync(string path, string output)
    {
        await EnsureStartedAsync();
        using var response = await _client.PostAsJsonAsync("api/build", new { path, output });
        await ThrowIfError(response);
        return await response.Content.ReadFromJsonAsync<BuildResult>(JsonOptions)
            ?? throw new IOException("后端返回了空结果");
    }

    public async Task<BuildJob> StartBuildAsync(string path, string output)
    {
        await EnsureStartedAsync();
        using var response = await _client.PostAsJsonAsync("api/build/start", new { path, output });
        await ThrowIfError(response);
        return await response.Content.ReadFromJsonAsync<BuildJob>(JsonOptions)
            ?? throw new IOException("后端未返回任务编号");
    }

    public async Task<BuildJob> GetBuildStatusAsync(string id)
    {
        using var response = await _client.GetAsync("api/build/status?id=" + Uri.EscapeDataString(id));
        await ThrowIfError(response);
        return await response.Content.ReadFromJsonAsync<BuildJob>(JsonOptions)
            ?? throw new IOException("后端未返回任务进度");
    }

    public async Task CancelBuildAsync(string id)
    {
        using var response = await _client.PostAsync("api/build/cancel?id=" + Uri.EscapeDataString(id), null);
        await ThrowIfError(response);
    }

    private static async Task ThrowIfError(HttpResponseMessage response)
    {
        if (response.IsSuccessStatusCode) return;
        string body = await response.Content.ReadAsStringAsync();
        try
        {
            using var error = JsonDocument.Parse(body);
            throw new IOException(error.RootElement.GetProperty("error").GetString());
        }
        catch (JsonException) { throw new IOException($"后端请求失败：{(int)response.StatusCode} {body}"); }
    }

    public async Task EnsureStartedAsync()
    {
        if (_backend is { HasExited: false }) return;
        string? lib = FindBackendLib();
        if (lib is null) throw new IOException("找不到 Java 后端。先运行 .\\gradlew.bat installDist，并安装 Java 21。详情见 README。");
        _backend = Process.Start(new ProcessStartInfo("java")
        {
            ArgumentList = { "-Djava.net.useSystemProxies=true", "-cp", Path.Combine(lib, "*"), "dev.mcspb.api.ApiServerMain", _port.ToString() },
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true
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

    public void Dispose()
    {
        if (_backend is { HasExited: false }) _backend.Kill();
        _backend?.Dispose();
        _client.Dispose();
    }
}
