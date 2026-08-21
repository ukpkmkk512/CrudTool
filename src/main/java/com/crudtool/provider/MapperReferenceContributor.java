package com.crudtool.provider;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.ProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @Description: 注册 XML resultMap 属性值 → <resultMap> 标签定义处的 PsiReference。
 *
 * 注意：Java 方法名 / XML id 属性值 → Java 调用处 的跳转由
 * MapperCallSiteGotoHandler（GotoDeclarationHandler）承担，不在此处用 PsiReference 实现。
 * 原因：reference 的 resolve() 会被平台自身的搜索（CodeVision、重命名、Find Usages 等）触发，
 * 在 resolve 内发起全局 MethodReferencesSearch 会与平台搜索嵌套执行，
 * 耗尽 ForkJoinPool 补偿线程并抛出 RejectedExecutionException。
 */
public class MapperReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        // XML resultMap 属性值 → <resultMap> 标签定义处
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                new XmlResultMapReferenceProvider()
        );
    }

    /**
     * XML resultMap 属性值 → 匹配的 <resultMap id="..."> 标签定义处
     */
    private static class XmlResultMapReferenceProvider extends PsiReferenceProvider {
        @NotNull
        @Override
        public PsiReference[] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue)) {
                return PsiReference.EMPTY_ARRAY;
            }
            XmlAttributeValue valueElement = (XmlAttributeValue) element;
            PsiElement parent = valueElement.getParent();
            if (!(parent instanceof XmlAttribute)) {
                return PsiReference.EMPTY_ARRAY;
            }
            XmlAttribute attribute = (XmlAttribute) parent;
            if (!"resultMap".equals(attribute.getName())) {
                return PsiReference.EMPTY_ARRAY;
            }
            PsiElement grandParent = attribute.getParent();
            if (!(grandParent instanceof XmlTag)) {
                return PsiReference.EMPTY_ARRAY;
            }
            XmlTag tag = (XmlTag) grandParent;
            if (!MyBatisMapperUtils.isStatementTag(tag)) {
                return PsiReference.EMPTY_ARRAY;
            }
            Project project = element.getProject();
            if (DumbService.isDumb(project)) {
                return PsiReference.EMPTY_ARRAY;
            }
            if (!ProjectUtils.isBizElement(element)) {
                return PsiReference.EMPTY_ARRAY;
            }
            if (!(element.getContainingFile() instanceof XmlFile)) {
                return PsiReference.EMPTY_ARRAY;
            }
            XmlFile xmlFile = (XmlFile) element.getContainingFile();
            // resultMap 属性支持逗号分隔多个，取第一个匹配
            String first = valueElement.getValue().split(",")[0].trim();
            if (first.isEmpty()) {
                return PsiReference.EMPTY_ARRAY;
            }
            XmlTag resultMap = MyBatisMapperUtils.findResultMapById(xmlFile, first);
            if (resultMap == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            return new PsiReference[]{ new XmlResultMapReference(valueElement, resultMap) };
        }
    }

    /**
     * 单目标引用：resolve 到对应的 <resultMap> 标签
     */
    private static class XmlResultMapReference extends PsiReferenceBase<PsiElement> {
        private final XmlTag resultMap;

        XmlResultMapReference(@NotNull PsiElement element, @NotNull XmlTag resultMap) {
            super(element);
            this.resultMap = resultMap;
        }

        @Override
        public @Nullable PsiElement resolve() {
            return resultMap.isValid() ? resultMap : null;
        }
    }
}
