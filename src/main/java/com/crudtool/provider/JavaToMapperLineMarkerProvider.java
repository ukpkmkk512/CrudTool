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
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.crudtool.constant.RestIcons;
import com.crudtool.utils.MyBatisMapperUtils;
import com.crudtool.utils.ProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @Description: 在 Mapper 接口方法上挂一个跳转图标，点击后跳转到 mapper XML 中对应的
 * select/insert/update/delete 语句
 *
 * 关键：getLineMarkerInfo 只对 leaf 元素调用，PsiMethod 不是 leaf，
 * 因此用 PsiIdentifier（方法名标识符，是 leaf）作为入口，再取父元素判断是否为 PsiMethod。
 * 不依赖 @Mapper/@MapperScan 判断，直接通过 findMapperXmlFiles 结果判断是否是 Mapper 接口。
 */
public class JavaToMapperLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // 1. 只处理方法名标识符（leaf 元素，getLineMarkerInfo 一定调用）
        if (!(element instanceof PsiIdentifier)) {
            return null;
        }
        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod)) {
            return null;
        }
        // 2. 索引未就绪跳过
        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        // 3. 排除三方依赖扫描
        if (!ProjectUtils.isBizElement(element)) {
            return null;
        }
        PsiMethod method = (PsiMethod) parent;
        if (!method.isValid()) {
            return null;
        }
        // 4. 必须是接口（Mapper 是接口），不依赖 @Mapper/@MapperScan，直接查 XML
        PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
        if (psiClass == null || !psiClass.isInterface()) {
            return null;
        }
        // 5. 找到 namespace 匹配的 mapper XML（有对应 XML 才显示图标）
        List<XmlFile> xmlFiles = MyBatisMapperUtils.findMapperXmlFiles(psiClass, project);
        if (xmlFiles.isEmpty()) {
            return null;
        }
        // 6. 在 XML 中找匹配的语句标签
        XmlTag targetStatement = null;
        for (XmlFile xmlFile : xmlFiles) {
            XmlTag tag = MyBatisMapperUtils.findStatementById(xmlFile, method.getName());
            if (tag != null) {
                targetStatement = tag;
                break;
            }
        }
        if (targetStatement == null) {
            return null;
        }

        // 7. 锚点用方法名标识符
        PsiElement anchor = element;
        XmlTag finalTarget = targetStatement;
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
                psi -> "Jump To Mapper XML: " + method.getName(),
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> "Jump To Mapper XML"
        );
    }

    @Override
    public @Nullable @GutterName String getName() {
        return "Jump To Mapper XML";
    }
}
