package com.crudtool.action;

import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.MybatisSqlFormatUtils;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 格式化 MyBatis mapper XML 中语句标签内的 SQL（Ctrl+Alt+F）。
 *
 * 实现方式（用户建议）：提取整个语句标签的 inner content，把 MyBatis 动态标签
 * 替换为 SQL 注释占位符后得到完整 SQL，用 IDEA 内置 CodeStyleManager 格式化，
 * 再还原标签，并通过字符级校验确保没有丢失代码。
 *
 * 格式化范围：有选区时格式化选区覆盖的所有语句标签；
 * 无选区时只格式化光标所在的单个语句标签。
 */
public class FormatMapperSqlAction extends AnAction {

    /** 一次文档替换 */
    private record Replacement(int start, int end, String newText) {
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean enabled = ReadAction.compute(() -> {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
            return editor != null && psiFile instanceof XmlFile
                    && MyBatisMapperUtils.getMapperRootTag((XmlFile) psiFile) != null;
        });
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(psiFile instanceof XmlFile xmlFile)) {
            return;
        }

        List<Replacement> replacements = ReadAction.compute(() ->
                collectReplacements(xmlFile, editor));

        if (replacements.isEmpty()) {
            notify(project, "No SQL statement to format", NotificationType.INFORMATION);
            return;
        }

        // 从后往前替换，避免偏移量变化
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());

        Document document = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, "Format Mapper SQL", null, () -> {
            for (Replacement rep : replacements) {
                document.replaceString(rep.start(), rep.end(), rep.newText());
            }
        });
        notify(project, "Mapper SQL formatted", NotificationType.INFORMATION);
    }

    /**
     * 收集需要格式化的语句标签，计算 inner content 的替换范围和新文本。
     */
    @NotNull
    private List<Replacement> collectReplacements(@NotNull XmlFile xmlFile,
                                                   @NotNull Editor editor) {
        List<Replacement> result = new ArrayList<>();
        Document document = editor.getDocument();

        List<XmlTag> tags = findTargetTags(xmlFile, editor);
        for (XmlTag tag : tags) {
            if (!tag.isValid()) {
                continue;
            }
            // 找到 inner content 的范围：开始标签的 '>' 之后 ~ 结束标签的 '<' 之前
            TextRange tagRange = tag.getTextRange();
            String tagFullText = document.getText(tagRange);

            int openEnd = tagFullText.indexOf('>');
            if (openEnd < 0) {
                continue;
            }
            int closeStart = tagFullText.lastIndexOf("</");
            if (closeStart < 0) {
                continue;
            }

            int innerStart = tagRange.getStartOffset() + openEnd + 1;
            int innerEnd = tagRange.getStartOffset() + closeStart;
            String innerContent = document.getText(new TextRange(innerStart, innerEnd));

            int baseIndent = lineIndent(document, tag.getTextOffset()) + 4;
            String formatted = MybatisSqlFormatUtils.formatStatement(
                    xmlFile.getProject(), innerContent, baseIndent);

            if (formatted != null) {
                // 闭合标签的缩进 = 语句标签自身的缩进
                int closeIndent = lineIndent(document, tag.getTextOffset());
                String newInner = formatted + "\n" + " ".repeat(closeIndent);
                if (!newInner.equals(innerContent)) {
                    result.add(new Replacement(innerStart, innerEnd, newInner));
                }
            }
        }
        return result;
    }

    /**
     * 找到需要格式化的语句标签：
     * - 有选区时，选区覆盖的所有语句标签
     * - 无选区时，光标所在的单个语句标签
     */
    @NotNull
    private List<XmlTag> findTargetTags(@NotNull XmlFile xmlFile, @NotNull Editor editor) {
        List<XmlTag> result = new ArrayList<>();
        XmlTag root = MyBatisMapperUtils.getMapperRootTag(xmlFile);
        if (root == null) {
            return result;
        }

        SelectionModel selection = editor.getSelectionModel();
        if (selection.hasSelection()) {
            int selStart = selection.getSelectionStart();
            int selEnd = selection.getSelectionEnd();
            collectStatementTags(root, result, tag -> {
                TextRange r = tag.getTextRange();
                return r.getEndOffset() > selStart && r.getStartOffset() < selEnd;
            });
        } else {
            XmlTag enclosing = findEnclosingStatement(xmlFile,
                    editor.getCaretModel().getOffset());
            if (enclosing != null) {
                result.add(enclosing);
            }
        }
        return result;
    }

    /**
     * 递归收集满足条件的语句标签
     */
    private void collectStatementTags(@NotNull XmlTag tag, @NotNull List<XmlTag> out,
                                       @NotNull java.util.function.Predicate<XmlTag> filter) {
        if (MyBatisMapperUtils.isStatementTag(tag) && filter.test(tag)) {
            out.add(tag);
        }
        for (XmlTag sub : tag.getSubTags()) {
            collectStatementTags(sub, out, filter);
        }
    }

    /**
     * 从偏移量向上查找所属的 MyBatis 语句标签
     */
    @Nullable
    private XmlTag findEnclosingStatement(@NotNull XmlFile xmlFile, int offset) {
        PsiElement element = xmlFile.findElementAt(offset);
        while (element != null) {
            if (element instanceof XmlTag tag && MyBatisMapperUtils.isStatementTag(tag)) {
                return tag;
            }
            element = element.getParent();
        }
        return null;
    }

    /**
     * 元素所在行的行首缩进宽度（tab 按 4 计）
     */
    private int lineIndent(@NotNull Document document, int offset) {
        int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
        CharSequence chars = document.getCharsSequence();
        int width = 0;
        for (int i = lineStart; i < offset; i++) {
            char c = chars.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    private void notify(@NotNull Project project, @NotNull String content,
                        @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("crud-tool")
                .createNotification(content, type)
                .notify(project);
    }
}
