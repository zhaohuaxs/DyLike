#!/usr/bin/env python3
"""
[SPIKE] 超分验证：同帧 dump + SSIM/锐度对比

用法：
  1. 手机端：播放视频，暂停在某一帧（细节多的画面）
  2. 在 PC 运行：python spike_compare.py

脚本会：
  1. 通过 adb 发广播让 app 用 shader dump 当前帧 -> frame_with_shader.png
  2. 等待 dump 完成（监听文件出现）
  3. 发广播关闭 shader，等 mpv 重渲染
  4. 再 dump 同一帧 -> frame_baseline.png
  5. 发广播重新开启 shader（恢复原状）
  6. adb pull 两张图，计算 SSIM + 锐度 + 平均像素差
  7. 输出量化报告
"""
import subprocess
import time
import os
import sys
from pathlib import Path

ADB = r"C:\Android\adb.exe"
PKG = "me.lingci.dy.player.debug"
ACTION_DUMP = "me.lingci.dy.player.spike.DUMP"
ACTION_OFF = "me.lingci.dy.player.spike.SHADER_OFF"
ACTION_ON = "me.lingci.dy.player.spike.SHADER_ON"
REMOTE_DIR = f"/data/data/{PKG}/files/shots"

OUT_DIR = Path(__file__).parent / "_spike_compare"
OUT_DIR.mkdir(exist_ok=True)


def adb(*args, timeout=30):
    """Run adb command, return stdout."""
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr


def broadcast(action, extra_name=None, extra_value=None):
    """Send a broadcast to the app."""
    cmd = ["shell", "am", "broadcast", "-a", action, "-n", f"{PKG}/.ui.long_video.LongVideoActivity"]
    # NOTE: 上面的 -n 是错的 —— receiver 是动态注册的，没有组件名。改用隐式广播。
    cmd = ["shell", "am", "broadcast", "-a", action]
    if extra_name:
        cmd += ["--es", extra_name, extra_value]
    code, out, err = adb(*cmd)
    return out


def wait_for_file(remote_path, timeout=10):
    """Wait until remote file exists and is non-empty."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, out, _ = adb("shell", f"run-as {PKG} ls -la {remote_path} 2>/dev/null")
        if out.strip() and "No such file" not in out:
            # file exists
            return True
        time.sleep(0.5)
    return False


def dump_frame(name):
    """Trigger a dump and pull the file."""
    remote_path = f"{REMOTE_DIR}/{name}.png"
    # 清理旧文件
    adb("shell", f"run-as {PKG} rm -f {remote_path} 2>/dev/null")

    print(f"  → 广播 dump ({name})...")
    out = broadcast(ACTION_DUMP, "name", name)
    if "BroadcastQueue" not in out and "result=" not in out:
        print(f"    广播异常: {out}")

    print(f"  → 等待文件出现 {remote_path}...")
    if not wait_for_file(remote_path, timeout=15):
        print(f"    ❌ dump 失败：超时未出现 {remote_path}")
        return None

    local = OUT_DIR / f"{name}.png"
    # 通过 run-as cat 把文件读出来（debug app 可读自己的 filesDir）
    print(f"  → 拉取到本地 {local}...")
    with open(local, "wb") as f:
        r = subprocess.run(
            [ADB, "exec-out", "run-as", PKG, "cat", remote_path],
            capture_output=True, timeout=30
        )
        f.write(r.stdout)
    if local.stat().st_size < 1000:
        print(f"    ❌ 文件过小 ({local.stat().st_size} bytes)，可能 dump 失败")
        return None
    print(f"    ✅ {local.stat().st_size} bytes")
    return local


def compute_metrics(with_shader, baseline):
    """Compute SSIM, mean abs diff, sharpness."""
    try:
        import numpy as np
        from PIL import Image
    except ImportError:
        print("\n⚠ 缺少 numpy/PIL，尝试安装...")
        subprocess.run([sys.executable, "-m", "pip", "install", "numpy", "pillow", "scikit-image"],
                       check=False)
        import numpy as np
        from PIL import Image

    a = np.asarray(Image.open(with_shader).convert("L"), dtype=np.float64)
    b = np.asarray(Image.open(baseline).convert("L"), dtype=np.float64)
    if a.shape != b.shape:
        print(f"⚠ 图像尺寸不同: {a.shape} vs {b.shape}，裁到相同大小")
        h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
        a, b = a[:h, :w], b[:h, :w]

    # 平均绝对差
    mean_abs_diff = float(np.mean(np.abs(a - b)))
    # 像素差 > 5 的占比
    diff_pct = float(np.mean(np.abs(a - b) > 5) * 100)

    # 锐度（Laplacian 方差，越大越锐）
    def laplacian_var(img):
        # 简化 Laplacian kernel
        k = np.array([[0, 1, 0], [1, -4, 1], [0, 1, 0]])
        from numpy.lib.stride_tricks import sliding_window_view
        win = sliding_window_view(img, (3, 3))
        lap = np.einsum("ijkl,kl->ij", win, k)
        return float(lap.var())

    try:
        sharp_a = laplacian_var(a)
        sharp_b = laplacian_var(b)
    except Exception as e:
        sharp_a = sharp_b = float("nan")
        print(f"  锐度计算失败: {e}")

    # SSIM
    ssim = float("nan")
    try:
        from skimage.metrics import structural_similarity as ssim_fn
        ssim = float(ssim_fn(b, a, data_range=255))
    except Exception as e:
        print(f"  SSIM 计算失败（skimage 未装？）: {e}")

    return {
        "shape": a.shape,
        "ssim": ssim,
        "mean_abs_diff": mean_abs_diff,
        "diff_pixel_pct": diff_pct,
        "sharpness_with_shader": sharp_a,
        "sharpness_baseline": sharp_b,
        "sharpness_ratio": (sharp_a / sharp_b) if sharp_b else float("nan"),
    }


def main():
    print("=" * 60)
    print(" [SPIKE] Anime4K 超分同帧对比")
    print("=" * 60)

    # 确认 app 在前台 / 有 player 实例
    print("\n[0] 检查 app 进程...")
    code, out, _ = adb("shell", f"pidof {PKG}")
    if not out.strip():
        print("  ❌ app 未运行，请先启动 app 并播放视频后暂停")
        sys.exit(1)
    print(f"  ✅ app PID = {out.strip()}")

    # 步骤 1：开 shader 状态下 dump
    print("\n[1/5] dump 当前帧（shader 应该是 ON 的初始状态）...")
    f_with = dump_frame("frame_with_shader")
    if not f_with:
        sys.exit(1)

    # 步骤 2：关 shader，等重渲染
    print("\n[2/5] 关闭 shader...")
    broadcast(ACTION_OFF)
    print("  → 等 2s 让 mpv 重渲染...")
    time.sleep(2)

    # 步骤 3：dump 基线
    print("\n[3/5] dump 同一帧（baseline, 无 shader）...")
    f_base = dump_frame("frame_baseline")
    if not f_base:
        sys.exit(1)

    # 步骤 4：恢复 shader
    print("\n[4/5] 重新开启 shader（恢复原状）...")
    broadcast(ACTION_ON)
    time.sleep(1)

    # 步骤 5：算指标
    print("\n[5/5] 计算量化指标...")
    m = compute_metrics(f_with, f_base)

    print("\n" + "=" * 60)
    print(" 📊 量化对比报告")
    print("=" * 60)
    print(f"  图像尺寸        : {m['shape']}")
    print(f"  SSIM            : {m['ssim']:.4f}   (1.0=完全相同，<0.95 有明显差异)")
    print(f"  平均像素差      : {m['mean_abs_diff']:.2f} / 255")
    print(f"  差异像素占比    : {m['diff_pixel_pct']:.1f}%   (差>5 灰阶的像素)")
    print(f"  锐度(开shader)  : {m['sharpness_with_shader']:.1f}")
    print(f"  锐度(关shader)  : {m['sharpness_baseline']:.1f}")
    print(f"  锐度提升比例    : {m['sharpness_ratio']:.2f}x   (>1.0 说明 shader 让画面更锐利)")
    print("=" * 60)
    print(f"\n两张图保存在: {OUT_DIR}")
    print(f"  - {f_with.name}")
    print(f"  - {f_base.name}")

    # 解读
    print("\n💡 解读：")
    if m["ssim"] > 0.995:
        print("  SSIM 接近 1 → 两帧几乎无差异，shader 可能没生效或对该帧无影响")
    elif m["ssim"] > 0.95:
        print("  SSIM 0.95-0.998 → 轻微差异（可能是色调/锐化）")
    else:
        print("  SSIM < 0.95 → 显著差异，shader 明显改变了画面")

    if m["sharpness_ratio"] > 1.1:
        print(f"  锐度提升 {((m['sharpness_ratio']-1)*100):.0f}% → shader 让画面明显更锐利 ✅")
    elif m["sharpness_ratio"] < 0.95:
        print(f"  锐度反而下降 → 异常，可能 shader 引入了模糊")
    else:
        print("  锐度几乎不变 → shader 未生效或对该帧无效")


if __name__ == "__main__":
    main()
