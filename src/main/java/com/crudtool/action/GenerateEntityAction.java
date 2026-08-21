package com.crudtool.action;

import com.crudtool.settings.EntityGeneratorSettings;
import com.crudtool.utils.EntityGeneratorUtils;
import com.intellij.database.model.DasColumn;
import com.intellij.database.psi.DbTable;
import com.intellij.database.util.DasUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Database 工具窗口右键表 → 生成实体类文件到桌面。
 * 生成内容（注解等）由 Settings → Tools → crud-tool → Entity Generator 按项目配置。
 */
public class GenerateEntityAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(!collectTables(e).isEmpty());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        List<DbTable> tables = collectTables(e);
        if (project == null || tables.isEmpty()) {
            return;
        }

        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        File desktop = FileSystemView.getFileSystemView().getDefaultDirectory();

        List<String> generated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (DbTable table : tables) {
            try {
                String tableName = table.getName();
                String className = EntityGeneratorUtils.toClassName(tableName);

                List<EntityGeneratorUtils.ColumnInfo> columns = new ArrayList<>();
                for (DasColumn column : DasUtil.getColumns(table.getDasObject())) {
                    EntityGeneratorUtils.ColumnInfo info = new EntityGeneratorUtils.ColumnInfo();
                    info.fieldName = EntityGeneratorUtils.toFieldName(column.getName());
                    info.javaType = EntityGeneratorUtils.toJavaType(column.getDasType());
                    info.comment = column.getComment();
                    columns.add(info);
                }

                String source = EntityGeneratorUtils.generateSource(
                        className, table.getComment(), columns,
                        settings.isData(), settings.isAllArgsConstructor(), settings.isNoArgsConstructor());

                File file = new File(desktop, className + ".java");
                Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
                generated.add(className + ".java");
            } catch (Exception ex) {
                failed.add(table.getName() + ": " + ex.getMessage());
            }
        }

        if (!generated.isEmpty()) {
            notify(project, "Generated " + String.join(", ", generated) + " to Desktop",
                    NotificationType.INFORMATION);
        }
        for (String message : failed) {
            notify(project, "Failed to generate entity: " + message, NotificationType.ERROR);
        }
    }

    /**
     * 收集右键选中的数据库表（支持多选）
     */
    @NotNull
    private List<DbTable> collectTables(@NotNull AnActionEvent e) {
        List<DbTable> tables = new ArrayList<>();
        PsiElement[] elements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        if (elements != null) {
            for (PsiElement element : elements) {
                if (element instanceof DbTable) {
                    tables.add((DbTable) element);
                }
            }
        }
        if (tables.isEmpty()) {
            PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
            if (element instanceof DbTable) {
                tables.add((DbTable) element);
            }
        }
        return tables;
    }

    private void notify(@NotNull Project project, @NotNull String content,
                        @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("crud-tool")
                .createNotification(content, type)
                .notify(project);
    }
}
