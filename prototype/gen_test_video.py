"""Generate a synthetic 720x720 test video with known ground-truth events.

The events are chosen to stress the failure modes we care about:
long static stretches, gradual pans, sub-second flashes, small localized
overlays, fast montages, incremental text, and returns to earlier scenes.
"""
import json, math, sys
import cv2
import numpy as np

W = H = 720
FPS = 30
DUR = 150.0
rng = np.random.default_rng(0)
FONT = cv2.FONT_HERSHEY_SIMPLEX

# (start, end, label, kind) - kind: "scene" = should be covered, "brief" = short event
EVENTS = [
    (0, 20, "title card 'Chapter 1: Setup'", "scene"),
    (20, 40, "slow pan across landscape", "scene"),
    (40.0, 40.5, "red flash 'ALERT 42'", "brief"),
    (40.5, 60, "chat messages appearing", "scene"),
    (60, 62, "fast montage of 6 shapes", "brief"),
    (62, 90, "static clock screen", "scene"),
    (90, 100, "bouncing ball", "scene"),
    (100, 101, "small overlay 'CODE: 7391'", "brief"),
    (101, 120, "title card 'Chapter 2: Results'", "scene"),
    (120, 135, "noisy dim room brightening", "scene"),
    (135, 150, "figure walks across room", "scene"),
]

landscape = np.zeros((H, W * 3, 3), np.uint8)
for x in range(W * 3):
    landscape[:, x] = (int(120 + 80 * math.sin(x / 150)), int(150 + 60 * math.cos(x / 90)), 200)
landscape[H // 2 :, :] = (40, 140, 60)
for i in range(18):
    cx = int(rng.integers(0, W * 3)); cy = int(rng.integers(H // 2 - 120, H // 2 + 40))
    cv2.circle(landscape, (cx, cy), int(rng.integers(30, 90)), tuple(int(c) for c in rng.integers(0, 255, 3)), -1)
for i in range(6):
    x = 200 + i * 350
    cv2.putText(landscape, f"MILE {i+1}", (x, H // 2 + 200), FONT, 1.5, (255, 255, 255), 3)

def title(text, sub):
    f = np.full((H, W, 3), (60, 30, 20), np.uint8)
    cv2.putText(f, text, (60, 330), FONT, 1.4, (255, 255, 255), 3)
    cv2.putText(f, sub, (60, 400), FONT, 0.9, (200, 200, 200), 2)
    return f

MSGS = ["hi, are you there?", "yes! what's up", "meeting moved to 3pm", "ok, room 204?",
        "no, room 512", "got it, thanks", "bring the slides"]
SHAPES = [((0, 0, 255), "circle"), ((0, 255, 0), "square"), ((255, 0, 0), "triangle"),
          ((0, 255, 255), "star"), ((255, 0, 255), "ring"), ((255, 255, 0), "bar")]

def frame_at(t):
    if t < 20:
        f = title("Chapter 1: Setup", "a synthetic test video")
    elif t < 40:
        off = int((t - 20) / 20 * (W * 2))
        f = landscape[:, off : off + W].copy()
    elif t < 40.5:
        f = np.full((H, W, 3), (0, 0, 230), np.uint8)
        cv2.putText(f, "ALERT 42", (170, 380), FONT, 2.5, (255, 255, 255), 6)
    elif t < 60:
        f = np.full((H, W, 3), (245, 245, 245), np.uint8)
        cv2.rectangle(f, (0, 0), (W, 70), (120, 80, 30), -1)
        cv2.putText(f, "Chat with Sam", (20, 48), FONT, 1.1, (255, 255, 255), 2)
        n = min(len(MSGS), int((t - 40.5) / 2.8) + 1)
        for i in range(n):
            left = i % 2 == 0
            x0 = 30 if left else 300
            y0 = 100 + i * 80
            cv2.rectangle(f, (x0, y0), (x0 + 390, y0 + 60), (230, 210, 180) if left else (180, 230, 190), -1)
            cv2.putText(f, MSGS[i], (x0 + 12, y0 + 40), FONT, 0.8, (20, 20, 20), 2)
    elif t < 62:
        k = min(5, int((t - 60) / (2 / 6)))
        col, name = SHAPES[k]
        f = np.full((H, W, 3), 30, np.uint8)
        cv2.circle(f, (360, 330), 180, col, -1)
        cv2.putText(f, name.upper(), (250, 640), FONT, 1.8, col, 4)
    elif t < 90:
        f = np.full((H, W, 3), (20, 20, 20), np.uint8)
        s = int(t)
        cv2.putText(f, f"12:{s // 60:02d}:{s % 60:02d}", (140, 390), FONT, 3, (0, 220, 255), 6)
        cv2.putText(f, "waiting for server...", (170, 480), FONT, 0.9, (150, 150, 150), 2)
    elif t < 101:
        f = np.full((H, W, 3), (200, 180, 160), np.uint8)
        u = (t - 90) / 10
        x = int(80 + u * 560)
        y = int(620 - abs(math.sin(u * math.pi * 4)) * 480)
        cv2.circle(f, (x, y), 50, (0, 100, 255), -1)
        cv2.line(f, (0, 672), (W, 672), (80, 80, 80), 4)
        if t >= 100:
            cv2.rectangle(f, (470, 20), (705, 70), (0, 0, 0), -1)
            cv2.putText(f, "CODE: 7391", (480, 57), FONT, 1.0, (255, 255, 255), 2)
    elif t < 120:
        f = title("Chapter 2: Results", "accuracy went from 71% to 88%")
    else:
        b = 40 + (min(t, 135) - 120) / 15 * 120
        f = np.full((H, W, 3), b, np.float32)
        cv2.rectangle(f, (100, 450), (620, 520), (b * 0.6, b * 0.5, b * 0.4), -1)  # table
        cv2.rectangle(f, (480, 120), (640, 300), (b * 1.2, b * 1.1, b * 0.9), -1)  # window
        if t >= 135:
            x = int(-80 + (t - 135) / 15 * 880)
            cv2.circle(f, (x, 260), 40, (60, 60, 160), -1)
            cv2.rectangle(f, (x - 45, 300), (x + 45, 520), (90, 60, 60), -1)
        f = np.clip(f + rng.normal(0, 6, f.shape), 0, 255).astype(np.uint8)
    return f

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "test_video.mp4"
    vw = cv2.VideoWriter(out, cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
    for i in range(int(DUR * FPS)):
        vw.write(frame_at(i / FPS))
    vw.release()
    with open(out.rsplit(".", 1)[0] + "_events.json", "w") as fh:
        json.dump([dict(start=s, end=e, label=l, kind=k) for s, e, l, k in EVENTS], fh, indent=1)
    print("wrote", out)
