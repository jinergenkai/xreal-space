"""
CLI entry point:  python -m xrair <command>

  doctor   kiểm tra môi trường (Python, hidapi, màn hình, kính)        [read-only]
  probe    liệt kê mọi HID interface của kính, thử mở từng cái          [read-only]
  info     đọc serial / firmware / độ sáng / display mode / IMU rate     [lệnh đọc an toàn]
  imu      in yaw/pitch/roll realtime, tùy chọn --record file.csv
  axes     dò trục cảm biến (gợi ý --forward / --flip-*) cho demo
  mcu      đọc/ghi độ sáng, nghe sự kiện nút bấm
  demo     app panel ghim trong không gian (--source glasses|mouse|replay)
"""
from __future__ import annotations

import argparse
import json
import math
import platform
import sys
import time

from . import protocol as P

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


def cmd_doctor(a):
    ok = True
    print(f"Python      : {sys.version.split()[0]} ({platform.system()} {platform.release()})")
    if sys.version_info < (3, 9):
        print("  !! Cần Python >= 3.9"); ok = False
    try:
        import hid  # noqa
        print(f"hidapi      : OK ({getattr(hid, '__version__', 'unknown version')})")
    except ImportError:
        print("hidapi      : THIẾU  -> pip install hidapi"); ok = False
    try:
        import tkinter
        r = tkinter.Tk(); r.withdraw()
        from .monitors import list_monitors, guess_glasses_monitor
        mons = list_monitors(r)
        r.destroy()
        print("Màn hình    :")
        for m in mons:
            print(f"   {m}")
        g = guess_glasses_monitor(mons)
        if len(mons) == 1:
            print("   (chỉ 1 màn hình -> kính KHÔNG có hình qua cổng này; demo sẽ chạy cửa sổ trên laptop)")
        else:
            print(f"   đoán màn hình kính: #{g.index}")
    except Exception as e:
        print(f"tkinter     : lỗi ({e}) -> demo app sẽ không chạy"); ok = False
    if ok:
        cmd_probe(a, open_test=False)
    print("\nKết luận:", "OK" if ok else "CẦN SỬA môi trường trước")


def cmd_probe(a, open_test=True):
    from .device import enumerate_glasses, HidChannel
    infos = enumerate_glasses()
    if not infos:
        print("\nKhông thấy thiết bị nào có VID 0x3318 (XREAL).")
        print("  - Thử cáp/cổng khác. Cổng USB-A có thể không đủ điện cho kính.")
        print("  - Kiểm tra Device Manager > Human Interface Devices.")
        return 1
    print(f"\nTìm thấy {len(infos)} HID interface của XREAL:")
    print(f"  {'PID':>6} {'model':<18} {'iface':>5} {'usage_page':>10} {'usage':>6}  role")
    for i in infos:
        role = ""
        if i.pid in P.MODELS:
            if i.interface == P.imu_interface(i.pid):
                role = "IMU"
            elif i.interface == P.mcu_interface(i.pid):
                role = "MCU"
        print(f"  0x{i.pid:04x} {i.model:<18} {i.interface:>5} 0x{i.usage_page:04x}     0x{i.usage:02x}  {role}")
    if open_test:
        print("\nThử mở từng interface (không gửi lệnh):")
        for i in infos:
            try:
                ch = HidChannel(i); ch.close()
                print(f"  iface {i.interface} usage_page 0x{i.usage_page:04x}: mở OK")
            except Exception as e:
                print(f"  iface {i.interface} usage_page 0x{i.usage_page:04x}: KHÔNG mở được ({e})")
    return 0


def cmd_info(a):
    from .device import AirGlasses
    g = AirGlasses(verbose=a.verbose)
    print(f"Model       : {g.model} (PID 0x{g.pid:04x})")
    if g.mcu:
        print(f"Serial      : {g.get_serial()}")
        print(f"Firmware    : {g.get_firmware()}")
        b = g.get_brightness()
        print(f"Độ sáng     : {b} / 8")
        dm = g.get_display_mode()
        print(f"Display mode: {dm} ({P.DISPLAY_MODES.get(dm, '?')})")
    else:
        print("MCU         : không thấy interface")
    print("IMU         : handshake (stop -> calibration -> start)...")
    g.start_imu(read_calibration=True)
    if g.calibration:
        with open("calibration.json", "w", encoding="utf-8") as f:
            json.dump(g.calibration, f, indent=2, ensure_ascii=False)
        print("              calibration.json đã lưu")
    else:
        print("              (không đọc được calibration — không sao)")
    first = []
    g.on_sample = lambda s: first.append(s) if len(first) < 1 else None
    g.run_background(listen_mcu=False)
    time.sleep(2.0)
    n = g.sample_count
    g.close()
    print(f"IMU rate    : {n / 2.0:.0f} gói/giây ({n} gói trong 2s)")
    if first:
        s = first[0]
        print(f"Mẫu đầu     : gyro {tuple(round(x, 2) for x in s.gyro_dps)} dps | "
              f"accel {tuple(round(x, 3) for x in s.accel_g)} g | {s.temperature_c:.1f}°C")
        mag = math.sqrt(sum(x * x for x in s.accel_g))
        print(f"|accel|     : {mag:.3f} g  ({'OK ~1g khi để yên' if 0.85 < mag < 1.15 else 'LẠ — kiểm tra lại'})")
    print("\nKẾT QUẢ:", "PASS" if n > 50 else "FAIL (không nhận được dòng IMU)")


def cmd_imu(a):
    from .fusion import HeadTracker
    from .device import AirGlasses
    from .sources import CSV_HEADER
    import csv
    tr = HeadTracker(forward_axis=a.forward, flip_yaw=a.flip_yaw, flip_pitch=a.flip_pitch)
    g = AirGlasses(verbose=a.verbose)
    rec = None
    if a.record:
        f = open(a.record, "w", newline="")
        rec = (f, csv.writer(f)); rec[1].writerow(CSV_HEADER)
    last = {}

    def on(s):
        tr.feed(s.timestamp_ns, s.gyro_dps, s.accel_g)
        last["s"] = s
        if rec:
            rec[1].writerow([s.timestamp_ns, *s.gyro_dps, *s.accel_g, *s.mag_raw, round(s.temperature_c, 2)])

    g.on_sample = on
    g.start_imu(read_calibration=False)
    g.run_background(listen_mcu=False)
    print("Đặt kính nằm yên trên bàn (hoặc giữ yên đầu) để hiệu chỉnh... (Ctrl+C để dừng)")
    t_end = time.time() + a.seconds if a.seconds else None
    yaw_start = None
    try:
        while t_end is None or time.time() < t_end:
            time.sleep(0.1)
            s = last.get("s")
            if not s:
                continue
            if tr.calibrating:
                print(f"\r  {tr.status:<60}", end="")
                if tr.warning:
                    print("\n  !!", tr.warning); tr.warning = ""
                continue
            p = tr.pose
            if yaw_start is None:
                yaw_start = (time.time(), p.yaw)
            line = f"\r  yaw {p.yaw:+7.1f}  pitch {p.pitch:+6.1f}  roll {p.roll:+6.1f}  | {tr.rate_hz:4.0f} Hz"
            if a.raw:
                line += f" | g {s.gyro_dps[0]:+7.1f} {s.gyro_dps[1]:+7.1f} {s.gyro_dps[2]:+7.1f}" \
                        f" a {s.accel_g[0]:+.2f} {s.accel_g[1]:+.2f} {s.accel_g[2]:+.2f}"
            print(line + "   ", end="")
            if tr.warning:
                print("\n  !!", tr.warning); tr.warning = ""
    except KeyboardInterrupt:
        pass
    finally:
        print()
        if yaw_start and a.still:
            dt_s = time.time() - yaw_start[0]
            dyaw = tr.pose.yaw - yaw_start[1]
            rate = dyaw / dt_s * 60 if dt_s > 0 else 0
            print(f"Drift: yaw thay đổi {dyaw:+.2f}° trong {dt_s:.0f}s = {rate:+.1f}°/phút "
                  f"-> {'OK (< 3°/phút)' if abs(rate) < 3 else 'CAO'}")
        g.close()
        if rec:
            rec[0].close(); print(f"Đã ghi {a.record}")


def cmd_axes(a):
    """Guided axis finder: gravity gives 'up'; nodding UP first gives 'right' with sign."""
    from .device import AirGlasses
    g = AirGlasses(verbose=a.verbose)
    buf = []
    g.on_sample = lambda s: buf.append(s)
    g.start_imu(read_calibration=False)
    g.run_background(listen_mcu=False)
    names = ["x", "y", "z"]
    try:
        def countdown(msg, n=3):
            for k in range(n, 0, -1):
                print(f"\r{msg} ... {k} ", end="", flush=True); time.sleep(1)
            print()
        countdown("1) Đeo kính, nhìn thẳng, GIỮ YÊN đầu. Bắt đầu đo sau", 4)
        buf.clear(); time.sleep(1.5)
        acc = [sum(s.accel_g[i] for s in buf) / max(1, len(buf)) for i in range(3)]
        bias = [sum(s.gyro_dps[i] for s in buf) / max(1, len(buf)) for i in range(3)]
        up_i = max(range(3), key=lambda i: abs(acc[i]))
        up_sign = 1 if acc[up_i] > 0 else -1
        print(f"   accel trung bình {['%.2f' % v for v in acc]} -> trục UP = {'+' if up_sign > 0 else '-'}{names[up_i]}")
        countdown("2) Chuẩn bị NGẨNG ĐẦU LÊN (nhìn trần nhà) khi đếm về 0", 3)
        print("   >>> NGẨNG LÊN NGAY <<<")
        buf.clear(); time.sleep(1.5)
        best_i, best_v = 0, 0.0
        for i in range(3):
            if i == up_i:
                continue
            v = sum(s.gyro_dps[i] - bias[i] for s in buf)
            if abs(v) > abs(best_v):
                best_i, best_v = i, v
        deg = best_v / max(1, len(buf)) * 1.5  # approx degrees rotated
        if abs(deg) < 8:
            print(f"   !! Gần như không thấy chuyển động (~{deg:.1f}°). Chạy lại và ngẩng đầu rõ hơn.")
            return 1
        right_sign = 1 if best_v > 0 else -1
        print(f"   trục RIGHT = {'+' if right_sign > 0 else '-'}{names[best_i]}  (tổng {best_v:.0f})")
        up = [0, 0, 0]; up[up_i] = up_sign
        rt = [0, 0, 0]; rt[best_i] = right_sign
        fwd = (up[1] * rt[2] - up[2] * rt[1], up[2] * rt[0] - up[0] * rt[2], up[0] * rt[1] - up[1] * rt[0])
        fi = max(range(3), key=lambda i: abs(fwd[i]))
        forward = f"{'+' if fwd[fi] > 0 else '-'}{names[fi]}"
        an = max(1e-6, math.sqrt(sum(v * v for v in acc)))
        tilt = math.degrees(math.asin(max(-1.0, min(1.0, fwd[fi] * acc[fi] / an))))
        print(f"   độ nghiêng cảm biến so với phương ngang khi nhìn thẳng: ~{tilt:+.0f}° (sẽ được bù khi recenter)")
        print(f"\n=> Dùng: --forward {forward}")
        print("   Sau đó chạy `python -m xrair imu --forward", forward,
              "` : quay phải -> yaw tăng, ngẩng lên -> pitch tăng. Nếu ngược thì thêm --flip-yaw / --flip-pitch.")
    finally:
        g.close()


def cmd_mcu(a):
    from .device import AirGlasses
    g = AirGlasses(verbose=a.verbose)
    if not g.mcu:
        print("Không có MCU interface"); return 1
    try:
        print(f"Độ sáng hiện tại: {g.get_brightness()}")
        if a.set_brightness is not None:
            ok = g.set_brightness(a.set_brightness)
            print(f"Đặt độ sáng {a.set_brightness}: {'OK' if ok else 'không xác nhận'} -> đọc lại: {g.get_brightness()}")
        if a.listen:
            print(f"Nghe sự kiện {a.listen}s — bấm các nút trên gọng kính...")
            g.on_mcu_event = lambda p: print(
                f"   event 0x{p.cmd_id:04x} data {P.hexdump(p.data, 12)}"
                + (f"  -> NÚT code {p.data[0]} ({P.KEY_NAMES.get(p.data[0], '?')})" if p.cmd_id == P.MCU_EVT_KEY and p.data else ""))
            g.run_background(listen_mcu=True)
            time.sleep(a.listen)
    finally:
        g._stop.set()
        g.mcu.close()
        g.imu.close()


def cmd_demo(a):
    from .demo import run_demo
    run_demo(a.source, replay=a.replay, forward=a.forward, flip_yaw=a.flip_yaw, flip_pitch=a.flip_pitch,
             hfov=a.hfov, windowed=a.windowed, monitor=a.monitor, record=a.record, verbose=a.verbose)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m xrair", description="XREAL Air 2 lab tools")
    ap.add_argument("-v", "--verbose", action="store_true", help="in mọi frame gửi/nhận")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("doctor")
    sub.add_parser("probe")
    sub.add_parser("info")

    def pose_opts(p):
        p.add_argument("--forward", default="-y", choices=["+x", "-x", "+y", "-y", "+z", "-z"])
        p.add_argument("--flip-yaw", action="store_true")
        p.add_argument("--flip-pitch", action="store_true")

    p = sub.add_parser("imu"); pose_opts(p)
    p.add_argument("--record", help="ghi raw IMU ra CSV")
    p.add_argument("--raw", action="store_true", help="in thêm gyro/accel thô")
    p.add_argument("--seconds", type=float, default=0)
    p.add_argument("--still", action="store_true", help="đo drift: để kính nằm yên suốt thời gian chạy")

    sub.add_parser("axes")

    p = sub.add_parser("mcu")
    p.add_argument("--set-brightness", type=int, choices=range(0, 9))
    p.add_argument("--listen", type=float, default=0, help="số giây nghe sự kiện nút")

    p = sub.add_parser("demo"); pose_opts(p)
    p.add_argument("--source", default="glasses", choices=["glasses", "mouse", "replay"])
    p.add_argument("--replay", help="file CSV từ `imu --record`")
    p.add_argument("--hfov", type=float, default=40.0, help="FOV ngang (độ), Air 2 ~40")
    p.add_argument("--windowed", action="store_true", help="chạy cửa sổ thay vì full-screen")
    p.add_argument("--monitor", type=int, help="index màn hình (xem `doctor`)")
    p.add_argument("--record", help="ghi raw IMU ra CSV khi chạy demo")

    a = ap.parse_args(argv)
    if a.cmd == "demo" and a.source == "replay" and not a.replay:
        ap.error("--source replay cần --replay file.csv")
    fn = {"doctor": cmd_doctor, "probe": cmd_probe, "info": cmd_info, "imu": cmd_imu,
          "axes": cmd_axes, "mcu": cmd_mcu, "demo": cmd_demo}[a.cmd]
    try:
        return fn(a) or 0
    except Exception as e:
        from .device import GlassesError
        if isinstance(e, GlassesError):
            print(f"\nLỖI: {e}")
            return 2
        raise


if __name__ == "__main__":
    sys.exit(main())
