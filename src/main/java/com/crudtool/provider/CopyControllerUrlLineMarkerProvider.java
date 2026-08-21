package com.crudtool.provider;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.GutterName;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.crudtool.constant.RestIcons;
import com.crudtool.utils.AnnotationParserUtils;
import com.crudtool.utils.ControllerClassScanUtils;
import com.crudtool.utils.ProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;

/**
 * @Description: 在 Controller 接口的 Restful 注解上挂一个复制图标的 Gutter，点击后把完整 URL 复制到剪贴板
 *
 * 实现接口 LineMarkerProviderDescriptor，手动构建 LineMarkerInfo<PsiElement>（点击行为是复制而非跳转）
 */
public class CopyControllerUrlLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // 1. 最廉价过滤：getLineMarkerInfo 会对每个叶子 PsiElement 触发，绝大多数不是方法
        if (!(element instanceof PsiMethod)) {
            return null;
        }
        // 2. 索引未就绪直接跳过，避免在 dumb mode 下访问 PSI 索引抛异常
        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return null;
        }
        // 3. 排除三方依赖扫描（涉及 VirtualFile/ProjectFileIndex 查询，放在 instanceof 之后）
        if (!ProjectUtils.isBizElement(element)) {
            return null;
        }
        PsiMethod method = (PsiMethod) element;
        if (!method.isValid()) {
            return null;
        }
        // 4. 仅 Controller 类下的方法才显示
        PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
        if (psiClass == null || !AnnotationParserUtils.isControllerClass(psiClass)) {
            return null;
        }
        // 5. 找到方法上的 Restful 注解，gutter 挂在注解上
        PsiAnnotation restfulAnnotation = AnnotationParserUtils.findRestfulAnnotation(method);
        if (restfulAnnotation == null) {
            return null;
        }
        // 6. 按需计算 URL，不做全工程扫描、不进缓存
        String url = ControllerClassScanUtils.buildControllerUrl(psiClass, project, method);
        if (url == null || url.isEmpty()) {
            return null;
        }

        PsiMethod finalMethod = method;
        GutterIconNavigationHandler<PsiElement> handler = (mouseEvent, elt) -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(url));
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("crud-tool")
                    .createNotification("URL Copied To Clipboard:\n" + url, NotificationType.INFORMATION)
                    .notify(finalMethod.getProject());
        };

        return new LineMarkerInfo<>(
                restfulAnnotation,
                restfulAnnotation.getTextRange(),
                RestIcons.STATEMENT_LINE_CLIPBOARD_CONTROLLER_ICON,
                psi -> "Click To Copy Controller-URL: " + url,
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> "Copy Controller URL"
        );
    }

    @Override
    public @Nullable @GutterName String getName() {
        return "Copy Controller URL";
    }
}
