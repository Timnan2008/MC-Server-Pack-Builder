using Microsoft.Win32;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Color = System.Windows.Media.Color;
using Brush = System.Windows.Media.Brush;
using DragEventArgs = System.Windows.DragEventArgs;
using DataFormats = System.Windows.DataFormats;
using DragDropEffects = System.Windows.DragDropEffects;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;

namespace McServerPackBuilder.Desktop;

public partial class MainWindow : Window
{
    private readonly BackendClient _backend = new();
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private ScanResult? _result;
    private InputInspection _inspection = new(false, false, "尚未选择内容");
    private CancellationTokenSource? _inspectionDelay;
    private bool _busy;
    private string? _buildJobId;
    private bool _cancelRequested;
    private bool _showingResults;

    public MainWindow()
    {
        InitializeComponent();
        // Keep the initial window inside the usable desktop area, including at larger DPI scales.
        MaxWidth = SystemParameters.WorkArea.Width;
        MaxHeight = SystemParameters.WorkArea.Height;
        Width = Math.Min(Width, MaxWidth);
        Height = Math.Min(Height, MaxHeight);
        StateChanged += (_, _) => MaximizeButton.Content = WindowState == WindowState.Maximized ? "❐" : "□";
        if (Environment.GetCommandLineArgs() is { Length: > 1 } args) PathBox.Text = args[1];
        Closed += (_, _) => _backend.Dispose();
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2) ToggleMaximize();
        else if (e.LeftButton == MouseButtonState.Pressed) DragMove();
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Maximize_Click(object sender, RoutedEventArgs e) => ToggleMaximize();
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
    private void ToggleMaximize() => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void WorkbenchNav_Click(object sender, RoutedEventArgs e) => ShowPage(false);
    private void ResultsNav_Click(object sender, RoutedEventArgs e) => ShowPage(true);

    private void ShowPage(bool results)
    {
        if (_showingResults == results && WorkbenchPanel.Visibility == (results ? Visibility.Collapsed : Visibility.Visible)) return;
        _showingResults = results;
        WorkbenchPanel.Visibility = results ? Visibility.Collapsed : Visibility.Visible;
        ResultsPanel.Visibility = results ? Visibility.Visible : Visibility.Collapsed;
        var activePanel = results ? ResultsPanel : WorkbenchPanel;
        activePanel.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        });
        var offset = new TranslateTransform(0, 8);
        activePanel.RenderTransform = offset;
        offset.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(8, 0, TimeSpan.FromMilliseconds(240))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
        MainScroll.ScrollToTop();
        PageTitle.Text = results ? "扫描结果" : "整合包工作台";
        PageSubtitle.Text = results ? "查看模组侧别、判断依据和诊断详情" : "导入客户端整合包，检查模组，一键生成服务端 ZIP";
        WorkbenchNavButton.Tag = results ? null : "Active";
        ResultsNavButton.Tag = results ? "Active" : null;
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (Keyboard.Modifiers == ModifierKeys.Control && e.Key == Key.O && !_busy)
        {
            ChooseFile_Click(sender, e);
            e.Handled = true;
        }
        else if (Keyboard.Modifiers == ModifierKeys.Control && e.Key == Key.F)
        {
            ShowPage(true);
            SearchBox.Focus();
            SearchBox.SelectAll();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && SearchBox.IsKeyboardFocusWithin && SearchBox.Text.Length > 0)
        {
            SearchBox.Clear();
            e.Handled = true;
        }
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

    private void Window_DragEnter(object sender, DragEventArgs e) => ShowDragState(e);
    private void Window_DragOver(object sender, DragEventArgs e) => ShowDragState(e);

    private void ShowDragState(DragEventArgs e)
    {
        bool singleFile = e.Data.GetDataPresent(DataFormats.FileDrop)
            && e.Data.GetData(DataFormats.FileDrop) is string[] { Length: 1 };
        e.Effects = singleFile && !_busy ? DragDropEffects.Copy : DragDropEffects.None;
        DropZone.Background = singleFile && !_busy
            ? new SolidColorBrush(Color.FromRgb(222, 239, 253))
            : new SolidColorBrush(Color.FromRgb(244, 249, 254));
        e.Handled = true;
    }

    private void Window_DragLeave(object sender, DragEventArgs e) => ResetDropZone();

    private void Window_Drop(object sender, DragEventArgs e)
    {
        ResetDropZone();
        if (_busy) return;
        if (e.Data.GetData(DataFormats.FileDrop) is string[] { Length: 1 } paths)
            PathBox.Text = paths[0];
        else
            InputStatus.Text = "一次只支持导入一个文件或文件夹。";
        e.Handled = true;
    }

    private void ResetDropZone() => DropZone.Background = new SolidColorBrush(Color.FromRgb(244, 249, 254));

    private async void PathBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (SummaryText is null) return;
        _inspectionDelay?.Cancel();
        var delay = new CancellationTokenSource();
        _inspectionDelay = delay;
        _inspection = new(false, false, "正在识别文件…");
        InputStatus.Text = _inspection.Description;
        _result = null;
        ResultsGrid.ItemsSource = null;
        SearchBox.Text = "";
        SummaryText.Text = "  ·  暂无结果";
        ResetStatistics();
        InputBadges.Visibility = Visibility.Collapsed;
        SetCallout("ⓘ", "诊断与说明", "#176FCC", "#EDF6FE");
        DetailText.Text = "选择扫描结果可查看诊断。待确认项需要人工检查。";
        UpdateButtons();
        string path = PathBox.Text.Trim();
        if (path.Length == 0)
        {
            InputStatus.Text = "尚未选择内容";
            StatusText.Text = "等待导入";
            ActivityText.Text = "选择一个文件以开始";
            return;
        }
        try
        {
            await Task.Delay(250, delay.Token);
            var inspection = await Task.Run(() => PackInputInspector.Inspect(path), delay.Token);
            if (delay.IsCancellationRequested) return;
            _inspection = inspection;
            InputStatus.Text = inspection.Description;
            ShowInputBadges(inspection);
            if (!_busy)
            {
                StatusText.Text = inspection.CanScan ? "已准备就绪" : "无法识别输入";
                ActivityText.Text = inspection.CanBuild ? "可以扫描或制作服务端 ZIP" : "请查看上方的识别说明";
            }
            UpdateButtons();
        }
        catch (OperationCanceledException) { }
    }

    private void ShowInputBadges(InputInspection inspection)
    {
        InputBadges.Visibility = inspection.PackType is null ? Visibility.Collapsed : Visibility.Visible;
        PackTypeBadge.Text = inspection.PackType ?? "";
        LoaderBadgeBox.Visibility = inspection.Loader is null ? Visibility.Collapsed : Visibility.Visible;
        LoaderBadge.Text = inspection.Loader ?? "";
        VersionBadgeBox.Visibility = inspection.Version is null ? Visibility.Collapsed : Visibility.Visible;
        VersionBadge.Text = inspection.Version ?? "";
    }

    private void ResetStatistics()
    {
        TotalCount.Text = ClientCount.Text = CompatibleCount.Text = KeepCount.Text = UnknownCount.Text = "—";
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (ResultsGrid is null || ClearSearchButton is null) return;
        ClearSearchButton.IsEnabled = SearchBox.Text.Length > 0;
        if (_result is null) return;
        var view = CollectionViewSource.GetDefaultView(ResultsGrid.ItemsSource);
        if (view is null) return;
        string term = SearchBox.Text.Trim();
        view.Filter = item => item is ScanEntry entry &&
            (term.Length == 0 || entry.Path.Contains(term, StringComparison.CurrentCultureIgnoreCase)
                || entry.Reason.Contains(term, StringComparison.CurrentCultureIgnoreCase)
                || entry.SideLabel.Contains(term, StringComparison.CurrentCultureIgnoreCase));
        SummaryText.Text = $"  ·  显示 {view.Cast<object>().Count()} / {_result.Entries.Count} 项";
    }

    private void ClearSearch_Click(object sender, RoutedEventArgs e)
    {
        SearchBox.Clear();
        SearchBox.Focus();
    }

    private void ShowDiagnostics(IReadOnlyList<ScanDiagnostic> diagnostics)
    {
        DetailText.Text = FormatDiagnostics(diagnostics);
        if (diagnostics.Any(d => d.Severity.Equals("ERROR", StringComparison.OrdinalIgnoreCase)))
            SetCallout("✕", "发现错误", "#D45B72", "#FBECEF");
        else if (diagnostics.Any(d => d.Severity.Equals("WARNING", StringComparison.OrdinalIgnoreCase)))
            SetCallout("!", "需要注意", "#C98B34", "#FCF3E7");
        else
            SetCallout("ⓘ", "诊断与说明", "#176FCC", "#EDF6FE");
    }

    private void SetCallout(string icon, string title, string accent, string background)
    {
        DetailIcon.Text = icon;
        DetailHeading.Text = title;
        DetailIcon.Foreground = DetailHeading.Foreground = (Brush)new BrushConverter().ConvertFromString(accent)!;
        Callout.Background = (Brush)new BrushConverter().ConvertFromString(background)!;
    }

    private void UpdateButtons()
    {
        PathBox.IsEnabled = !_busy;
        ChooseFileButton.IsEnabled = !_busy;
        ChooseFolderButton.IsEnabled = !_busy;
        ScanButton.IsEnabled = !_busy && _inspection.CanScan;
        BuildButton.IsEnabled = !_busy && _inspection.CanBuild;
        ExportButton.IsEnabled = !_busy && _result is not null;
        CancelBuildButton.Visibility = _buildJobId is null ? Visibility.Collapsed : Visibility.Visible;
        CancelBuildButton.IsEnabled = !_cancelRequested && _buildJobId is not null;
    }

    private void SetActivity(string title, string detail, bool running)
    {
        StatusText.Text = title;
        ActivityText.Text = detail;
        TaskProgress.IsIndeterminate = running;
        TaskProgress.Value = running ? 0 : 100;
        _busy = running;
        UpdateButtons();
    }

    private async void Scan_Click(object sender, RoutedEventArgs e)
    {
        string path = PathBox.Text.Trim();
        if (!_inspection.CanScan)
        {
            StatusText.Text = _inspection.Description;
            return;
        }
        SetActivity("正在启动扫描", "连接本地 Java 后端…", true);
        DetailText.Text = "";
        try
        {
            await _backend.EnsureStartedAsync();
            SetActivity("正在扫描模组", "读取元数据并判断服务端侧别；整合包可能需要查询项目声明…", true);
            _result = await _backend.ScanAsync(path);
            ResultsGrid.ItemsSource = _result.Entries;
            SearchBox_TextChanged(SearchBox, null!);
            int client = _result.Entries.Count(x => x.Side == "CLIENT_ONLY");
            int compatible = _result.Entries.Count(x => x.Side == "SERVER_COMPATIBLE");
            int keep = _result.Entries.Count(x => x.Side == "KEEP_ON_SERVER");
            int unknown = _result.Entries.Count(x => x.Side == "UNKNOWN");
            TotalCount.Text = _result.Entries.Count.ToString();
            ClientCount.Text = client.ToString();
            CompatibleCount.Text = compatible.ToString();
            KeepCount.Text = keep.ToString();
            UnknownCount.Text = unknown.ToString();
            StatusText.Text = $"扫描完成 · 共 {_result.Entries.Count} 项";
            ActivityText.Text = "扫描结果已就绪，可选择条目查看诊断";
            ShowDiagnostics(_result.Diagnostics);
            ShowPage(true);
        }
        catch (Exception ex)
        {
            StatusText.Text = "扫描失败";
            ActivityText.Text = "查看下方错误详情";
            DetailText.Text = ex.Message;
            SetCallout("✕", "扫描失败", "#D45B72", "#FBECEF");
        }
        finally { _busy = false; TaskProgress.IsIndeterminate = false; TaskProgress.Value = 100; UpdateButtons(); }
    }

    private void ResultsGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResultsGrid.SelectedItem is ScanEntry entry)
        {
            ShowDiagnostics(entry.Diagnostics);
            DetailText.Text = $"{entry.Path}\n{entry.Reason}\n\n" + DetailText.Text;
        }
    }

    private async void Build_Click(object sender, RoutedEventArgs e)
    {
        string path = PathBox.Text.Trim();
        if (!_inspection.CanBuild)
        {
            StatusText.Text = _inspection.Description;
            return;
        }
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "ZIP 压缩包 (*.zip)|*.zip",
            FileName = Path.GetFileNameWithoutExtension(path) + "-server.zip" };
        if (dialog.ShowDialog(this) != true) return;
        SetActivity("正在准备制作", "连接本地 Java 后端…", true);
        DetailText.Text = "这一步可能需要几分钟，取决于整合包大小和网络速度。";
        try
        {
            await _backend.EnsureStartedAsync();
            var job = await _backend.StartBuildAsync(path, dialog.FileName);
            _buildJobId = job.Id;
            _cancelRequested = false;
            UpdateButtons();
            while (job.State is "running" or "cancelling")
            {
                ShowBuildProgress(job);
                await Task.Delay(250);
                job = await _backend.GetBuildStatusAsync(job.Id);
            }
            if (job.State == "cancelled") throw new OperationCanceledException("制作已取消，未生成服务端 ZIP。");
            if (job.State != "completed" || job.Result is null)
                throw new IOException(job.Error ?? "制作失败");
            var built = job.Result;
            StatusText.Text = $"已制作：保留 {built.KeptMods} 个模组，排除 {built.ExcludedMods} 个仅客户端模组";
            ActivityText.Text = "服务端整合包已保存";
            DetailText.Text = $"输出：{built.Output}\nMinecraft {built.MinecraftVersion} / NeoForge {built.NeoforgeVersion}\n" +
                "服务端核心和依赖已预装。解压后运行 start-server.bat。\n" +
                string.Join("\n", built.Warnings);
            if (built.Warnings.Count > 0) SetCallout("!", "制作完成 · 有提示", "#C98B34", "#FCF3E7");
            else SetCallout("✓", "制作完成", "#398EAE", "#E8F4F8");
        }
        catch (OperationCanceledException ex)
        {
            StatusText.Text = "制作已取消";
            ActivityText.Text = "临时文件已清理";
            DetailText.Text = ex.Message;
            TaskProgress.Value = 0;
            SetCallout("ⓘ", "已取消", "#398EAE", "#E8F4F8");
        }
        catch (Exception ex)
        {
            StatusText.Text = "制作失败";
            ActivityText.Text = "查看下方错误详情";
            DetailText.Text = ex.Message;
            SetCallout("✕", "制作失败", "#D45B72", "#FBECEF");
        }
        finally
        {
            _buildJobId = null;
            _cancelRequested = false;
            _busy = false;
            TaskProgress.IsIndeterminate = false;
            if (StatusText.Text.Contains("失败")) TaskProgress.Value = 0;
            UpdateButtons();
        }
    }

    private async void CancelBuild_Click(object sender, RoutedEventArgs e)
    {
        if (_buildJobId is null || _cancelRequested) return;
        _cancelRequested = true;
        UpdateButtons();
        StatusText.Text = "正在取消制作";
        ActivityText.Text = "正在停止下载或安装，并清理临时文件…";
        try { await _backend.CancelBuildAsync(_buildJobId); }
        catch (Exception ex)
        {
            _cancelRequested = false;
            DetailText.Text = "取消请求失败：" + ex.Message;
            UpdateButtons();
        }
    }

    private void ShowBuildProgress(BuildJob job)
    {
        if (_cancelRequested) return;
        string stage = job.Stage switch
        {
            "preparing" => "读取整合包索引",
            "downloading" => "正在下载模组",
            "scanning" => "正在检查模组",
            "installer-download" => "正在下载 NeoForge 安装器",
            "installing" => "正在安装服务端核心",
            "packaging" => "正在写入服务端 ZIP",
            _ => "正在制作服务端 ZIP"
        };
        StatusText.Text = stage;
        bool downloading = job.Stage is "downloading" or "installer-download";
        if (downloading && job.TotalBytes > 0)
        {
            TaskProgress.IsIndeterminate = false;
            TaskProgress.Value = Math.Clamp(100d * job.DownloadedBytes / job.TotalBytes, 0, 100);
            ActivityText.Text = $"{job.DownloadedBytes / 1048576d:F1} / {job.TotalBytes / 1048576d:F1} MiB · {job.CurrentFile}";
        }
        else
        {
            TaskProgress.IsIndeterminate = true;
            ActivityText.Text = downloading && job.DownloadedBytes > 0
                ? $"已下载 {job.DownloadedBytes / 1048576d:F1} MiB · {job.CurrentFile}（服务器未提供总大小）"
                : job.CurrentFile;
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
        catch (Exception ex)
        {
            DetailText.Text = "导出失败：" + ex.Message;
            SetCallout("✕", "导出失败", "#D45B72", "#FBECEF");
        }
    }

    private static string FormatDiagnostics(IReadOnlyList<ScanDiagnostic> diagnostics) =>
        diagnostics.Count == 0 ? "没有诊断。UNKNOWN 需要人工确认；分类不代表服务端启动验证。" :
        string.Join(Environment.NewLine, diagnostics.Select(d => $"[{d.Severity}] {d.Code}: {d.Message}"));

}
