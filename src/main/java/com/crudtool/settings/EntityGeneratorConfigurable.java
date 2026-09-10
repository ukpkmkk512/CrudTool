package com.crudtool.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings 页面：实体类生成配置 + Eolink 上传配置（按项目隔离）。
 * 位于 Settings → Tools → CrudTool。
 */
public class EntityGeneratorConfigurable implements Configurable {

    private final Project project;
    private JPanel panel;
    private JBCheckBox dataCheckBox;
    private JBCheckBox allArgsConstructorCheckBox;
    private JBCheckBox noArgsConstructorCheckBox;
    private JBTextField eolinkBaseUrlField;
    private JBTextField eolinkAccountField;
    private JBPasswordField eolinkPasswordField;
    private JBTextField eolinkProjectHashKeyField;
    private JBTextField eolinkGroupNameField;

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

        eolinkBaseUrlField = new JBTextField();
        eolinkAccountField = new JBTextField();
        eolinkPasswordField = new JBPasswordField();
        eolinkProjectHashKeyField = new JBTextField();
        eolinkGroupNameField = new JBTextField();

        panel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("实体类注解:"))
                .addComponent(dataCheckBox)
                .addComponent(allArgsConstructorCheckBox)
                .addComponent(noArgsConstructorCheckBox)
                .addVerticalGap(12)
                .addComponent(new JBLabel("Eolink 上传配置（Controller 右键 → Upload to Eolink）:"))
                .addLabeledComponent("实例地址:", eolinkBaseUrlField)
                .addLabeledComponent("账号:", eolinkAccountField)
                .addLabeledComponent("密码:", eolinkPasswordField)
                .addLabeledComponent("项目 HashKey:", eolinkProjectHashKeyField)
                .addLabeledComponent("分组名(支持 a/b 层级):", eolinkGroupNameField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    @Override
    public boolean isModified() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        EolinkSettings eolink = EolinkSettings.getInstance(project);
        return dataCheckBox.isSelected() != settings.isData()
                || allArgsConstructorCheckBox.isSelected() != settings.isAllArgsConstructor()
                || noArgsConstructorCheckBox.isSelected() != settings.isNoArgsConstructor()
                || !eolinkBaseUrlField.getText().trim().equals(eolink.getState().baseUrl)
                || !eolinkAccountField.getText().trim().equals(eolink.getState().account)
                || !new String(eolinkPasswordField.getPassword()).equals(eolink.getPassword())
                || !eolinkProjectHashKeyField.getText().trim().equals(eolink.getState().projectHashKey)
                || !eolinkGroupNameField.getText().trim().equals(eolink.getState().groupName);
    }

    @Override
    public void apply() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        settings.setData(dataCheckBox.isSelected());
        settings.setAllArgsConstructor(allArgsConstructorCheckBox.isSelected());
        settings.setNoArgsConstructor(noArgsConstructorCheckBox.isSelected());

        EolinkSettings eolink = EolinkSettings.getInstance(project);
        eolink.getState().baseUrl = eolinkBaseUrlField.getText().trim();
        eolink.getState().account = eolinkAccountField.getText().trim();
        eolink.setPassword(new String(eolinkPasswordField.getPassword()));
        eolink.getState().projectHashKey = eolinkProjectHashKeyField.getText().trim();
        eolink.getState().groupName = eolinkGroupNameField.getText().trim();
    }

    @Override
    public void reset() {
        EntityGeneratorSettings settings = EntityGeneratorSettings.getInstance(project);
        dataCheckBox.setSelected(settings.isData());
        allArgsConstructorCheckBox.setSelected(settings.isAllArgsConstructor());
        noArgsConstructorCheckBox.setSelected(settings.isNoArgsConstructor());

        EolinkSettings eolink = EolinkSettings.getInstance(project);
        eolinkBaseUrlField.setText(eolink.getState().baseUrl);
        eolinkAccountField.setText(eolink.getState().account);
        eolinkPasswordField.setText(eolink.getPassword());
        eolinkProjectHashKeyField.setText(eolink.getState().projectHashKey);
        eolinkGroupNameField.setText(eolink.getState().groupName);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        dataCheckBox = null;
        allArgsConstructorCheckBox = null;
        noArgsConstructorCheckBox = null;
        eolinkBaseUrlField = null;
        eolinkAccountField = null;
        eolinkPasswordField = null;
        eolinkProjectHashKeyField = null;
        eolinkGroupNameField = null;
    }
}
