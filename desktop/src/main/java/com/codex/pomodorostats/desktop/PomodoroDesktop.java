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
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JWindow;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
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
import java.time.ZoneId;
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
        frame.setMinimumSize(new Dimension(900, 620));
        frame.setSize(980, 680);
        frame.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        timerPanel = new TimerPanel();
        statsPanel = new StatsPanel();
        settingsPanel = new SettingsPanel();
        tabs.addTab("专注", timerPanel);
        tabs.addTab("统计", statsPanel);
        tabs.addTab("设置", settingsPanel);
        tabs.setFont(AppFonts.ui(15, Font.BOLD));
        frame.setContentPane(tabs);

        miniWindow = new MiniWindow();
        installTray();
        timer = new Timer(250, e -> onTick());
        timer.start();
        frame.setVisible(true);
        miniWindow.setVisible(true);
        refreshAll();
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

    class TimerPanel extends JPanel {
        private final RingPanel ring = new RingPanel();
        private final JLabel phase = label("专注", 18, Font.BOLD, AppColors.muted);
        private final JLabel time = label("25:00", 64, Font.BOLD, AppColors.ink);
        private final JLabel today = label("", 16, Font.PLAIN, AppColors.muted);
        private final JLabel week = label("", 16, Font.PLAIN, AppColors.muted);
        private final JLabel month = label("", 16, Font.PLAIN, AppColors.muted);

        TimerPanel() {
            setLayout(new BorderLayout(24, 24));
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));

            JPanel center = card(new BorderLayout());
            center.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
            JPanel stack = new JPanel(new GridBagLayout());
            stack.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            stack.add(phase, gc);
            gc.gridy++;
            stack.add(Box.createVerticalStrut(8), gc);
            gc.gridy++;
            stack.add(time, gc);
            gc.gridy++;
            stack.add(Box.createVerticalStrut(8), gc);
            gc.gridy++;
            stack.add(today, gc);
            ring.setLayout(new GridBagLayout());
            ring.add(stack);
            center.add(ring, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
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
            s.insets = new Insets(0, 0, 0, 12);
            summary.add(metric("本周专注", week), s);
            s.gridx = 1;
            s.insets = new Insets(0, 12, 0, 0);
            summary.add(metric("本月专注", month), s);
            add(summary, BorderLayout.SOUTH);
        }

        void refresh() {
            phase.setText(focusMode ? "专注中" : "休息中");
            time.setText(timeText());
            today.setText("今日完成 " + todayCount() + " / " + settings.dailyGoal + " 个");
            week.setText(weekMinutes() + " 分钟");
            month.setText(monthMinutes() + " 分钟");
            ring.repaint();
        }
    }

    class StatsPanel extends JPanel {
        private final JLabel total = label("", 22, Font.BOLD, accentColor());
        private final JLabel streak = label("", 22, Font.BOLD, new Color(65, 105, 190));
        private final ChartPanel days = new ChartPanel(false);
        private final ChartPanel months = new ChartPanel(true);

        StatsPanel() {
            setLayout(new BorderLayout(24, 24));
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            JPanel top = new JPanel(new GridBagLayout());
            top.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.fill = GridBagConstraints.BOTH;
            gc.weightx = 1;
            gc.insets = new Insets(0, 0, 0, 12);
            top.add(metric("累计专注", total), gc);
            gc.gridx = 1;
            gc.insets = new Insets(0, 12, 0, 0);
            top.add(metric("连续记录", streak), gc);
            add(top, BorderLayout.NORTH);

            JPanel charts = new JPanel(new GridBagLayout());
            charts.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            c.insets = new Insets(0, 0, 14, 0);
            charts.add(chartCard("最近 7 天", days), c);
            c.gridy = 1;
            c.insets = new Insets(14, 0, 0, 0);
            charts.add(chartCard("最近 6 个月", months), c);
            add(charts, BorderLayout.CENTER);
        }

        void refresh() {
            total.setText(totalMinutes() + " 分钟");
            streak.setText(streak() + " 天");
            days.setData(dailyTotals());
            months.setData(monthlyTotals());
        }
    }

    class SettingsPanel extends JPanel {
        private boolean refreshing = false;
        private final JSpinner focus = spinner(settings.focusMin, 5, 120);
        private final JSpinner brk = spinner(settings.breakMin, 1, 60);
        private final JSpinner longBreak = spinner(settings.longBreakMin, 5, 90);
        private final JSpinner rounds = spinner(settings.rounds, 2, 12);
        private final JSpinner goal = spinner(settings.dailyGoal, 1, 16);
        private final JCheckBox autoFocus = new JCheckBox("休息结束后自动开始下一轮专注");
        private final JComboBox<String> theme = new JComboBox<>(new String[]{"番茄红", "森林绿", "海湾蓝", "暖橙", "紫罗兰"});

        SettingsPanel() {
            setLayout(new BorderLayout());
            setBackground(AppColors.paper);
            setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            JPanel form = card(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(0, 0, 14, 0);
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
            autoFocus.setFont(AppFonts.ui(15, Font.PLAIN));
            form.add(autoFocus, gc);
            add(form, BorderLayout.NORTH);

            ChangeBinder binder = new ChangeBinder();
            focus.addChangeListener(binder);
            brk.addChangeListener(binder);
            longBreak.addChangeListener(binder);
            rounds.addChangeListener(binder);
            goal.addChangeListener(binder);
            autoFocus.addActionListener(e -> saveSettingsFromForm());
            theme.addActionListener(e -> saveSettingsFromForm());
            refreshValues();
        }

        void refreshValues() {
            refreshing = true;
            autoFocus.setSelected(settings.autoFocus);
            theme.setSelectedIndex(themeIndex(settings.accent));
            refreshing = false;
        }

        private void saveSettingsFromForm() {
            if (refreshing) return;
            settings.focusMin = (Integer) focus.getValue();
            settings.breakMin = (Integer) brk.getValue();
            settings.longBreakMin = (Integer) longBreak.getValue();
            settings.rounds = (Integer) rounds.getValue();
            settings.dailyGoal = (Integer) goal.getValue();
            settings.autoFocus = autoFocus.isSelected();
            settings.accent = accentAt(theme.getSelectedIndex());
            store.saveSettings(settings);
            resetTimer();
        }

        private class ChangeBinder implements javax.swing.event.ChangeListener {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                saveSettingsFromForm();
            }
        }
    }

    class MiniWindow extends JWindow {
        private final JLabel time = label("25:00", 26, Font.BOLD, AppColors.ink);
        private final JLabel phase = label("专注", 13, Font.BOLD, AppColors.muted);
        private int dragX;
        private int dragY;

        MiniWindow() {
            setAlwaysOnTop(true);
            setSize(210, 82);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            setLocation(screen.width - 250, 96);
            JPanel root = new MiniPanel();
            root.setLayout(new BorderLayout(12, 0));
            root.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));
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

    class RingPanel extends JPanel {
        RingPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(360, 360));
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 24;
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            g.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AppColors.soft);
            g.drawOval(x, y, size, size);
            g.setColor(accentColor());
            g.draw(new Arc2D.Float(x, y, size, size, 90, -360 * progress(), Arc2D.OPEN));
            g.dispose();
        }
    }

    class ChartPanel extends JPanel {
        private final boolean monthly;
        private Map<String, Integer> data = new LinkedHashMap<>();

        ChartPanel(boolean monthly) {
            this.monthly = monthly;
            setOpaque(false);
        }

        void setData(Map<String, Integer> data) {
            this.data = data;
            repaint();
        }

        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int count = monthly ? 6 : 7;
            int max = 1;
            List<String> keys = new ArrayList<>();
            for (int i = count - 1; i >= 0; i--) {
                String key = monthly ? YearMonth.now().minusMonths(i).toString() : LocalDate.now().minusDays(i).toString();
                keys.add(key);
                max = Math.max(max, data.getOrDefault(key, 0));
            }
            int left = 14;
            int right = 14;
            int top = 12;
            int bottom = 30;
            int w = getWidth() - left - right;
            int h = getHeight() - top - bottom;
            int gap = 12;
            int barW = Math.max(14, (w - gap * (count - 1)) / count);
            g.setFont(AppFonts.ui(12, Font.PLAIN));
            for (int i = 0; i < count; i++) {
                int value = data.getOrDefault(keys.get(i), 0);
                int barH = Math.max(8, Math.round(h * (value / (float) max)));
                int x = left + i * (barW + gap);
                int y = top + h - barH;
                g.setColor(accentColor());
                g.fill(new RoundRectangle2D.Float(x, y, barW, barH, 10, 10));
                g.setColor(AppColors.muted);
                String label = monthly ? keys.get(i).substring(5) + "月" : keys.get(i).substring(8);
                int sw = g.getFontMetrics().stringWidth(label);
                g.drawString(label, x + (barW - sw) / 2, getHeight() - 8);
            }
            g.dispose();
        }
    }

    class MiniPanel extends JPanel {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 255, 255, 242));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 28, 28));
            g.setColor(accentColor());
            g.setStroke(new BasicStroke(4));
            g.draw(new RoundRectangle2D.Float(2, 2, getWidth() - 4, getHeight() - 4, 28, 28));
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
        JPanel panel = card(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        JLabel label = label(title, 14, Font.PLAIN, AppColors.muted);
        panel.add(label, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private JPanel chartCard(String title, Component chart) {
        JPanel panel = card(new BorderLayout(0, 14));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        panel.add(label(title, 18, Font.BOLD, AppColors.ink), BorderLayout.NORTH);
        panel.add(chart, BorderLayout.CENTER);
        return panel;
    }

    private JPanel settingRow(String label, String unit, JSpinner spinner) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.add(label(label, 16, Font.BOLD, AppColors.ink), BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        spinner.setFont(AppFonts.ui(15, Font.PLAIN));
        spinner.setPreferredSize(new Dimension(92, 34));
        right.add(spinner);
        right.add(label(unit, 14, Font.PLAIN, AppColors.muted));
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JPanel themeRow(JComboBox<String> themeBox) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.add(label("主题色", 16, Font.BOLD, AppColors.ink), BorderLayout.WEST);
        themeBox.setFont(AppFonts.ui(15, Font.PLAIN));
        themeBox.setPreferredSize(new Dimension(160, 34));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(themeBox);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JSpinner spinner(int value, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, 1));
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
        button.setPreferredSize(new Dimension(44, 44));
        return button;
    }

    private JButton styledButton(String text, int bg, Color fg) {
        JButton button = new JButton(text);
        button.setFont(AppFonts.ui(15, Font.BOLD));
        button.setForeground(fg);
        button.setBackground(new Color(bg));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
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

    static class RoundedPanel extends JPanel {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 18, 18));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static class AppColors {
        static final Color paper = new Color(248, 247, 243);
        static final Color soft = new Color(236, 234, 226);
        static final Color ink = new Color(33, 35, 39);
        static final Color muted = new Color(104, 108, 116);
    }

    static class AppFonts {
        static Font ui(int size, int style) {
            String family = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames().length > 0 ? "Microsoft YaHei UI" : Font.SANS_SERIF;
            return new Font(family, style, size);
        }
    }
}
