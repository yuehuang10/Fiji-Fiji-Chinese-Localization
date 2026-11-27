# 变更记录

## v0.1.0 — 2026-11-27

首个可用版本，在 Fiji 2.16.0 / ImageJ 1.54p / Windows 上实测通过。

### 新增

- 运行时菜单汉化插件 `Zh_Locale`（默认包，ImageJ1 插件扫描要求）
- 支持四处菜单源：`Menus.getMenuBar()`、所有 `Frame.getMenuBar()`、
  `JRootPane.getJMenuBar()`、组件树中的 `JMenuBar`
- 递归翻译任意层级子菜单与右键菜单；保留原始 `actionCommand`，宏/快捷键不受影响
- 外部词典 `plugins/zh_dict.txt`（917 条），修改后重启即可生效
- 启动宏 `macros/AutoRun/ChineseLocalization.ijm`，随 Fiji 自动运行
- 后台线程补扫（每 5 秒、持续 2 分钟），捕捉启动后异步创建的 Swing 菜单栏
- ImageJ2 legacy 重复菜单处理：独有命令合并进首个同名菜单，重复菜单栏移除（实测去重 7 条）
- 与主菜单重复的 Swing 菜单栏自动隐藏
- 诊断文件：`zh_report.txt`（统计）、`zh_missing.txt`（未翻译清单）、`zh_frames.txt`（窗口结构）

### 修复（开发过程中的坑）

- jar 必须包含匿名内部类（`Zh_Locale$1.class`），否则运行时报 `NoClassDefFoundError`
- 右键菜单 `PopupMenu` 与 AWT `Menu` 是同一继承链，遍历时需区分
- 启动期 EDT 会被模态检查阻塞，延迟扫描不能用 `javax.swing.Timer`，改用普通守护线程
- 中文标签宽度变化会暴露 ImageJ2 legacy 追加的重复菜单栏（英文时文字相同看不出来）

### 已知限制

- 插件对话框、按钮、提示文字不汉化（运行时动态生成）
- 专有名词、示例图像名、LUT 名、文件格式名保留英文
