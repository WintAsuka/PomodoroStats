package com.codex.pomodorostats.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.DayOfWeek;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class PomodoroDesktop {
    private final Store store = new Store();
    private final Settings settings = store.loadSettings();
    private final List<Session> sessions = store.loadSessions();
    private JFrame frame;
    private MiniWindow miniWindow;
    private TrayIcon trayIcon;
    private Timer timer;
    private boolean running = false;
    private boolean focusMode = true;
    private int focusDoneInCycle = 0;
    private long remainingMs = settings.focusMin * 60_000L;
    private long lastTick = System.currentTimeMillis();
    private TimerPanel timerPanel;
    private StatsPanel statsPanel;
    private SettingsPanel settingsPanel;
    private CardLayout pageLayout;
    private JPanel pages;
    private JButton focusNav;
    private JButton statsNav;
    private JButton settingsNav;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new PomodoroDesktop().start());
    }

    private void start() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        frame = new JFrame("PomodoroStats");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1360, 860));
        frame.setSize(1680, 980);
        frame.setLocationRelativeTo(null);

        timerPanel = new TimerPanel();
        statsPanel = new StatsPanel();
        settingsPanel = new SettingsPanel();
        pageLayout = new CardLayout();
        pages = new JPanel(pageLayout);
        pages.add(timerPanel, "focus");
        pages.add(statsPanel, "stats");
        pages.add(settingsPanel, "settings");

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(AppColors.paper);
        shell.add(createNavigation(), BorderLayout.NORTH);
        shell.add(pages, BorderLayout.CENTER);
        frame.setContentPane(shell);
        selectPage("focus");

        miniWindow = new MiniWindow();
        installTray();
        timer = new Timer(250, e -> onTick());
        timer.start();
        frame.setVisible(true);
        miniWindow.setVisible(true);
        refreshAll();
    }

    private JPanel createNavigation() {
        JPanel nav = new JPanel(new BorderLayout());
        nav.setBackground(new Color(255, 255, 255));
        nav.setBorder(BorderFactory.createEmptyBorder(20, 34, 18, 34));

        JPanel brand = new JPanel(new BorderLayout(0, 4));
        brand.setOpaque(false);
        brand.add(label("PomodoroStats", 28, Font.BOLD, AppColors.ink), BorderLayout.NORTH);
        brand.add(label("桌面专注计时器", 15, Font.PLAIN, AppColors.muted), BorderLayout.SOUTH);
        nav.add(brand, BorderLayout.WEST);

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
        tabs.setOpaque(false);
        focusNav = navButton("专注");
        statsNav = navButton("统计");
        settingsNav = navButton("设置");
        focusNav.addActionListener(e -> selectPage("focus"));
        statsNav.addActionListener(e -> selectPage("stats"));
        settingsNav.addActionListener(e -> selectPage("settings"));
        tabs.add(focusNav);
        tabs.add(statsNav);
        tabs.add(settingsNav);
        nav.add(tabs, BorderLayout.CENTER);
        return nav;
    }

    private JButton navButton(String text) {
        JButton button = new RoundedButton(text, Color.WHITE, AppColors.ink);
        button.setFont(AppFonts.ui(20, Font.BOLD));
        button.setBorder(BorderFactory.createEmptyBorder(16, 34, 16, 34));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void selectPage(String page) {
        if (pageLayout == null) return;
        pageLayout.show(pages, page);
        styleNav(focusNav, "focus".equals(page));
        styleNav(statsNav, "stats".equals(page));
        styleNav(settingsNav, "settings".equals(page));
    }

    private void styleNav(JButton button, boolean selected) {
        if (!(button instanceof RoundedButton)) return;
        RoundedButton rb = (RoundedButton) button;
        rb.setColors(selected ? accentColor() : Color.WHITE, selected ? Color.WHITE : AppColors.ink);
    }

    private void onTick() {
        if (running) {
            long now = System.currentTimeMillis();
            remainingMs -= now - lastTick;
            lastTick = now;
            if (remainingMs <= 0) {
                remainingMs = 0;
                running = false;
                if (focusMode) {
                    focusDoneInCycle++;
                    sessions.add(store.addSession(settings.focusMin));
                    store.saveSessions(sessions);
                }
                Toolkit.getDefaultToolkit().beep();
                advancePhase(false);
            }
        } else {
            lastTick = System.currentTimeMillis();
        }
        refreshAll();
    }

    private void startTimer() {
        if (running) return;
        running = true;
        lastTick = System.currentTimeMillis();
        refreshAll();
    }

    private void pauseTimer() {
        running = false;
        refreshAll();
    }

    private void toggleTimer() {
        if (running) pauseTimer(); else startTimer();
    }

    private void resetTimer() {
        running = false;
        remainingMs = currentPhaseMinutes() * 60_000L;
        refreshAll();
    }

    private void skipPhase() {
        running = false;
        advancePhase(true);
    }

    private void advancePhase(boolean manual) {
        if (focusMode) {
            boolean longBreak = settings.rounds > 0 && focusDoneInCycle > 0 && focusDoneInCycle % settings.rounds == 0;
            if (manual) longBreak = settings.rounds > 0 && (focusDoneInCycle + 1) % settings.rounds == 0;
            focusMode = false;
            remainingMs = (longBreak ? settings.longBreakMin : settings.breakMin) * 60_000L;
        } else {
            focusMode = true;
            remainingMs = settings.focusMin * 60_000L;
            if (settings.autoFocus) startTimer();
        }
        refreshAll();
    }

    private int currentPhaseMinutes() {
        if (focusMode) return settings.focusMin;
        boolean longBreak = settings.rounds > 0 && focusDoneInCycle > 0 && focusDoneInCycle % settings.rounds == 0;
        return longBreak ? settings.longBreakMin : settings.breakMin;
    }

    private float progress() {
        long total = Math.max(1, currentPhaseMinutes() * 60_000L);
        return Math.max(0f, Math.min(1f, 1f - remainingMs / (float) total));
    }

    private String timeText() {
        long total = Math.max(0, (remainingMs + 999) / 1000);
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    private void refreshAll() {
        if (timerPanel != null) timerPanel.refresh();
        if (statsPanel != null) statsPanel.refresh();
        if (settingsPanel != null) settingsPanel.refreshValues();
        if (miniWindow != null) miniWindow.refresh();
        if (trayIcon != null) trayIcon.setToolTip("PomodoroStats - " + (focusMode ? "专注 " : "休息 ") + timeText());
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem startPause = new MenuItem("开始 / 暂停");
        MenuItem reset = new MenuItem("重置");
        MenuItem show = new MenuItem("显示主面板");
        MenuItem mini = new MenuItem("显示迷你计时器");
        MenuItem exit = new MenuItem("退出");
        startPause.addActionListener(e -> toggleTimer());
        reset.addActionListener(e -> resetTimer());
        show.addActionListener(e -> showFrame());
        mini.addActionListener(e -> miniWindow.setVisible(true));
        exit.addActionListener(e -> exitApp());
        menu.add(startPause);
        menu.add(reset);
        menu.addSeparator();
        menu.add(show);
        menu.add(mini);
        menu.addSeparator();
        menu.add(exit);
        trayIcon = new TrayIcon(createTrayImage(), "PomodoroStats", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showFrame());
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ignored) {
            trayIcon = null;
        }
    }

    private Image createTrayImage() {
        int size = 64;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(accentColor());
        g.fillOval(8, 8, 48, 48);
        g.setColor(Color.WHITE);
        g.fillOval(18, 18, 28, 28);
        g.setColor(accentColor());
        g.fillRect(30, 22, 5, 17);
        g.dispose();
        return img;
    }

    private void showFrame() {
        frame.setVisible(true);
        frame.setState(JFrame.NORMAL);
        frame.toFront();
    }

    private void exitApp() {
        store.saveSettings(settings);
        store.saveSessions(sessions);
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        System.exit(0);
    }

    private int todayCount() {
        String today = Store.DAY.format(new Date());
        int count = 0;
        for (Session s : sessions) if (today.equals(s.date)) count++;
        return count;
    }

    private int weekMinutes() {
        LocalDate start = LocalDate.now().minusDays(6);
        int total = 0;
        for (Session s : sessions) {
            LocalDate day = LocalDate.parse(s.date);
            if (!day.isBefore(start)) total += s.minutes;
        }
        return total;
    }

    private int monthMinutes() {
        String month = YearMonth.now().toString();
        int total = 0;
        for (Session s : sessions) if (month.equals(s.month)) total += s.minutes;
        return total;
    }

    private int totalMinutes() {
        int total = 0;
        for (Session s : sessions) total += s.minutes;
        return total;
    }

    private int streak() {
        Map<String, Integer> days = dailyTotals();
        LocalDate day = LocalDate.now();
        int count = 0;
        while (days.containsKey(day.toString())) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    private Map<String, Integer> dailyTotals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Session s : sessions) totals.put(s.date, totals.getOrDefault(s.date, 0) + s.minutes);
        return totals;
    }

    private Map<String, Integer> monthlyTotals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Session s : sessions) totals.put(s.month, totals.getOrDefault(s.month, 0) + s.minutes);
        return totals;
    }

    private Map<String, Integer> currentWeekDailyTotals() {
        Map<String, Integer> totals = dailyTotals();
        Map<String, Integer> week = new LinkedHashMap<>();
        LocalDate start = LocalDate.now().with(DayOfWeek.MONDAY);
        for (int i = 0; i < 7; i++) {
            String key = start.plusDays(i).toString();
            week.put(key, totals.getOrDefault(key, 0));
        }
        return week;
    }

    private Map<String, Integer> weeklyTotals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        WeekFields fields = WeekFields.ISO;
        for (Session s : sessions) {
            LocalDate day = LocalDate.parse(s.date);
            int week = day.get(fields.weekOfWeekBasedYear());
            int year = day.get(fields.weekBasedYear());
            String key = year + "-W" + String.format(Locale.CHINA, "%02d", week);
            totals.put(key, totals.getOrDefault(key, 0) + s.minutes);
        }
        return totals;
    }

    private Map<String, Integer> recentWeekTotals() {
        Map<String, Integer> all = weeklyTotals();
        Map<String, Integer> recent = new LinkedHashMap<>();
        WeekFields fields = WeekFields.ISO;
        LocalDate cursor = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(7);
        for (int i = 0; i < 8; i++) {
            int week = cursor.get(fields.weekOfWeekBasedYear());
            int year = cursor.get(fields.weekBasedYear());
            String key = year + "-W" + String.format(Locale.CHINA, "%02d", week);
            recent.put(key, all.getOrDefault(key, 0));
            cursor = cursor.plusWeeks(1);
        }
        return recent;
    }

    private String insightText() {
        int week = weekMinutes();
        int month = monthMinutes();
        int target = Math.max(1, settings.dailyGoal * settings.focusMin * 7);
        if (week == 0) return "本周还没有专注记录。先开启一轮 25 分钟，让统计开始发光。";
        if (week >= target) return "本周节奏非常稳，已达到或超过当前目标。可以保持，不必再加压。";
        if (month > 0 && week < Math.max(settings.focusMin, month / 8)) return "本周专注时间偏低，建议把下一轮任务拆得更小，先恢复节奏。";
        return "本周已经进入稳定节奏。继续用短目标推进，月底统计会更漂亮。";
    }

    class TimerPanel extends JPanel {
        private final RingPanel ring = new RingPanel();
        private final JLabel week = label("", 22, Font.BOLD, accentColor());
        private final JLabel month = label("", 22, Font.BOLD, new Color(43, 136, 116));
        private final JLabel phaseBadge = badge("专注中");

        TimerPanel() {
            setLayout(new BorderLayout(28, 28));
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(36, 44, 38, 44));

            JPanel center = heroCard(new BorderLayout(32, 22));
            center.setBorder(BorderFactory.createEmptyBorder(38, 42, 38, 42));
            JPanel header = new JPanel(new BorderLayout(18, 0));
            header.setOpaque(false);
            JPanel titleBlock = new JPanel(new BorderLayout(0, 8));
            titleBlock.setOpaque(false);
            titleBlock.add(label("PomodoroStats", 34, Font.BOLD, AppColors.ink), BorderLayout.NORTH);
            titleBlock.add(label("轻量常驻的桌面专注伴侣", 17, Font.PLAIN, AppColors.muted), BorderLayout.SOUTH);
            header.add(titleBlock, BorderLayout.WEST);
            header.add(phaseBadge, BorderLayout.EAST);
            center.add(header, BorderLayout.NORTH);

            center.add(ring, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0));
            buttons.setOpaque(false);
            buttons.add(primaryButton("开始 / 暂停", e -> toggleTimer()));
            buttons.add(secondaryButton("重置", e -> resetTimer()));
            buttons.add(secondaryButton("跳过", e -> skipPhase()));
            center.add(buttons, BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);

            JPanel summary = new JPanel(new GridBagLayout());
            summary.setOpaque(false);
            GridBagConstraints s = new GridBagConstraints();
            s.fill = GridBagConstraints.BOTH;
            s.weightx = 1;
            s.insets = new Insets(0, 0, 0, 16);
            summary.add(metric("本周专注", week), s);
            s.gridx = 1;
            s.insets = new Insets(0, 16, 0, 0);
            summary.add(metric("本月专注", month), s);
            add(summary, BorderLayout.SOUTH);
        }

        void refresh() {
            phaseBadge.setText(focusMode ? "专注中" : "休息中");
            phaseBadge.setBackground(accentColor());
            week.setText(weekMinutes() + " 分钟");
            month.setText(monthMinutes() + " 分钟");
            ring.repaint();
        }
    }

    class StatsPanel extends JPanel {
        private final JLabel total = label("", 28, Font.BOLD, accentColor());
        private final JLabel streak = label("", 28, Font.BOLD, new Color(65, 105, 190));
        private final JLabel weekTotal = label("", 28, Font.BOLD, new Color(228, 86, 79));
        private final JLabel monthTotal = label("", 28, Font.BOLD, new Color(43, 136, 116));
        private final ChartPanel weekDays = new ChartPanel(ChartKind.WEEK_DAYS);
        private final ChartPanel weeks = new ChartPanel(ChartKind.WEEKS);
        private final ChartPanel months = new ChartPanel(ChartKind.MONTHS);
        private final JLabel insight = label("", 18, Font.PLAIN, AppColors.ink);

        StatsPanel() {
            setLayout(new BorderLayout(28, 28));
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(36, 44, 38, 44));
            JPanel top = new JPanel(new GridBagLayout());
            top.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.fill = GridBagConstraints.BOTH;
            gc.weightx = 1;
            gc.insets = new Insets(0, 0, 0, 16);
            top.add(metric("累计专注", total), gc);
            gc.gridx = 1;
            gc.insets = new Insets(0, 16, 0, 16);
            top.add(metric("连续记录", streak), gc);
            gc.gridx = 2;
            top.add(metric("本周专注", weekTotal), gc);
            gc.gridx = 3;
            gc.insets = new Insets(0, 16, 0, 0);
            top.add(metric("本月专注", monthTotal), gc);
            add(top, BorderLayout.NORTH);

            JPanel charts = new JPanel(new GridBagLayout());
            charts.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.weighty = 0;
            c.fill = GridBagConstraints.BOTH;
            c.insets = new Insets(0, 0, 16, 0);
            charts.add(insightCard(insight), c);
            c.gridy = 1;
            c.weighty = 1;
            charts.add(chartCard("本周每天专注时间", weekDays), c);
            c.gridy = 2;
            c.insets = new Insets(16, 0, 16, 0);
            charts.add(chartCard("最近 8 周专注时间", weeks), c);
            c.gridy = 3;
            c.insets = new Insets(16, 0, 0, 0);
            charts.add(chartCard("最近 6 个月专注时间", months), c);
            add(charts, BorderLayout.CENTER);
        }

        void refresh() {
            total.setText(totalMinutes() + " 分钟");
            streak.setText(streak() + " 天");
            weekTotal.setText(weekMinutes() + " 分钟");
            monthTotal.setText(monthMinutes() + " 分钟");
            weekDays.setData(currentWeekDailyTotals());
            weeks.setData(recentWeekTotals());
            months.setData(monthlyTotals());
            insight.setText(insightText());
        }
    }

    class SettingsPanel extends JPanel {
        private boolean refreshing = false;
        private final Stepper focus = new Stepper(settings.focusMin, 5, 120);
        private final Stepper brk = new Stepper(settings.breakMin, 1, 60);
        private final Stepper longBreak = new Stepper(settings.longBreakMin, 5, 90);
        private final Stepper rounds = new Stepper(settings.rounds, 2, 12);
        private final Stepper goal = new Stepper(settings.dailyGoal, 1, 16);
        private final JCheckBox autoFocus = new JCheckBox("休息结束后自动开始下一轮专注");
        private final JComboBox<String> theme = new JComboBox<>(new String[]{"番茄红", "森林绿", "海湾蓝", "暖橙", "紫罗兰"});

        SettingsPanel() {
            setLayout(new BorderLayout());
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(36, 44, 38, 44));
            JPanel form = heroCard(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(34, 40, 34, 40));
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(0, 0, 18, 0);
            JPanel header = new JPanel(new BorderLayout(0, 8));
            header.setOpaque(false);
            header.add(label("偏好设置", 30, Font.BOLD, AppColors.ink), BorderLayout.NORTH);
            header.add(label("调整节奏、目标和桌面提醒方式。", 16, Font.PLAIN, AppColors.muted), BorderLayout.SOUTH);
            form.add(header, gc);
            gc.gridy++;
            form.add(settingRow("专注时长", "分钟", focus), gc);
            gc.gridy++;
            form.add(settingRow("短休息", "分钟", brk), gc);
            gc.gridy++;
            form.add(settingRow("长休息", "分钟", longBreak), gc);
            gc.gridy++;
            form.add(settingRow("长休息间隔", "轮", rounds), gc);
            gc.gridy++;
            form.add(settingRow("每日目标", "个", goal), gc);
            gc.gridy++;
            form.add(themeRow(theme), gc);
            gc.gridy++;
            autoFocus.setOpaque(false);
            autoFocus.setFont(AppFonts.ui(17, Font.PLAIN));
            autoFocus.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            form.add(autoFocus, gc);
            add(form, BorderLayout.NORTH);

            focus.setOnChange(this::saveSettingsFromForm);
            brk.setOnChange(this::saveSettingsFromForm);
            longBreak.setOnChange(this::saveSettingsFromForm);
            rounds.setOnChange(this::saveSettingsFromForm);
            goal.setOnChange(this::saveSettingsFromForm);
            autoFocus.addActionListener(e -> saveSettingsFromForm());
            theme.addActionListener(e -> saveSettingsFromForm());
            refreshValues();
        }

        void refreshValues() {
            refreshing = true;
            autoFocus.setSelected(settings.autoFocus);
            theme.setSelectedIndex(themeIndex(settings.accent));
            focus.setValue(settings.focusMin);
            brk.setValue(settings.breakMin);
            longBreak.setValue(settings.longBreakMin);
            rounds.setValue(settings.rounds);
            goal.setValue(settings.dailyGoal);
            refreshing = false;
        }

        private void saveSettingsFromForm() {
            if (refreshing) return;
            settings.focusMin = focus.getValue();
            settings.breakMin = brk.getValue();
            settings.longBreakMin = longBreak.getValue();
            settings.rounds = rounds.getValue();
            settings.dailyGoal = goal.getValue();
            settings.autoFocus = autoFocus.isSelected();
            settings.accent = accentAt(theme.getSelectedIndex());
            store.saveSettings(settings);
            resetTimer();
        }
    }

    class MiniWindow extends JWindow {
        private final JLabel time = label("25:00", 34, Font.BOLD, AppColors.ink);
        private final JLabel phase = label("专注", 15, Font.BOLD, AppColors.muted);
        private int dragX;
        private int dragY;

        MiniWindow() {
            setAlwaysOnTop(true);
            setSize(278, 104);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            setLocation(screen.width - 330, 110);
            JPanel root = new MiniPanel();
            root.setLayout(new BorderLayout(18, 0));
            root.setBorder(BorderFactory.createEmptyBorder(16, 22, 16, 18));
            JPanel texts = new JPanel(new BorderLayout(0, 2));
            texts.setOpaque(false);
            texts.add(phase, BorderLayout.NORTH);
            texts.add(time, BorderLayout.CENTER);
            root.add(texts, BorderLayout.CENTER);
            JButton toggle = iconButton("▶");
            toggle.addActionListener(e -> toggleTimer());
            root.add(toggle, BorderLayout.EAST);
            setContentPane(root);
            MouseAdapter mover = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    dragX = e.getX();
                    dragY = e.getY();
                }
                public void mouseDragged(MouseEvent e) {
                    setLocation(e.getXOnScreen() - dragX, e.getYOnScreen() - dragY);
                }
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) showMiniMenu(e);
                }
            };
            addMouseListener(mover);
            addMouseMotionListener(mover);
            root.addMouseListener(mover);
            root.addMouseMotionListener(mover);
        }

        void refresh() {
            time.setText(timeText());
            phase.setText(focusMode ? "专注中" : "休息中");
            repaint();
        }

        private void showMiniMenu(MouseEvent e) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem show = new JMenuItem("打开主面板");
            JMenuItem hide = new JMenuItem("隐藏迷你计时器");
            show.addActionListener(a -> showFrame());
            hide.addActionListener(a -> setVisible(false));
            menu.add(show);
            menu.add(hide);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    class Stepper extends JPanel {
        private final int min;
        private final int max;
        private int value;
        private Runnable onChange;
        private final JLabel valueLabel = label("", 22, Font.BOLD, AppColors.ink);

        Stepper(int value, int min, int max) {
            this.value = value;
            this.min = min;
            this.max = max;
            setOpaque(false);
            setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            JButton minus = stepButton("-");
            JButton plus = stepButton("+");
            valueLabel.setHorizontalAlignment(JLabel.CENTER);
            valueLabel.setPreferredSize(new Dimension(72, 52));
            JPanel valuePill = new RoundedPanel();
            valuePill.setLayout(new BorderLayout());
            valuePill.setOpaque(false);
            valuePill.setPreferredSize(new Dimension(92, 56));
            valuePill.add(valueLabel, BorderLayout.CENTER);
            minus.addActionListener(e -> setValue(this.value - 1, true));
            plus.addActionListener(e -> setValue(this.value + 1, true));
            add(minus);
            add(valuePill);
            add(plus);
            refresh();
        }

        int getValue() {
            return value;
        }

        void setValue(int value) {
            setValue(value, false);
        }

        void setOnChange(Runnable onChange) {
            this.onChange = onChange;
        }

        private void setValue(int next, boolean notify) {
            int clamped = Math.max(min, Math.min(max, next));
            if (clamped == value && notify) return;
            value = clamped;
            refresh();
            if (notify && onChange != null) onChange.run();
        }

        private void refresh() {
            valueLabel.setText(Integer.toString(value));
        }

        private JButton stepButton(String text) {
            JButton button = new RoundedButton(text, accentColor(), Color.WHITE);
            button.setFont(AppFonts.ui(24, Font.BOLD));
            button.setPreferredSize(new Dimension(58, 56));
            button.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            button.setFocusPainted(false);
            button.setContentAreaFilled(false);
            button.setOpaque(false);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return button;
        }
    }

    class RingPanel extends JPanel {
        RingPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(780, 560));
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.max(280, Math.min(getWidth(), getHeight()) - 42);
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            int stroke = Math.max(16, size / 34);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AppColors.soft);
            g.drawOval(x, y, size, size);
            g.setColor(accentColor());
            g.draw(new Arc2D.Float(x, y, size, size, 90, -360 * progress(), Arc2D.OPEN));
            g.setColor(accentColor());
            double pulse = 0.5 + Math.sin(System.currentTimeMillis() / 420.0) * 0.5;
            int glow = Math.round(stroke * (1.7f + (float) pulse * 0.55f));
            g.setColor(new Color(accentColor().getRed(), accentColor().getGreen(), accentColor().getBlue(), 42));
            g.fillOval(x + size / 2 - glow / 2, y - glow / 2, glow, glow);
            g.setColor(accentColor());
            g.fillOval(x + size / 2 - stroke / 2, y - stroke / 2, stroke, stroke);

            String phaseText = focusMode ? "专注中" : "休息中";
            String goalText = "今日完成 " + todayCount() + " / " + settings.dailyGoal + " 个";
            int timeSize = Math.max(58, size / 6);
            int phaseSize = Math.max(22, size / 26);
            int goalSize = Math.max(18, size / 34);
            g.setFont(AppFonts.ui(phaseSize, Font.BOLD));
            g.setColor(AppColors.muted);
            drawCentered(g, phaseText, getWidth() / 2, getHeight() / 2 - timeSize / 2 - 18);
            g.setFont(AppFonts.ui(timeSize, Font.BOLD));
            g.setColor(AppColors.ink);
            drawCentered(g, timeText(), getWidth() / 2, getHeight() / 2 + timeSize / 3);
            g.setFont(AppFonts.ui(goalSize, Font.PLAIN));
            g.setColor(AppColors.muted);
            drawCentered(g, goalText, getWidth() / 2, getHeight() / 2 + timeSize / 2 + goalSize + 10);
            g.dispose();
            if (running) repaint(33);
        }

        private void drawCentered(Graphics2D g, String text, int cx, int baseline) {
            int width = g.getFontMetrics().stringWidth(text);
            g.drawString(text, cx - width / 2, baseline);
        }
    }

    enum ChartKind {
        WEEK_DAYS,
        WEEKS,
        MONTHS
    }

    class ChartPanel extends JPanel {
        private final ChartKind kind;
        private Map<String, Integer> data = new LinkedHashMap<>();
        private final Map<String, Float> animated = new LinkedHashMap<>();

        ChartPanel(ChartKind kind) {
            this.kind = kind;
            setOpaque(false);
        }

        void setData(Map<String, Integer> data) {
            this.data = data;
            for (String key : data.keySet()) animated.putIfAbsent(key, 0f);
            repaint();
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            List<String> keys = chartKeys();
            int max = 1;
            for (String key : keys) {
                max = Math.max(max, data.getOrDefault(key, 0));
            }
            int count = keys.size();
            int left = 22;
            int right = 22;
            int top = 22;
            int bottom = 52;
            int w = getWidth() - left - right;
            int h = getHeight() - top - bottom;
            int gap = kind == ChartKind.WEEKS ? 14 : 18;
            int barW = Math.max(22, (w - gap * (count - 1)) / count);
            g.setFont(AppFonts.ui(14, Font.PLAIN));
            for (int i = 0; i < count; i++) {
                String key = keys.get(i);
                int value = data.getOrDefault(key, 0);
                float current = animated.getOrDefault(key, 0f);
                current += (value - current) * 0.18f;
                if (Math.abs(value - current) < 0.35f) current = value;
                animated.put(key, current);
                int barH = Math.max(10, Math.round(h * (current / (float) max)));
                int x = left + i * (barW + gap);
                int y = top + h - barH;
                g.setPaint(new GradientPaint(x, y, brighten(accentColor(), 22), x, y + barH, accentColor()));
                g.fill(new RoundRectangle2D.Float(x, y, barW, barH, 14, 14));
                g.setColor(new Color(255, 255, 255, 90));
                g.fill(new RoundRectangle2D.Float(x + 4, y + 4, Math.max(4, barW - 8), Math.max(2, barH / 3), 10, 10));
                g.setColor(AppColors.muted);
                String label = labelFor(key);
                int sw = g.getFontMetrics().stringWidth(label);
                g.drawString(label, x + (barW - sw) / 2, getHeight() - 10);
                if (value > 0) {
                    String number = value + "m";
                    int nw = g.getFontMetrics().stringWidth(number);
                    g.setColor(AppColors.ink);
                    g.drawString(number, x + (barW - nw) / 2, Math.max(top + 15, y - 8));
                }
            }
            boolean moving = false;
            for (String key : keys) if (Math.abs(data.getOrDefault(key, 0) - animated.getOrDefault(key, 0f)) > 0.5f) moving = true;
            if (moving) repaint(16);
            g.dispose();
        }

        private List<String> chartKeys() {
            List<String> keys = new ArrayList<>();
            if (kind == ChartKind.WEEK_DAYS) {
                LocalDate start = LocalDate.now().with(DayOfWeek.MONDAY);
                for (int i = 0; i < 7; i++) keys.add(start.plusDays(i).toString());
            } else if (kind == ChartKind.WEEKS) {
                WeekFields fields = WeekFields.ISO;
                LocalDate cursor = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(7);
                for (int i = 0; i < 8; i++) {
                    int week = cursor.get(fields.weekOfWeekBasedYear());
                    int year = cursor.get(fields.weekBasedYear());
                    keys.add(year + "-W" + String.format(Locale.CHINA, "%02d", week));
                    cursor = cursor.plusWeeks(1);
                }
            } else {
                for (int i = 5; i >= 0; i--) keys.add(YearMonth.now().minusMonths(i).toString());
            }
            return keys;
        }

        private String labelFor(String key) {
            if (kind == ChartKind.WEEK_DAYS) {
                String[] labels = {"一", "二", "三", "四", "五", "六", "日"};
                int index = LocalDate.parse(key).getDayOfWeek().getValue() - 1;
                return "周" + labels[index];
            }
            if (kind == ChartKind.WEEKS) return key.substring(key.indexOf('W'));
            return key.substring(5) + "月";
        }
    }

    class MiniPanel extends JPanel {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 255, 255, 246));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 34, 34));
            g.setColor(accentColor());
            g.setStroke(new BasicStroke(5));
            g.draw(new RoundRectangle2D.Float(3, 3, getWidth() - 6, getHeight() - 6, 34, 34));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static class Store {
        static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        private static final SimpleDateFormat MONTH = new SimpleDateFormat("yyyy-MM", Locale.CHINA);
        private final Path dir = Paths.get(System.getProperty("user.home"), ".pomodoro-stats");
        private final Path settingsFile = dir.resolve("desktop-settings.properties");
        private final Path sessionsFile = dir.resolve("desktop-sessions.csv");

        Settings loadSettings() {
            Settings s = new Settings();
            Properties props = new Properties();
            try {
                Files.createDirectories(dir);
                if (Files.exists(settingsFile)) {
                    try (BufferedReader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
                        props.load(reader);
                    }
                }
                s.focusMin = intProp(props, "focusMin", s.focusMin);
                s.breakMin = intProp(props, "breakMin", s.breakMin);
                s.longBreakMin = intProp(props, "longBreakMin", s.longBreakMin);
                s.rounds = intProp(props, "rounds", s.rounds);
                s.dailyGoal = intProp(props, "dailyGoal", s.dailyGoal);
                s.accent = intProp(props, "accent", s.accent);
                s.autoFocus = Boolean.parseBoolean(props.getProperty("autoFocus", Boolean.toString(s.autoFocus)));
            } catch (IOException ignored) {
            }
            return s;
        }

        void saveSettings(Settings s) {
            Properties props = new Properties();
            props.setProperty("focusMin", Integer.toString(s.focusMin));
            props.setProperty("breakMin", Integer.toString(s.breakMin));
            props.setProperty("longBreakMin", Integer.toString(s.longBreakMin));
            props.setProperty("rounds", Integer.toString(s.rounds));
            props.setProperty("dailyGoal", Integer.toString(s.dailyGoal));
            props.setProperty("accent", Integer.toString(s.accent));
            props.setProperty("autoFocus", Boolean.toString(s.autoFocus));
            try {
                Files.createDirectories(dir);
                try (BufferedWriter writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
                    props.store(writer, "PomodoroStats desktop settings");
                }
            } catch (IOException ignored) {
            }
        }

        List<Session> loadSessions() {
            List<Session> list = new ArrayList<>();
            try {
                Files.createDirectories(dir);
                if (!Files.exists(sessionsFile)) return list;
                for (String line : Files.readAllLines(sessionsFile, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(",", -1);
                    if (parts.length < 4) continue;
                    list.add(new Session(parts[0], parts[1], Integer.parseInt(parts[2]), Long.parseLong(parts[3])));
                }
            } catch (Exception ignored) {
            }
            return list;
        }

        Session addSession(int minutes) {
            Date now = new Date();
            return new Session(DAY.format(now), MONTH.format(now), minutes, now.getTime());
        }

        void saveSessions(List<Session> sessions) {
            try {
                Files.createDirectories(dir);
                List<String> lines = new ArrayList<>();
                for (Session s : sessions) lines.add(s.date + "," + s.month + "," + s.minutes + "," + s.startedAt);
                Files.write(sessionsFile, lines, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }

        private int intProp(Properties props, String key, int fallback) {
            try {
                return Integer.parseInt(props.getProperty(key, Integer.toString(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    static class Settings {
        int focusMin = 25;
        int breakMin = 5;
        int longBreakMin = 15;
        int rounds = 4;
        int dailyGoal = 8;
        int accent = new Color(228, 86, 79).getRGB();
        boolean autoFocus = false;
    }

    static class Session {
        final String date;
        final String month;
        final int minutes;
        final long startedAt;

        Session(String date, String month, int minutes, long startedAt) {
            this.date = date;
            this.month = month;
            this.minutes = minutes;
            this.startedAt = startedAt;
        }
    }

    private JPanel metric(String title, JLabel value) {
        JPanel panel = card(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        JLabel label = label(title, 16, Font.PLAIN, AppColors.muted);
        panel.add(label, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private JPanel chartCard(String title, Component chart) {
        JPanel panel = card(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        panel.add(label(title, 21, Font.BOLD, AppColors.ink), BorderLayout.NORTH);
        panel.add(chart, BorderLayout.CENTER);
        return panel;
    }

    private JPanel insightCard(JLabel insightLabel) {
        JPanel panel = heroCard(new BorderLayout(16, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 28));
        panel.add(label("智能洞察", 21, Font.BOLD, AppColors.ink), BorderLayout.WEST);
        panel.add(insightLabel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel settingRow(String label, String unit, Stepper stepper) {
        JPanel row = new JPanel(new BorderLayout(24, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AppColors.line), BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        row.add(label(label, 18, Font.BOLD, AppColors.ink), BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);
        right.add(stepper);
        right.add(label(unit, 16, Font.PLAIN, AppColors.muted));
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JPanel themeRow(JComboBox<String> themeBox) {
        JPanel row = new JPanel(new BorderLayout(24, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AppColors.line), BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        row.add(label("主题色", 18, Font.BOLD, AppColors.ink), BorderLayout.WEST);
        themeBox.setFont(AppFonts.ui(17, Font.PLAIN));
        themeBox.setPreferredSize(new Dimension(190, 42));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(themeBox);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JButton primaryButton(String text, java.awt.event.ActionListener listener) {
        JButton button = styledButton(text, settings.accent, Color.WHITE);
        button.addActionListener(listener);
        return button;
    }

    private JButton secondaryButton(String text, java.awt.event.ActionListener listener) {
        JButton button = styledButton(text, Color.WHITE.getRGB(), AppColors.ink);
        button.addActionListener(listener);
        return button;
    }

    private JButton iconButton(String text) {
        JButton button = styledButton(text, settings.accent, Color.WHITE);
        button.setPreferredSize(new Dimension(58, 58));
        return button;
    }

    private JButton styledButton(String text, int bg, Color fg) {
        JButton button = new RoundedButton(text, new Color(bg), fg);
        button.setFont(AppFonts.ui(17, Font.BOLD));
        button.setForeground(fg);
        button.setBackground(new Color(bg));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder(15, 26, 15, 26));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(AppFonts.ui(size, style));
        label.setForeground(color);
        return label;
    }

    private JPanel card(java.awt.LayoutManager layout) {
        JPanel panel = new RoundedPanel();
        panel.setLayout(layout);
        panel.setOpaque(false);
        return panel;
    }

    private JPanel heroCard(LayoutManager layout) {
        JPanel panel = new HeroPanel();
        panel.setLayout(layout);
        panel.setOpaque(false);
        return panel;
    }

    private JLabel badge(String text) {
        JLabel label = label(text, 15, Font.BOLD, Color.WHITE);
        label.setOpaque(true);
        label.setBackground(accentColor());
        label.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
        return label;
    }

    private int themeIndex(int color) {
        int[] colors = themeColors();
        for (int i = 0; i < colors.length; i++) if (colors[i] == color) return i;
        return 0;
    }

    private int accentAt(int index) {
        int[] colors = themeColors();
        return colors[Math.max(0, Math.min(index, colors.length - 1))];
    }

    private int[] themeColors() {
        return new int[]{
                new Color(228, 86, 79).getRGB(),
                new Color(43, 136, 116).getRGB(),
                new Color(65, 105, 190).getRGB(),
                new Color(204, 133, 46).getRGB(),
                new Color(132, 91, 174).getRGB()
        };
    }

    private Color accentColor() {
        return new Color(settings.accent);
    }

    private Color brighten(Color color, int amount) {
        return new Color(
                Math.min(255, color.getRed() + amount),
                Math.min(255, color.getGreen() + amount),
                Math.min(255, color.getBlue() + amount)
        );
    }

    static class RoundedPanel extends JPanel {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, Color.WHITE, 0, getHeight(), new Color(250, 249, 245)));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 28, 28));
            g.setColor(new Color(228, 224, 216));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 28, 28));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static class HeroPanel extends JPanel {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255), getWidth(), getHeight(), new Color(249, 247, 241)));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 34, 34));
            g.setColor(new Color(226, 222, 212));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 34, 34));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static class RoundedButton extends JButton {
        private Color bg;
        private Color fg;

        RoundedButton(String text, Color bg, Color fg) {
            super(text);
            this.bg = bg;
            this.fg = fg;
        }

        void setColors(Color bg, Color fg) {
            this.bg = bg;
            this.fg = fg;
            repaint();
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = getModel().isPressed() ? bg.darker() : (getModel().isRollover() ? brighten(bg) : bg);
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 24, 24));
            if (bg.getRGB() == Color.WHITE.getRGB()) {
                g.setColor(new Color(220, 216, 206));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 24, 24));
            }
            g.dispose();
            setForeground(fg);
            super.paintComponent(graphics);
        }

        private static Color brighten(Color color) {
            return new Color(
                    Math.min(255, color.getRed() + 10),
                    Math.min(255, color.getGreen() + 10),
                    Math.min(255, color.getBlue() + 10)
            );
        }
    }

    static class AppColors {
        static final Color paper = new Color(248, 247, 243);
        static final Color soft = new Color(236, 234, 226);
        static final Color ink = new Color(33, 35, 39);
        static final Color muted = new Color(104, 108, 116);
        static final Color line = new Color(226, 222, 212);
    }

    static class AppFonts {
        static Font ui(int size, int style) {
            String family = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames().length > 0 ? "Microsoft YaHei UI" : Font.SANS_SERIF;
            return new Font(family, style, size);
        }
    }
}
