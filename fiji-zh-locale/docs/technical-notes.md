# 技术记录：Fiji 菜单汉化的逆向过程

本文记录实现过程中得到的结论和踩过的坑，方便后续维护者快速上手。

## 1. 为什么不能"换语言包"

Fiji（ImageJ 发行版）没有 i18n 资源机制：

- ImageJ1（`ij-1.54p.jar`）的菜单文字是 `ij/gui/Menus.class` 常量池里的字符串字面量，
  没有 `.properties`、没有 resource bundle；
- ImageJ2/SciJava 的命令描述（`@Plugin(menuPath = "...")`）编进类注解里；
- 各第三方插件同理。

因此只有两条路：修改字节码（不可维护、破坏校验和），或在运行时遍历 UI 改文字（本方案）。

## 2. 菜单到底藏在哪（四个来源）

实测（Fiji 2.16.0 + Windows）主窗口是 `ij.ImageJ`（AWT `Frame`），它同时挂着 AWT 菜单栏和
后续被追加的 ImageJ2 菜单。可用的遍历入口有四处，缺一不可：

| 来源 | 覆盖的场景 | 注意 |
|---|---|---|
| `Menus.getMenuBar()` | ImageJ1 主菜单栏 | 只返回 IJ1 自己的那条 |
| 所有 `Frame.getMenuBar()` | legacy 追加的第二条、`TextWindow` 的菜单栏 | `ij.ImageJ` 的菜单栏会被遍历两次，第二次因标签已是中文而自然跳过 |
| `JRootPane.getJMenuBar()` | Swing 菜单栏 | **不在组件树里**（见 §4） |
| 组件树中的 `JMenuBar` | 嵌在面板里的 SciJava 菜单栏 | `SearchBarHacker` 会把 Swing 组件塞进 AWT `Panel` |

`PopupMenu`（右键菜单）继承自 `Menu`，而 `Menu` 继承自 `MenuItem`，所以遍历时要先判断
`instanceof Menu` 再递归，否则会漏掉子菜单或死循环。

## 3. ImageJ2 legacy 的重复菜单栏

英文界面下看不出来，汉化后立刻暴露成两排菜单。dump 出来的真实结构：

```
MENUBAR items=15
[文件|编辑|图像|处理|分析|插件|窗口|帮助|插件|编辑|文件|图像|分析|处理|窗口]
 └──────────── ImageJ1 ────────────┘ └──── legacy(ImageJ2) 追加 ────┘
```

处理策略不是简单删除，而是**先合并再删除**：

1. 记录每个顶层菜单标签第一次出现的位置；
2. 再次出现同名菜单时，把它的子项中"目标菜单没有的"搬过去（按标签去重）；
3. 删除重复菜单本身。

这样菜单栏恢复为 8 项，而 legacy 独有的命令（调试、沙盒、自动运行、时空图、Labkit、脚本、
控制台等）全部并入同名菜单，没有功能损失。实测 `dedupe=7`。

另外，如果存在内容与主菜单重复的 Swing 菜单栏（`JMenuBar`≥4 项且标签几乎全重合），
直接 `setVisible(false)` 隐藏，避免出现第二条。

## 4. 两个"看不见"的坑

### 4.1 JRootPane 挂载的菜单栏不在组件树里

`JFrame.setJMenuBar(bar)` 内部是 `JRootPane.setJMenuBar()`，菜单栏保存在 `JRootPane` 的
独立字段中，不计入 `getComponentCount()`。只按组件树递归搜索会**完全找不到**它。
必须对每个 `RootPaneContainer` 显式调用 `getRootPane().getJMenuBar()`。

### 4.2 Swing 菜单栏是启动后异步创建的

AutoRun 宏执行时（约启动后 1~2 秒）右侧 ImageJ2 菜单栏还不存在，实测 25 秒后仍然可能没有，
在交互触发后才出现。因此需要延迟补扫。

**注意**：延迟补扫**不能用 `javax.swing.Timer`**。Fiji 启动期会弹出模态对话框（更新器检查、
Windows 防火墙询问等），EDT 被阻塞，`Timer` 一次都不会触发。改用普通守护线程 +
`Thread.sleep(5000)`、循环 24 次（约 2 分钟）后即可稳定命中。

## 5. 不能破坏宏和快捷键

ImageJ1 通过菜单项的 `actionCommand` 或标签查找命令，宏里写的是英文命令名
（如 `run("Analyze Particles...")`）。改标签时同时执行：

```java
it.setActionCommand(originalEnglishLabel);  // 保留英文命令名
```

这样菜单显示中文，而 `run("英文名")`、快捷键、录制宏都不受影响。

## 6. 打包坑：内部类必须一起打进 jar

最初用 `jar cf Zh_Locale.jar Zh_Locale.class` 打包，只包含主类。加入匿名内部类
（延迟扫描的 `Runnable`、`ActionListener`）后，运行时报：

```
java.lang.NoClassDefFoundError: Zh_Locale$1
```

因为 JVM 加载匿名类时在 jar 里找不到 `Zh_Locale$1.class`。修复方式是打包全部 class：

```powershell
jar cf Zh_Locale.jar (Get-ChildItem *.class).Name
```

`build.ps1` / `build.sh` 都加了校验：缺少 `Zh_Locale$*.class` 时直接报错终止。

## 7. 词典与标签匹配细节

- 标签可能带快捷键后缀（`\tCtrl+O`），匹配前需要去掉制表符之后的部分；
- 部分条目带尾随空格（如 `新建 `），统一 `trim()` 后比对；
- 中文标签不再进入词典查找（`dict.get` 返回 null 后，`isEnglish` 会把它过滤掉），
  所以插件可以被重复执行而不出错——这也是"遍历两次 MenuBar"不会出问题的原因；
- 词典文件名 `zh_dict.txt` 放在 `plugins/` 下，插件每次启动加载，改词不用重新编译。

## 8. 统计口径

`plugins/zh_report.txt` 字段：

| 字段 | 含义 |
|---|---|
| `awt` | 已翻译的 AWT 菜单项（含子菜单、右键菜单） |
| `swing` | 已翻译的 Swing 菜单项 |
| `hidden` | 被隐藏的重复 Swing 菜单栏数量 |
| `dedupe` | 被合并删除的重复 AWT 菜单数量 |
| `missing` | 仍未翻译的条目（写入 `zh_missing.txt`） |
| `dict` | 词典条目总数 |

## 9. 演进过程（供参考）

| 阶段 | 翻译数 | 说明 |
|---|---|---|
| 初版 | 132 | 只遍历 `Menus.getMenuBar()` |
| 补词典 + Transform 子菜单 | 710 → 852 | 递归遍历生效 |
| 增加所有 Frame 的菜单栏 | 914 | 右侧 ImageJ2 菜单变中文 |
| 修复内部类打包 + 补扫 | 1067 | Swing/延迟菜单命中 |
| 合并重复菜单 | 1067（8 条菜单） | `dedupe=7`，界面恢复整洁 |
