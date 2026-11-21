from pathlib import Path
import ctypes
import os
import re
import tkinter as tk
from tkinter import ttk


LOGS = Path(__file__).resolve().parent / "手机免广告助手日志"
RULES = Path(__file__).resolve().parent / "拦截规则"
ICON = Path(__file__).resolve().with_name("手机免广告助手.ico")
MARKER = "PhoneAdSkipper: foreground sample: "
SKIP = re.compile(r"PhoneAdSkipper: (?:skip action accepted|skip tap completed) app=(\S+) seconds=(-?\d+)")


def intercept_message(line):
    match = SKIP.search(line)
    if match:
        package, seconds = match.groups()
        app = {"com.baidu.netdisk": "百度网盘", "com.MobileTicket": "铁路12306",
               "ctrip.android.view": "携程旅行"}.get(package, package)
        saving = f"{seconds} 秒" if int(seconds) >= 0 else "5 秒（估算）"
        return f"拦截成功 | {app} | 已点「跳过」 | 节省 {saving}"
    if "PhoneAdSkipper: Baidu promotion closed" in line:
        return "拦截成功 | 百度网盘 | 已关闭促销弹窗"
    if "PhoneAdSkipper: Didi promotion closed" in line:
        return "拦截成功 | 滴滴出行 | 已关闭促销弹窗"
    if "PhoneAdSkipper: Ctrip promotion closed" in line:
        return "拦截成功 | 携程旅行 | 已关闭促销弹窗"
    return None


class ForegroundConsole:
    def __init__(self, window):
        self.window = window
        self.path = None
        self.offset = 0
        self.window.title("手机免广告助手控制台")
        self.window.iconbitmap(default=str(ICON))
        self.window.geometry("720x420")
        self.window.minsize(580, 260)

        frame = ttk.Frame(window, padding=14)
        frame.pack(fill="both", expand=True)
        header = ttk.Frame(frame)
        header.pack(fill="x")
        ttk.Label(header, text="手机免广告助手控制台", font=("Microsoft YaHei UI", 15, "bold")).pack(side="left")
        ttk.Button(header, text="清空", command=self.clear).pack(side="right")
        ttk.Button(header, text="打开日志文件夹", command=self.open_logs).pack(side="right", padx=(0, 8))
        ttk.Button(header, text="打开规则文件夹", command=self.open_rules).pack(side="right", padx=(0, 8))
        self.status = ttk.Label(frame)
        self.status.pack(anchor="w", pady=(4, 10))
        panel = ttk.Frame(frame)
        panel.pack(fill="both", expand=True)
        self.lines = tk.Text(panel, wrap="none", font=("Microsoft YaHei UI", 11), state="disabled")
        self.lines.tag_configure("success", foreground="#136b38", font=("Microsoft YaHei UI", 11, "bold"))
        scrollbar = ttk.Scrollbar(panel, command=self.lines.yview)
        self.lines.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.lines.pack(side="left", fill="both", expand=True)
        self.refresh(first=True)

    def refresh(self, first=False):
        try:
            if (LOGS / ".clear-request").exists():
                self.status.configure(text="正在清空日志…")
                self.window.after(300, self.refresh)
                return
            latest = max(LOGS.glob("*.log"), key=lambda path: path.name, default=None)
            if latest is None:
                self.status.configure(text="等待 ADB 日志文件")
            else:
                if latest != self.path:
                    self.path = latest
                    self.offset = latest.stat().st_size if first else 0
                self.status.configure(text="清空日志失败，请检查采集进程"
                                      if (LOGS / ".clear-error").exists() else "等待手机切换应用")
                with latest.open("r", encoding="utf-8-sig", errors="replace") as log:
                    log.seek(self.offset)
                    while line := log.readline():
                        if not line.endswith("\n"):
                            break
                        self.offset = log.tell()
                        if MARKER in line:
                            app = line.split(MARKER, 1)[1].strip()
                            self.append(f"{line[6:18]}  {app}")
                        else:
                            result = intercept_message(line)
                            if result:
                                self.append(f"{line[6:18]}  {result}", "success")
        except OSError:
            self.status.configure(text="暂时无法读取 ADB 日志")
        self.window.after(300, self.refresh)

    def append(self, line, tag=""):
        self.lines.configure(state="normal")
        self.lines.insert("end", line + "\n", (tag,) if tag else ())
        if int(self.lines.index("end-1c").split(".")[0]) > 500:
            self.lines.delete("1.0", "2.0")
        self.lines.see("end")
        self.lines.configure(state="disabled")

    def clear(self):
        try:
            LOGS.mkdir(parents=True, exist_ok=True)
            (LOGS / ".clear-request").touch()
        except OSError:
            self.status.configure(text="无法请求清空日志")
            return
        self.lines.configure(state="normal")
        self.lines.delete("1.0", "end")
        self.lines.configure(state="disabled")

    def open_logs(self):
        LOGS.mkdir(parents=True, exist_ok=True)
        os.startfile(str(LOGS))

    def open_rules(self):
        os.startfile(str(RULES))


if __name__ == "__main__":
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Local.PhoneAdSkipper.Console")
    root = tk.Tk()
    ForegroundConsole(root)
    root.mainloop()
