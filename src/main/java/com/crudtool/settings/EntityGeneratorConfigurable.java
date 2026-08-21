package com.crudtool.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings 页面：实体类生成配置（按项目隔离）。
 * 位于 Settings → Tools → CrudTool，可勾选生成实体类时携带的注解。
 */
public class EntityGeneratorConfigurable implements Configurable {

    private final Project project;
    private JPanel panel;
    private JBCheckBox dataCheckBox;
    private JBCheckBox allArgsConstructorCheckBox;
    private JBCheckBox noArgsConstructorCheckBox;

    public EntityGeneratorConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "CrudTool";
    }

    @Override
    public @Nullable JComponent createComponent() {
        dataCheckBox = new JBCheckBox("@Data");
        allArgsConstructorCheckBox = new JBCheckBox("@AllArgsConstructor");
        noArgsConstructorCheckBox = new JBCheckBox("@NoArgsConstructor");
        panel = FormBuilder.createFormBuilder()
                .addComponent(dataCheckBox)
                .addComponent(allArgsConstructorCheckBox)
                .addComponent(noArgsConstructorCheckBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    @Override
    public boolean isModified() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        return dataCheckBox.isSelected() != settings.isData()
                || allArgsConstructorCheckBox.isSelected() != settings.isAllArgsConstructor()
                || noArgsConstructorCheckBox.isSelected() != settings.isNoArgsConstructor();
    }

    @Override
    public void apply() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        settings.setData(dataCheckBox.isSelected());
        settings.setAllArgsConstructor(allArgsConstructorCheckBox.isSelected());
        settings.setNoArgsConstructor(noArgsConstructorCheckBox.isSelected());
    }

    @Override
    public void reset() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        dataCheckBox.setSelected(settings.isData());
        allArgsConstructorCheckBox.setSelected(settings.isAllArgsConstructor());
        noArgsConstructorCheckBox.setSelected(settings.isNoArgsConstructor());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        dataCheckBox = null;
        allArgsConstructorCheckBox = null;
        noArgsConstructorCheckBox = null;
    }
}
