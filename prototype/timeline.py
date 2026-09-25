"""Video -> compact visual timeline for multimodal LLMs (reference prototype).

Formulation: "budgeted coverage segmentation".
Split the video into <= K contiguous segments so that every analysed frame is
visually close to its segment's representative frame. Each tile on the sheet
is (representative frame, time range it stands for). Greedy top-down: split
the segment with the highest priority = capped worst-case deviation x
sqrt(duration). Static stretches collapse into one long-range tile; bursts of
change and brief events get their own tiles; near-identical tiles never
appear because a split only happens where something is *not* represented.
The same routine on a sub-window (at a higher analysis rate) gives zoom sheets.
"""
import json, math, os, sys
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SIG = 32          # signature grid (SIG x SIG luma) + 8x8 chroma
TOPK = 24         # local-change term: mean of the TOPK most-changed cells

# --------------------------------------------------------------- analysis
def analyse(path, rate=10.0, t0=0.0, t1=None):
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    total = cap.get(cv2.CAP_PROP_FRAME_COUNT) / fps
    step = max(1, round(fps / rate))
    lum, col, times, detail = [], [], [], []
    i = int(round(t0 * fps))
    if i:
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
    first = i
    while True:
        ok = cap.grab()
        if not ok or (t1 is not None and i / fps >= t1):
            break
        if (i - first) % step == 0:
            _, f = cap.retrieve()
            small = cv2.resize(f, (SIG, SIG), interpolation=cv2.INTER_AREA)
            ycc = cv2.cvtColor(small, cv2.COLOR_BGR2YCrCb).astype(np.float32)
            y = ycc[:, :, 0].ravel()
            # illumination-invariant luma: remove brightness offset, normalise contrast
            lum.append((y - y.mean()) / (y.std() + 8.0) * 40.0)
            col.append(cv2.resize(ycc[:, :, 1:], (8, 8), interpolation=cv2.INTER_AREA).ravel())
            g = cv2.cvtColor(cv2.resize(f, (128, 128), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
            detail.append(float(cv2.Laplacian(g, cv2.CV_32F).var()))
            times.append(i / fps)
        i += 1
    cap.release()
    return dict(lum=np.array(lum), col=np.array(col), t=np.array(times), detail=np.array(detail),
                t0=times[0], t1=i / fps, dur=i / fps - times[0], total=total, fps=fps)

def dist(a_l, a_c, B_l, B_c):
    """Distance from one frame to many. Global + local (top-k cells) + colour."""
    g = np.abs(B_l - a_l)
    topk = np.partition(g, -TOPK, axis=1)[:, -TOPK:].mean(1)
    return 0.5 * g.mean(1) + 0.5 * topk + 0.5 * np.abs(B_c - a_c).mean(1)

# ----------------------------------------------------------- segmentation
class Seg:
    def __init__(s, A, a, b):
        s.a, s.b = a, b
        L, C = A["lum"][a:b], A["col"][a:b]
        mean = L.mean(0)
        dm = ((L - mean) ** 2).sum(1)
        # representative: among the frames closest to the segment mean, the most detailed
        # (sharpest / most content, e.g. the chat with more messages, the unblurred frame)
        near = np.where(dm <= np.percentile(dm, 30) + 1e-6)[0]
        s.rep = a + int(near[np.argmax(A["detail"][a:b][near])])
        d = dist(A["lum"][s.rep], A["col"][s.rep], L, C)
        s.dev = d
        s.maxdev = float(d.max())

def best_split(A, s, cap=25.0):
    a, b = s.a, s.b
    if b - a < 2:
        return None
    L = A["lum"][a:b]
    # candidate 1: SSE-optimal split via prefix sums
    cs, cs2 = np.cumsum(L, 0), np.cumsum((L ** 2).sum(1))
    n = b - a
    k = np.arange(1, n)
    sse_l = cs2[k - 1] - (cs[k - 1] ** 2).sum(1) / k
    tot, tot2 = cs[-1], cs2[-1]
    sse_r = (tot2 - cs2[k - 1]) - ((tot - cs[k - 1]) ** 2).sum(1) / (n - k)
    cands = {a + int(k[np.argmin(sse_l + sse_r)])}
    # candidates 2-3: isolate the worst-represented frame
    w = a + int(np.argmax(s.dev))
    cands |= {w, w + 1}
    # candidates 4-6: biggest frame-to-frame jumps inside the segment
    jumps = A["jump"][a + 1 : b]
    for j in np.argsort(jumps)[-3:]:
        cands.add(a + 1 + int(j))
    best = None
    for c in cands:
        if a < c < b:
            l, r = Seg(A, a, c), Seg(A, c, b)
            # same objective as the greedy loop: minimise the larger half's priority
            # (raw maxdev as tie-break so brief events are still isolated)
            score = (max(priority(A, l, cap), priority(A, r, cap)), max(l.maxdev, r.maxdev))
            if best is None or score < best[0]:
                best = (score, l, r)
    return best

def priority(A, s, cap):
    """Capped worst-case deviation x sqrt(duration): once a frame is 'clearly different'
    (>= cap) magnitude stops mattering, and among clearly-unrepresented segments the
    longer one is refined first. Brief events still get isolated because the split
    candidates include the (uncapped) worst frame."""
    t = A["t"]
    dur = t[s.b - 1] - t[s.a] + (t[1] - t[0] if len(t) > 1 else 0)
    if outlier_run(A, s, cap):   # an unshown brief event matters regardless of how short it is
        dur = max(dur, A["dur"] / 8)
    return min(s.maxdev, cap) / cap * math.sqrt(dur / A["dur"])

def outlier_run(A, s, cap, brief=2.0, min_len=0.25):
    """A brief event = two big frame-to-frame jumps (in and out) less than `brief`
    seconds apart inside the segment. Returns the (start, end) sample run, or None."""
    t, J = A["t"], A["jump"]
    if s.b - s.a < 3:
        return None
    js = J[s.a + 1 : s.b]
    # jumps must be exceptional both locally and for the whole video, so cut-heavy
    # footage (music videos, vlogs) doesn't turn every shot into a "brief event"
    thr = max(cap, 4 * np.median(js), A.setdefault("jump_p98", float(np.percentile(J, 98))))
    big = np.where(js > thr)[0] + s.a + 1
    best = None
    for x, y in zip(big[:-1], big[1:]):
        if min_len <= t[y] - t[x] <= brief:   # shorter = transition frames, not content
            strength = min(J[x], J[y])
            if best is None or strength > best[0]:
                best = (strength, (int(x), int(y)))
    return best[1] if best else None

def auto_min_dur(A, frac=1 / 40, lo=1.5, hi=60.0):
    """Finest temporal resolution the overview is allowed to use for busy content."""
    return min(hi, max(lo, A["dur"] * frac))

def segment(A, K=60, d0=6.0, merge_eps=3.0, cap=25.0, min_dur=None):
    """Split until every range is represented (maxdev < d0) or shorter than min_dur
    (brief events are still isolated), or until K ranges. The tile count is decided
    by the content; K is only a ceiling."""
    if min_dur is None:
        min_dur = auto_min_dur(A)
    step = A["t"][1] - A["t"][0] if len(A["t"]) > 1 else 0
    A["jump"] = np.r_[0, [dist(A["lum"][i - 1], A["col"][i - 1], A["lum"][i:i + 1], A["col"][i:i + 1])[0]
                          for i in range(1, len(A["t"]))]]
    segs = [Seg(A, 0, len(A["t"]))]
    while len(segs) < K:
        def needs(sg):
            if sg.maxdev < d0 or sg.b - sg.a < 2:
                return False
            dur = A["t"][sg.b - 1] - A["t"][sg.a] + step
            # small changes (noise, a ticking digit) must span longer to earn another tile;
            # clearly different content (>= cap) splits down to the base resolution
            need = 2 * min_dur * cap / min(sg.maxdev, cap)
            return dur >= need or outlier_run(A, sg, cap) is not None
        cand = [i for i in range(len(segs)) if needs(segs[i])]
        if not cand:                       # everything is represented well enough -> stop early
            break
        i = max(cand, key=lambda i: priority(A, segs[i], cap))
        run = outlier_run(A, segs[i], cap)
        if run:                            # brief outlier (flash, overlay, cut-in): isolate the whole run at once
            a, b = segs[i].a, segs[i].b
            parts = [(x, y) for x, y in ((a, run[0]), run, (run[1], b)) if y > x]
            if len(segs) + len(parts) - 1 > K:      # budget allows only one cut: take the run's start
                parts = [(a, run[0]), (run[0], b)] if run[0] > a else [(a, run[1]), (run[1], b)]
            segs[i : i + 1] = [Seg(A, x, y) for x, y in parts]
            continue
        sp = best_split(A, segs[i])
        if sp is None:
            break
        segs[i : i + 1] = [sp[1], sp[2]]
    # merge adjacent segments whose representatives look the same
    out = [segs[0]]
    for s in segs[1:]:
        p = out[-1]
        if dist(A["lum"][p.rep], A["col"][p.rep], A["lum"][s.rep:s.rep + 1], A["col"][s.rep:s.rep + 1])[0] < merge_eps:
            out[-1] = Seg(A, p.a, s.b)
        else:
            out.append(s)
    return out

def tiles_from_segments(A, segs):
    t = A["t"]
    res = []
    for s in segs:
        end = t[s.b] if s.b < len(t) else A["t1"]
        res.append(dict(time=float(t[s.rep]), start=float(t[s.a]), end=float(end), maxdev=s.maxdev))
    return res

def build(path, K=60, t0=0.0, t1=None, rate=None):
    """Overview when t0/t1 cover the whole video; a zoom sheet otherwise.
    Zoom windows are re-analysed at a higher rate (up to every frame)."""
    zoom = t1 is not None
    span = (t1 - t0) if zoom else None
    if rate is None:
        rate = 10.0 if not zoom or span > 60 else min(60.0, max(10.0, 160 / span))
    A = analyse(path, rate, t0, t1)
    md = auto_min_dur(A, lo=1.5) if not zoom else auto_min_dur(A, lo=1.0 / rate)
    return A, tiles_from_segments(A, segment(A, K, min_dur=md))

# ---------------------------------------------------------------- baselines
def baseline_uniform(A, n=20):
    return [dict(time=float((i + 0.5) * A["dur"] / n)) for i in range(n)]

def baseline_peaks(A, n=16, nms=1.0):
    j = A["jump"].copy(); t = A["t"]; out = []
    for _ in range(n):
        i = int(np.argmax(j))
        if j[i] <= 0: break
        out.append(dict(time=float(t[i]), score=float(j[i])))
        j[np.abs(t - t[i]) < nms] = -1
    return sorted(out, key=lambda x: x["time"])

# ---------------------------------------------------------------- rendering
def fmt(sec, dec=1):
    m, s = divmod(max(0.0, sec), 60)
    if dec == 0:
        return f"{int(m)}:{int(s):02d}"
    return f"{int(m)}:{s:0{3 + dec}.{dec}f}"

def grab_frames(path, times):
    cap = cv2.VideoCapture(path); out = []
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    for t in times:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(round(t * fps)))
        ok, f = cap.read()
        out.append(Image.fromarray(cv2.cvtColor(f, cv2.COLOR_BGR2RGB)) if ok else Image.new("RGB", (8, 8)))
    cap.release()
    return out

def font(sz, bold=False):
    for n in (["consolab.ttf", "arialbd.ttf"] if bold else ["consola.ttf", "arial.ttf"]):
        try: return ImageFont.truetype("C:/Windows/Fonts/" + n, sz)
        except OSError: pass
    return ImageFont.load_default()

def nice_step(span, target=10):
    for st in (0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800):
        if span / st <= target:
            return st
    return 3600

def tile_ids(n, prefix):
    return [f"{prefix}{i + 1}" for i in range(n)]

def paginate(n, per_sheet=16):
    """Split n tiles into the fewest sheets of <= per_sheet, as evenly as possible."""
    k = max(1, math.ceil(n / per_sheet))
    sizes = [n // k + (1 if i < n % k else 0) for i in range(k)]
    out, a = [], 0
    for sz in sizes:
        out.append((a, a + sz)); a += sz
    return out

def render_pages(path, A, tiles, out_stem, title, prefix="", per_sheet=16):
    """One or more sheets; IDs run continuously across sheets."""
    pages = paginate(len(tiles), per_sheet)
    files = []
    for p, (a, b) in enumerate(pages):
        head = title if len(pages) == 1 else             f"SHEET {p + 1}/{len(pages)} (tiles {prefix}{a + 1}–{prefix}{b}) · {title}"
        out = f"{out_stem}.png" if len(pages) == 1 else f"{out_stem}_{p + 1}of{len(pages)}.png"
        render(path, A, tiles[a:b], out, head, prefix=prefix, all_tiles=tiles, first=a)
        files.append(out)
    return files

def render(path, A, tiles, out_png, title, sheet_w=1536, prefix="", show_ranges=True, activity=True,
           all_tiles=None, first=0):
    n = len(tiles)
    all_tiles = all_tiles if all_tiles is not None else tiles
    ids = [f"{prefix}{first + i + 1}" for i in range(n)]
    all_ids = tile_ids(len(all_tiles), prefix)
    frames = grab_frames(path, [t["time"] for t in tiles])
    fw, fh = frames[0].size
    ar = fh / fw
    pad, band = 6, 30
    head = 34 + (72 if activity else 0)
    # grid: the column count that gives the largest tiles inside a sheet_w x sheet_w box
    def tile_w(c):
        r = math.ceil(n / c)
        by_w = (sheet_w - pad * (c + 1)) / c
        by_h = (sheet_w - head - pad - r * (band + pad)) / r / ar
        return min(by_w, by_h)
    cols = max(range(1, n + 1), key=tile_w)
    rows = math.ceil(n / cols)
    tw = int(tile_w(cols))
    th = int(tw * ar)
    sheet_w = max(900, cols * tw + pad * (cols + 1))
    H = head + rows * (th + band + pad) + pad
    img = Image.new("RGB", (sheet_w, H), (18, 18, 22))
    d = ImageDraw.Draw(img)
    f_id, f_t, f_s = font(20, True), font(18), font(15)
    ts = 19   # shrink the title until it fits the sheet width
    while ts > 11 and d.textlength(title, font=font(ts, True)) > sheet_w - 2 * pad - 8:
        ts -= 1
    d.text((pad + 4, 7), title, font=font(ts, True), fill=(235, 235, 235))
    w0, w1 = A["t0"], A["t1"]
    span = max(1e-6, w1 - w0)
    dec = 2 if span < 15 else 1
    rdec = 1 if span < 60 else 0
    X = lambda x0, x1, t: x0 + (x1 - x0) * (t - w0) / span
    if activity:  # activity strip: per-sample visual change, tile positions + range boundaries
        x0, x1, y0, y1 = pad + 4, sheet_w - pad - 4, 36, 36 + 54
        d.rectangle([x0, y0, x1, y1], fill=(32, 32, 38))
        j = A["jump"]; jt = A["t"]
        mx = np.percentile(j, 99) + 1e-6
        for k in range(len(j)):
            v = min(1, j[k] / mx)
            x = X(x0, x1, jt[k])
            d.line([x, y1, x, y1 - v * (y1 - y0 - 16)], fill=(90, 150, 220))
        if len(all_tiles) > n and "start" in tiles[0]:   # shade the part of the video this sheet covers
            d.rectangle([X(x0, x1, tiles[0]["start"]), y0, X(x0, x1, tiles[-1]["end"]), y1], outline=(255, 200, 60), width=2)
        on = set(range(first, first + n))
        for i, t in enumerate(all_tiles):
            col = (255, 200, 60) if i in on else (110, 100, 70)
            if "start" in t:
                xs = X(x0, x1, t["start"])
                d.line([xs, y0, xs, y1], fill=(90, 90, 100))
            x = X(x0, x1, t["time"])
            d.polygon([(x - 4, y0), (x + 4, y0), (x, y0 + 7)], fill=col)
            if i in on or len(all_tiles) <= 24:
                d.text((x + 3, y0 + 1), all_ids[i], font=font(12, True), fill=col)
        st = nice_step(span)
        s = math.ceil(w0 / st) * st
        while s <= w1 + 1e-6:
            d.text((X(x0, x1, s), y1 + 2), fmt(s, 0 if st >= 1 else 1), font=font(12), fill=(150, 150, 160))
            s += st
    for i, (t, fr) in enumerate(zip(tiles, frames)):
        r, c = divmod(i, cols)
        x = (sheet_w - cols * tw - pad * (cols - 1)) // 2 + c * (tw + pad); y = head + pad + r * (th + band + pad)
        img.paste(fr.resize((tw, th), Image.LANCZOS), (x, y))
        brief = show_ranges and "start" in t and (t["end"] - t["start"]) < max(2.0, span / 60) and span > 20
        d.rectangle([x, y + th, x + tw, y + th + band], fill=(120, 80, 10) if brief else (40, 40, 48))
        d.text((x + 5, y + th + 3), ids[i], font=f_id, fill=(255, 210, 90))
        d.text((x + 5 + d.textlength(ids[i], font=f_id) + 8, y + th + 4), fmt(t["time"], dec), font=f_t, fill=(255, 255, 255))
        if show_ranges and "start" in t:
            rng = f"{fmt(t['start'], rdec)}–{fmt(t['end'], rdec)}"
            d.text((x + tw - d.textlength(rng, font=f_s) - 5, y + th + 6), rng, font=f_s, fill=(200, 200, 210))
            by = y + th + band - 4          # position bar: this tile's range within the sheet's window
            d.rectangle([x, by, x + tw, by + 3], fill=(70, 70, 80))
            d.rectangle([X(x, x + tw, t["start"]), by, max(X(x, x + tw, t["start"]) + 2, X(x, x + tw, t["end"])), by + 3],
                        fill=(255, 200, 60))
        elif "score" in t:
            sc = f"Δ{t['score']:.0f}"
            d.text((x + tw - d.textlength(sc, font=f_s) - 5, y + th + 6), sc, font=f_s, fill=(200, 200, 210))
    img.save(out_png, optimize=True)
    return img.size

def index_text(name, A, tiles, prefix=""):
    span = A["t1"] - A["t0"]
    dec = 2 if span < 15 else 1
    lines = [f"{name}: window {fmt(A['t0'])}–{fmt(A['t1'])} of {fmt(A['total'])}. "
             "Tiles in time order; each is one frame standing for the range shown.",
             "id | frame time | covers"]
    for i, t in zip(tile_ids(len(tiles), prefix), tiles):
        lines.append(f"{i} | {fmt(t['time'], dec)} | {fmt(t['start'], dec)}–{fmt(t['end'], dec)}")
    return "\n".join(lines)

# --------------------------------------------------------------- evaluation
def evaluate(A, tiles, events, dup_eps=3.0):
    t = A["t"]
    idx = [int(np.argmin(np.abs(t - x["time"]))) for x in tiles]
    hit = [any(e["start"] <= x["time"] < e["end"] for x in tiles) for e in events]
    dups = 0
    for a in range(len(idx)):
        for b in range(a + 1, len(idx)):
            if dist(A["lum"][idx[a]], A["col"][idx[a]], A["lum"][idx[b]:idx[b] + 1], A["col"][idx[b]:idx[b] + 1])[0] < dup_eps:
                dups += 1
    return dict(n=len(tiles), recall=sum(hit), of=len(events),
                brief=sum(h for h, e in zip(hit, events) if e["kind"] == "brief"),
                brief_of=sum(e["kind"] == "brief" for e in events),
                missed=[e["label"] for h, e in zip(hit, events) if not h], dup_pairs=dups)

if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("video"); ap.add_argument("-k", type=int, default=60, help="max tiles"); ap.add_argument("-o", default="out")
    ap.add_argument("--zoom", help="start-end seconds, e.g. 60-62"); ap.add_argument("--id", default="")
    ap.add_argument("--baselines", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.o, exist_ok=True)
    name = os.path.basename(a.video)
    if a.zoom:
        z0, z1 = map(float, a.zoom.split("-"))
        A, tiles = build(a.video, a.k, z0, z1)
        pre = a.id + "." if a.id else "Z"
        title = f"{name} · ZOOM {('of tile ' + a.id + ' · ') if a.id else ''}{fmt(A['t0'])}–{fmt(A['t1'])} · {len(tiles)} tiles"
        out = f"{a.o}/zoom_{a.id or a.zoom}.png"
    else:
        A, tiles = build(a.video, a.k)
        pre = ""
        title = f"{name} · {fmt(A['total'], 0)} · overview · {len(tiles)} tiles in time order · id, frame time | range covered"
        out = f"{a.o}/overview.png"
    print(render_pages(a.video, A, tiles, out[:-4], title, prefix=pre))
    print(index_text(name, A, tiles, pre))
    ev_path = a.video.rsplit(".", 1)[0] + "_events.json"
    if os.path.exists(ev_path) and not a.zoom:
        events = json.load(open(ev_path))
        print("coverage:", evaluate(A, tiles, events))
        if a.baselines:
            u, p = baseline_uniform(A), baseline_peaks(A)
            print("baseline uniform20:", evaluate(A, u, events))
            print("baseline peaks16:", evaluate(A, p, events))
            print("baseline both (36):", evaluate(A, sorted(u + p, key=lambda x: x["time"]), events))
            render(a.video, A, u, f"{a.o}/baseline_uniform.png", "uniform 20", show_ranges=False, activity=False)
            render(a.video, A, p, f"{a.o}/baseline_peaks.png", "peaks 16", show_ranges=False, activity=False)
