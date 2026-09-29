using System;
using System.IO;
using System.Drawing;
using System.Diagnostics;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Collections.Generic;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    internal static string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".smart-inbox", "desktop");
    internal static string Root;
    internal static string Locale = "zh-CN";
    private static readonly Dictionary<string, string> English = new Dictionary<string, string> {
        { "软件", "App" }, { "主界面", "Home" }, { "刷新界面", "Reload" },
        { "进入待机", "Enter standby" }, { "恢复运行", "Resume" },
        { "打开运行日志", "Open runtime logs" }, { "退出软件（停止后台）", "Exit app (stop background services)" },
        { "打开 Smart Inbox", "Open Smart Inbox" }, { "重新尝试", "Retry" },
        { "正在启动", "Starting" }, { "运行中", "Running" }, { "待机中", "Standby" },
        { "正在切换 / 恢复", "Switching / resuming" }, { "后台连接中断", "Backend disconnected" },
        { "Smart Inbox 无法启动", "Smart Inbox could not start" },
        { "找不到项目目录。请在项目中重新运行 scripts/install-desktop.ps1。", "Project folder not found. Run scripts/install-desktop.ps1 in the project again." },
        { "软件仍在启动，请稍后再次打开。", "The app is still starting. Please try again shortly." },
        { "正在准备桌面环境…", "Preparing the desktop environment…" },
        { "Smart Inbox 已收进托盘", "Smart Inbox is in the system tray" },
        { "后台继续收信。右键托盘图标可待机或退出软件。", "Email sync continues in the background. Right-click the tray icon to enter standby or exit." },
        { "启动未完成。请查看运行日志后重试。", "Startup did not finish. Check the runtime log, then retry." },
        { "日志：", "Log: " }, { "正在恢复上次退出前的后台服务…", "Restoring background services from the last session…" },
        { "正在打开独立窗口…", "Opening the app window…" },
        { "页面连接中断，请点击重新尝试。", "The page disconnected. Click Retry." },
        { "界面进程已停止，请重新打开软件。", "The interface process stopped. Please reopen the app." },
        { "另一次状态切换刚刚结束，请再次操作。", "Another mode change just finished. Please try again." },
        { "后台操作仍未结束，请查看运行日志。没有强制结束数据进程。", "The background operation is still running. Check the runtime log. Data processes have not been forced to stop." },
        { "正在保存数据并进入待机…", "Saving data and entering standby…" },
        { "正在恢复后台服务…", "Resuming background services…" }, { "状态切换未完成", "Mode change did not finish" },
        { "正在保存数据并停止本项目后台，请稍候…", "Saving data and stopping project services. Please wait…" },
        { "退出未完成：", "Exit did not finish: " }, { "退出未完成", "Exit did not finish" },
        { "后台尚未确认安全停止，软件暂时保留。", "Background services have not confirmed a safe stop. The app will stay open." },
        { "正在检查本机运行环境…", "Checking the local environment…" },
        { "正在启动桌面界面…", "Starting the desktop interface…" },
        { "正在启动邮件、任务和 AI 后台…", "Starting email, tasks, and AI services…" },
        { "已保留上次待机状态，可在窗口中恢复运行。", "Previous standby state restored. Resume from this window when ready." },
        { "准备就绪", "Ready" }, { "正常运行", "Running normally" },
        { "上次切换被中断，请点击恢复运行。", "The previous mode change was interrupted. Click Resume." },
        { "项目正在待机或恢复", "The project is in standby or resuming" },
        { "请刷新页面后重试", "Refresh the page and try again" },
        { "正在保存数据并释放资源…", "Saving data and releasing resources…" },
        { "已停止后台服务并卸载本项目 AI 模型", "Background services stopped and the project AI model unloaded" },
        { "正在启动邮件队列和后台服务…", "Starting the email queue and background services…" },
        { "后台服务已就绪，邮件将自动补同步", "Services are ready. Email sync will catch up automatically." },
        { "正在停止收信，并等待本地数据保存…", "Stopping email sync and waiting for local data to save…" },
        { "正在卸载 AI 模型，释放内存和显存…", "Unloading the AI model to release RAM and GPU memory…" },
        { "正在停止本项目容器…", "Stopping project containers…" },
        { "待机完成，关闭开关即可恢复", "Standby is ready. Turn off the switch to resume." },
        { "后台端口被占用或被 Windows 保留，邮件队列无法启动。需要调整端口配置后恢复运行；详情见 .smart-inbox/runtime/errors.log。", "A backend port is in use or reserved by Windows, so the email queue cannot start. Adjust the port configuration and resume. See .smart-inbox/runtime/errors.log." },
        { "切换未完成。请点击恢复运行重试，详情见 .smart-inbox/runtime/errors.log。", "The mode change did not finish. Click Resume to retry. See .smart-inbox/runtime/errors.log." }
    };
    internal static string T(string source) {
        string result;
        return Locale == "en-US" && source != null && English.TryGetValue(source, out result) ? result : source;
    }
    internal static string StatusText(string source) {
        string result = T(source);
        if (Locale == "en-US" && result != null && System.Text.RegularExpressions.Regex.IsMatch(result, "[\\u3400-\\u9fff]"))
            return "A background operation needs attention. See the runtime log for details.";
        return result;
    }
    internal static bool SetLocale(string locale) {
        if (locale != "zh-CN" && locale != "en-US") return false;
        if (Locale == locale) return true;
        Locale = locale;
        try { File.WriteAllText(Path.Combine(DataDir, "ui-language.txt"), locale); } catch (Exception error) { Log("Locale persistence: " + error.Message); }
        return true;
    }
    private static Mutex instance;
    private static readonly List<EventWaitHandle> events = new List<EventWaitHandle>();
    private static string EventName(string command) { return "Local\\SmartInboxDesktop." + Environment.UserName + "." + command; }
    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Directory.CreateDirectory(DataDir);
        try {
            string savedLocale = File.ReadAllText(Path.Combine(DataDir, "ui-language.txt")).Trim();
            if (savedLocale == "en-US" || savedLocale == "zh-CN") Locale = savedLocale;
        } catch { }
        try {
            Root = File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "project-path.txt")).Trim();
            if (!File.Exists(Path.Combine(Root, "scripts", "start-desktop.ps1"))) throw new Exception("找不到项目目录。请在项目中重新运行 scripts/install-desktop.ps1。");
            bool first;
            instance = new Mutex(true, EventName("instance"), out first);
            string command = args.Length == 0 ? "show" : args[0].TrimStart('-');
            if (!new[] { "show", "standby", "resume", "exit" }.ContainsValue(command)) command = "show";
            if (!first) {
                for (int i = 0; i < 30; i++) {
                    try { using (var signal = EventWaitHandle.OpenExisting(EventName(command))) signal.Set(); return; }
                    catch (WaitHandleCannotBeOpenedException) { Thread.Sleep(100); }
                }
                throw new Exception("软件仍在启动，请稍后再次打开。");
            }
            if (command == "exit") return;
            using (var form = new InboxWindow()) {
                foreach (string action in new[] { "show", "standby", "resume", "exit" }) {
                    string captured = action;
                    var signal = new EventWaitHandle(false, EventResetMode.AutoReset, EventName(action));
                    events.Add(signal);
                    ThreadPool.RegisterWaitForSingleObject(signal, delegate {
                        if (form.IsHandleCreated && !form.IsDisposed) form.BeginInvoke((Action)(() => form.Command(captured)));
                    }, null, -1, false);
                }
                Application.Run(form);
            }
        } catch (Exception error) {
            Log("Fatal: " + error);
            MessageBox.Show(StatusText(error.Message), T("Smart Inbox 无法启动"), MessageBoxButtons.OK, MessageBoxIcon.Error);
        } finally {
            foreach (var signal in events) signal.Dispose();
            if (instance != null) instance.Dispose();
        }
    }
    private static bool ContainsValue(this string[] values, string wanted) { foreach (var value in values) if (value == wanted) return true; return false; }
    internal static void Log(string message) {
        try { lock (DataDir) File.AppendAllText(Path.Combine(DataDir, "desktop.log"), DateTime.Now.ToString("s") + " " + message + Environment.NewLine); } catch { }
    }
}

internal sealed class InboxWindow : Form
{
    private const string BaseUrl = "http://127.0.0.1:5173/";
    private readonly HttpClient http = new HttpClient(new HttpClientHandler { UseProxy = false });
    private readonly JavaScriptSerializer json = new JavaScriptSerializer();
    private readonly WebView2 web = new WebView2();
    private readonly Panel splash = new Panel();
    private readonly Label message = new Label();
    private readonly Button retry = new Button();
    private readonly NotifyIcon tray = new NotifyIcon();
    private readonly System.Windows.Forms.Timer statusTimer = new System.Windows.Forms.Timer();
    private readonly ToolStripMenuItem sleepItem = new ToolStripMenuItem("进入待机");
    private readonly ToolStripMenuItem wakeItem = new ToolStripMenuItem("恢复运行");
    private bool booting, actionBusy, closing, ready, balloonShown;
    private string progressText = "正在准备桌面环境…", trayStatus = "正在启动";
    private string ResumeFlag { get { return Path.Combine(Program.Root, ".smart-inbox", "runtime", "desktop-resume-on-launch"); } }
    internal InboxWindow()
    {
        Text = "Smart Inbox";
        Size = new Size(1340, 930); MinimumSize = new Size(820, 620);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Icon = new Icon(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "smart-inbox.ico"));
        http.Timeout = TimeSpan.FromSeconds(10);
        var menus = new MenuStrip();
        var appMenu = new ToolStripMenuItem(Program.T("软件")) { Tag = "软件" };
        AddLocalized(appMenu.DropDownItems, "主界面", delegate { Reveal(); if (ready) web.CoreWebView2.Navigate(BaseUrl); });
        AddLocalized(appMenu.DropDownItems, "刷新界面", delegate { if (ready) web.Reload(); });
        appMenu.DropDownItems.Add(new ToolStripSeparator());
        AddLocalized(appMenu.DropDownItems, "进入待机", async delegate { await ChangeMode("standby"); });
        AddLocalized(appMenu.DropDownItems, "恢复运行", async delegate { await ChangeMode("resume"); });
        appMenu.DropDownItems.Add(new ToolStripSeparator());
        AddLocalized(appMenu.DropDownItems, "打开运行日志", delegate { Process.Start(new ProcessStartInfo(Path.Combine(Program.Root, ".run-logs")) { UseShellExecute = true }); });
        AddLocalized(appMenu.DropDownItems, "退出软件（停止后台）", async delegate { await ExitSafely(); });
        menus.Items.Add(appMenu); MainMenuStrip = menus;
        web.Dock = DockStyle.Fill; web.Visible = false;
        splash.Dock = DockStyle.Fill; splash.BackColor = Color.FromArgb(240, 245, 253);
        message.Dock = DockStyle.Fill; message.TextAlign = ContentAlignment.MiddleCenter;
        message.Font = new Font("Microsoft YaHei UI", 15); message.Padding = new Padding(40);
        message.Text = "Smart Inbox\n" + Program.T(progressText);
        retry.Text = Program.T("重新尝试"); retry.Dock = DockStyle.Bottom; retry.Height = 52; retry.Visible = false;
        retry.Click += async delegate { await Boot(); };
        splash.Controls.Add(message); splash.Controls.Add(retry);
        Controls.Add(web); Controls.Add(splash); Controls.Add(menus);
        var trayMenu = new ContextMenuStrip();
        AddLocalized(trayMenu.Items, "打开 Smart Inbox", delegate { Reveal(); });
        sleepItem.Tag = "进入待机"; wakeItem.Tag = "恢复运行";
        sleepItem.Click += async delegate { await ChangeMode("standby"); };
        wakeItem.Click += async delegate { await ChangeMode("resume"); };
        trayMenu.Items.Add(sleepItem); trayMenu.Items.Add(wakeItem);
        trayMenu.Items.Add(new ToolStripSeparator());
        AddLocalized(trayMenu.Items, "退出软件（停止后台）", async delegate { await ExitSafely(); });
        tray.Icon = Icon; tray.ContextMenuStrip = trayMenu; tray.Visible = true;
        ApplyLocale();
        tray.DoubleClick += delegate { Reveal(); };
        statusTimer.Interval = 15000; statusTimer.Tick += async delegate { await RefreshStatus(); };
        Shown += async delegate { await Boot(); };
        FormClosing += delegate(object sender, FormClosingEventArgs e) {
            if (closing || e.CloseReason == CloseReason.WindowsShutDown || e.CloseReason == CloseReason.TaskManagerClosing) return;
            e.Cancel = true; Hide(); SuspendRenderer();
            if (!balloonShown) { balloonShown = true; tray.ShowBalloonTip(2500, Program.T("Smart Inbox 已收进托盘"), Program.T("后台继续收信。右键托盘图标可待机或退出软件。"), ToolTipIcon.Info); }
        };
        FormClosed += delegate { statusTimer.Stop(); tray.Visible = false; tray.Dispose(); http.Dispose(); web.Dispose(); };
    }
    private static void AddLocalized(ToolStripItemCollection items, string key, EventHandler action) {
        var item = items.Add(Program.T(key), null, action); item.Tag = key;
    }
    private static void TranslateMenu(ToolStripItemCollection items) {
        foreach (ToolStripItem item in items) {
            if (item.Tag is string) item.Text = Program.T((string)item.Tag);
            var group = item as ToolStripDropDownItem;
            if (group != null) TranslateMenu(group.DropDownItems);
        }
    }
    private void ApplyLocale() {
        if (MainMenuStrip != null) TranslateMenu(MainMenuStrip.Items);
        if (tray.ContextMenuStrip != null) TranslateMenu(tray.ContextMenuStrip.Items);
        retry.Text = Program.T("重新尝试");
        tray.Text = "Smart Inbox · " + Program.T(trayStatus);
        Progress(progressText);
    }
    private void Progress(string text) {
        if (IsDisposed) return;
        if (InvokeRequired) { BeginInvoke((Action)(() => Progress(text))); return; }
        progressText = text;
        message.Text = "Smart Inbox\n\n" + Program.StatusText(text);
    }
    private async Task RunScript(string name) {
        var info = new ProcessStartInfo("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -File \"" + Path.Combine(Program.Root, "scripts", name) + "\"") {
            WorkingDirectory = Program.Root, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8, StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        using (var process = new Process { StartInfo = info }) {
            var completion = new TaskCompletionSource<int>();
            process.EnableRaisingEvents = true;
            process.Exited += delegate { completion.TrySetResult(process.ExitCode); };
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) {
                if (e.Data == null) return;
                Program.Log(e.Data);
                if (e.Data.StartsWith("PROGRESS:")) Progress(e.Data.Substring(9));
            };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Program.Log(e.Data); };
            process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine();
            int code = await completion.Task;
            // Detached backend children can inherit pipe handles on Windows. Waiting
            // for output EOF here would block the UI even after PowerShell exits.
            process.CancelOutputRead(); process.CancelErrorRead();
            if (code != 0) throw new Exception(Program.T("启动未完成。请查看运行日志后重试。") + "\n" + Program.T("日志：") + Path.Combine(Program.DataDir, "desktop.log"));
        }
    }
    private async Task Boot() {
        if (booting || actionBusy) return;
        booting = true; retry.Visible = false; splash.Visible = true; web.Visible = false;
        try {
            Program.Log("Desktop starting; project=" + Program.Root);
            await RunScript("start-desktop.ps1");
            if (File.Exists(ResumeFlag)) {
                Progress("正在恢复上次退出前的后台服务…");
                await Transition("resume"); File.Delete(ResumeFlag);
            }
            Progress("正在打开独立窗口…");
            if (web.CoreWebView2 == null) {
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.DataDir, "WebView2"));
                await web.EnsureCoreWebView2Async(env);
                web.CoreWebView2.Settings.AreHostObjectsAllowed = false;
                web.CoreWebView2.Settings.IsWebMessageEnabled = true;
                web.CoreWebView2.Settings.AreDevToolsEnabled = false;
                web.CoreWebView2.WebMessageReceived += async delegate(object sender, CoreWebView2WebMessageReceivedEventArgs e) {
                    // CoreWebView2 receives messages from the top document only.
                    // Only fixed exit and locale commands are accepted, never arbitrary native operations.
                    if (!IsAppUrl(e.Source) || !IsAppUrl(web.CoreWebView2.Source)) return;
                    string command;
                    try { command = e.TryGetWebMessageAsString(); } catch (ArgumentException) { return; }
                    if (command == "smart-inbox:exit") {
                        Program.Log("Homepage exit requested");
                        await ExitSafely();
                    } else if (command == "smart-inbox:locale:zh-CN" || command == "smart-inbox:locale:en-US") {
                        if (Program.SetLocale(command.Substring("smart-inbox:locale:".Length))) ApplyLocale();
                    }
                };
                await web.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bridge.js")));
                web.CoreWebView2.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs e) {
                    e.Handled = true; if (e.IsUserInitiated) OpenLink(e.Uri);
                };
                web.CoreWebView2.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs e) {
                    if (!IsAppUrl(e.Uri)) { e.Cancel = true; if (e.IsUserInitiated) OpenLink(e.Uri); }
                };
                web.CoreWebView2.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs e) {
                    Program.Log("WebView navigation completed: success=" + e.IsSuccess + ", status=" + e.WebErrorStatus);
                    if (!e.IsSuccess) { Progress("页面连接中断，请点击重新尝试。"); splash.Visible = true; retry.Visible = true; web.Visible = false; }
                };
                web.CoreWebView2.ProcessFailed += delegate { Program.Log("WebView process failed"); Progress("界面进程已停止，请重新打开软件。"); splash.Visible = true; web.Visible = false; };
            }
            web.CoreWebView2.Navigate(BaseUrl);
            web.Visible = true; splash.Visible = false; ready = true;
            statusTimer.Start(); await RefreshStatus();
            Program.Log("Desktop ready");
        } catch (Exception error) { Program.Log(error.ToString()); Progress(error.Message); retry.Visible = true; }
        finally { booting = false; }
    }
    private static bool IsAppUrl(string value) {
        Uri uri; return Uri.TryCreate(value, UriKind.Absolute, out uri) && uri.Scheme == "http" && uri.Host == "127.0.0.1" && uri.Port == 5173;
    }
    private void OpenLink(string value) {
        Uri uri;
        if (!Uri.TryCreate(value, UriKind.Absolute, out uri)) return;
        if (IsAppUrl(value)) { web.CoreWebView2.Navigate(value); return; }
        if (uri.Scheme != "https" && uri.Scheme != "http" && uri.Scheme != "mailto") return;
        try { Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); } catch (Exception error) { Program.Log(error.Message); }
    }
    private async Task<Dictionary<string, object>> State() {
        using (var result = await http.GetAsync(BaseUrl + "api/runtime/status")) {
            result.EnsureSuccessStatusCode(); return json.Deserialize<Dictionary<string, object>>(await result.Content.ReadAsStringAsync());
        }
    }
    private async Task RefreshStatus() {
        if (actionBusy || booting) return;
        try {
            var state = await State(); var mode = (string)state["mode"];
            trayStatus = mode == "active" ? "运行中" : mode == "standby" ? "待机中" : "正在切换 / 恢复";
            tray.Text = "Smart Inbox · " + Program.T(trayStatus);
            sleepItem.Enabled = mode == "active"; wakeItem.Enabled = mode == "standby" || mode == "error";
        } catch { trayStatus = "后台连接中断"; tray.Text = "Smart Inbox · " + Program.T(trayStatus); }
    }
    private async Task Transition(string action) {
        var state = await State();
        string target = action == "standby" ? "standby" : "active";
        if ((string)state["mode"] == target) return;
        using (var request = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "api/runtime/" + action)) {
            request.Headers.Add("X-Runtime-Token", (string)state["token"]);
            using (var result = await http.SendAsync(request)) {
                if ((int)result.StatusCode != 409) result.EnsureSuccessStatusCode();
            }
        }
        var deadline = DateTime.UtcNow.AddMinutes(15);
        while (DateTime.UtcNow < deadline) {
            state = await State(); var mode = (string)state["mode"];
            Progress((string)state["message"]);
            if (mode == target) { Program.Log("Runtime reached " + target); return; }
            if (mode == "error") throw new Exception((string)state["message"]);
            if (mode == "active" || mode == "standby") throw new Exception("另一次状态切换刚刚结束，请再次操作。");
            await Task.Delay(1500);
        }
        throw new Exception("后台操作仍未结束，请查看运行日志。没有强制结束数据进程。");
    }
    private async Task ChangeMode(string action) {
        if (booting || actionBusy) return;
        actionBusy = true; sleepItem.Enabled = wakeItem.Enabled = false;
        Reveal(); splash.Visible = true; web.Visible = false; retry.Visible = false;
        Progress(action == "standby" ? "正在保存数据并进入待机…" : "正在恢复后台服务…");
        try { await Transition(action); if (action == "resume" && File.Exists(ResumeFlag)) File.Delete(ResumeFlag); }
        catch (Exception error) { Program.Log(error.ToString()); MessageBox.Show(this, Program.StatusText(error.Message), Program.T("状态切换未完成")); }
        finally {
            actionBusy = false; splash.Visible = false; web.Visible = true;
            if (ready) web.Reload();
        }
        await RefreshStatus();
    }
    private async Task ExitSafely() {
        if (booting || actionBusy || closing) return;
        actionBusy = true; Reveal(); splash.Visible = true; web.Visible = false; retry.Visible = false;
        Progress("正在保存数据并停止本项目后台，请稍候…");
        try {
            var state = await State();
            if ((string)state["mode"] == "active") File.WriteAllText(ResumeFlag, "Resume after a normal desktop exit.");
            await Transition("standby");
            await RunScript("stop-desktop-web.ps1");
            closing = true; Program.Log("Desktop exited safely"); Application.Exit();
        } catch (Exception error) {
            Program.Log(error.ToString()); Progress(Program.T("退出未完成：") + Program.StatusText(error.Message));
            MessageBox.Show(this, Program.T("后台尚未确认安全停止，软件暂时保留。") + "\n" + Program.StatusText(error.Message), Program.T("退出未完成"));
            splash.Visible = false; web.Visible = ready;
        } finally { actionBusy = false; }
    }
    private async void SuspendRenderer() {
        try { if (web.CoreWebView2 != null) await web.CoreWebView2.TrySuspendAsync(); } catch { }
    }
    private void Reveal() {
        if (web.CoreWebView2 != null) web.CoreWebView2.Resume();
        Show(); if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }
    internal async void Command(string command) {
        if (command == "exit") await ExitSafely();
        else if (command == "standby") await ChangeMode("standby");
        else if (command == "resume") await ChangeMode("resume");
        else Reveal();
    }
}
