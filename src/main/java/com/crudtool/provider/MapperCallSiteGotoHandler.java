package com.crudtool.provider;

import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.ProjectUtils;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+Click / Goto Declaration：Java 方法名 / XML id 属性值 → 该 Mapper 方法的 Java 调用处。
 *
 * 用 GotoDeclarationHandler 而非 PsiReference 实现的原因：
 * reference 的 resolve() 会被平台自身的搜索（CodeVision、重命名、Find Usages 等）触发，
 * 在 resolve 内发起全局 MethodReferencesSearch 会与平台搜索嵌套执行，
 * 耗尽 ForkJoinPool 补偿线程并抛出 RejectedExecutionException。
 * handler 只在用户主动导航时执行，不会与平台搜索嵌套；
 * 多个调用点时 IDEA 自动弹出选择列表，单个调用点直接跳转。
 */
public class MapperCallSiteGotoHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement,
                                                             int offset, Editor editor) {
        if (sourceElement == null) {
            return null;
        }
        PsiMethod mapperMethod = findMapperMethod(sourceElement);
        if (mapperMethod == null) {
            return null;
        }
        List<PsiElement> targets = new ArrayList<>();
        for (PsiReference ref : MethodReferencesSearch.search(
                mapperMethod, GlobalSearchScope.projectScope(mapperMethod.getProject()), false)) {
            PsiElement element = ref.getElement();
            if (element != null && element.isValid()) {
                targets.add(element);
            }
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    /**
     * 光标位于 Mapper 接口的 Java 方法名标识符或 mapper XML 语句的 id 属性值时，
     * 返回对应 Mapper 方法；否则返回 null
     */
    @Nullable
    private PsiMethod findMapperMethod(@NotNull PsiElement element) {
        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        if (!ProjectUtils.isBizElement(element)) {
            return null;
        }

        // 1. Java 方法名标识符（必须是 Mapper 接口方法）
        PsiIdentifier identifier = PsiTreeUtil.getParentOfType(element, PsiIdentifier.class, false);
        if (identifier != null && identifier.getParent() instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) identifier.getParent();
            PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
            if (psiClass == null || !psiClass.isInterface()) {
                return null;
            }
            if (MyBatisMapperUtils.findMapperXmlFiles(psiClass, project).isEmpty()) {
                return null;
            }
            return method;
        }

        // 2. XML id 属性值（必须是 MyBatis 语句标签）
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
        PsiClass mapperClass = MyBatisMapperUtils.findMapperInterface((XmlFile) valueElement.getContainingFile(), project);
        if (mapperClass == null) {
            return null;
        }
        return MyBatisMapperUtils.findMethodByName(mapperClass, valueElement.getValue());
    }
}
