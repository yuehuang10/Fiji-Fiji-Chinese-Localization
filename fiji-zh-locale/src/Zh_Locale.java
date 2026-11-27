import ij.IJ;
import ij.Menus;
import ij.plugin.PlugIn;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuComponent;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

/**
 * Fiji/ImageJ 运行时中文汉化插件（AWT 菜单 + SciJava Swing 菜单，递归到任意层级）。
 * 词典可被 plugins/zh_dict.txt 覆盖（每行：英文<TAB>中文，# 注释）。
 * 启动时把未翻译的英文条目写入 plugins/zh_missing.txt 便于扩充词典。
 */
public class Zh_Locale implements PlugIn {

    private static final Map<String, String> dict = new LinkedHashMap<String, String>();
    private final TreeSet<String> missing = new TreeSet<String>();
    private int countAwt = 0, countSwing = 0, hiddenBars = 0, dedupeCount = 0;

    public void run(String arg) {
        try {
            loadDict();
            countAwt = 0; countSwing = 0; missing.clear();

            MenuBar mb = Menus.getMenuBar();
            if (mb != null) {
                for (int i = 0; i < mb.getMenuCount(); i++) {
                    Menu m = mb.getMenu(i);
                    translateItem(m);
                    walkMenu(m);
                }
            }
            try {
                PopupMenu pm = Menus.getPopupMenu();
                if (pm != null) walkMenu(pm);
            } catch (Throwable ignored) {}

            walkSwingWindows();

            saveMissing();
            saveReport();
            dumpFrames();
            scheduleSwingPasses();
            IJ.log("[汉化] AWT 菜单项 " + countAwt + "，Swing 菜单项 " + countSwing
                    + "，未收录 " + missing.size() + "（词典 " + dict.size() + " 条）");
        } catch (Throwable t) {
            IJ.log("[汉化] 出错: " + t);
        }
    }

    /* ---------- AWT 菜单 ---------- */

    private void walkMenu(Menu menu) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem it = menu.getItem(i);
            translateItem(it);
            if (it instanceof Menu) walkMenu((Menu) it);
            else if (it instanceof PopupMenu) walkMenu((PopupMenu) it);
        }
    }

    private void translateItem(MenuItem it) {
        String lbl = it.getLabel();
        if (lbl == null || lbl.trim().length() == 0) return;
        String key = keyOf(lbl);
        if (key == null) { noteMissing(lbl); return; }
        String zh = dict.get(key);
        if (zh == null) { noteMissing(lbl); return; }
        String rest = lbl.substring(Math.min(key.length(), lbl.length()));
        it.setLabel(zh + rest);
        try { it.setActionCommand(lbl); } catch (Throwable ignored) {}
        countAwt++;
    }

    /* ---------- Swing (SciJava) 菜单 ---------- */

    private void walkSwingWindows() {
        try {
            for (Window w : Window.getWindows()) {
                // 所有 AWT Frame 自己的菜单栏（不止 IJ1 主窗口）
                if (w instanceof java.awt.Frame) {
                    java.awt.MenuBar fbar = ((java.awt.Frame) w).getMenuBar();
                    if (fbar != null) walkAwtBar(fbar);
                }
                // JRootPane.setJMenuBar() 挂载的菜单栏不在组件树里，需单独取
                if (w instanceof javax.swing.RootPaneContainer) {
                    JMenuBar bar = ((javax.swing.RootPaneContainer) w).getRootPane().getJMenuBar();
                    if (bar != null) walkSwingBar(bar);
                }
                searchSwing(w);
            }
        } catch (Throwable ignored) {}
    }

    private void walkAwtBar(java.awt.MenuBar bar) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            Menu m = bar.getMenu(i);
            translateItem(m);
            walkMenu(m);
        }
        dedupeMenubar(bar);
    }

    /**
     * ImageJ2 legacy 会把同一批菜单再次追加到主菜单栏末尾，形成 8+7 条重复菜单。
     * 这里把重复菜单里独有的子项并入第一次出现的同名菜单，然后移除重复菜单。
     */
    private void dedupeMenubar(java.awt.MenuBar bar) {
        try {
            Map<String, Integer> firstIndex = new java.util.HashMap<String, Integer>();
            int i = 0;
            while (i < bar.getMenuCount()) {
                Menu m = bar.getMenu(i);
                if (m == null) { i++; continue; }
                String lbl = m.getLabel() == null ? "" : m.getLabel().trim();
                Integer first = firstIndex.get(lbl);
                if (first == null) {
                    firstIndex.put(lbl, i);
                    i++;
                    continue;
                }
                Menu target = bar.getMenu(first);
                mergeMenu(target, m);
                bar.remove(i);          // 索引自动前移，无需 i++
                dedupeCount++;
            }
        } catch (Throwable ignored) {}
    }

    /** 把 source 中 target 没有的子项搬过去（递归合并同名子菜单）。 */
    private void mergeMenu(Menu target, Menu source) {
        java.util.Set<String> existing = new java.util.HashSet<String>();
        for (int i = 0; i < target.getItemCount(); i++) {
            MenuItem it = target.getItem(i);
            existing.add(it.getLabel() == null ? "" : it.getLabel().trim());
        }
        int i = 0;
        while (i < source.getItemCount()) {
            MenuItem it = source.getItem(i);
            String lbl = it.getLabel() == null ? "" : it.getLabel().trim();
            if (existing.contains(lbl)) { i++; continue; }
            source.remove(i);
            target.add(it);
            existing.add(lbl);
        }
    }

    /** 递归搜索组件树，找到所有 JMenuBar（含嵌入在面板里的 SciJava 菜单栏）。 */
    private void searchSwing(java.awt.Container c) {
        try {
            for (int i = 0; i < c.getComponentCount(); i++) {
                java.awt.Component comp = c.getComponent(i);
                if (comp instanceof JMenuBar) {
                    walkSwingBar((JMenuBar) comp);
                } else if (comp instanceof java.awt.Container) {
                    searchSwing((java.awt.Container) comp);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void walkSwingBar(JMenuBar smb) {
        for (int i = 0; i < smb.getMenuCount(); i++) {
            JMenu m = smb.getMenu(i);
            if (m == null) continue;
            translateSwing(m);
            walkSwingMenu(m);
        }
        maybeHideDuplicate(smb);
    }

    /**
     * 与 IJ1 主菜单栏重复的 Swing 菜单栏（SciJava/ImageJ2 的 LegacyUI 那条）会显示成第二排菜单，
     * 这里检测到内容重复就隐藏它，只保留一条菜单栏。
     */
    private void maybeHideDuplicate(JMenuBar smb) {
        try {
            if (smb.getMenuCount() < 4) return;
            MenuBar main = Menus.getMenuBar();
            if (main == null) return;
            java.util.Set<String> ij1 = new java.util.HashSet<String>();
            for (int i = 0; i < main.getMenuCount(); i++) {
                Menu m = main.getMenu(i);
                if (m != null) ij1.add(m.getLabel().trim());
            }
            if (ij1.isEmpty()) return;
            int match = 0;
            for (int i = 0; i < smb.getMenuCount(); i++) {
                JMenu m = smb.getMenu(i);
                if (m != null && ij1.contains(m.getText().trim())) match++;
            }
            if (match >= smb.getMenuCount() - 1 && smb.isVisible()) {
                smb.setVisible(false);
                hiddenBars++;
                IJ.log("[汉化] 隐藏重复菜单栏：" + smb.getMenuCount() + " 个菜单与主菜单重复");
            }
        } catch (Throwable ignored) {}
    }

    private void walkSwingMenu(JMenu m) {
        for (int i = 0; i < m.getMenuComponentCount(); i++) {
            java.awt.Component c = m.getMenuComponent(i);
            if (c instanceof JMenuItem) {
                JMenuItem it = (JMenuItem) c;
                translateSwing(it);
                if (it instanceof JMenu) walkSwingMenu((JMenu) it);
            }
        }
    }

    private void translateSwing(JMenuItem it) {
        String lbl = it.getText();
        if (lbl == null || lbl.trim().length() == 0) return;
        String key = keyOf(lbl);
        if (key == null) { noteMissing(lbl); return; }
        String zh = dict.get(key);
        if (zh == null) { noteMissing(lbl); return; }
        String rest = lbl.substring(Math.min(key.length(), lbl.length()));
        it.setText(zh + rest);
        countSwing++;
    }

    /* ---------- 诊断：列出所有窗口与菜单栏 ---------- */

    private void dumpFrames() {
        StringBuilder sb = new StringBuilder();
        try {
            Window[] ws = Window.getWindows();
            sb.append("WINDOW_COUNT=").append(ws.length).append('\n');
            for (Window w : ws) {
                sb.append("WINDOW class=").append(w.getClass().getName())
                  .append(" visible=").append(w.isVisible())
                  .append(" bounds=").append(w.getBounds())
                  .append(" name=").append(w.getName()).append('\n');
                if (w instanceof java.awt.Frame) {
                    java.awt.MenuBar bar = ((java.awt.Frame) w).getMenuBar();
                    if (bar != null) {
                        sb.append("  MENUBAR items=").append(bar.getMenuCount()).append(" [");
                        for (int i = 0; i < bar.getMenuCount(); i++) {
                            sb.append(bar.getMenu(i).getLabel()).append('|');
                        }
                        sb.append("]\n");
                        for (int i = 0; i < bar.getMenuCount(); i++) {
                            Menu m = bar.getMenu(i);
                            sb.append("    ").append(m.getLabel()).append(" -> ");
                            for (int j = 0; j < m.getItemCount(); j++) {
                                sb.append(m.getItem(j).getLabel()).append(',');
                            }
                            sb.append('\n');
                        }
                    }
                }
                if (w instanceof javax.swing.RootPaneContainer) {
                    JMenuBar sb2 = ((javax.swing.RootPaneContainer) w).getRootPane().getJMenuBar();
                    if (sb2 != null) {
                        sb.append("  SWING_MENUBAR items=").append(sb2.getMenuCount()).append(" [");
                        for (int i = 0; i < sb2.getMenuCount(); i++) {
                            sb.append(sb2.getMenu(i).getText()).append('|');
                        }
                        sb.append("]\n");
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("ERROR ").append(t).append('\n');
        }
        try {
            String dir = IJ.getDirectory("plugins");
            if (dir != null) {
                Files.write(Paths.get(dir, "zh_frames.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    /* ---------- 延迟扫描（右侧 ImageJ2 菜单栏在启动后异步创建）---------- */

    private void scheduleSwingPasses() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int attempt = 1; attempt <= 24; attempt++) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        walkSwingWindows();
                        saveMissing();
                        saveReport();
                        dumpFrames();
                    } catch (Throwable ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /* ---------- 工具 ---------- */

    /** 截取键名：去掉制表符后的快捷键与首尾空白；"-"/空返回 null。 */
    private String keyOf(String lbl) {
        String key = lbl;
        int tab = key.indexOf('\t');
        if (tab >= 0) key = key.substring(0, tab);
        key = key.trim();
        if (key.length() == 0 || key.equals("-")) return null;
        return key;
    }

    private void noteMissing(String lbl) {
        String key = keyOf(lbl);
        if (key == null) return;
        if (!isEnglish(key)) return;
        missing.add(key);
    }

    private boolean isEnglish(String s) {
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7E) return false;
            if (Character.isLetter(c)) hasLetter = true;
        }
        return hasLetter;
    }

    private void loadDict() throws Exception {
        dict.clear();
        put("File", "文件");
        put("Edit", "编辑");
        put("Image", "图像");
        put("Process", "处理");
        put("Analyze", "分析");
        put("Plugins", "插件");
        put("Window", "窗口");
        put("Help", "帮助");
        // File
        put("New", "新建");
        put("Open...", "打开...");
        put("Open Samples", "打开示例图像");
        put("Blobs", "Blobs 示例图");
        put("Close", "关闭");
        put("Close All", "全部关闭");
        put("Save", "保存");
        put("Save As", "另存为");
        put("Revert", "还原");
        put("Import", "导入");
        put("Export", "导出");
        put("Print...", "打印...");
        put("Quit", "退出");
        // Edit
        put("Undo", "撤销");
        put("Redo", "重做");
        put("Cut", "剪切");
        put("Copy", "复制");
        put("Copy to System", "复制到系统剪贴板");
        put("Paste", "粘贴");
        put("Paste Control...", "粘贴控制...");
        put("Clear", "清除");
        put("Clear Outside", "清除选区外");
        put("Fill", "填充");
        put("Draw", "描边");
        put("Invert", "反色");
        put("Selection", "选区");
        put("Options...", "选项...");
        put("Options", "选项");
        // Image
        put("Type", "类型");
        put("Adjust", "调整");
        put("Show Info...", "显示信息...");
        put("Color", "颜色");
        put("Stacks", "堆栈");
        put("Stack", "堆栈");
        put("Hyperstacks", "超堆栈");
        put("Crop", "裁剪");
        put("Duplicate...", "复制当前图像...");
        put("Rename...", "重命名...");
        put("Scale...", "缩放...");
        put("Transform", "变换");
        put("Zoom", "缩放视图");
        put("Overlay", "叠加标注");
        put("Lookup Tables", "查找表 (LUT)");
        put("Hyperstack...", "超堆栈...");
        // Image > Transform
        put("Flip Horizontally", "水平翻转");
        put("Flip Vertically", "垂直翻转");
        put("Flip Z", "Z 轴翻转");
        put("Rotate 90 Degrees Right", "右旋 90 度");
        put("Rotate 90 Degrees Left", "左旋 90 度");
        put("Rotate...", "旋转...");
        put("Translate...", "平移...");
        put("Bin...", "像素合并...");
        put("Image to Results", "图像到结果表");
        put("Results to Image", "结果表到图像");
        // Image > Annotate / Drawing / Video Editing
        put("Annotate", "标注");
        put("Drawing", "绘图");
        put("Video Editing", "视频编辑");
        // Process
        put("Smooth", "平滑");
        put("Sharpen", "锐化");
        put("Find Edges", "查找边缘");
        put("Find Maxima...", "查找极大值...");
        put("Enhance Contrast", "增强对比度");
        put("Enhance Local Contrast (CLAHE)", "局部对比度增强 (CLAHE)");
        put("Noise", "噪声");
        put("Filters", "滤波器");
        put("Filters...", "滤波器...");
        put("Convolver...", "卷积...");
        put("Math", "数学运算");
        put("FFT", "傅里叶变换 (FFT)");
        put("Filters (FFT)", "FFT 滤波器");
        put("Background", "背景去除");
        put("Binary", "二值化");
        put("Grayscale...", "灰度形态学...");
        put("Image Calculator...", "图像计算器...");
        put("Subtract Background...", "减去背景...");
        put("Batch", "批处理");
        // Analyze
        put("Measure", "测量");
        put("Analyze Particles...", "分析粒子...");
        put("Analyze Line Graph", "分析折线图");
        put("3D Surface Plot", "3D 曲面图");
        put("Surface Plot...", "曲面图...");
        put("Gels", "凝胶分析");
        put("Tools", "工具");
        put("Histogram", "直方图");
        put("Set Measurements...", "测量设置...");
        put("Set Scale...", "设置标尺...");
        put("Calibrate...", "校准...");
        put("Clear Results", "清除结果表");
        put("Summarize", "汇总");
        put("Distribution...", "分布图...");
        put("Label...", "标注...");
        put("Plot Profile", "绘制轮廓线图");
        put("Plot Z-axis Profile", "Z 轴轮廓图");
        // Plugins
        put("Macros", "宏");
        put("Shortcuts", "快捷键");
        put("Utilities", "实用工具");
        put("Record...", "录制...");
        put("Edit...", "编辑...");
        put("Run...", "运行...");
        put("Startup...", "启动项...");
        put("Install...", "安装...");
        put("Scripting", "脚本");
        put("Compile and Run", "编译并运行");
        put("Add Shortcut...", "添加快捷键...");
        put("Remove Shortcut...", "移除快捷键...");
        put("Install Plugin...", "安装插件...");
        put("Control Panel...", "控制面板...");
        put("Fiji Updater", "Fiji 更新器");
        put("Update Fiji", "更新 Fiji");
        // Window
        put("Cascade", "层叠窗口");
        put("Tile", "平铺窗口");
        put("Show All", "显示全部");
        put("Put Behind [tab]", "切换到上一窗口 [Tab]");
        put("Move to Next Table [>]", "下一结果表 [>]");
        put("Previous Table [<]", "上一结果表 [<]");
        // Help
        put("ImageJ Website...", "ImageJ 官网...");
        put("ImageJ Docs...", "ImageJ 文档...");
        put("ImageJ Wiki...", "ImageJ Wiki...");
        put("Plugin Resources...", "插件资源...");
        put("Update Menus", "刷新菜单");
        put("Refresh Menus", "刷新菜单");
        put("Reset...", "重置...");
        put("Unlock", "解锁");
        put("About ImageJ...", "关于 ImageJ...");
        put("About Plugins", "关于插件子菜单");
        put("Search...", "搜索...");
        // 右键弹出菜单
        put("Show List", "显示列表");
        put("Draw Overlay", "绘制叠加层");
        put("To ROI Manager", "加入 ROI 管理器");
        put("Add [t]", "添加到叠加 [t]");
        put("Properties...", "属性...");
        put("Fit", "适合窗口");
        put("Bring to Front", "置于顶层");
        put("Send to Back", "移到底层");

        // 外部词典覆盖：plugins/zh_dict.txt
        try {
            String dir = IJ.getDirectory("plugins");
            if (dir != null) {
                Path ext = Paths.get(dir, "zh_dict.txt");
                if (Files.exists(ext)) {
                    for (String line : Files.readAllLines(ext, StandardCharsets.UTF_8)) {
                        line = line.trim();
                        if (line.length() == 0 || line.startsWith("#")) continue;
                        int i = line.indexOf('\t');
                        if (i > 0) dict.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void put(String en, String zh) { dict.put(en, zh); }

    private void saveMissing() {
        try {
            String dir = IJ.getDirectory("plugins");
            if (dir == null) return;
            List<String> lines = new ArrayList<String>(missing);
            Files.write(Paths.get(dir, "zh_missing.txt"), lines, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    private void saveReport() {
        try {
            String dir = IJ.getDirectory("plugins");
            if (dir == null) return;
            Path p = Paths.get(dir, "zh_report.txt");
            String text = "awt=" + countAwt + " swing=" + countSwing + " hidden=" + hiddenBars + " dedupe=" + dedupeCount + " missing=" + missing.size()
                    + " dict=" + dict.size() + " time=" + new java.util.Date();
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }
}
