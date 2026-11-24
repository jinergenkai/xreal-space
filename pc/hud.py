"""
HUD layers for the glasses:
  - MapPanel: navigation mini-map (mock route), lazy-follows your head, bottom-left.
  - Info bar: head-locked clock / battery / mode, top-right.
  - Toasts: notifications + gesture feedback, head-locked.
  - HeadGestures: nod = OK, shake = dismiss (from gyro).

Designed for an ADDITIVE see-through display: black = transparent, so we draw bright
thin strokes on black and blend additively (no opaque panels).
"""
import ctypes
import math
import time

import numpy as np
import pygame
import moderngl

CYAN = (0, 215, 255)
CYAN_DIM = (0, 70, 90)
WHITE = (235, 245, 255)
STREET = (55, 85, 115)
STREET_MAIN = (85, 120, 155)
WATER = (15, 40, 75)
AMBER = (255, 185, 60)
GREEN = (90, 255, 150)
RED = (255, 90, 90)
GREY = (150, 165, 180)


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


def wrap180(a):
    return (a + 180) % 360 - 180


def battery():
    class SPS(ctypes.Structure):
        _fields_ = [("AC", ctypes.c_ubyte), ("Flag", ctypes.c_ubyte), ("Pct", ctypes.c_ubyte),
                    ("Saver", ctypes.c_ubyte), ("Life", ctypes.c_ulong), ("Full", ctypes.c_ulong)]
    s = SPS()
    if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)) and s.Pct != 255:
        return s.Pct, s.AC == 1
    return None, False


# ------------------------------------------------------------------ GL helpers
WORLD_VS = """
#version 330
uniform mat4 mvp; in vec2 pos; out vec2 uv;
void main(){ uv = vec2(pos.x + 0.5, 0.5 - pos.y); gl_Position = mvp * vec4(pos, 0.0, 1.0); }
"""
NDC_VS = """
#version 330
uniform vec4 rect; in vec2 pos; out vec2 uv;
void main(){ vec2 p = pos + 0.5; uv = vec2(p.x, 1.0 - p.y); gl_Position = vec4(rect.xy + p * rect.zw, 0.0, 1.0); }
"""
TEX_FS = """
#version 330
uniform sampler2D tex; uniform float alpha; in vec2 uv; out vec4 color;
void main(){ vec4 c = texture(tex, uv); color = vec4(c.rgb, c.a * alpha); }
"""


class SurfaceQuad:
    """A pygame Surface uploaded to a texture; draw in world space or NDC (additive)."""

    def __init__(self, ctx, w, h, progs):
        self.ctx = ctx
        self.surf = pygame.Surface((w, h), pygame.SRCALPHA)
        self.tex = ctx.texture((w, h), 4)
        self.tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.world_prog, self.ndc_prog, self.vao_w, self.vao_n = progs

    def upload(self):
        self.tex.write(pygame.image.tobytes(self.surf, "RGBA"))

    def draw_world(self, mvp, alpha=1.0):
        self.world_prog["mvp"].write(mvp.T.astype("f4").tobytes())
        self.world_prog["alpha"].value = alpha
        self.tex.use(0)
        self.vao_w.render(moderngl.TRIANGLE_STRIP)

    def draw_ndc(self, x, y, win_w, win_h, alpha=1.0, scale=1.0):
        """x,y = top-left in NDC."""
        w, h = self.surf.get_size()
        sw, sh = 2 * w * scale / win_w, 2 * h * scale / win_h
        self.ndc_prog["rect"].value = (x, y - sh, sw, sh)
        self.ndc_prog["alpha"].value = alpha
        self.tex.use(0)
        self.vao_n.render(moderngl.TRIANGLE_STRIP)


# ------------------------------------------------------------------ mock navigation
class MockRoute:
    """A fake city + walking route so we can design the HUD without GPS."""

    def __init__(self):
        self.streets = []
        for i in range(-8, 9):
            main = i % 4 == 0
            self.streets.append((((i * 90, -800), (i * 90, 800)), main))
            self.streets.append((((-800, i * 90), (800, i * 90)), main))
        self.streets.append((((-700, -500), (700, 600)), True))  # diagonal avenue
        self.river = [(x, 500 + 60 * math.sin(x / 140)) for x in range(-800, 801, 40)]
        self.route = [(0, -360), (0, -90), (180, -90), (180, 180), (360, 180), (360, 450)]
        self.names = ["Lê Lợi", "Nguyễn Huệ", "Hai Bà Trưng", "Đồng Khởi", "Tôn Đức Thắng"]
        segs = np.diff(np.array(self.route, float), axis=0)
        self.seg_len = np.linalg.norm(segs, axis=1)
        self.total = float(self.seg_len.sum())
        self.s = 0.0
        self.speed = 6.0  # m/s, sped-up walk for the demo

    def update(self, dt):
        self.s = (self.s + self.speed * dt) % self.total

    def state(self):
        s = self.s
        for i, L in enumerate(self.seg_len):
            if s <= L:
                a, b = np.array(self.route[i], float), np.array(self.route[i + 1], float)
                d = (b - a) / L
                pos = a + d * s
                heading = math.degrees(math.atan2(d[0], d[1]))  # 0 = north(+y), cw positive
                turn, nxt = None, None
                if i + 1 < len(self.seg_len):
                    c = np.array(self.route[i + 2], float)
                    d2 = (c - b) / np.linalg.norm(c - b)
                    cross = d[0] * d2[1] - d[1] * d2[0]
                    turn = "left" if cross > 0 else "right"
                    nxt = self.names[i + 1]
                remain = self.total - self.s
                return dict(pos=pos, heading=heading, seg=i, to_turn=L - s, turn=turn,
                            street=self.names[i], next_street=nxt, remain=remain)
            s -= L
        return self.state()


# ------------------------------------------------------------------ map panel
class MapPanel:
    W, H = 440, 600
    MAP = 400

    def __init__(self, ctx, progs, cfg):
        self.q = SurfaceQuad(ctx, self.W, self.H, progs)
        self.route = MockRoute()
        self.cfg = cfg
        pygame.font.init()
        self.f_big = pygame.font.SysFont("Segoe UI", 54, bold=True)
        self.f_mid = pygame.font.SysFont("Segoe UI", 30, bold=True)
        self.f_small = pygame.font.SysFont("Segoe UI", 24)
        self.f_n = pygame.font.SysFont("Segoe UI", 22, bold=True)
        r = self.MAP // 2
        self.mask = pygame.Surface((self.MAP, self.MAP))
        self.mask.fill((0, 0, 0))
        self.mask.set_colorkey((255, 0, 255))
        pygame.draw.circle(self.mask, (255, 0, 255), (r, r), r - 4)
        self.follow_yaw = None
        self.follow_pitch = 0.0

    def follow(self, C, dt):
        """Lazy follow: only moves when you look away more than a dead zone."""
        f = C @ np.array([0, 0, -1.0])
        yaw = math.degrees(math.atan2(-f[0], -f[2]))
        pitch = math.degrees(math.asin(max(-1, min(1, f[1]))))
        if self.follow_yaw is None:
            self.follow_yaw, self.follow_pitch = yaw, pitch
        dead, k = self.cfg.get("follow_deadzone_deg", 12), min(1.0, 3.0 * dt)
        dy = wrap180(yaw - self.follow_yaw)
        if abs(dy) > dead:
            self.follow_yaw += (dy - math.copysign(dead, dy)) * k
        dp = pitch - self.follow_pitch
        if abs(dp) > dead * 0.6:
            self.follow_pitch += (dp - math.copysign(dead * 0.6, dp)) * k
        return rot_y(self.follow_yaw) @ rot_x(self.follow_pitch)

    def render(self, dt):
        self.route.update(dt)
        st = self.route.state()
        s = self.q.surf
        s.fill((0, 0, 0, 0))
        pygame.draw.circle(s, (0, 0, 0, 235), (self.W // 2, self.MAP // 2), self.MAP // 2)
        pygame.draw.rect(s, (0, 0, 0, 235), (0, self.MAP + 4, self.W, self.H - self.MAP - 4), border_radius=22)
        m = pygame.Surface((self.MAP, self.MAP))
        m.fill((0, 0, 0))
        cx, cy = self.MAP / 2, self.MAP * 0.62
        scale = 0.75  # px per meter
        th = math.radians(st["heading"])
        hx, hy = math.sin(th), math.cos(th)
        rx, ry = math.cos(th), -math.sin(th)
        px, py = st["pos"]

        def P(p):
            dx, dy = p[0] - px, p[1] - py
            return (cx + (dx * rx + dy * ry) * scale, cy - (dx * hx + dy * hy) * scale)

        pygame.draw.lines(m, WATER, False, [P(p) for p in self.route.river], 16)
        for (a, b), main in self.route.streets:
            pygame.draw.line(m, STREET_MAIN if main else STREET, P(a), P(b), 4 if main else 2)
        # route: travelled part dim, remaining bright
        pts = self.route.route
        seg = st["seg"]
        done = [P(p) for p in pts[: seg + 1]] + [P(st["pos"])]
        todo = [P(st["pos"])] + [P(p) for p in pts[seg + 1:]]
        if len(done) > 1:
            pygame.draw.lines(m, CYAN_DIM, False, done, 8)
        if len(todo) > 1:
            pygame.draw.lines(m, CYAN, False, todo, 9)
            pygame.draw.circle(m, GREEN, todo[-1], 10, 3)
        # user arrow
        pygame.draw.circle(m, (0, 60, 90), (cx, cy), 26)
        pygame.draw.polygon(m, WHITE, [(cx, cy - 18), (cx - 13, cy + 13), (cx, cy + 6), (cx + 13, cy + 13)])
        m.blit(self.mask, (0, 0))
        m.set_colorkey((0, 0, 0))
        r = self.MAP / 2
        pygame.draw.circle(m, STREET_MAIN, (r, r), r - 4, 2)
        # north marker on the ring
        nang = -th  # north direction rotated into heading-up frame
        nx, ny = r + math.sin(nang) * (r - 22), r - math.cos(nang) * (r - 22)
        t = self.f_n.render("N", True, AMBER)
        m.blit(t, t.get_rect(center=(nx, ny)))
        s.blit(m, ((self.W - self.MAP) // 2, 0))

        # turn card
        y0 = self.MAP + 12
        self._turn_icon(s, (52, y0 + 48), st["turn"])
        dist = st["to_turn"]
        dtxt = f"{dist:.0f} m" if dist < 1000 else f"{dist / 1000:.1f} km"
        s.blit(self.f_big.render(dtxt, True, WHITE), (110, y0 - 2))
        street = st["next_street"] or "Điểm đến"
        verb = {"left": "Rẽ trái vào", "right": "Rẽ phải vào", None: "Đến"}[st["turn"]]
        s.blit(self.f_small.render(f"{verb} {street}", True, CYAN), (112, y0 + 66))
        mins = st["remain"] / 1.4 / 60
        eta = time.strftime("%H:%M", time.localtime(time.time() + mins * 60))
        s.blit(self.f_small.render(f"{mins:.0f} phút · {st['remain'] / 1000:.1f} km · đến {eta}",
                                   True, GREY), (20, y0 + 120))
        pygame.draw.line(s, STREET, (20, y0 + 108), (self.W - 20, y0 + 108), 1)
        self.q.upload()

    def _turn_icon(self, s, c, turn):
        x, y = c
        pygame.draw.line(s, WHITE, (x, y + 36), (x, y - 6), 8)
        if turn is None:
            pygame.draw.polygon(s, WHITE, [(x - 18, y - 4), (x + 18, y - 4), (x, y - 30)])
            return
        sgn = -1 if turn == "left" else 1
        pygame.draw.line(s, WHITE, (x, y - 6), (x + sgn * 26, y - 6), 8)
        pygame.draw.polygon(s, WHITE, [(x + sgn * 24, y - 24), (x + sgn * 24, y + 12), (x + sgn * 44, y - 6)])

    def draw(self, vp, C, dt):
        F = self.follow(C, dt)
        c = self.cfg
        w = c.get("width_m", 0.22)
        h = w * self.H / self.W
        R = F @ rot_y(c.get("yaw", 15)) @ rot_x(c.get("pitch", -4))
        model = mat4(R) @ mat4(t=(0, 0, -c.get("distance_m", 1.5)), s=(w, h, 1))
        self.q.draw_world(vp @ model)


# ------------------------------------------------------------------ head gestures
class HeadGestures:
    """Nod (down-up) -> 'nod', shake (left-right-left) -> 'shake'. Input: camera-frame gyro rad/s."""

    def __init__(self, nod_dps=80, shake_dps=90):
        self.nod_t = math.radians(nod_dps)
        self.shake_t = math.radians(shake_dps)
        self.down_at = -1
        self.flips = []
        self.cool_until = 0

    def update(self, w):
        now = time.perf_counter()
        if now < self.cool_until:
            return None
        wx, wy = w[0], w[1]
        if wx < -self.nod_t and abs(wy) < 0.6 * abs(wx):
            self.down_at = now
        if wx > self.nod_t and now - self.down_at < 0.6 and abs(wy) < 0.6 * abs(wx):
            self.down_at, self.cool_until, self.flips = -1, now + 1.0, []
            return "nod"
        if abs(wy) > self.shake_t and abs(wx) < 0.7 * abs(wy):
            sgn = 1 if wy > 0 else -1
            if not self.flips or self.flips[-1][1] != sgn:
                self.flips.append((now, sgn))
        self.flips = [f for f in self.flips if now - f[0] < 0.9]
        if len(self.flips) >= 3:
            self.flips, self.cool_until, self.down_at = [], now + 1.0, -1
            return "shake"
        return None


# ------------------------------------------------------------------ system
class HudSystem:
    def __init__(self, ctx, cfg):
        self.ctx = ctx
        self.cfg = cfg
        wp = ctx.program(vertex_shader=WORLD_VS, fragment_shader=TEX_FS)
        npg = ctx.program(vertex_shader=NDC_VS, fragment_shader=TEX_FS)
        quad = ctx.buffer(np.array([-.5, -.5, .5, -.5, -.5, .5, .5, .5], dtype="f4").tobytes())
        progs = (wp, npg, ctx.vertex_array(wp, [(quad, "2f", "pos")]), ctx.vertex_array(npg, [(quad, "2f", "pos")]))
        self.map = MapPanel(ctx, progs, cfg.setdefault("map", {}))
        self.info = SurfaceQuad(ctx, 430, 92, progs)
        self.toast_q = SurfaceQuad(ctx, 760, 120, progs)
        self.f_clock = pygame.font.SysFont("Segoe UI", 46, bold=True)
        self.f_small = pygame.font.SysFont("Segoe UI", 22)
        self.f_toast = pygame.font.SysFont("Segoe UI", 30, bold=True)
        self.gestures = HeadGestures()
        self.show_map = True
        self.toast = None          # (title, body, color, until)
        self.notif_pending = False
        self.next_demo_notif = time.perf_counter() + 20
        self._info_key = None
        self._toast_key = None

    def notify(self, title, body, color=CYAN, secs=6.0, actionable=False):
        self.toast = (title, body, color, time.perf_counter() + secs)
        self.notif_pending = actionable

    def update(self, dt, w_cam):
        now = time.perf_counter()
        if now > self.next_demo_notif:
            self.next_demo_notif = now + 45
            self.notify("Tin nhắn · An", "Tối nay đi ăn không?   (gật = OK · lắc = bỏ qua)", AMBER, 10, True)
        g = self.gestures.update(w_cam) if w_cam is not None else None
        if g == "nod":
            if self.notif_pending:
                self.notify("Đã trả lời", "\"OK, 7h nhé!\"", GREEN, 2.5)
            else:
                self.show_map = not self.show_map
                self.notify("Gật đầu", "Bản đồ: " + ("bật" if self.show_map else "tắt"), GREEN, 1.5)
        elif g == "shake":
            self.notify("Lắc đầu", "Đã bỏ qua" if self.notif_pending else "Đóng", RED, 1.5)
        return g

    def _render_info(self, mode):
        pct, ac = battery()
        clock = time.strftime("%H:%M")
        key = (clock, pct, ac, mode)
        if key == self._info_key:
            return
        self._info_key = key
        s = self.info.surf
        s.fill((0, 0, 0, 0))
        pygame.draw.rect(s, (0, 0, 0, 220), s.get_rect(), border_radius=18)
        t = self.f_clock.render(clock, True, WHITE)
        s.blit(t, (s.get_width() - t.get_width() - 12, 2))
        bx, by = 14, 18
        if pct is not None:
            col = GREEN if pct > 30 else AMBER if pct > 15 else RED
            pygame.draw.rect(s, GREY, (bx, by, 54, 24), 2, border_radius=4)
            pygame.draw.rect(s, GREY, (bx + 54, by + 7, 4, 10))
            pygame.draw.rect(s, col, (bx + 4, by + 4, int(46 * pct / 100), 16), border_radius=2)
            s.blit(self.f_small.render(f"{pct}%" + (" · sạc" if ac else ""), True, GREY), (bx + 66, by - 2))
        s.blit(self.f_small.render(mode, True, CYAN), (bx, by + 36))
        self.info.upload()

    def _render_toast(self):
        title, body, color, until = self.toast
        key = (title, body)
        if key == self._toast_key:
            return
        self._toast_key = key
        s = self.toast_q.surf
        s.fill((0, 0, 0, 0))
        pygame.draw.rect(s, (0, 0, 0, 235), s.get_rect(), border_radius=18)
        pygame.draw.rect(s, color, (0, 8, 6, s.get_height() - 16), border_radius=3)
        s.blit(self.f_toast.render(title, True, color), (22, 10))
        s.blit(self.f_small.render(body, True, WHITE), (22, 62))
        self.toast_q.upload()

    def draw(self, vp, C, dt, mode, win_w, win_h):
        ctx = self.ctx
        ctx.enable(moderngl.BLEND)
        # black backgrounds occlude the virtual screens behind (black = see-through on the glasses)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        if self.show_map:
            self.map.render(dt)
            self.map.draw(vp, C, dt)
        self._render_info(mode)
        self.info.draw_ndc(1 - 2 * 450 / win_w, 0.96, win_w, win_h)
        if self.toast:
            if time.perf_counter() > self.toast[3]:
                self.toast, self.notif_pending, self._toast_key = None, False, None
            else:
                self._render_toast()
                self.toast_q.draw_ndc(-2 * 380 / win_w, 0.40, win_w, win_h)
        ctx.disable(moderngl.BLEND)
