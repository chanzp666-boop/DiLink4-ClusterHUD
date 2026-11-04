package com.byd.clusterassist;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/**
 * 元素样式面板：6 个元素 × 颜色(深/浅) / 大小 / 透明度 / 描边(开关/粗细/颜色)。
 * 全部独立存 /sdcard/clusterlab_style.txt，overlay 每 2 秒重读。
 */
public class StyleEditor extends Dialog {
    private int BG, PANEL, TXT, DIM, SEL, OK, WARN, ACCENT;

    /**
     * 色板：浅色一行 + 对应深色一行，方便"绿 / 深绿"这种成对选择。
     */
    private static final String[] SWATCH = {
            // 中性
            "#FFFFFF", "#E9EFF7", "#BFD2E6", "#8496AE",
            // 中性深色
            "#5B6A7D", "#3E4C60", "#243244", "#0A1220",
            // 蓝
            "#4DA3FF", "#1170FF", "#0B4FBF", "#062B66",
            // 绿
            "#2FD79B", "#0E9F6E", "#0A6B4A", "#053B29",
            // 黄 / 橙
            "#FFD166", "#FFB020", "#B26A00", "#6B4000",
            // 红 / 粉
            "#FF6B6B", "#D93A3A", "#8E1F1F", "#4A0F0F",
            // 紫
            "#B388FF", "#7C4DFF", "#4527A0", "#221350"
    };

    private final Map<String, TextView> elChips = new java.util.HashMap<String, TextView>();
    private final TextView[] modeBtns = new TextView[2];
    private final TextView[] targetBtns = new TextView[2];
    private String el = "cap";
    private boolean night = true;
    private boolean strokeTarget = false;

    private PreviewView preview;
    private EditText hex;
    private boolean syncing = false;
    private TextView sizeVal, alphaVal, strokeWVal, strokeOnBtn;
    private TextView[] weightBtns;
    private GradientDrawable unused;

    public StyleEditor(Context c) { super(c); }

    private int dp(float v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private TextView button(String label, int bg, int fg, float sp) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, 10));
        t.setPadding(dp(8), dp(9), dp(8), dp(9));
        t.setClickable(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTextColor(TXT);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        Ui.refresh(getContext());
        night = Ui.night;          // 默认编辑"当前界面正在用的那套"，避免改了半天看不到
        BG = Ui.bg(); PANEL = Ui.card2(); TXT = Ui.txt(); DIM = Ui.dim();
        SEL = Ui.sel(); OK = Ui.ok(); WARN = Ui.warn(); ACCENT = Ui.primary();
        Window w = getWindow();
        if (w != null) w.setBackgroundDrawable(round(BG, 18));

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(BG);

        TextView title = new TextView(getContext());
        title.setText("元素样式");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTextColor(TXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView sub = new TextView(getContext());
        sub.setText("每个元素独立：颜色(深/浅)、大小、透明度、描边 · 写 /sdcard/clusterlab_style.txt");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setTextColor(DIM);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        // 元素选择
        for (int i = 0; i < Style.EL.length; i += 3) {
            LinearLayout r = row();
            for (int j = i; j < i + 3 && j < Style.EL.length; j++) {
                final String id = Style.EL[j];
                TextView chip = button(Style.LABEL[j], PANEL, TXT, 13f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = dp(8);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { el = id; syncAll(); }
                });
                elChips.put(id, chip);
                r.addView(chip);
            }
            root.addView(r);
        }

        // 模式 + 预览
        LinearLayout modeRow = row();
        modeBtns[0] = button("深色", SEL, 0xFFFFFFFF, 13f);
        modeBtns[1] = button("浅色", PANEL, TXT, 13f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.rightMargin = dp(8);
        modeBtns[0].setLayoutParams(mlp);
        modeBtns[1].setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeBtns[0].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { night = true; syncAll(); }
        });
        modeBtns[1].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { night = false; syncAll(); }
        });
        modeRow.addView(modeBtns[0]);
        modeRow.addView(modeBtns[1]);
        root.addView(modeRow);

        TextView modeHint = new TextView(getContext());
        modeHint.setText("深色 / 浅色 只切换“颜色”，大小 · 透明度 · 描边两套共用");
        modeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        modeHint.setTextColor(DIM);
        modeHint.setPadding(0, 0, 0, dp(8));
        root.addView(modeHint);

        preview = new PreviewView(getContext());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        plp.bottomMargin = dp(8);
        preview.setLayoutParams(plp);
        preview.setOnClickListener(null);
        root.addView(preview);

        // 颜色目标
        LinearLayout tRow = row();
        targetBtns[0] = button("文字颜色", SEL, 0xFFFFFFFF, 13f);
        targetBtns[1] = button("描边颜色", PANEL, TXT, 13f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = dp(8);
        targetBtns[0].setLayoutParams(tlp);
        targetBtns[1].setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targetBtns[0].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { strokeTarget = false; syncAll(); }
        });
        targetBtns[1].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { strokeTarget = true; syncAll(); }
        });
        tRow.addView(targetBtns[0]);
        tRow.addView(targetBtns[1]);
        root.addView(tRow);

        // 色板
        for (int i = 0; i < SWATCH.length; i += 4) {
            LinearLayout r = row();
            for (int j = i; j < i + 4 && j < SWATCH.length; j++) {
                final String c = SWATCH[j];
                TextView sw = new TextView(getContext());
                GradientDrawable g = round(Style.parse(c, 0xFFFFFFFF), 8);
                g.setStroke(dp(1), Ui.swatchBorder());
                sw.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
                lp.rightMargin = dp(8);
                sw.setLayoutParams(lp);
                sw.setClickable(true);
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { applyColor(c); }
                });
                r.addView(sw);
            }
            root.addView(r);
        }

        // hex + 应用
        LinearLayout hexRow = row();
        hex = new EditText(getContext());
        hex.setHint("#RRGGBB");
        hex.setHintTextColor(DIM);
        hex.setTextColor(TXT);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setSingleLine(true);
        hex.setBackground(round(PANEL, 10));
        hex.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.rightMargin = dp(8);
        hex.setLayoutParams(hlp);
        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (syncing) return;
                scheduleHexApply(e.toString());
            }
        });
        hexRow.addView(hex);
        root.addView(hexRow);

        // 字重：细 / 基本 / 加粗 / 超粗
        LinearLayout wRow = row();
        wRow.addView(label("字重"));
        weightBtns = new TextView[4];
        for (int wi = 0; wi < 4; wi++) {
            final int chosen = wi;
            TextView btn = button(Style.WEIGHT_NAME[wi], PANEL, TXT, 12f);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Style.set(el, "weight", String.valueOf(chosen));
                    refreshWeight();
                    Journal.add("字重 · " + Style.WEIGHT_NAME[chosen]);
                }
            });
            weightBtns[wi] = btn;
            wRow.addView(btn);
        }
        root.addView(wRow);

        // 大小 / 透明度 / 描边开关 / 描边粗细
        LinearLayout sRow = row();
        sRow.addView(label("大小"));
        sRow.addView(stepBtn(-1, new Runnable() { @Override public void run() { bumpSize(-1); } }));
        sizeVal = valueView();
        sRow.addView(sizeVal);
        sRow.addView(stepBtn(+1, new Runnable() { @Override public void run() { bumpSize(+1); } }));
        root.addView(sRow);

        LinearLayout aRow = row();
        aRow.addView(label("透明度"));
        aRow.addView(stepBtn(-16, new Runnable() { @Override public void run() { bumpAlpha(-16); } }));
        alphaVal = valueView();
        aRow.addView(alphaVal);
        aRow.addView(stepBtn(+16, new Runnable() { @Override public void run() { bumpAlpha(+16); } }));
        root.addView(aRow);

        LinearLayout kRow = row();
        kRow.addView(label("描边"));
        strokeOnBtn = button("显示", OK, Ui.onAccent(), 13f);
        LinearLayout.LayoutParams onLp = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
        onLp.rightMargin = dp(10);
        strokeOnBtn.setLayoutParams(onLp);
        strokeOnBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleStroke(); }
        });
        kRow.addView(strokeOnBtn);
        kRow.addView(stepBtn(-1, new Runnable() { @Override public void run() { bumpStrokeW(-1); } }));
        strokeWVal = valueView();
        kRow.addView(strokeWVal);
        kRow.addView(stepBtn(+1, new Runnable() { @Override public void run() { bumpStrokeW(+1); } }));
        root.addView(kRow);

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, dp(6), 0, 0);
        TextView reset = button("恢复默认", PANEL, WARN, 13f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = dp(8);
        reset.setLayoutParams(blp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        Style.reset();
                        Journal.add("元素样式已恢复默认");
                    }
                }).start();
                syncAll();
            }
        });
        bottom.addView(reset);
        TextView close = button("关闭", ACCENT, TXT, 13f);
        close.setPadding(dp(26), dp(10), dp(26), dp(10));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        bottom.addView(close);
        root.addView(bottom);

        ScrollView wrap = new ScrollView(getContext());
        wrap.addView(root);
        setContentView(wrap);
        syncAll();
    }

    /** 字重按钮高亮当前值。 */
    private void refreshWeight() {
        if (weightBtns == null) return;
        int cur = Style.intOf(el, "weight", Style.DEF_WEIGHT[Math.max(0, indexOf(el))]);
        for (int i = 0; i < weightBtns.length; i++) {
            boolean on = (i == cur);
            weightBtns[i].setBackground(round(on ? SEL : PANEL, 8));
            weightBtns[i].setTextColor(on ? 0xFFFFFFFF : TXT);
        }
    }

    private TextView stepBtn(final int delta, final Runnable r) {
        TextView t = button(delta < 0 ? "−" : "+", PANEL, TXT, 17f);
        t.setPadding(dp(16), dp(6), dp(16), dp(6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return t;
    }

    private TextView valueView() {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(OK);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    // ---------- 交互 ----------

    private final android.os.Handler debounce = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pending = null;

    /** 输入 HEX 时不用点「应用」：停手 400ms 就自动生效。 */
    private void scheduleHexApply(final String raw) {
        if (pending != null) debounce.removeCallbacks(pending);
        pending = new Runnable() {
            @Override public void run() {
                String s = raw.trim().toUpperCase();
                if (s.length() == 0) return;
                if (!s.startsWith("#")) s = "#" + s;
                boolean okChars = s.length() == 7 || s.length() == 9;
                for (int i = 1; okChars && i < s.length(); i++) {
                    if ("0123456789ABCDEF".indexOf(s.charAt(i)) < 0) okChars = false;
                }
                if (!okChars) return;   // 还没输完，等下一次
                applyColor(s);
            }
        };
        debounce.postDelayed(pending, 400);
    }

    private void applyColor(final String hexValue) {
        final String prop = (strokeTarget ? "stroke." : "color.") + (night ? "night" : "light");
        preview.setColorNow(hexValue);
        new Thread(new Runnable() {
            @Override public void run() {
                Style.set(el, prop, hexValue);
                Journal.add(Style.LABEL[Style.EL.length > 0 ? indexOf(el) : 0]
                        + " · " + (strokeTarget ? "描边" : "文字") + " · " + (night ? "深色" : "浅色")
                        + " = " + hexValue);
            }
        }).start();
    }

    private int indexOf(String id) {
        for (int i = 0; i < Style.EL.length; i++) if (Style.EL[i].equals(id)) return i;
        return 0;
    }

    private void bumpSize(final int delta) {
        final int cur = Style.intOf(el, "size", Style.DEF_SIZE[indexOf(el)]);
        final int idx = indexOf(el);
        int v = Math.max(Style.MIN_SIZE[idx], Math.min(Style.MAX_SIZE[idx], cur + delta));
        sizeVal.setText(String.valueOf(v));
        new Thread(new Runnable() {
            @Override public void run() { Style.set(el, "size", String.valueOf(v)); }
        }).start();
        preview.invalidate();
    }

    private void bumpAlpha(final int delta) {
        final int cur = Style.intOf(el, "alpha", Style.DEF_ALPHA[indexOf(el)]);
        int v = Math.max(0, Math.min(255, cur + delta));
        alphaVal.setText(String.valueOf(v));
        new Thread(new Runnable() {
            @Override public void run() { Style.set(el, "alpha", String.valueOf(v)); }
        }).start();
        preview.invalidate();
    }

    private void bumpStrokeW(final int delta) {
        final int cur = Style.intOf(el, "stroke.w", Style.DEF_STROKE_W[indexOf(el)]);
        int v = Math.max(0, Math.min(14, cur + delta));
        strokeWVal.setText(String.valueOf(v));
        new Thread(new Runnable() {
            @Override public void run() { Style.set(el, "stroke.w", String.valueOf(v)); }
        }).start();
        preview.invalidate();
    }

    private void toggleStroke() {
        final int cur = Style.intOf(el, "stroke.on", 1);
        final int v = cur >= 1 ? 0 : 1;
        new Thread(new Runnable() {
            @Override public void run() { Style.set(el, "stroke.on", String.valueOf(v)); }
        }).start();
        strokeOnBtn.setText(v == 1 ? "显示" : "不显示");
        strokeOnBtn.setBackground(round(v == 1 ? OK : Ui.offBadge(), 10));
        strokeOnBtn.setTextColor(v == 1 ? Ui.onAccent() : Ui.offBadgeText());
        preview.invalidate();
    }

    private void syncAll() {
        for (Map.Entry<String, TextView> e : elChips.entrySet()) {
            boolean on = e.getKey().equals(el);
            e.getValue().setBackground(round(on ? SEL : PANEL, 10));
            e.getValue().setTextColor(on ? 0xFFFFFFFF : TXT);
        }
        // 标一下哪套是"车机当前正在用的"，避免改错那套还以为没生效
        modeBtns[0].setText(Ui.night ? "深色（当前）" : "深色");
        modeBtns[1].setText(Ui.night ? "浅色" : "浅色（当前）");
        modeBtns[0].setBackground(round(night ? SEL : PANEL, 10));
        modeBtns[0].setTextColor(night ? 0xFFFFFFFF : TXT);
        modeBtns[1].setBackground(round(night ? PANEL : SEL, 10));
        modeBtns[1].setTextColor(night ? TXT : 0xFFFFFFFF);

        targetBtns[0].setBackground(round(strokeTarget ? PANEL : SEL, 10));
        targetBtns[0].setTextColor(strokeTarget ? TXT : 0xFFFFFFFF);
        targetBtns[1].setBackground(round(strokeTarget ? SEL : PANEL, 10));
        targetBtns[1].setTextColor(strokeTarget ? 0xFFFFFFFF : TXT);

        int i = indexOf(el);
        sizeVal.setText(String.valueOf(Style.intOf(el, "size", Style.DEF_SIZE[i])));
        alphaVal.setText(String.valueOf(Style.intOf(el, "alpha", Style.DEF_ALPHA[i])));
        strokeWVal.setText(String.valueOf(Style.intOf(el, "stroke.w", Style.DEF_STROKE_W[i])));
        int on = Style.intOf(el, "stroke.on", 1);
        strokeOnBtn.setText(on >= 1 ? "显示" : "不显示");
        strokeOnBtn.setBackground(round(on >= 1 ? OK : Ui.offBadge(), 10));
        strokeOnBtn.setTextColor(on >= 1 ? Ui.onAccent() : Ui.offBadgeText());

        syncing = true;
        hex.setText(Style.colorHex(el, night, strokeTarget));
        syncing = false;
        refreshWeight();
        preview.refresh(el, night, strokeTarget);
    }

    /** 用真实参数画一遍，所见即所得。 */
    private class PreviewView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String overrideHex;

        PreviewView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            Typeface tf = Typeface.create("sans-serif-black", Typeface.NORMAL);
            stroke.setTypeface(tf);
            fill.setTypeface(tf);
        }

        void refresh(String elId, boolean nightMode, boolean asStroke) {
            overrideHex = null;
            invalidate();
        }

        void setColorNow(String hexValue) { overrideHex = hexValue; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            // 预览底色跟随"当前选的模式"，不是跟随界面主题：
            // 选深色就应该在深底上看白字，否则白字压在浅底上什么都看不见
            bgp.setColor(night ? 0xFF0E1725 : 0xFFEAF0F8);
            cv.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), bgp);

            int i = indexOf(el);
            float size = Style.intOf(el, "size", Style.DEF_SIZE[i]);
            if (size < 16f) size = 16f;
            if (size > 40f) size = 40f;
            int alpha = Style.intOf(el, "alpha", Style.DEF_ALPHA[i]);
            int color = Style.parse(overrideHex != null ? overrideHex
                    : Style.colorHex(el, night, strokeTarget), 0xFFFFFFFF);
            int argb = (color & 0x00FFFFFF) | (alpha << 24);

            float w = Style.intOf(el, "stroke.w", Style.DEF_STROKE_W[i]);
            boolean on = Style.intOf(el, "stroke.on", 1) >= 1;
            int sc = Style.parse(Style.colorHex(el, night, true), 0xFF000000);
            sc = (sc & 0x00FFFFFF) | (alpha << 24);

            fill.setTextSize(size);
            stroke.setTextSize(size);
            stroke.setStrokeWidth(Math.max(1f, w));
            stroke.setColor(sc);
            fill.setColor(argb);

            String sample = "Aa 255 kPa";
            float x = dp(14), y = getHeight() / 2f + size * 0.35f;
            if (on) cv.drawText(sample, x, y, stroke);
            cv.drawText(sample, x, y, fill);
        }
    }
}
