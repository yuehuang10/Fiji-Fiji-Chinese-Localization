# Fiji 汉化说明

## 文件夹结构（Fiji.app）

| 目录/文件 | 作用 |
|---|---|
| `fiji-windows-x64.exe` / `ImageJ-win64.exe` / `fiji.bat` | Windows 启动器 |
| `jars/`（578 个 jar） | Java 库；核心是 `ij-1.54p.jar`（ImageJ1 内核）、`imagej-*.jar`（ImageJ2）、`scijava-*.jar`（框架） |
| `plugins/` | 用户插件目录（汉化插件与词典装在这里） |
| `macros/` | 宏；`macros/AutoRun/` 下的宏**每次启动自动执行** |
| `scripts/`、`luts/`、`lib/`、`java/` | 脚本、伪彩色表、本地库、内置 JDK8（Zulu） |
| `.checksums`、`db.xml.gz` | Fiji 更新器的校验数据库 |

## 汉化原理

Fiji/ImageJ **没有多语言资源机制**，菜单和界面文字硬编码在 Java 字节码里，无法切换语言包。
因此采用**运行时菜单翻译**（不改任何原始 jar，可逆）：

| 文件 | 作用 |
|---|---|
| `plugins/Zh_Locale.jar` | 汉化插件。启动后递归遍历菜单并替换英文标签；覆盖三处菜单源：① IJ1 主菜单栏（`Menus.getMenuBar()`）② 所有 AWT Frame 自带的菜单栏（右上角 ImageJ2 风格菜单）③ Swing `JMenuBar`（含 `JRootPane` 挂载与组件树内嵌）。5 秒间隔后台线程再扫约 2 分钟，捕捉启动后才异步创建的菜单。翻译时保留原始命令名，**宏/快捷键按英文名调用不受影响** |
| `plugins/zh_dict.txt` | 扩展词典（917 条，英文<TAB>中文，`#` 注释）。改完重启 Fiji 即生效，**无需重新编译** |
| `macros/AutoRun/ChineseLocalization.ijm` | 启动宏，内容一行 `run("Zh Locale");`（命令名由插件类名 `Zh_Locale` 决定，不能写成别名字符串） |

实测结果：`plugins/zh_report.txt` 显示 **awt=1067，dedupe=7（去重菜单栏 7 条），词典 917 条，未收录 189 条**。

## 关于「菜单重复」

ImageJ2 legacy 会把同一批菜单（插件/编辑/文件/图像/分析/处理/窗口）**再次追加到主菜单栏末尾**，英文时因为文字完全一样、宽度相同，看起来像一条；汉化后字宽变化才暴露成两排（你会看到 `文件…帮助 插件 编辑 文件 图像 分析…`）。

插件的处理：把重复菜单中**独有的子项合并**进第一次出现的同名菜单，然后删除重复菜单本身。所以：

- 菜单栏恢复为 8 条：文件 / 编辑 / 图像 / 处理 / 分析 / 插件 / 窗口 / 帮助
- 独有命令没有丢失，例如「插件」菜单末尾新增：调试、沙盒、自动运行、时空图、Labkit、脚本；「文件」新增：修正异常文件名、制作屏幕录像；「窗口」新增：控制台
- 若还有第二条 Swing 菜单栏且内容与主菜单重复，插件会直接把它隐藏（`hidden` 计数）

## 覆盖范围

- ✅ 一级菜单（文件/编辑/图像/处理/分析/插件/窗口/帮助）、二级/三级/四级子菜单（递归遍历，无限层级），例如 图像→变换→水平翻转/垂直翻转/Z 轴翻转/右旋 90 度……
- ✅ ImageJ2 legacy 追加的菜单：内容合并进主菜单后删除重复菜单栏，命令不丢失
- ✅ 右键弹出菜单
- ❌ 各插件弹出的对话框/按钮文字（运行时动态生成，无法统一拦截）
- ❌ 示例图像名（Clown、Boats…）、LUT 文件名（glasbey…）、文件格式名（TIFF/BMP/HDF5…）、插件专有名词（TrackMate/TrakEM2/BigDataViewer…）保留英文，避免误读

## 如何扩充词典

直接编辑 `Fiji.app/plugins/zh_dict.txt`：每行 `英文<TAB>中文`，保存后重启 Fiji 即可，不需要重新编译。

若要修改插件逻辑：源码在 `src/Zh_Locale.java`，用仓库自带脚本构建：

```powershell
.\build.ps1                          # Windows，默认读取 ..\Fiji.app
FIJI_HOME=/Applications/Fiji.app ./build.sh   # macOS / Linux
```

构建脚本会自动把 `Zh_Locale$1.class` 等内部类一起打包——**漏掉内部类会导致运行时报 `NoClassDefFoundError`**。

## 诊断文件

- `Fiji.app\plugins\zh_report.txt`：上次汉化的统计（awt/swing/hidden/dedupe/missing/dict）
- `Fiji.app\plugins\zh_missing.txt`：仍未翻译的菜单项清单，扩充词典时照它补
- `Fiji.app\plugins\zh_frames.txt`：窗口与菜单栏结构快照（排查菜单问题用）

## 已知提示

- 首次启动 Java 时 Windows 防火墙会弹「是否允许网络访问」，点「允许」即可；该弹窗不影响汉化执行。
- Fiji 更新器可能提示 `plugins/Zh_Locale.jar` 不在官方校验库中，属正常，无需处理。

## 恢复英文

删除 `Fiji.app\plugins\Zh_Locale.jar`、`Fiji.app\plugins\zh_dict.txt`、`Fiji.app\macros\AutoRun\ChineseLocalization.ijm` 三个文件即可，原始 jar 未被修改。
