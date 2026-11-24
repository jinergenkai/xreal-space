"""List monitors without extra dependencies (Windows via ctypes; other OS: primary only)."""
from __future__ import annotations

import sys
from dataclasses import dataclass
from typing import List


@dataclass
class Monitor:
    index: int
    x: int
    y: int
    w: int
    h: int
    primary: bool

    def __str__(self):
        return f"#{self.index}: {self.w}x{self.h} at ({self.x},{self.y}){' [primary]' if self.primary else ''}"


def list_monitors(tk_root=None) -> List[Monitor]:
    if sys.platform.startswith("win"):
        try:
            import ctypes
            from ctypes import wintypes
            user32 = ctypes.windll.user32
            try:
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception:
                pass
            mons = []

            class MONITORINFO(ctypes.Structure):
                _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                            ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]

            def cb(hmon, hdc, lprc, data):
                mi = MONITORINFO()
                mi.cbSize = ctypes.sizeof(MONITORINFO)
                user32.GetMonitorInfoW(hmon, ctypes.byref(mi))
                r = mi.rcMonitor
                mons.append(Monitor(len(mons), r.left, r.top, r.right - r.left, r.bottom - r.top,
                                    bool(mi.dwFlags & 1)))
                return True

            proc = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p,
                                      ctypes.POINTER(wintypes.RECT), ctypes.c_double)
            user32.EnumDisplayMonitors(0, 0, proc(cb), 0)
            if mons:
                return mons
        except Exception:
            pass
    if tk_root is not None:
        return [Monitor(0, 0, 0, tk_root.winfo_screenwidth(), tk_root.winfo_screenheight(), True)]
    return [Monitor(0, 0, 0, 1920, 1080, True)]


def guess_glasses_monitor(mons: List[Monitor]) -> Monitor:
    """XREAL Air shows up as a 1920x1080 non-primary display (3840x1080 in SBS)."""
    for m in mons:
        if not m.primary and (m.w, m.h) in ((1920, 1080), (3840, 1080)):
            return m
    for m in mons:
        if not m.primary:
            return m
    return mons[0]
