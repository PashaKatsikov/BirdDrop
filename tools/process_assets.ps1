$ErrorActionPreference = "Stop"

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class AssetTool
{
    static byte[] Load(string path, out int w, out int h)
    {
        using (var src = new Bitmap(path))
        using (var bmp = new Bitmap(src.Width, src.Height, PixelFormat.Format32bppArgb))
        {
            using (var g = Graphics.FromImage(bmp)) g.DrawImage(src, 0, 0, src.Width, src.Height);
            w = bmp.Width; h = bmp.Height;
            var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            var buf = new byte[data.Stride * h];
            Marshal.Copy(data.Scan0, buf, 0, buf.Length);
            bmp.UnlockBits(data);
            return buf;
        }
    }

    static void Save(byte[] buf, int w, int h, string path)
    {
        using (var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
        {
            var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            Marshal.Copy(buf, 0, data.Scan0, buf.Length);
            bmp.UnlockBits(data);
            bmp.Save(path, ImageFormat.Png);
        }
    }

    // Wide *and* flat blobs are shared ground shadows; a wide but tall blob is
    // just one object leaning into the next cell (a long tail, a spread wing).
    static bool IsSpanningScenery(int[] cp, int cell, int h)
    {
        return (cp[2] - cp[0]) > cell * 1.3 && (cp[3] - cp[1]) < h * 0.35;
    }

    static byte Clamp(double v) { return (byte)(v < 0 ? 0 : (v > 255 ? 255 : v)); }

    static void Seed(Stack<int> st, bool[] back, bool[] dark, int i)
    {
        if (i < 0 || i >= back.Length) return;
        if (back[i] || !dark[i]) return;
        back[i] = true;
        st.Push(i);
    }

    // Only the black studio backdrop reachable from the image border becomes
    // transparent, so dark shading lines inside the artwork stay opaque.
    static void KeyBackdrop(byte[] buf, int w, int h, int lo, int hi)
    {
        int n = w * h;
        var dark = new bool[n];
        for (int i = 0; i < n; i++)
        {
            int o = i * 4;
            int lum = Math.Max(buf[o], Math.Max(buf[o + 1], buf[o + 2]));
            dark[i] = lum <= hi;
        }

        var back = new bool[n];
        var st = new Stack<int>();
        for (int x = 0; x < w; x++) { Seed(st, back, dark, x); Seed(st, back, dark, (h - 1) * w + x); }
        for (int y = 0; y < h; y++) { Seed(st, back, dark, y * w); Seed(st, back, dark, y * w + w - 1); }
        while (st.Count > 0)
        {
            int i = st.Pop();
            int x = i % w, y = i / w;
            if (x > 0) Seed(st, back, dark, i - 1);
            if (x < w - 1) Seed(st, back, dark, i + 1);
            if (y > 0) Seed(st, back, dark, i - w);
            if (y < h - 1) Seed(st, back, dark, i + w);
        }

        for (int i = 0; i < n; i++)
        {
            int o = i * 4;
            int lum0 = Math.Max(buf[o], Math.Max(buf[o + 1], buf[o + 2]));
            // Pure black is always backdrop, even when walled in by the artwork
            // (the gap between an arm and a torso, for instance).
            if (lum0 <= lo)
            {
                buf[o] = 0; buf[o + 1] = 0; buf[o + 2] = 0; buf[o + 3] = 0;
                continue;
            }
            if (!back[i]) { buf[o + 3] = 255; continue; }
            int lum = lum0;
            {
                // Keep the observed colour: brightening edge pixels here is what
                // produces white halos around dark cartoon outlines.
                int a = (int)((lum - lo) * 255.0 / (hi - lo));
                if (a < 0) a = 0; if (a > 255) a = 255;
                buf[o + 3] = (byte)a;
            }
        }
    }

    static int[] LabelComponents(byte[] buf, int w, int h, out List<int[]> comps)
    {
        int n = w * h;
        var label = new int[n];
        for (int i = 0; i < n; i++) label[i] = -1;
        comps = new List<int[]>();
        var st = new Stack<int>();
        int cur = 0;
        for (int i = 0; i < n; i++)
        {
            if (label[i] != -1 || buf[i * 4 + 3] <= 40) continue;
            st.Push(i); label[i] = cur;
            int minx = w, miny = h, maxx = -1, maxy = -1, count = 0;
            long sumx = 0;
            while (st.Count > 0)
            {
                int j = st.Pop();
                int x = j % w, y = j / w;
                count++; sumx += x;
                if (x < minx) minx = x;
                if (x > maxx) maxx = x;
                if (y < miny) miny = y;
                if (y > maxy) maxy = y;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                    {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int k = ny * w + nx;
                        if (label[k] != -1 || buf[k * 4 + 3] <= 40) continue;
                        label[k] = cur;
                        st.Push(k);
                    }
            }
            comps.Add(new int[] { minx, miny, maxx, maxy, count, (int)(sumx / Math.Max(count, 1)) });
            cur++;
        }
        return label;
    }

    static void Emit(byte[] buf, bool[] mask, int w, int h, int pad, string path)
    {
        int minx = w, miny = h, maxx = -1, maxy = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (mask[y * w + x])
                {
                    if (x < minx) minx = x;
                    if (x > maxx) maxx = x;
                    if (y < miny) miny = y;
                    if (y > maxy) maxy = y;
                }
        if (maxx < 0) { Console.WriteLine("  ! nothing to emit for " + path); return; }

        minx = Math.Max(0, minx - pad); miny = Math.Max(0, miny - pad);
        maxx = Math.Min(w - 1, maxx + pad); maxy = Math.Min(h - 1, maxy + pad);
        int ow = maxx - minx + 1, oh = maxy - miny + 1;
        var outBuf = new byte[ow * oh * 4];
        for (int y = 0; y < oh; y++)
            for (int x = 0; x < ow; x++)
            {
                int si = (y + miny) * w + (x + minx);
                if (!mask[si]) continue;
                int s = si * 4;
                int d = (y * ow + x) * 4;
                outBuf[d] = buf[s]; outBuf[d + 1] = buf[s + 1];
                outBuf[d + 2] = buf[s + 2]; outBuf[d + 3] = buf[s + 3];
            }
        Save(outBuf, ow, oh, path);
        Console.WriteLine("  -> " + System.IO.Path.GetFileName(path) + "  " + ow + "x" + oh);
    }

    public static void Sheet(string src, string[] outPaths, int lo, int hi)
    {
        int w, h;
        var buf = Load(src, out w, out h);
        KeyBackdrop(buf, w, h, lo, hi);
        List<int[]> comps;
        var label = LabelComponents(buf, w, h, out comps);

        int n = outPaths.Length;
        int cell = w / n;
        int minArea = (w * h) / 900;
        int nc = comps.Count;

        // How each blob's pixels spread over the cells. A blob that holds real
        // weight in several cells is several objects fused by contact shadows and
        // must be sliced; otherwise it is one object leaning into its neighbour
        // (a long tail, a spread wing) and belongs to its heaviest cell alone.
        var spread = new int[nc * n];
        for (int i = 0; i < w * h; i++)
        {
            int lbl = label[i];
            if (lbl < 0 || buf[i * 4 + 3] <= 40) continue;
            int c = Math.Min(n - 1, (i % w) / cell);
            spread[lbl * n + c]++;
        }

        var fused = new bool[nc];
        var homeCell = new int[nc];
        for (int k = 0; k < nc; k++)
        {
            int total = comps[k][4];
            int significant = 0, best = 0;
            for (int c = 0; c < n; c++)
            {
                if (spread[k * n + c] >= total * 0.25) significant++;
                if (spread[k * n + c] > spread[k * n + best]) best = c;
            }
            fused[k] = significant >= 2;
            homeCell[k] = best;
        }

        for (int c = 0; c < n; c++)
        {
            int x0 = c * cell;
            int x1 = (c == n - 1) ? w - 1 : (c + 1) * cell - 1;

            // The dominant blob of the cell is the object itself; smaller blobs are
            // kept only when they touch it (held props), so stray specks from the
            // neighbouring object get dropped.
            int primary = -1;
            for (int k = 0; k < nc; k++)
            {
                if (fused[k] || homeCell[k] != c || comps[k][4] < minArea) continue;
                if (primary < 0 || comps[k][4] > comps[primary][4]) primary = k;
            }
            int primaryArea = primary >= 0 ? comps[primary][4] : 0;
            int slack = (int)(cell * 0.05);
            int inset = (int)(cell * 0.02);

            var mask = new bool[w * h];
            for (int i = 0; i < w * h; i++)
            {
                int lbl = label[i];
                if (lbl < 0 || buf[i * 4 + 3] <= 40) continue;
                var cp = comps[lbl];
                if (cp[4] < minArea) continue;
                if (fused[lbl])
                {
                    int x = i % w;
                    if (x >= x0 + inset && x <= x1 - inset) mask[i] = true;
                    continue;
                }
                if (homeCell[lbl] != c) continue;
                if (lbl == primary) { mask[i] = true; continue; }
                bool big = cp[4] >= primaryArea * 0.15;
                bool touches = primary >= 0
                    && cp[0] <= comps[primary][2] + slack && cp[2] >= comps[primary][0] - slack
                    && cp[1] <= comps[primary][3] + slack && cp[3] >= comps[primary][1] - slack;
                if (big || touches) mask[i] = true;
            }
            Emit(buf, mask, w, h, 6, outPaths[c]);
        }
    }

    public static void Single(string src, string outPath, int lo, int hi)
    {
        int w, h;
        var buf = Load(src, out w, out h);
        KeyBackdrop(buf, w, h, lo, hi);
        List<int[]> comps;
        var label = LabelComponents(buf, w, h, out comps);
        int minArea = (w * h) / 900;
        var mask = new bool[w * h];
        for (int i = 0; i < w * h; i++)
        {
            int lbl = label[i];
            if (lbl < 0 || buf[i * 4 + 3] <= 40) continue;
            if (comps[lbl][4] < minArea) continue;
            mask[i] = true;
        }
        Emit(buf, mask, w, h, 8, outPath);
    }

    public static void Flat(string src, string outPath, int maxWidth)
    {
        using (var bmp = new Bitmap(src))
        {
            int w = bmp.Width, h = bmp.Height;
            if (maxWidth > 0 && w > maxWidth)
            {
                h = (int)(h * (maxWidth / (double)w));
                w = maxWidth;
            }
            using (var dst = new Bitmap(w, h, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(dst))
                {
                    g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                    g.DrawImage(bmp, 0, 0, w, h);
                }
                dst.Save(outPath, ImageFormat.Png);
            }
            Console.WriteLine("flat -> " + System.IO.Path.GetFileName(outPath) + "  " + w + "x" + h);
        }
    }

    public static void Icons(string src, string resDir, string[] folders, int[] sizes, double inset)
    {
        using (var icon = new Bitmap(src))
        {
            int cut = (int)(icon.Width * inset);
            var rect = new Rectangle(cut, cut, icon.Width - cut * 2, icon.Height - cut * 2);
            using (var cropped = icon.Clone(rect, icon.PixelFormat))
            {
                for (int i = 0; i < folders.Length; i++)
                {
                    using (var bmp = new Bitmap(sizes[i], sizes[i], PixelFormat.Format32bppArgb))
                    {
                        using (var g = Graphics.FromImage(bmp))
                        {
                            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                            g.DrawImage(cropped, 0, 0, sizes[i], sizes[i]);
                        }
                        bmp.Save(resDir + "\\" + folders[i] + "\\ic_launcher.png", ImageFormat.Png);
                        bmp.Save(resDir + "\\" + folders[i] + "\\ic_launcher_round.png", ImageFormat.Png);
                    }
                }
            }
        }
        Console.WriteLine("launcher icons done");
    }
}
"@

$src = "C:\Users\Paul\.cursor\projects\c-Dev-flutter-projects-BirdDrop\assets"
$res = "C:\Dev\flutter_projects\BirdDrop\app\src\main\res"
$out = "$res\drawable"

function Sheet([string]$file, [string[]]$names) {
    Write-Host "sheet $file"
    $paths = $names | ForEach-Object { "$out\$_.png" }
    [AssetTool]::Sheet("$src\$file", $paths, 10, 42)
}

Sheet "gen_birds_set.png" @("bird_red", "bird_blue", "bird_yellow", "bird_green")
Sheet "gen_foes_set.png" @("foe_grunt", "foe_builder", "foe_armored", "foe_engineer")
Sheet "gen_wood_set.png" @("wood_1", "wood_2", "wood_3", "wood_4")
Sheet "gen_stone_set.png" @("stone_1", "stone_2", "stone_3", "stone_4")
Sheet "gen_metal_set.png" @("metal_1", "metal_2", "metal_3", "metal_4")

[AssetTool]::Single("$src\gen_launcher.png", "$out\cannon.png", 10, 42)
[AssetTool]::Single("$src\gen_logo.png", "$out\game_logo.png", 10, 42)

[AssetTool]::Flat("$src\gen_loading_h.png", "$out\loading_horizontal.png", 0)
[AssetTool]::Flat("$src\gen_loading_v.png", "$out\loading_vertical.png", 0)
[AssetTool]::Flat("$src\gen_bg_foundry.png", "$out\bg_foundry.png", 0)
[AssetTool]::Flat("$src\gen_bg_volcano.png", "$out\bg_volcano.png", 0)

[AssetTool]::Icons(
    "$src\gen_icon.png",
    $res,
    @("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"),
    @(48, 72, 96, 144, 192),
    0.06
)

# Retire the assets that carried infringing motifs.
$stale = @(
    "pig_normal", "pig_worker", "pig_armored", "pig_engineer",
    "wood_tower_small", "wood_wall", "wood_hut", "wood_tower_tall",
    "bg_pig_factory", "bird_master", "device_magnet", "device_portal_b"
)
foreach ($name in $stale) {
    $p = "$out\$name.png"
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "removed $name.png" }
}
Write-Host "ALL DONE"


