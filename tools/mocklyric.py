# 大音乐窗布局预演（PIL），按 CardActivity 常量出图
# 用法: ~/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 tools/mocklyric.py
from PIL import Image, ImageDraw, ImageFont
import os, sys

W, H = 1920, 720
SL, ST, SB = 120, 165, 555
BIG_LYRIC_TOP, BIG_LYRIC_ROWS, BIG_LYRIC_X = 186, 6, 296
LYRIC_MAX_W, PROG_W, PROG_H = 316, 326, 6
ART_X, ART_SIZE = 156, 116
CUR_S, PREV_S, FAR_S = 26, 26, 20
LINE_H = max(26, CUR_S + 18)
BLOCK_H = LINE_H * BIG_LYRIC_ROWS
ART_Y = BIG_LYRIC_TOP + (BLOCK_H - ART_SIZE) / 2

FONT_CANDIDATES = [
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Supplemental/Songti.ttc",
]

def F(sz):
    for p in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(p, int(sz), index=0)
        except Exception:
            continue
    return ImageFont.load_default()

def fit(draw, text, maxw, sz):
    f = F(sz)
    if draw.textlength(text, font=f) <= maxw:
        return text
    ell = "\u2026"
    while text and draw.textlength(text + ell, font=f) > maxw:
        text = text[:-1]
    return text + ell

def render(lines, idx, out, label=""):
    img = Image.new("RGB", (W, H), (22, 30, 43))
    d = ImageDraw.Draw(img)
    d.ellipse([ART_X, ART_Y, ART_X + ART_SIZE, ART_Y + ART_SIZE],
              fill=(28, 40, 60), outline=(60, 90, 140))
    d.text((ART_X + ART_SIZE / 2, ART_Y + ART_SIZE / 2), "\u266a",
           font=F(46), fill=(120, 150, 200), anchor="mm")
    above = 1
    maxK = BIG_LYRIC_ROWS - above - 1
    minK = -above
    lastIdx = len(lines) - 1
    maxBase = lastIdx - maxK
    baseIdx = min(idx, maxBase) if maxBase > 0 else idx
    baseY = BIG_LYRIC_TOP + above * LINE_H + CUR_S
    for k in range(minK, maxK + 1):
        i = baseIdx + k
        if i < 0 or i >= len(lines):
            continue
        y = baseY + k * LINE_H
        sz = CUR_S if k == 0 else (PREV_S if k == -1 else FAR_S)
        col = (255, 255, 255) if i == idx else ((110, 235, 180) if i < idx else (124, 141, 166))
        d.text((BIG_LYRIC_X, y - sz), fit(d, lines[i], LYRIC_MAX_W, sz), font=F(sz), fill=col)
    barY = BIG_LYRIC_TOP + LINE_H * 1.05 * BIG_LYRIC_ROWS + 20
    d.rounded_rectangle([BIG_LYRIC_X, barY, BIG_LYRIC_X + PROG_W, barY + PROG_H],
                        PROG_H / 2, fill=(70, 80, 95))
    d.rounded_rectangle([BIG_LYRIC_X, barY, BIG_LYRIC_X + PROG_W * 0.42, barY + PROG_H],
                        PROG_H / 2, fill=(255, 255, 255))
    if label:
        d.text((BIG_LYRIC_X, 20), label, font=F(20), fill=(120, 140, 170))
    img.save(out)
    print("%s  art_right=%d lyric_x=%d lyric_right=%d prog_right=%d barY=%.1f card_bottom=%d"
          % (out, ART_X + ART_SIZE, BIG_LYRIC_X, BIG_LYRIC_X + LYRIC_MAX_W,
             BIG_LYRIC_X + PROG_W, barY, SB))

L = ["夜空中最亮的星", "能否听清", "那仰望的人心底的孤独和叹息",
     "这是一句特别特别长的歌词用来验证超长的时候会不会自动换行而把下面一行挤空",
     "我祈祷拥有一颗透明的心灵", "和会流泪的眼睛", "给我再去相信的勇气"]

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "preview")
OUT = os.path.normpath(OUT)
os.makedirs(OUT, exist_ok=True)
render(L, 2, os.path.join(OUT, "mock-mid-v2.png"), "超长歌词一行（省略号截断）")
render(L, 3, os.path.join(OUT, "mock-curlong-v2.png"), "当前行=超长歌词")
render(L, 6, os.path.join(OUT, "mock-tail-v2.png"), "末尾：不滚动，永远 6 行")
