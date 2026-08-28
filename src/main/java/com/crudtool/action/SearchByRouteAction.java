package com.crudtool.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.crudtool.utils.ControllerRouteScanner;
import com.crudtool.utils.ControllerRouteScanner.RouteItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: Ctrl+\ 弹出路由搜索窗口，输入接口路由后搜索并跳转到对应 Controller 方法
 *
 * 交互流程：
 *   1. Ctrl+\ 触发本 Action
 *   2. 弹出搜索窗口（输入框 + 路由列表）
 *   3. 后台线程（ReadAction）扫描全工程 Controller 路由
 *   4. 用户输入路由关键字（支持模糊匹配），列表实时过滤
 *   5. 按 Enter 或双击列表项跳转到对应 Controller 方法
 *   6. 按 Esc 关闭弹窗
 */
public class SearchByRouteAction extends AnAction {

    private static final int MAX_DISPLAY_RESULTS = 200;
    private static final String ENTER_ACTION_KEY = "crud-tool.enterNavigate";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).showDumbModeNotification("Search by Route is not available while indexing");
            return;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        showRouteSearchPopup(project, editor);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !DumbService.isDumb(project));
    }

    private void showRouteSearchPopup(@NotNull Project project, @Nullable Editor editor) {
        JTextField textField = new JTextField();
        textField.setBorder(JBUI.Borders.empty(5, 8));
        textField.setFont(UIUtil.getLabelFont().deriveFont(13f));

        JBList<RouteItem> list = new JBList<>();
        list.setCellRenderer(new RouteListCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBorder(JBUI.Borders.empty());

        // 底部状态提示：搜索中显示旋转动画图标，完成后显示结果数（非弹窗）
        JLabel hintLabel = new JLabel("正在搜索路由...", new AnimatedIcon.Default(), SwingConstants.LEFT);
        hintLabel.setBorder(JBUI.Borders.empty(6, 10));
        hintLabel.setForeground(JBColor.GRAY);

        final JBPopup[] popupRef = new JBPopup[1];
        final List<RouteItem>[] allRoutesRef = new List[]{new ArrayList<>()};
        final boolean[] loadingRef = {true};

        // 输入实时过滤
        textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(textField.getText(), allRoutesRef[0], list, hintLabel, loadingRef[0]); }
            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(textField.getText(), allRoutesRef[0], list, hintLabel, loadingRef[0]); }
            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(textField.getText(), allRoutesRef[0], list, hintLabel, loadingRef[0]); }
        });

        // 上下键移动列表选中（在输入框按方向键时）
        textField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (list.getModel().getSize() > 0) {
                        int idx = Math.min(list.getSelectedIndex() + 1, list.getModel().getSize() - 1);
                        list.setSelectedIndex(idx);
                        list.ensureIndexIsVisible(idx);
                        e.consume();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    if (list.getModel().getSize() > 0) {
                        int idx = Math.max(list.getSelectedIndex() - 1, 0);
                        list.setSelectedIndex(idx);
                        list.ensureIndexIsVisible(idx);
                        e.consume();
                    }
                }
            }
        });

        // 双击列表跳转
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0 || index >= list.getModel().getSize()) return;
                    navigateAndClose(project, list.getModel().getElementAt(index), popupRef[0]);
                }
            }
        });

        // 构建面板
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(textField, BorderLayout.NORTH);
        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(550, 320));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(hintLabel, BorderLayout.SOUTH);

        // Enter 键绑定到整个面板
        InputMap inputMap = panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ENTER_ACTION_KEY);
        panel.getActionMap().put(ENTER_ACTION_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RouteItem selected = list.getSelectedValue();
                if (selected == null && list.getModel().getSize() > 0) {
                    selected = list.getModel().getElementAt(0);
                }
                navigateAndClose(project, selected, popupRef[0]);
            }
        });

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, textField)
                .setTitle("Search Controller by Route")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setDimensionServiceKey(project, "crud-tool.SearchByRoutePopup", false)
                .setMinSize(new Dimension(550, 380))
                .setCancelOnWindowDeactivation(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .createPopup();
        popupRef[0] = popup;

        if (editor != null) {
            popup.showInBestPositionFor(editor);
        } else {
            popup.showCenteredInCurrentWindow(project);
        }

        // popup 显示后启动后台扫描（必须在读锁内访问 PSI）
        ReadAction.nonBlocking(() -> ControllerRouteScanner.scanAllRoutes(project))
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.stateForComponent(panel), routes -> {
                    if (!popup.isVisible()) return;
                    loadingRef[0] = false;
                    allRoutesRef[0] = routes;
                    applyFilter(textField.getText(), routes, list, hintLabel, loadingRef[0]);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void applyFilter(@NotNull String keyword, @NotNull List<RouteItem> allRoutes,
                             @NotNull JBList<RouteItem> list, @NotNull JLabel hintLabel, boolean loading) {
        String key = normalizeSlashes(keyword.trim()).toLowerCase();
        List<RouteItem> filtered = new ArrayList<>();
        for (RouteItem item : allRoutes) {
            if (key.isEmpty() || normalizeSlashes(item.url).toLowerCase().contains(key)
                    || item.className.toLowerCase().contains(key)
                    || item.methodName.toLowerCase().contains(key)) {
                filtered.add(item);
                if (filtered.size() >= MAX_DISPLAY_RESULTS) break;
            }
        }
        list.setModel(new AbstractListModel<RouteItem>() {
            @Override public int getSize() { return filtered.size(); }
            @Override public RouteItem getElementAt(int index) { return filtered.get(index); }
        });
        if (loading) {
            // 搜索中：保持旋转动画提示
            hintLabel.setIcon(new AnimatedIcon.Default());
            hintLabel.setText("正在搜索路由...");
            return;
        }
        hintLabel.setIcon(null);
        if (!filtered.isEmpty()) {
            list.setSelectedIndex(0);
            hintLabel.setText(filtered.size() + " 个结果" +
                    (filtered.size() >= MAX_DISPLAY_RESULTS ? "（已截断）" : ""));
        } else {
            hintLabel.setText(key.isEmpty()
                    ? "项目中未找到路由"
                    : "没有匹配的路由: " + keyword);
        }
    }

    /**
     * 导航到选中的 Controller 方法
     * 1. 读锁内收集 VirtualFile + offset（不可变数据，脱离 PSI 生命周期）
     * 2. 关闭 popup
     * 3. invokeLater 打开编辑器（popup 关闭后 invokeLater 不会被取消）
     */
    private void navigateAndClose(@NotNull Project project, @Nullable RouteItem item, @Nullable JBPopup popup) {
        if (item == null || popup == null || !popup.isVisible()) return;
        NavTarget target = ReadAction.compute(() -> {
            PsiMethod method = item.method;
            if (!method.isValid()) return null;
            PsiFile psiFile = method.getContainingFile();
            if (psiFile == null) return null;
            VirtualFile vf = psiFile.getVirtualFile();
            if (vf == null || !vf.isValid()) return null;
            int offset = method.getNameIdentifier() != null
                    ? method.getNameIdentifier().getTextOffset()
                    : method.getTextOffset();
            return new NavTarget(vf, offset);
        });
        if (target == null) return;
        popup.closeOk(null);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (target.file.isValid()) {
                new OpenFileDescriptor(project, target.file, target.offset)
                        .navigate(true);
            }
        });
    }

    /** 连续 '/' 折叠为单个，保证 "//" 与 "/" 互相可搜 */
    private static String normalizeSlashes(String s) {
        return s.replaceAll("/{2,}", "/");
    }

    private static class NavTarget {
        final VirtualFile file;
        final int offset;
        NavTarget(@NotNull VirtualFile file, int offset) { this.file = file; this.offset = offset; }
    }

    private static class RouteListCellRenderer extends JPanel implements ListCellRenderer<RouteItem> {
        private final JLabel urlLabel = new JLabel();
        private final JLabel descLabel = new JLabel();

        RouteListCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(JBUI.Borders.empty(4, 8));
            Font labelFont = UIUtil.getLabelFont();
            urlLabel.setFont(labelFont.deriveFont(13f));
            descLabel.setFont(labelFont.deriveFont(Math.max(labelFont.getSize2D() - 1f, 10f)));
            descLabel.setForeground(JBColor.GRAY);
            add(urlLabel, BorderLayout.WEST);
            add(descLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends RouteItem> list, RouteItem value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            urlLabel.setText(value.url);
            descLabel.setText(value.className + "#" + value.methodName);
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                urlLabel.setForeground(list.getSelectionForeground());
                descLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                urlLabel.setForeground(list.getForeground());
                descLabel.setForeground(JBColor.GRAY);
            }
            return this;
        }
    }
}
