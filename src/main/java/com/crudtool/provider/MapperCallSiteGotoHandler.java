package com.crudtool.provider;

import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.ProjectUtils;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ctrl+Click / Goto Declaration：
 * - mapper XML 语句 id 属性值 → Java Mapper 接口方法
 *
 * 注意：Java → XML 方向的跳转不使用 GotoDeclarationHandler / PsiReference 实现，
 * 因为会与 IDEA 内置 Ctrl+Click（跳转到声明/实现）冲突。
 * Java → XML 由 MapperXmlJumpAction + MapperJumpEditorListener（Ctrl+Alt+Click）
 * 以及 gutter 图标（JavaToMapperLineMarkerProvider）承担。
 */
public class MapperCallSiteGotoHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement,
                                                             int offset, Editor editor) {
        if (sourceElement == null) {
            return null;
        }
        Project project = sourceElement.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        if (!ProjectUtils.isBizElement(sourceElement)) {
            return null;
        }

        // mapper XML 语句 id 属性值 → Java Mapper 接口方法
        PsiElement xmlTarget = findJavaMethodFromXmlId(sourceElement);
        if (xmlTarget != null) {
            return new PsiElement[]{xmlTarget};
        }

        return null;
    }

    /**
     * 光标位于 mapper XML 语句 id 属性值时，返回对应 Java Mapper 方法；否则返回 null
     */
    @Nullable
    private PsiElement findJavaMethodFromXmlId(@NotNull PsiElement element) {
        XmlAttributeValue valueElement = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
        if (valueElement == null) {
            return null;
        }
        XmlAttribute attribute = PsiTreeUtil.getParentOfType(valueElement, XmlAttribute.class, false);
        if (attribute == null || !"id".equals(attribute.getName())) {
            return null;
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(attribute, XmlTag.class, false);
        if (tag == null || !MyBatisMapperUtils.isStatementTag(tag)) {
            return null;
        }
        if (!(valueElement.getContainingFile() instanceof XmlFile)) {
            return null;
        }
        PsiClass mapperClass = MyBatisMapperUtils.findMapperInterface(
                (XmlFile) valueElement.getContainingFile(), element.getProject());
        if (mapperClass == null) {
            return null;
        }
        return MyBatisMapperUtils.findMethodByName(mapperClass, valueElement.getValue());
    }
}
