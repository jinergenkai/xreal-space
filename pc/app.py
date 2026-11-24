"""
XREAL Air 2 virtual screens prototype (Windows).

- Fullscreen window on the glasses display (must be in EXTEND mode, Win+P).
- Captures real desktop monitors / windows into textures.
- Places them world-locked around you using the glasses IMU (3DoF).

Hotkeys (global, work while you use the laptop):
  Ctrl+Alt+R   recenter screens in front of you
  Ctrl+Alt+C   redo axis calibration wizard
  Ctrl+Alt+B   re-estimate gyro bias (keep head still 1 s)
  Ctrl+Alt+F   toggle world-locked / head-locked (compare)
  Ctrl+Alt+Up/Down   screens farther / closer
  Ctrl+Alt+[ / ]     FOV smaller / larger (match real size)
  Ctrl+Alt+H   toggle HUD
  Ctrl+Alt+W   walk mode (hide big screens, keep map + info)
  Ctrl+Alt+M   toggle map    Ctrl+Alt+N  demo notification
  Head: nod = OK / toggle map, shake = dismiss
  Ctrl+Alt+Q   quit
Run with --window to preview in a normal window on the laptop.
"""
import ctypes
import json
import math
import os
import sys
import threading
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)  # physical pixels everywhere

import numpy as np
import win32api
import win32con
import win32gui
import win32ui

os.environ.setdefault("PYGAME_HIDE_SUPPORT_PROMPT", "1")
import pygame
import moderngl

from xreal_imu import ImuTracker, _expm_so3
from hud import HudSystem

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

DEFAULT_CONFIG = {
    "imu_to_cam": None,          # 3x3, filled by calibration wizard
    "fov_v_deg": 23.5,           # Air 2: ~46 deg diagonal, 16:9
    "distance_m": 2.0,
    "predict_ms": 15,
    "screens": [
        {"source": "monitor", "match": "primary", "yaw": 0, "pitch": 0, "width_m": 1.2},
        {"source": "monitor", "match": "secondary", "yaw": -40, "pitch": 0, "width_m": 1.2},
        {"source": "window", "match": "auto", "yaw": 40, "pitch": 0, "width_m": 1.0},
    ],
}


def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


# ------------------------------------------------------------------ displays
def list_displays():
    """[{name, rect(x,y,w,h), primary, glasses, monitors:[ids]}] for active displays."""
    out = []
    i = 0
    while True:
        try:
            dev = win32api.EnumDisplayDevices(None, i)
        except Exception:
            break
        i += 1
        if not dev.StateFlags & win32con.DISPLAY_DEVICE_ATTACHED_TO_DESKTOP:
            continue
        mons = []
        k = 0
        while True:
            try:
                m = win32api.EnumDisplayDevices(dev.DeviceName, k)
            except Exception:
                break
            k += 1
            mons.append(m.DeviceID)
        s = win32api.EnumDisplaySettings(dev.DeviceName, win32con.ENUM_CURRENT_SETTINGS)
        out.append({
            "name": dev.DeviceName,
            "rect": (s.Position_x, s.Position_y, s.PelsWidth, s.PelsHeight),
            "primary": bool(dev.StateFlags & win32con.DISPLAY_DEVICE_PRIMARY_DEVICE),
            "glasses": any("\\MRG" in mid.upper() for mid in mons),
            "monitors": mons,
        })
    return out


# ------------------------------------------------------------------ capture
class Source:
    def __init__(self):
        self.frame = None  # (bytes BGRA top-down, w, h)
        self.seq = 0
        self.rect = getattr(self, "rect", None)  # screen rect for cursor mapping (monitors only)
        self.label = getattr(self, "label", "")
        self.fps = 0.0
        self._run = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        t0, n = time.perf_counter(), 0
        while self._run:
            try:
                f = self.grab()
            except Exception as e:  # keep going, sources come and go
                f = None
                print(f"[capture {self.label}] {e}")
                time.sleep(0.5)
            if f is not None:
                self.frame = f
                self.seq += 1
                n += 1
            else:
                time.sleep(0.005)
            now = time.perf_counter()
            if now - t0 > 1:
                self.fps, t0, n = n / (now - t0), now, 0

    def stop(self):
        self._run = False


class MonitorSource(Source):
    def __init__(self, disp):
        self.disp = disp
        self.label = disp["name"].lstrip("\\.")
        self.cam = None
        self.sct = None
        try:
            import dxcam
            for idx in range(8):
                try:
                    c = dxcam.create(output_idx=idx, output_color="BGRA")
                except Exception:
                    break
                dn = getattr(getattr(c, "_output", None), "devicename", None)
                if dn and dn.rstrip("\x00") == disp["name"]:
                    self.cam = c
                    break
                # don't release: dxcam caches one instance per output and another
                # MonitorSource may already be using it
        except Exception as e:
            print(f"[dxcam] unavailable ({e}), using mss")
        if self.cam is None:
            import mss
            self._mss = mss
        super().__init__()
        self.rect = disp["rect"]
        print(f"[capture] monitor {disp['name']} {disp['rect']} via {'dxcam' if self.cam else 'mss'}")

    def grab(self):
        x, y, w, h = self.disp["rect"]
        if self.cam is not None:
            img = self.cam.grab()
            if img is None:  # no change since last frame
                time.sleep(1 / 120)
                return None
            return img.tobytes(), img.shape[1], img.shape[0]
        if self.sct is None:
            self.sct = self._mss.mss()  # mss objects are thread-bound
        shot = self.sct.grab({"left": x, "top": y, "width": w, "height": h})
        time.sleep(1 / 60)
        return shot.bgra, shot.width, shot.height


IGNORE_CLASSES = {"Progman", "WorkerW", "Shell_TrayWnd", "Windows.UI.Core.CoreWindow", "SDL_app"}


def pick_window(match, exclude_hwnds):
    best, best_area = None, 0
    def cb(hwnd, _):
        nonlocal best, best_area
        if hwnd in exclude_hwnds or not win32gui.IsWindowVisible(hwnd) or win32gui.IsIconic(hwnd):
            return True
        title = win32gui.GetWindowText(hwnd)
        if not title or win32gui.GetClassName(hwnd) in IGNORE_CLASSES:
            return True
        if match != "auto" and match.lower() not in title.lower():
            return True
        l, t, r, b = win32gui.GetClientRect(hwnd)
        area = (r - l) * (b - t)
        if area > best_area:
            best, best_area = hwnd, area
        return True
    win32gui.EnumWindows(cb, None)
    return best


class WindowSource(Source):
    def __init__(self, hwnd):
        self.hwnd = hwnd
        self.label = win32gui.GetWindowText(hwnd)[:40]
        self._dc = None
        print(f"[capture] window '{self.label}'")
        super().__init__()

    def grab(self):
        if not win32gui.IsWindow(self.hwnd):
            time.sleep(0.5)
            return None
        l, t, r, b = win32gui.GetClientRect(self.hwnd)
        w, h = r - l, b - t
        if w <= 0 or h <= 0:
            time.sleep(0.2)
            return None
        hdc = win32gui.GetWindowDC(self.hwnd)
        src = win32ui.CreateDCFromHandle(hdc)
        mem = src.CreateCompatibleDC()
        bmp = win32ui.CreateBitmap()
        bmp.CreateCompatibleBitmap(src, w, h)
        mem.SelectObject(bmp)
        ctypes.windll.user32.PrintWindow(self.hwnd, mem.GetSafeHdc(), 3)  # CLIENTONLY | RENDERFULLCONTENT
        data = bmp.GetBitmapBits(True)
        win32gui.DeleteObject(bmp.GetHandle())
        mem.DeleteDC()
        src.DeleteDC()
        win32gui.ReleaseDC(self.hwnd, hdc)
        time.sleep(1 / 30)
        return data, w, h


# ------------------------------------------------------------------ math
def perspective(fov_y_deg, aspect, near=0.05, far=100.0):
    f = 1 / math.tan(math.radians(fov_y_deg) / 2)
    m = np.zeros((4, 4), dtype=np.float32)
    m[0, 0] = f / aspect
    m[1, 1] = f
    m[2, 2] = (far + near) / (near - far)
    m[2, 3] = 2 * far * near / (near - far)
    m[3, 2] = -1
    return m


def rot_y(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rot_x(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def mat4(R=np.eye(3), t=(0, 0, 0), s=(1, 1, 1)):
    m = np.eye(4, dtype=np.float32)
    m[:3, :3] = R @ np.diag(s)
    m[:3, 3] = t
    return m


# ------------------------------------------------------------------ hotkeys
VK = {"ctrl": 0x11, "alt": 0x12}


class Hotkeys:
    def __init__(self):
        self.prev = {}

    def pressed(self, vk):
        """Edge-triggered Ctrl+Alt+vk."""
        g = ctypes.windll.user32.GetAsyncKeyState
        down = bool(g(VK["ctrl"]) & 0x8000 and g(VK["alt"]) & 0x8000 and g(vk) & 0x8000)
        was = self.prev.get(vk, False)
        self.prev[vk] = down
        return down and not was

    def held(self, vk):
        g = ctypes.windll.user32.GetAsyncKeyState
        return bool(g(VK["ctrl"]) & 0x8000 and g(VK["alt"]) & 0x8000 and g(vk) & 0x8000)


# ------------------------------------------------------------------ render
VS = """
#version 330
uniform mat4 mvp;
in vec2 pos;
out vec2 uv;
void main() {
    uv = vec2(pos.x + 0.5, 0.5 - pos.y);   // images are top-down
    gl_Position = mvp * vec4(pos, 0.0, 1.0);
}
"""
FS = """
#version 330
uniform sampler2D tex;
uniform vec2 cursor;      // uv, <0 if none
uniform vec2 texsize;
uniform float has_tex;
in vec2 uv;
out vec4 color;
void main() {
    vec4 c = has_tex > 0.5 ? texture(tex, uv) : vec4(0.15, 0.15, 0.2, 1.0);
    vec2 px = uv * texsize;
    float border = min(min(px.x, texsize.x - px.x), min(px.y, texsize.y - px.y));
    if (border < 3.0) c = vec4(0.3, 0.6, 1.0, 1.0);
    if (cursor.x >= 0.0) {
        float d = length((uv - cursor) * texsize);
        if (d < 9.0) c = vec4(1.0);
        if (d < 6.0) c = vec4(0.0, 0.0, 0.0, 1.0);
    }
    color = vec4(c.rgb, 1.0);
}
"""


class Screen:
    def __init__(self, ctx, prog, vao_fn, spec, source):
        self.ctx, self.prog, self.spec, self.src = ctx, prog, spec, source
        self.vao = vao_fn(prog)
        self.tex = None
        self.seq = -1

    def upload(self):
        f = self.src.frame
        if f is None or self.src.seq == self.seq:
            return
        self.seq = self.src.seq
        data, w, h = f
        if self.tex is None or self.tex.size != (w, h):
            if self.tex:
                self.tex.release()
            self.tex = self.ctx.texture((w, h), 4)
            self.tex.swizzle = "BGRA"
            self.tex.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        self.tex.write(data)
        self.tex.build_mipmaps()

    def draw(self, vp, distance):
        aspect = (self.tex.size[1] / self.tex.size[0]) if self.tex else 9 / 16
        wm = self.spec.get("width_m", 1.2)  # physical size: farther = smaller, closer = bigger
        R = rot_y(self.spec.get("yaw", 0)) @ rot_x(self.spec.get("pitch", 0))
        model = mat4(R) @ mat4(t=(0, 0, -distance), s=(wm, wm * aspect, 1))
        self.prog["mvp"].write((vp @ model).T.astype("f4").tobytes())
        self.prog["has_tex"].value = 1.0 if self.tex else 0.0
        self.prog["texsize"].value = self.tex.size if self.tex else (1920, 1080)
        cur = (-1.0, -1.0)
        if self.src.rect and self.tex:
            x, y, w, h = self.src.rect
            cx, cy = win32api.GetCursorPos()
            if x <= cx < x + w and y <= cy < y + h:
                cur = ((cx - x) / w, (cy - y) / h)
        self.prog["cursor"].value = cur
        if self.tex:
            self.tex.use(0)
        self.vao.render(moderngl.TRIANGLE_STRIP)


class Hud:
    def __init__(self, ctx):
        self.ctx = ctx
        self.font = pygame.font.SysFont("Segoe UI", 34, bold=True)
        self.prog = ctx.program(vertex_shader="""
            #version 330
            uniform vec4 rect; in vec2 pos; out vec2 uv;
            void main(){ uv = vec2(pos.x, 1.0 - pos.y);
                gl_Position = vec4(rect.xy + pos * rect.zw, 0.0, 1.0); }""",
            fragment_shader="""
            #version 330
            uniform sampler2D tex; in vec2 uv; out vec4 color;
            void main(){ color = texture(tex, uv); }""")
        vbo = ctx.buffer(np.array([0, 0, 1, 0, 0, 1, 1, 1], dtype="f4").tobytes())
        self.vao = ctx.vertex_array(self.prog, [(vbo, "2f", "pos")])
        self.tex = None
        self.text = None

    def draw(self, lines, win_w, win_h):
        text = "\n".join(lines)
        if text != self.text:
            self.text = text
            surfs = [self.font.render(l, True, (255, 255, 120)) for l in lines]
            w = max(s.get_width() for s in surfs) + 30
            h = sum(s.get_height() for s in surfs) + 20
            surf = pygame.Surface((w, h), pygame.SRCALPHA)
            surf.fill((0, 0, 0, 255))
            y = 10
            for s in surfs:
                surf.blit(s, (15, y))
                y += s.get_height()
            if self.tex:
                self.tex.release()
            self.tex = self.ctx.texture((w, h), 4, pygame.image.tobytes(surf, "RGBA"))
        w, h = self.tex.size
        sw, sh = 2 * w / win_w, 2 * h / win_h
        self.prog["rect"].value = (-sw / 2, 0.85 - sh, sw, sh)
        self.tex.use(0)
        self.vao.render(moderngl.TRIANGLE_STRIP)


# ------------------------------------------------------------------ calibration wizard
class Wizard:
    """Turn left, then look down; derive IMU->camera axis mapping."""
    STEPS = [
        ("still", 0.0, ["Giữ yên đầu...", "Keep still"]),
        ("prep_left", 1.5, ["Chuẩn bị: QUAY ĐẦU SANG TRÁI ~45°", "Get ready: turn LEFT"]),
        ("left", 2.0, [">>> QUAY TRÁI NGAY & giữ <<<", "Turn LEFT now"]),
        ("back1", 2.0, ["Quay về giữa", "Back to center"]),
        ("prep_down", 1.5, ["Chuẩn bị: CÚI XUỐNG ~30°", "Get ready: look DOWN"]),
        ("down", 2.0, [">>> CÚI XUỐNG NGAY & giữ <<<", "Look DOWN now"]),
        ("back2", 2.0, ["Quay về giữa, nhìn thẳng", "Back to center"]),
    ]

    def __init__(self, tracker):
        self.t = tracker
        self.i = 0
        self.t0 = time.perf_counter()
        self.left = self.down = None
        self.error = None
        tracker.recalibrate_bias()

    def lines(self):
        base = ["CALIBRATION (Ctrl+Alt+C to redo)"]
        if self.error:
            base.append(self.error)
        return base + self.STEPS[self.i][2]

    def update(self):
        """Returns 3x3 imu_to_cam when finished, else None."""
        name, dur, _ = self.STEPS[self.i]
        now = time.perf_counter()
        if name == "still":
            if not self.t.bias_ready:
                return None
        elif now - self.t0 < dur:
            return None
        # leaving step `name`
        if name == "left":
            self.left = self.t.end_gesture()
        if name == "down":
            self.down = self.t.end_gesture()
        self.i += 1
        self.t0 = now
        if self.i < len(self.STEPS):
            if self.STEPS[self.i][0] in ("left", "down"):
                self.t.start_gesture()
            return None
        # finished
        if np.linalg.norm(self.left) < math.radians(15) or np.linalg.norm(self.down) < math.radians(10):
            self.error = "Chuyển động quá nhỏ, làm lại (movement too small)"
            self.i, self.t0 = 1, now
            return None
        y = self.left / np.linalg.norm(self.left)     # turning left = +rotation about camera +Y
        x = -self.down / np.linalg.norm(self.down)    # looking down = -rotation about camera +X
        x = x - y * (x @ y)
        x /= np.linalg.norm(x)
        z = np.cross(x, y)
        return np.array([x, y, z])  # rows: camera axes in IMU coordinates


# ------------------------------------------------------------------ main
def main():
    windowed = "--window" in sys.argv
    cfg = load_config()
    displays = list_displays()
    for d in displays:
        print(f"[display] {d['name']} {d['rect']} primary={d['primary']} glasses={d['glasses']} monitors={len(d['monitors'])}")
    glasses = next((d for d in displays if d["glasses"] and len(d["monitors"]) == 1), None)
    if glasses is None and not windowed:
        print("\n!! Không tìm thấy màn hình kính riêng. Nhấn Win+P -> 'Extend' rồi chạy lại.")
        print("!! Glasses display not found as a separate display. Press Win+P -> Extend.\n"
              "   (Or run with --window to preview on the laptop.)")
        if any(d["glasses"] for d in displays):
            print("   (Glasses detected but in DUPLICATE mode together with other monitors.)")
        return
    desk = [d for d in displays if not d["glasses"]] or displays
    desk.sort(key=lambda d: (not d["primary"], d["rect"][0]))

    tracker = ImuTracker()
    print(f"[imu] opened XREAL pid=0x{tracker.pid:04x}")

    pygame.init()
    if windowed:
        size = (1280, 720)
        flags = pygame.OPENGL | pygame.DOUBLEBUF
    else:
        x, y, w, h = glasses["rect"]
        os.environ["SDL_VIDEO_WINDOW_POS"] = f"{x},{y}"
        size = (w, h)
        flags = pygame.OPENGL | pygame.DOUBLEBUF | pygame.NOFRAME
    pygame.display.set_mode(size, flags, vsync=1)
    pygame.display.set_caption("XREAL virtual screens")
    hwnd = pygame.display.get_wm_info()["window"]
    if not windowed:
        win32gui.SetWindowPos(hwnd, win32con.HWND_TOPMOST, glasses["rect"][0], glasses["rect"][1], size[0], size[1],
                              win32con.SWP_NOACTIVATE)
    ctx = moderngl.create_context()
    prog = ctx.program(vertex_shader=VS, fragment_shader=FS)
    quad = ctx.buffer(np.array([-.5, -.5, .5, -.5, -.5, .5, .5, .5], dtype="f4").tobytes())
    vao_fn = lambda p: ctx.vertex_array(p, [(quad, "2f", "pos")])

    screens = []
    for spec in cfg["screens"]:
        src = None
        if spec["source"] == "monitor":
            m = spec.get("match", "primary")
            disp = (desk[0] if m == "primary" else
                    (desk[1] if len(desk) > 1 else None) if m == "secondary" else
                    next((d for d in desk if d["name"].lower().endswith(str(m).lower())), None))
            if disp:
                src = MonitorSource(disp)
        elif spec["source"] == "window":
            used = {s.src.hwnd for s in screens if isinstance(s.src, WindowSource)}
            hw = pick_window(spec.get("match", "auto"), used | {hwnd, ctypes.windll.kernel32.GetConsoleWindow()})
            if hw:
                src = WindowSource(hw)
        if src:
            screens.append(Screen(ctx, prog, vao_fn, spec, src))
        else:
            print(f"[screens] skipped {spec}")

    hud = Hud(ctx)
    keys = Hotkeys()
    M = np.array(cfg["imu_to_cam"]) if cfg.get("imu_to_cam") else None
    wizard = Wizard(tracker) if M is None else None
    if wizard is None:
        tracker.recalibrate_bias()
    show_hud = True
    head_locked = False
    walk_mode = False
    hudsys = HudSystem(ctx, cfg)
    t_prev = time.perf_counter()
    clock = pygame.time.Clock()
    hud_until = time.perf_counter() + 6
    snap_at = time.perf_counter() + 3 if "--snap" in sys.argv else None
    if snap_at and "--notif" in sys.argv:
        hudsys.next_demo_notif = time.perf_counter() + 1

    running = True
    while running:
        for e in pygame.event.get():
            if e.type == pygame.QUIT or (e.type == pygame.KEYDOWN and e.key == pygame.K_ESCAPE):
                running = False
        if keys.pressed(ord("Q")):
            running = False
        if keys.pressed(ord("R")):
            tracker.reset()
        if keys.pressed(ord("C")):
            wizard = Wizard(tracker)
        if keys.pressed(ord("B")):
            tracker.recalibrate_bias()
        if keys.pressed(ord("F")):
            head_locked = not head_locked
        if keys.pressed(ord("H")):
            show_hud = not show_hud
            hud_until = time.perf_counter() + 1e9 if show_hud else 0
        if keys.pressed(ord("M")):
            hudsys.show_map = not hudsys.show_map
        if keys.pressed(ord("W")):
            walk_mode = not walk_mode
            hudsys.notify("Chế độ đi bộ" if walk_mode else "Chế độ làm việc",
                          "Ẩn màn hình lớn, chỉ giữ bản đồ + thông tin" if walk_mode else "Hiện lại 3 màn hình", secs=2.5)
        if keys.pressed(ord("N")):
            hudsys.next_demo_notif = 0
        if keys.held(win32con.VK_UP):
            cfg["distance_m"] = min(6.0, cfg["distance_m"] * 1.01)
        if keys.held(win32con.VK_DOWN):
            cfg["distance_m"] = max(0.6, cfg["distance_m"] / 1.01)
        if keys.held(0xDD):  # ]
            cfg["fov_v_deg"] = min(60, cfg["fov_v_deg"] + 0.1)
        if keys.held(0xDB):  # [
            cfg["fov_v_deg"] = max(10, cfg["fov_v_deg"] - 0.1)

        if wizard:
            res = wizard.update()
            if res is not None:
                M = res
                cfg["imu_to_cam"] = M.round(5).tolist()
                save_config(cfg)
                print(f"[calib] imu_to_cam saved:\n{M.round(3)}")
                wizard = None
                tracker.reset()
                hud_until = time.perf_counter() + 4

        # camera orientation (cam -> world)
        C = np.eye(3)
        if M is not None and not head_locked and wizard is None and "--still" not in sys.argv:
            R = tracker.orientation()
            w = tracker.last_gyro
            R = R @ _expm_so3(w * cfg["predict_ms"] / 1000.0)  # latency prediction
            C = M @ R @ M.T
        view = mat4(C.T)
        proj = perspective(cfg["fov_v_deg"], size[0] / size[1])
        vp = proj @ view

        now = time.perf_counter()
        dt, t_prev = min(0.1, now - t_prev), now
        ctx.clear(0, 0, 0)  # black = transparent on the glasses
        if wizard is None:
            if not walk_mode:
                for s in screens:
                    s.upload()
                    s.draw(vp, cfg["distance_m"])
            w_cam = M @ tracker.last_gyro if M is not None else None
            hudsys.update(dt, w_cam)
            mode = "ĐI BỘ" if walk_mode else ("HEAD-LOCKED" if head_locked else "WORLD-LOCKED")
            hudsys.draw(vp, C, dt, mode, *size)
        lines = None
        if wizard:
            lines = wizard.lines()
        elif show_hud and time.perf_counter() < hud_until:
            lines = [f"{'HEAD-LOCKED' if head_locked else 'WORLD-LOCKED'}  imu {tracker.rate:.0f}Hz  "
                     f"render {clock.get_fps():.0f}fps",
                     "  ".join(f"{s.src.label[:18]}:{s.src.fps:.0f}" for s in screens),
                     "Ctrl+Alt+ R recenter | W walk | M map | N notif | F lock | Q quit"]
        if lines:
            ctx.enable(moderngl.BLEND)
            hud.draw(lines, *size)
            ctx.disable(moderngl.BLEND)
        if snap_at and time.perf_counter() > snap_at:
            snap_at = None
            img = pygame.image.frombytes(ctx.screen.read(components=3), size, "RGB", True)
            pygame.image.save(img, os.path.join(HERE, "snap.png"))
            print("[snap] saved snap.png")
        pygame.display.flip()
        clock.tick(120)

    cfg_saved = load_config()
    cfg_saved.update({k: cfg[k] for k in ("distance_m", "fov_v_deg", "map") if k in cfg})
    if M is not None:
        cfg_saved["imu_to_cam"] = M.round(5).tolist()
    save_config(cfg_saved)
    for s in screens:
        s.src.stop()
    tracker.close()
    pygame.quit()


if __name__ == "__main__":
    main()
