"""
Demo app: world-anchored panels on the glasses display (tkinter, no extra dependency).

Black = transparent on the glasses (optical see-through), so the background is pure black.

Keys:
  R recenter | M mode (Anchor / Follow / Corner) | + - zoom | Tab select panel
  Arrow keys move selected panel (Anchor) or the 'head' (mouse source) | [ ] brightness
  H help | Esc quit
"""
from __future__ import annotations

import time
import tkinter as tk
from dataclasses import dataclass
from typing import Callable, List

from .fusion import HeadTracker
from .monitors import guess_glasses_monitor, list_monitors

MODES = ["ANCHOR", "FOLLOW", "CORNER"]
MODE_VI = {"ANCHOR": "Ghim trong không gian", "FOLLOW": "Đi theo đầu", "CORNER": "Thu nhỏ ở góc"}


@dataclass
class Panel:
    title: str
    yaw: float
    pitch: float
    w: float      # degrees
    h: float      # degrees
    body: Callable[[], str]
    color: str = "#39d0ff"


class DemoApp:
    def __init__(self, source, hfov: float = 40.0, fullscreen: bool = True, monitor_index=None):
        self.source = source
        self.hfov = hfov
        self.mode = "ANCHOR"
        self.zoom = 1.0
        self.sel = 0
        self.show_help = True
        self.toast = ("", 0.0)
        self.started = time.time()

        self.root = tk.Tk()
        self.root.title("XR Air Lab")
        self.root.configure(bg="black")
        mons = list_monitors(self.root)
        mon = mons[monitor_index] if monitor_index is not None and monitor_index < len(mons) \
            else guess_glasses_monitor(mons)
        self.monitor = mon
        if fullscreen:
            self.root.overrideredirect(True)
            self.root.geometry(f"{mon.w}x{mon.h}+{mon.x}+{mon.y}")
            self.W, self.H = mon.w, mon.h
        else:
            self.W, self.H = 1280, 720
            self.root.geometry(f"{self.W}x{self.H}+60+60")
        if self.W >= 3000:  # SBS mode: draw on left half only, keep it simple
            self.W = self.W // 2
        self.canvas = tk.Canvas(self.root, width=self.W, height=self.H, bg="black", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)

        self.panels: List[Panel] = [
            Panel("Đồng hồ", 0, 0, 16, 9, lambda: time.strftime("%H:%M:%S"), "#39d0ff"),
            Panel("Ghi chú", -28, 0, 16, 12, lambda: "Xin chào anh Khoa!\nPanel này ghim bên trái.\nQuay đầu sang trái để thấy.", "#7cff9b"),
            Panel("Trạng thái", 28, 0, 18, 12, self._status_text, "#ffd166"),
            Panel("Bản đồ (placeholder)", 0, -22, 22, 10, lambda: "Chỗ này sau sẽ là HUD chỉ đường\n(nhìn xuống để thấy)", "#ff7ab6"),
        ]

        self.root.bind("<Escape>", lambda e: self.quit())
        self.root.bind("<Key>", self.on_key)
        self.root.bind("<B1-Motion>", self.on_drag)
        self.root.bind("<ButtonPress-1>", self.on_press)
        self._last_mouse = None
        self.root.protocol("WM_DELETE_WINDOW", self.quit)
        self.root.focus_force()

    # ------------------------------------------------------------------ input
    def on_press(self, e):
        self._last_mouse = (e.x, e.y)

    def on_drag(self, e):
        if getattr(self.source, "name", "") != "mouse" or self._last_mouse is None:
            return
        dx, dy = e.x - self._last_mouse[0], e.y - self._last_mouse[1]
        self._last_mouse = (e.x, e.y)
        ppd = self.W / self.hfov
        self.source.nudge(-dx / ppd, dy / ppd)

    def on_key(self, e):
        k = e.keysym
        if k in ("r", "R"):
            self.recenter()
        elif k in ("m", "M"):
            self.mode = MODES[(MODES.index(self.mode) + 1) % len(MODES)]
            self.say(f"Chế độ: {MODE_VI[self.mode]}")
        elif k in ("plus", "equal", "KP_Add"):
            self.zoom = min(2.5, self.zoom * 1.1)
        elif k in ("minus", "KP_Subtract"):
            self.zoom = max(0.4, self.zoom / 1.1)
        elif k == "Tab":
            self.sel = (self.sel + 1) % len(self.panels)
            self.say(f"Chọn: {self.panels[self.sel].title}")
        elif k in ("h", "H"):
            self.show_help = not self.show_help
        elif k in ("bracketleft", "bracketright"):
            self.brightness(-1 if k == "bracketleft" else 1)
        elif k in ("Left", "Right", "Up", "Down"):
            d = {"Left": (-2, 0), "Right": (2, 0), "Up": (0, 2), "Down": (0, -2)}[k]
            if getattr(self.source, "name", "") == "mouse" and not (e.state & 0x1):
                self.source.nudge(d[0], d[1])
            else:  # Shift+arrows (or real glasses): move the selected panel
                p = self.panels[self.sel]
                p.yaw += d[0]
                p.pitch += d[1]

    def recenter(self):
        if getattr(self.source, "tracker", None):
            self.source.tracker.recenter()
        elif hasattr(self.source, "recenter"):
            self.source.recenter()
        self.say("Đã recenter")

    def brightness(self, step):
        b = getattr(self.source, "brightness", None)
        g = getattr(self.source, "g", None)
        if b is None or g is None:
            self.say("Chỉnh độ sáng chỉ có khi dùng kính thật")
            return
        b = max(0, min(8, b + step))
        self.source.brightness = b
        g.send_brightness_nowait(b)
        self.say(f"Độ sáng: {b}/8")

    def say(self, text):
        self.toast = (text, time.time())

    # ------------------------------------------------------------------ render
    def _status_text(self):
        p = self.source.pose
        tr = getattr(self.source, "tracker", None)
        hz = f"{tr.rate_hz:.0f} Hz" if tr else "-"
        return (f"Nguồn: {getattr(self.source, 'name', '?')}  ({hz})\n"
                f"yaw {p.yaw:+6.1f}°  pitch {p.pitch:+6.1f}°\nroll {p.roll:+6.1f}°\n"
                f"Chế độ: {MODE_VI[self.mode]}")

    def _draw_panel(self, p: Panel, cx: float, cy: float, scale: float, selected: bool):
        ppd = self.W / self.hfov * scale
        w, h = p.w * ppd, p.h * ppd
        x0, y0 = cx - w / 2, cy - h / 2
        if x0 > self.W or x0 + w < 0 or y0 > self.H or y0 + h < 0:
            return
        c = self.canvas
        c.create_rectangle(x0, y0, x0 + w, y0 + h, outline=p.color, width=4 if selected else 2, fill="#0b0b0b")
        fs = max(9, int(ppd * 0.62))
        c.create_text(x0 + 12, y0 + 8, anchor="nw", text=p.title, fill=p.color,
                      font=("Segoe UI", max(10, int(fs * 0.8)), "bold"))
        c.create_text(cx, cy + fs * 0.5, text=p.body(), fill="white", font=("Segoe UI", fs), justify="center",
                      width=max(40, w - 24))

    def frame(self):
        c = self.canvas
        c.delete("all")
        pose = self.source.pose
        tr = getattr(self.source, "tracker", None)
        ppd = self.W / self.hfov * self.zoom
        if tr and tr.calibrating:
            c.create_text(self.W / 2, self.H / 2, text=tr.status or "Đang hiệu chỉnh gyro...",
                          fill="#ffd166", font=("Segoe UI", 28))
        elif self.mode == "ANCHOR":
            for i, p in enumerate(self.panels):
                cx = self.W / 2 + (p.yaw - pose.yaw) * ppd
                cy = self.H / 2 - (p.pitch - pose.pitch) * ppd
                self._draw_panel(p, cx, cy, self.zoom, i == self.sel)
        elif self.mode == "FOLLOW":
            self._draw_panel(self.panels[self.sel], self.W / 2, self.H / 2, self.zoom, True)
        else:  # CORNER: small, head-locked, top-right (like XREAL "Side View")
            p = self.panels[self.sel]
            s = 0.45 * self.zoom
            pp = self.W / self.hfov * s
            self._draw_panel(p, self.W - p.w * pp / 2 - 30, p.h * pp / 2 + 30, s, True)

        # HUD
        hud = f"{getattr(self.source, 'name', '?')} | yaw {pose.yaw:+.1f} pitch {pose.pitch:+.1f} | {MODE_VI[self.mode]}"
        c.create_text(12, self.H - 12, anchor="sw", text=hud, fill="#777777", font=("Consolas", 12))
        if tr and tr.warning:
            c.create_text(12, self.H - 36, anchor="sw", text=tr.warning, fill="#ff6b6b", font=("Segoe UI", 12))
        if self.show_help:
            c.create_text(12, 12, anchor="nw", fill="#888888", font=("Consolas", 12), text=(
                "R recenter | M chế độ | +/- zoom | Tab chọn panel | Shift+mũi tên dời panel\n"
                "[ ] độ sáng kính | H ẩn trợ giúp | Esc thoát"
                + ("\nKéo chuột / mũi tên = xoay đầu giả lập" if getattr(self.source, "name", "") == "mouse" else "")))
        # MCU events (glasses buttons)
        ev = getattr(self.source, "events", [])
        while ev:
            p = ev.pop(0)
            if p.cmd_id == 0x6C05 and p.data:
                from .protocol import KEY_NAMES
                code = p.data[0]
                self.say(f"Nút trên kính: code {code} ({KEY_NAMES.get(code, '?')})")
        if self.toast[0] and time.time() - self.toast[1] < 2.0:
            c.create_text(self.W / 2, 40, text=self.toast[0], fill="#ffffff", font=("Segoe UI", 18, "bold"))
        self.root.after(16, self.frame)

    def run(self):
        self.root.after(16, self.frame)
        self.root.mainloop()

    def quit(self):
        try:
            self.source.close()
        finally:
            self.root.destroy()


def run_demo(source_kind: str, replay: str = None, forward: str = "-y", flip_yaw=False, flip_pitch=False,
             hfov: float = 40.0, windowed: bool = False, monitor=None, record=None, verbose=False):
    from .sources import GlassesSource, MouseSource, ReplaySource
    tracker = HeadTracker(forward_axis=forward, flip_yaw=flip_yaw, flip_pitch=flip_pitch)
    if source_kind == "glasses":
        src = GlassesSource(tracker, verbose=verbose, record_path=record)
    elif source_kind == "replay":
        src = ReplaySource(tracker, replay)
    else:
        src = MouseSource()
    DemoApp(src, hfov=hfov, fullscreen=not windowed, monitor_index=monitor).run()
