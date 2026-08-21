package com.crudtool.action;

import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.MybatisSqlFormatUtils;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 格式化 MyBatis mapper XML 中语句标签内的 SQL（Ctrl+Alt+F）。
 *
 * 针对 IJPL-18250 场景：当 &lt;select&gt; 等语句中混入 &lt;if&gt; 等动态标签时，
 * IDE 内置格式化无法正确格式化 SQL。本动作遍历语句标签内所有纯文本 SQL 段，
 * 用 sql-formatter 逐段格式化（保留动态标签结构与 #{} / ${} 占位符）。
 */
public class FormatMapperSqlAction extends AnAction {

    /** XML 单级缩进（4 空格） */
    private static final String INDENT_UNIT = "    ";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(findEnclosingStatement(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        XmlTag statementTag = findEnclosingStatement(e);
        if (project == null || statementTag == null) {
            return;
        }

        // 收集语句标签内所有可格式化的文本节点（含嵌套动态标签内的文本）
        List<XmlText> textNodes = new ArrayList<>();
        collectSqlTexts(statementTag, textNodes);
        if (textNodes.isEmpty()) {
            notify(project, "No SQL fragment to format", NotificationType.INFORMATION);
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, "Format Mapper SQL", null, () -> {
            for (XmlText text : textNodes) {
                if (!text.isValid()) {
                    continue;
                }
                int tagDepth = getTagDepth(text.getParentTag());
                String formatted = MybatisSqlFormatUtils.format(
                        text.getValue(), INDENT_UNIT, tagDepth + 1);
                if (formatted.equals(text.getValue())) {
                    continue;
                }
                // 重建文本：换行 + 内容缩进 + 闭合标签前的父级缩进
                String parentIndent = INDENT_UNIT.repeat(tagDepth);
                text.setValue("\n" + formatted + "\n" + parentIndent);
            }
        });
        notify(project, "SQL formatted in <" + statementTag.getName() + ">",
                NotificationType.INFORMATION);
    }

    /**
     * 从光标位置向上查找所属的 MyBatis 语句标签（select/insert/update/delete），
     * 且文件必须是 mapper XML；不满足返回 null
     */
    private XmlTag findEnclosingStatement(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(psiFile instanceof XmlFile)) {
            return null;
        }
        XmlFile xmlFile = (XmlFile) psiFile;
        if (MyBatisMapperUtils.getMapperRootTag(xmlFile) == null) {
            return null;
        }
        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        while (element != null) {
            if (element instanceof XmlTag && MyBatisMapperUtils.isStatementTag((XmlTag) element)) {
                return (XmlTag) element;
            }
            element = element.getParent();
        }
        return null;
    }

    /**
     * 递归收集标签内所有"看起来是 SQL"的文本节点
     */
    private void collectSqlTexts(@NotNull XmlTag tag, @NotNull List<XmlText> out) {
        for (PsiElement child = tag.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof XmlText) {
                if (MybatisSqlFormatUtils.looksLikeSql(((XmlText) child).getValue())) {
                    out.add((XmlText) child);
                }
            } else if (child instanceof XmlTag) {
                collectSqlTexts((XmlTag) child, out);
            }
        }
    }

    /**
     * 计算标签嵌套深度（mapper 根标签为 0）
     */
    private int getTagDepth(@NotNull XmlTag tag) {
        int depth = 0;
        PsiElement parent = tag.getParent();
        while (parent instanceof XmlTag) {
            depth++;
            parent = parent.getParent();
        }
        return depth;
    }

    private void notify(@NotNull Project project, @NotNull String content,
                        @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("crud-tool")
                .createNotification(content, type)
                .notify(project);
    }
}
