package com.crudtool.provider;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.GutterName;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.crudtool.constant.RestIcons;
import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.ProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @Description: 在 mapper XML 中的 select/insert/update/delete 语句上挂一个跳转图标，
 * 点击后跳转到对应的 Mapper 接口方法
 */
public class MapperToJavaLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // 1. 快速过滤：仅 XmlTag 触发
        if (!(element instanceof XmlTag)) {
            return null;
        }
        XmlTag tag = (XmlTag) element;
        // 2. 索引未就绪直接跳过
        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        // 3. 排除三方依赖扫描
        if (!ProjectUtils.isBizElement(element)) {
            return null;
        }
        // 4. 仅 MyBatis 语句标签（select/insert/update/delete）才处理
        if (!MyBatisMapperUtils.isStatementTag(tag)) {
            return null;
        }
        String id = tag.getAttributeValue("id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        // 5. 必须是 mapper XML 文件
        PsiFile containingFile = element.getContainingFile();
        if (!(containingFile instanceof XmlFile)) {
            return null;
        }
        XmlFile xmlFile = (XmlFile) containingFile;
        // 6. 根据 namespace 找到 Mapper Java 接口
        PsiClass mapperClass = MyBatisMapperUtils.findMapperInterface(xmlFile, project);
        if (mapperClass == null) {
            return null;
        }
        // 7. 在接口中按 id 找到方法
        PsiMethod targetMethod = MyBatisMapperUtils.findMethodByName(mapperClass, id);
        if (targetMethod == null) {
            return null;
        }

        // 8. 锚点优先用 id 属性值元素，使 gutter 挂在 id 行
        PsiElement anchor = null;
        XmlAttribute idAttr = MyBatisMapperUtils.getIdAttribute(tag);
        if (idAttr != null) {
            XmlAttributeValue valueElement = idAttr.getValueElement();
            if (valueElement != null) {
                anchor = valueElement;
            }
        }
        if (anchor == null) {
            anchor = tag;
        }
        PsiMethod finalTarget = targetMethod;
        GutterIconNavigationHandler<PsiElement> handler = (mouseEvent, elt) -> {
            PsiElement navTarget = finalTarget.getNavigationElement();
            if (navTarget != null && navTarget.isValid() && navTarget instanceof Navigatable) {
                ((Navigatable) navTarget).navigate(true);
            }
        };

        return new LineMarkerInfo<>(
                anchor,
                anchor.getTextRange(),
                RestIcons.STATEMENT_LINE_MAPPER_JUMP_ICON,
                psi -> "Jump To Mapper Method: " + id,
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> "Jump To Mapper Method"
        );
    }

    @Override
    public @Nullable @GutterName String getName() {
        return "Jump To Mapper Method";
    }
}
