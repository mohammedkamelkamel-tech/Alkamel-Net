using System.Diagnostics;
using System.Net;
using System.Text.Json;

namespace AlkamelChatServer;

internal sealed class ServerConfig
{
    public string BrokerServiceName { get; set; } = "Mosquitto";
    public string BrokerExecutable { get; set; } = @"C:\Program Files\mosquitto\mosquitto.exe";
    public string BrokerConfig { get; set; } = @"C:\Program Files\mosquitto\mosquitto.conf";
    public string TurnListenIp { get; set; } = "0.0.0.0";
    public string TurnRelayIp { get; set; } = "10.10.10.10";
    public int TurnPort { get; set; } = 3478;
    public int TurnCredentialsPort { get; set; } = 3479;
    public int RelayMinPort { get; set; } = 49160;
    public int RelayMaxPort { get; set; } = 49200;
    public string TurnUsername { get; set; } = "alkamel-chat";
    public string TurnPassword { get; set; } = "";
}

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

internal sealed class MainForm : Form
{
    private readonly string root = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "Alkamel", "ChatServer");
    private readonly string configPath;
    private readonly TextBox logBox = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill };
    private readonly Label brokerStatus = new() { AutoSize = true, Text = "Mosquitto: غير مفحوص" };
    private readonly Label turnStatus = new() { AutoSize = true, Text = "خادم الصوت: غير مفحوص" };
    private Process? turnProcess;
    private Process? brokerProcessStartedHere;
    private bool brokerServiceStartedHere;
    private ServerConfig config = new();

    public MainForm()
    {
        configPath = Path.Combine(root, "server.json");
        Text = "خادم شبكة الكامل - الدردشة فقط";
        Width = 720; Height = 480; MinimumSize = new Size(600, 380);
        StartPosition = FormStartPosition.CenterScreen;
        RightToLeft = RightToLeft.Yes; RightToLeftLayout = true;
        var start = new Button { Text = "تشغيل خدمات الدردشة", AutoSize = true, Padding = new Padding(12) };
        var stop = new Button { Text = "إيقاف الخدمات التي شغّلها البرنامج", AutoSize = true, Padding = new Padding(12) };
        var check = new Button { Text = "فحص الحالة", AutoSize = true, Padding = new Padding(12) };
        start.Click += async (_, _) => await StartAllAsync();
        stop.Click += (_, _) => StopManagedServices();
        check.Click += (_, _) => RefreshStatus();
        var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(10), FlowDirection = FlowDirection.RightToLeft };
        top.Controls.AddRange([start, stop, check]);
        var status = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(10), FlowDirection = FlowDirection.RightToLeft };
        status.Controls.AddRange([brokerStatus, turnStatus]);
        Controls.Add(logBox); Controls.Add(status); Controls.Add(top);
        LoadConfig();
        FormClosing += (_, _) => { /* Services intentionally remain running when the window closes. */ };
        RefreshStatus();
        Log("هذا البرنامج يدير خدمات الدردشة والصوت فقط. إغلاق النافذة لا يوقف الخدمات.");
    }

    private void LoadConfig()
    {
        Directory.CreateDirectory(root);
        if (File.Exists(configPath))
        {
            try { config = JsonSerializer.Deserialize<ServerConfig>(File.ReadAllText(configPath)) ?? new ServerConfig(); }
            catch (Exception ex) { Log("تعذر قراءة الإعدادات: " + ex.Message); }
        }
        else
        {
            config.TurnPassword = Convert.ToBase64String(System.Security.Cryptography.RandomNumberGenerator.GetBytes(24));
            File.WriteAllText(configPath, JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true }));
        }
    }

    private void Log(string message)
    {
        if (InvokeRequired) { BeginInvoke(() => Log(message)); return; }
        logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
    }

    private static Process? FindProcess(string name) =>
        Process.GetProcessesByName(Path.GetFileNameWithoutExtension(name)).FirstOrDefault();

    private async Task StartAllAsync()
    {
        try
        {
            await StartMosquittoAsync();
            StartTurn();
            RefreshStatus();
        }
        catch (Exception ex) { Log("فشل التشغيل: " + ex.Message); }
    }

    private async Task StartMosquittoAsync()
    {
        if (FindProcess("mosquitto") is not null) { Log("Mosquitto يعمل بالفعل؛ لم يتم تشغيل نسخة ثانية."); return; }
        var query = await RunScAsync("query", config.BrokerServiceName);
        if (query.Contains("RUNNING", StringComparison.OrdinalIgnoreCase))
        { Log("خدمة Mosquitto تعمل بالفعل."); return; }
        if (query.Contains("STOPPED", StringComparison.OrdinalIgnoreCase))
        {
            var start = await RunScAsync("start", config.BrokerServiceName);
            if (start.Contains("FAILED", StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException(start);
            brokerServiceStartedHere = true;
            Log("تم تشغيل خدمة Mosquitto الموجودة.");
            return;
        }
        if (!File.Exists(config.BrokerExecutable)) throw new FileNotFoundException("لم أجد خدمة Mosquitto ولا ملف التشغيل في المسار المحدد.", config.BrokerExecutable);
        var args = File.Exists(config.BrokerConfig) ? $"-c \"{config.BrokerConfig}\"" : "";
        brokerProcessStartedHere = Process.Start(new ProcessStartInfo(config.BrokerExecutable, args) { UseShellExecute = false, CreateNoWindow = true });
        Log("تم تشغيل Mosquitto من ملف البرنامج.");
        await Task.Delay(900);
    }

    private void StartTurn()
    {
        if (turnProcess is { HasExited: false }) { Log("خادم الصوت يعمل بالفعل."); return; }
        var exe = Path.Combine(AppContext.BaseDirectory, "alkamel-chat-turn.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("ملف خادم الصوت غير موجود بجانب برنامج الإدارة.", exe);
        var start = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = AppContext.BaseDirectory };
        start.Environment["ALKAMEL_TURN_LISTEN_IP"] = config.TurnListenIp;
        start.Environment["ALKAMEL_TURN_RELAY_IP"] = config.TurnRelayIp;
        start.Environment["ALKAMEL_TURN_PORT"] = config.TurnPort.ToString();
        start.Environment["ALKAMEL_TURN_CREDENTIALS_PORT"] = config.TurnCredentialsPort.ToString();
        start.Environment["ALKAMEL_TURN_MIN_PORT"] = config.RelayMinPort.ToString();
        start.Environment["ALKAMEL_TURN_MAX_PORT"] = config.RelayMaxPort.ToString();
        start.Environment["ALKAMEL_TURN_USERNAME"] = config.TurnUsername;
        start.Environment["ALKAMEL_TURN_PASSWORD"] = config.TurnPassword;
        start.Environment["ALKAMEL_TURN_REALM"] = "alkamel-chat";
        turnProcess = Process.Start(start) ?? throw new InvalidOperationException("تعذر بدء خادم الصوت.");
        Log("تم تشغيل خادم الصوت.");
    }

    private void StopManagedServices()
    {
        try
        {
            if (turnProcess is { HasExited: false }) { turnProcess.Kill(true); turnProcess.Dispose(); turnProcess = null; Log("تم إيقاف خادم الصوت."); }
            if (brokerServiceStartedHere)
            {
                _ = RunScAsync("stop", config.BrokerServiceName).GetAwaiter().GetResult();
                brokerServiceStartedHere = false;
                Log("تم إيقاف خدمة Mosquitto التي شغّلها هذا البرنامج.");
            }
            if (brokerProcessStartedHere is { HasExited: false })
            { brokerProcessStartedHere.Kill(true); brokerProcessStartedHere.Dispose(); brokerProcessStartedHere = null; Log("تم إيقاف نسخة Mosquitto التي شغّلها هذا البرنامج."); }
            RefreshStatus();
        }
        catch (Exception ex) { Log("خطأ أثناء الإيقاف: " + ex.Message); }
    }

    private static async Task<string> RunScAsync(string verb, string service)
    {
        using var p = Process.Start(new ProcessStartInfo("sc.exe", $"{verb} \"{service}\"")
        { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true })!;
        var output = await p.StandardOutput.ReadToEndAsync();
        var error = await p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        return output + error;
    }

    private void RefreshStatus()
    {
        var broker = FindProcess("mosquitto") is not null;
        brokerStatus.Text = "Mosquitto: " + (broker ? "يعمل" : "متوقف");
        var turn = turnProcess is { HasExited: false } || FindProcess("alkamel-chat-turn") is not null;
        turnStatus.Text = "خادم الصوت: " + (turn ? "يعمل" : "متوقف");
    }
}
