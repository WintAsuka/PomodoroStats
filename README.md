# PomodoroStats

一个简洁、美观、可自定义的番茄钟应用。项目包含 Android 手机版和 Java 桌面版。

## 功能

- 专注、短休息、长休息三阶段计时
- 支持开始、暂停、重置、跳过当前阶段
- 可自定义专注时长、短休息、长休息、长休息间隔和每日目标
- 支持主题色切换、振动提醒、休息结束后自动开始专注
- 支持今日、本周、本月专注统计
- 支持最近 7 天和最近 6 个月统计，覆盖跨天、跨月记录
- 数据保存在手机本地 `SharedPreferences`，无需联网
- 竖屏手机 UI 适配，按钮和文字按 `dp/sp` 缩放
- 桌面版支持主面板、系统托盘常驻、可置顶迷你计时器

## 技术栈

- Android Java
- 自定义 `View` 绘制界面
- Gradle + Android Gradle Plugin
- 无第三方运行时依赖

## 构建

### Android

本项目需要 JDK、Gradle 和 Android SDK。

```bash
gradle assembleDebug
```

构建成功后，APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本地已打包版本：

```text
PomodoroStats-mobile-ui-debug.apk
```

## 说明

当前 APK 是 debug 签名版本，适合直接安装测试。如果需要正式发布到应用商店，需要配置 release 签名并执行 release 构建。

### 桌面版

桌面版位于 `desktop/`，使用 Java Swing 实现，无第三方运行时依赖。

功能形态：

- 主窗口：专注计时、统计图表、参数设置
- 迷你计时器：小型置顶悬浮窗，适合放在屏幕角落
- 系统托盘：可快速开始/暂停、重置、显示主面板、退出
- 本地数据：保存到用户目录下的 `.pomodoro-stats`
- UI：按 2.5K 桌面分辨率优化，使用自定义大导航、自绘按钮、动态缩放计时环和大号设置步进器
- 统计：用总览卡、本周 7 天节奏卡、最近 8 周列表、最近 6 个月列表清晰展示专注时间
- 动效：计时环呼吸光点、统计进度条缓动增长、卡片细腻渐变

构建：

```bat
cd desktop
build-desktop.bat
```

运行：

```bat
cd desktop
run-desktop.bat
```

也可以直接运行已构建的包：

```bat
desktop\dist\Run-PomodoroStatsDesktop.bat
```

Windows 安装器发布在 GitHub Releases，本地生成版本位于：

```text
desktop\PomodoroStats-Setup-v1.2.0.exe
```

安装器会创建开始菜单项和桌面快捷方式，并内置 Java 运行时。
