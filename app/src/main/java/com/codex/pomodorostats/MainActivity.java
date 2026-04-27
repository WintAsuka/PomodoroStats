package com.codex.pomodorostats;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private PomodoroView view;
    private CountDownTimer timer;
    private long remainingMs;
    private boolean running;
    private boolean focusMode = true;
    private int focusDoneInCycle = 0;
    private Settings settings;
    private Store store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        store = new Store(this);
        settings = store.loadSettings();
        remainingMs = settings.focusMin * 60_000L;
        view = new PomodoroView(this);
        setContentView(view);
    }

    private void toggleTimer() {
        if (running) {
            pauseTimer();
        } else {
            startTimer();
        }
    }

    private void startTimer() {
        if (running) return;
        running = true;
        timer = new CountDownTimer(Math.max(remainingMs, 1000), 250) {
            @Override public void onTick(long millisUntilFinished) {
                remainingMs = millisUntilFinished;
                view.invalidate();
            }

            @Override public void onFinish() {
                remainingMs = 0;
                running = false;
                if (focusMode) {
                    focusDoneInCycle++;
                    store.addSession(settings.focusMin);
                }
                buzz();
                advancePhase();
            }
        }.start();
        view.invalidate();
    }

    private void pauseTimer() {
        if (timer != null) timer.cancel();
        running = false;
        view.invalidate();
    }

    private void resetTimer() {
        pauseTimer();
        remainingMs = currentPhaseMinutes() * 60_000L;
        view.invalidate();
    }

    private void skipPhase() {
        pauseTimer();
        advancePhase();
    }

    private void advancePhase() {
        if (focusMode) {
            boolean longBreak = settings.rounds > 0 && focusDoneInCycle % settings.rounds == 0;
            focusMode = false;
            remainingMs = (longBreak ? settings.longBreakMin : settings.breakMin) * 60_000L;
        } else {
            focusMode = true;
            remainingMs = settings.focusMin * 60_000L;
            if (settings.autoFocus) startTimer();
        }
        view.invalidate();
    }

    private int currentPhaseMinutes() {
        if (focusMode) return settings.focusMin;
        boolean longBreak = settings.rounds > 0 && focusDoneInCycle > 0 && focusDoneInCycle % settings.rounds == 0;
        return longBreak ? settings.longBreakMin : settings.breakMin;
    }

    private void buzz() {
        if (!settings.vibrate) return;
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.vibrate(450);
        } catch (Exception ignored) {
        }
    }

    private void updateSetting(String key, int delta) {
        if ("focus".equals(key)) settings.focusMin = clamp(settings.focusMin + delta, 5, 120);
        if ("break".equals(key)) settings.breakMin = clamp(settings.breakMin + delta, 1, 60);
        if ("long".equals(key)) settings.longBreakMin = clamp(settings.longBreakMin + delta, 5, 90);
        if ("rounds".equals(key)) settings.rounds = clamp(settings.rounds + delta, 2, 12);
        if ("goal".equals(key)) settings.dailyGoal = clamp(settings.dailyGoal + delta, 1, 16);
        store.saveSettings(settings);
        resetTimer();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatTime(long ms) {
        long total = Math.max(0, (ms + 999) / 1000);
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    static class Settings {
        int focusMin = 25;
        int breakMin = 5;
        int longBreakMin = 15;
        int rounds = 4;
        int dailyGoal = 8;
        int accent = Color.rgb(228, 86, 79);
        boolean autoFocus = false;
        boolean vibrate = true;
    }

    static class Session {
        String date;
        String month;
        int minutes;
        long startedAt;
    }

    static class Store {
        private final SharedPreferences prefs;
        private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        private final SimpleDateFormat monthFmt = new SimpleDateFormat("yyyy-MM", Locale.CHINA);

        Store(Context context) {
            prefs = context.getSharedPreferences("pomodoro_store", MODE_PRIVATE);
        }

        Settings loadSettings() {
            Settings s = new Settings();
            s.focusMin = prefs.getInt("focusMin", s.focusMin);
            s.breakMin = prefs.getInt("breakMin", s.breakMin);
            s.longBreakMin = prefs.getInt("longBreakMin", s.longBreakMin);
            s.rounds = prefs.getInt("rounds", s.rounds);
            s.dailyGoal = prefs.getInt("dailyGoal", s.dailyGoal);
            s.accent = prefs.getInt("accent", s.accent);
            s.autoFocus = prefs.getBoolean("autoFocus", s.autoFocus);
            s.vibrate = prefs.getBoolean("vibrate", s.vibrate);
            return s;
        }

        void saveSettings(Settings s) {
            prefs.edit()
                    .putInt("focusMin", s.focusMin)
                    .putInt("breakMin", s.breakMin)
                    .putInt("longBreakMin", s.longBreakMin)
                    .putInt("rounds", s.rounds)
                    .putInt("dailyGoal", s.dailyGoal)
                    .putInt("accent", s.accent)
                    .putBoolean("autoFocus", s.autoFocus)
                    .putBoolean("vibrate", s.vibrate)
                    .apply();
        }

        void addSession(int minutes) {
            try {
                JSONArray arr = new JSONArray(prefs.getString("sessions", "[]"));
                Date now = new Date();
                JSONObject item = new JSONObject();
                item.put("date", dayFmt.format(now));
                item.put("month", monthFmt.format(now));
                item.put("minutes", minutes);
                item.put("startedAt", now.getTime());
                arr.put(item);
                prefs.edit().putString("sessions", arr.toString()).apply();
            } catch (Exception ignored) {
            }
        }

        List<Session> sessions() {
            ArrayList<Session> list = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(prefs.getString("sessions", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Session s = new Session();
                    s.date = o.optString("date");
                    s.month = o.optString("month");
                    s.minutes = o.optInt("minutes");
                    s.startedAt = o.optLong("startedAt");
                    list.add(s);
                }
            } catch (Exception ignored) {
            }
            return list;
        }
    }

    class PomodoroView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Action> actions = new ArrayList<>();
        private int tab = 0;
        private float scrollY = 0;
        private float downY = 0;
        private float lastY = 0;
        private boolean dragging = false;
        private int contentBottom = 0;
        private final float density;
        private final float scaledDensity;
        private final int ink = Color.rgb(33, 35, 39);
        private final int muted = Color.rgb(104, 108, 116);
        private final int paper = Color.rgb(248, 247, 243);
        private final int panel = Color.WHITE;
        private final int soft = Color.rgb(240, 238, 231);
        private final int line = Color.rgb(226, 224, 216);
        private final int[] accents = {
                Color.rgb(228, 86, 79), Color.rgb(43, 136, 116),
                Color.rgb(65, 105, 190), Color.rgb(204, 133, 46),
                Color.rgb(132, 91, 174)
        };

        PomodoroView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            setBackgroundColor(paper);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            actions.clear();
            p.setLetterSpacing(0);
            int w = getWidth();
            int h = getHeight();
            drawHeader(c, w);
            c.save();
            c.translate(0, -scrollY);
            if (tab == 0) drawTimer(c, w, h);
            if (tab == 1) drawStats(c, w, h);
            if (tab == 2) drawSettings(c, w, h);
            c.restore();
            scrollY = clampScroll(scrollY, h);
            drawTabs(c, w, h);
        }

        private int dp(float value) {
            return Math.round(value * density);
        }

        private float sp(float value) {
            return value * scaledDensity;
        }

        private float clampScroll(float value, int h) {
            int max = Math.max(0, contentBottom - (h - dp(98)));
            return Math.max(0, Math.min(value, max));
        }

        private int top() {
            return dp(92);
        }

        private int side() {
            return dp(20);
        }

        private void drawHeader(Canvas c, int w) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(paper);
            c.drawRect(0, 0, w, dp(86), p);
            text(c, "番茄钟", side(), dp(54), 28, ink, Paint.Align.LEFT, true);
            String phase = focusMode ? "专注中" : "休息中";
            pill(c, w - side() - dp(92), dp(25), dp(92), dp(38), phase, settings.accent, Color.WHITE);
        }

        private void drawTimer(Canvas c, int w, int h) {
            int s = side();
            int cx = w / 2;
            int cy = dp(242);
            int radius = Math.min(w - dp(104), dp(252)) / 2;
            round(c, s, dp(92), w - s * 2, dp(304), dp(24), panel, true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(14));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(soft);
            c.drawCircle(cx, cy, radius, p);
            float total = currentPhaseMinutes() * 60_000f;
            float sweep = total <= 0 ? 0 : 360f * (1f - remainingMs / total);
            p.setColor(settings.accent);
            c.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), -90, sweep, false, p);
            p.setStyle(Paint.Style.FILL);

            text(c, focusMode ? "专注" : "休息", cx, cy - dp(44), 20, muted, Paint.Align.CENTER, true);
            text(c, formatTime(remainingMs), cx, cy + dp(18), 48, ink, Paint.Align.CENTER, true);
            text(c, "今日完成 " + todayCount() + " / " + settings.dailyGoal + " 个", cx, cy + dp(58), 17, muted, Paint.Align.CENTER, false);

            int buttonY = dp(420);
            button(c, s, buttonY, w - s * 2, dp(64), running ? "暂停" : "开始", settings.accent, Color.WHITE, () -> toggleTimer());
            int half = (w - s * 2 - dp(12)) / 2;
            button(c, s, buttonY + dp(76), half, dp(58), "重置", panel, ink, () -> resetTimer());
            button(c, s + half + dp(12), buttonY + dp(76), half, dp(58), "跳过", panel, ink, () -> skipPhase());

            int y = buttonY + dp(158);
            metric(c, s, y, w - s * 2, "本周专注", weekMinutes() + " 分钟", settings.accent);
            metric(c, s, y + dp(88), w - s * 2, "本月专注", monthMinutes() + " 分钟", Color.rgb(43, 136, 116));
            contentBottom = y + dp(188);
        }

        private void drawStats(Canvas c, int w, int h) {
            List<Session> sessions = store.sessions();
            Map<String, Integer> days = new HashMap<>();
            Map<String, Integer> months = new HashMap<>();
            int total = 0;
            for (Session s : sessions) {
                days.put(s.date, days.containsKey(s.date) ? days.get(s.date) + s.minutes : s.minutes);
                months.put(s.month, months.containsKey(s.month) ? months.get(s.month) + s.minutes : s.minutes);
                total += s.minutes;
            }
            int s = side();
            int y = top();
            metric(c, s, y, w - s * 2, "累计专注", total + " 分钟", settings.accent);
            metric(c, s, y + dp(88), w - s * 2, "连续记录天数", streak(days) + " 天", Color.rgb(65, 105, 190));

            int cardY = y + dp(196);
            chartCard(c, "最近 7 天", days, false, s, cardY, w - s * 2, dp(238));
            chartCard(c, "最近 6 个月", months, true, s, cardY + dp(270), w - s * 2, dp(238));
            contentBottom = cardY + dp(540);
        }

        private void drawSettings(Canvas c, int w, int h) {
            int s = side();
            int y = top();
            settingStepper(c, "focus", "专注时长", settings.focusMin + " 分钟", y, w);
            settingStepper(c, "break", "短休息", settings.breakMin + " 分钟", y + dp(82), w);
            settingStepper(c, "long", "长休息", settings.longBreakMin + " 分钟", y + dp(164), w);
            settingStepper(c, "rounds", "长休息间隔", settings.rounds + " 轮", y + dp(246), w);
            settingStepper(c, "goal", "每日目标", settings.dailyGoal + " 个", y + dp(328), w);

            int colorY = y + dp(438);
            round(c, s, colorY, w - s * 2, dp(104), dp(20), panel, true);
            text(c, "主题色", s + dp(18), colorY + dp(35), 18, ink, Paint.Align.LEFT, true);
            for (int i = 0; i < accents.length; i++) {
                final int color = accents[i];
                swatch(c, s + dp(20) + i * dp(58), colorY + dp(52), color, () -> {
                    settings.accent = color;
                    store.saveSettings(settings);
                    invalidate();
                });
            }

            toggle(c, s, colorY + dp(126), w - s * 2, "完成休息后自动开始专注", settings.autoFocus, () -> {
                settings.autoFocus = !settings.autoFocus;
                store.saveSettings(settings);
                invalidate();
            });
            toggle(c, s, colorY + dp(196), w - s * 2, "阶段结束振动提醒", settings.vibrate, () -> {
                settings.vibrate = !settings.vibrate;
                store.saveSettings(settings);
                invalidate();
            });
            contentBottom = colorY + dp(286);
        }

        private void drawTabs(Canvas c, int w, int h) {
            int navH = dp(74);
            int y = h - navH - dp(12);
            round(c, side(), y, w - side() * 2, navH, dp(24), panel, true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(line);
            c.drawRoundRect(new RectF(side(), y, w - side(), y + navH), dp(24), dp(24), p);
            String[] names = {"计时", "统计", "设置"};
            int inner = w - side() * 2;
            for (int i = 0; i < 3; i++) {
                final int next = i;
                int x = side() + i * (inner / 3);
                int tw = inner / 3;
                if (tab == i) round(c, x + dp(6), y + dp(8), tw - dp(12), navH - dp(16), dp(18), settings.accent, true);
                text(c, names[i], x + tw / 2, y + dp(46), 18, tab == i ? Color.WHITE : muted, Paint.Align.CENTER, true);
                actions.add(new Action(x, y, tw, navH, () -> {
                    tab = next;
                    scrollY = 0;
                    invalidate();
                }));
            }
        }

        private void metric(Canvas c, int x, int y, int w, String label, String value, int color) {
            round(c, x, y, w, dp(72), dp(20), panel, true);
            text(c, label, x + dp(18), y + dp(30), 16, muted, Paint.Align.LEFT, false);
            text(c, value, x + w - dp(18), y + dp(48), 24, color, Paint.Align.RIGHT, true);
        }

        private void settingStepper(Canvas c, String key, String label, String value, int y, int w) {
            int s = side();
            round(c, s, y, w - s * 2, dp(68), dp(18), panel, true);
            text(c, label, s + dp(18), y + dp(42), 18, ink, Paint.Align.LEFT, true);
            text(c, value, w - s - dp(112), y + dp(42), 17, muted, Paint.Align.RIGHT, false);
            smallButton(c, w - s - dp(96), y + dp(12), "-", () -> updateSetting(key, -1));
            smallButton(c, w - s - dp(46), y + dp(12), "+", () -> updateSetting(key, 1));
        }

        private void chartCard(Canvas c, String title, Map<String, Integer> values, boolean monthly, int x, int y, int w, int h) {
            round(c, x, y, w, h, dp(22), panel, true);
            text(c, title, x + dp(18), y + dp(40), 19, ink, Paint.Align.LEFT, true);
            drawBars(c, values, monthly, x + dp(18), y + dp(62), w - dp(36), h - dp(82));
        }

        private void drawBars(Canvas c, Map<String, Integer> values, boolean monthly, int x, int y, int w, int h) {
            int count = monthly ? 6 : 7;
            int max = 1;
            ArrayList<String> keys = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat fmt = new SimpleDateFormat(monthly ? "yyyy-MM" : "yyyy-MM-dd", Locale.CHINA);
            SimpleDateFormat labelFmt = new SimpleDateFormat(monthly ? "MM月" : "dd", Locale.CHINA);
            if (monthly) cal.set(Calendar.DAY_OF_MONTH, 1);
            for (int i = count - 1; i >= 0; i--) {
                Calendar item = (Calendar) cal.clone();
                item.add(monthly ? Calendar.MONTH : Calendar.DAY_OF_YEAR, -i);
                String key = fmt.format(item.getTime());
                keys.add(key);
                max = Math.max(max, values.containsKey(key) ? values.get(key) : 0);
            }
            int gap = dp(8);
            int bw = (w - gap * (count - 1)) / count;
            for (int i = 0; i < keys.size(); i++) {
                int v = values.containsKey(keys.get(i)) ? values.get(keys.get(i)) : 0;
                int bh = Math.max(dp(10), (int) ((h - dp(32)) * (v / (float) max)));
                int bx = x + i * (bw + gap);
                round(c, bx, y + h - dp(28) - bh, bw, bh, dp(8), settings.accent, true);
                try {
                    Date d = fmt.parse(keys.get(i));
                    text(c, labelFmt.format(d), bx + bw / 2, y + h - dp(5), 12, muted, Paint.Align.CENTER, false);
                } catch (Exception ignored) {
                }
            }
        }

        private int todayCount() {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
            int n = 0;
            for (Session s : store.sessions()) if (today.equals(s.date)) n++;
            return n;
        }

        private int weekMinutes() {
            Calendar start = Calendar.getInstance();
            start.add(Calendar.DAY_OF_YEAR, -6);
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            int total = 0;
            for (Session s : store.sessions()) if (s.startedAt >= start.getTimeInMillis()) total += s.minutes;
            return total;
        }

        private int monthMinutes() {
            String month = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
            int total = 0;
            for (Session s : store.sessions()) if (month.equals(s.month)) total += s.minutes;
            return total;
        }

        private int streak(Map<String, Integer> days) {
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            int count = 0;
            while (days.containsKey(fmt.format(cal.getTime()))) {
                count++;
                cal.add(Calendar.DAY_OF_YEAR, -1);
            }
            return count;
        }

        private void button(Canvas c, int x, int y, int w, int h, String text, int bg, int fg, Runnable r) {
            round(c, x, y, w, h, dp(18), bg, true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(bg == panel ? line : bg);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(18), dp(18), p);
            p.setStyle(Paint.Style.FILL);
            text(c, text, x + w / 2, y + h / 2 + dp(7), 20, fg, Paint.Align.CENTER, true);
            actions.add(new Action(x, Math.round(y - scrollY), w, h, r));
        }

        private void smallButton(Canvas c, int x, int y, String text, Runnable r) {
            round(c, x, y, dp(42), dp(42), dp(14), soft, true);
            text(c, text, x + dp(21), y + dp(29), 24, ink, Paint.Align.CENTER, true);
            actions.add(new Action(x, Math.round(y - scrollY), dp(42), dp(42), r));
        }

        private void swatch(Canvas c, int x, int y, int color, Runnable r) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(x + dp(20), y + dp(20), dp(18), p);
            if (settings.accent == color) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(ink);
                c.drawCircle(x + dp(20), y + dp(20), dp(24), p);
            }
            actions.add(new Action(x - dp(8), Math.round(y - scrollY - dp(8)), dp(56), dp(56), r));
        }

        private void toggle(Canvas c, int x, int y, int w, String label, boolean on, Runnable r) {
            round(c, x, y, w, dp(58), dp(18), panel, true);
            text(c, label, x + dp(16), y + dp(37), 17, ink, Paint.Align.LEFT, false);
            int tx = x + w - dp(66);
            round(c, tx, y + dp(15), dp(50), dp(28), dp(14), on ? settings.accent : Color.rgb(215, 214, 207), true);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(tx + (on ? dp(36) : dp(14)), y + dp(29), dp(11), p);
            actions.add(new Action(x, Math.round(y - scrollY), w, dp(58), r));
        }

        private void pill(Canvas c, int x, int y, int w, int h, String text, int bg, int fg) {
            round(c, x, y, w, h, h / 2, bg, true);
            text(c, text, x + w / 2, y + h / 2 + dp(5), 14, fg, Paint.Align.CENTER, true);
        }

        private void round(Canvas c, int x, int y, int w, int h, int r, int color, boolean fill) {
            p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setColor(color);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, p);
        }

        private void text(Canvas c, String text, float x, float y, float size, int color, Paint.Align align, boolean bold) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(sp(size));
            p.setTextAlign(align);
            p.setFakeBoldText(bold);
            c.drawText(text, x, y, p);
            p.setFakeBoldText(false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downY = event.getY();
                lastY = downY;
                dragging = false;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dy = lastY - event.getY();
                if (Math.abs(event.getY() - downY) > dp(6)) dragging = true;
                scrollY = clampScroll(scrollY + dy, getHeight());
                lastY = event.getY();
                invalidate();
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (!dragging) {
                for (int i = actions.size() - 1; i >= 0; i--) {
                    Action a = actions.get(i);
                    if (event.getX() >= a.x && event.getX() <= a.x + a.w && event.getY() >= a.y && event.getY() <= a.y + a.h) {
                        a.r.run();
                        return true;
                    }
                }
            }
            return true;
        }
    }

    static class Action {
        final int x, y, w, h;
        final Runnable r;

        Action(int x, int y, int w, int h, Runnable r) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.r = r;
        }
    }
}
