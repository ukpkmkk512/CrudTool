package com.crudtool.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.crudtool.utils.MyBatisMapperUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @Description: Ctrl+Alt+Click 触发的 Mapper 接口方法 ↔ XML 语句双向跳转
 */
public class MapperXmlJumpAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || DumbService.isDumb(project)) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || psiFile == null) return;

        JumpResult result = ReadAction.compute(() -> findJumpTarget(project, editor, psiFile));
        handleResult(project, result);
    }

    /**
     * 查找跳转目标（在读锁内调用），供 EditorMouseListener/AnAction 使用
     * 使用当前 caret 位置定位元素
     * @return 查找结果（包含目标元素或错误信息）
     */
    @NotNull
    public static JumpResult findJumpTarget(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile psiFile) {
        return findJumpTarget(project, editor, psiFile, editor.getCaretModel().getOffset());
    }

    /**
     * 查找跳转目标（在读锁内调用），使用指定 offset 定位元素
     * @param offset 鼠标点击/光标对应的文档偏移量
     */
    @NotNull
    public static JumpResult findJumpTarget(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile psiFile, int offset) {
        if (DumbService.isDumb(project)) return JumpResult.notFound();
        if (offset < 0 || offset > psiFile.getTextLength()) return JumpResult.notFound();
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null) return JumpResult.notFound();

        // XML id → Java 方法
        PsiElement target = findXmlToJavaTarget(element, project);
        if (target != null) return JumpResult.found(target);

        // Java 方法名 → XML 语句
        return findJavaToXmlTarget(element, project);
    }

    /**
     * 处理查找结果（在读锁外调用，可安全导航）
     * 注意：不再弹错误提示框，避免干扰用户（纯注解 Mapper、未写完的方法等都不应弹窗）
     */
    public static void handleResult(@NotNull Project project, @NotNull JumpResult result) {
        if (result.target != null) {
            navigateTo(result.target);
        }
    }

    private static void navigateTo(@Nullable PsiElement target) {
        if (target == null || !target.isValid()) return;
        PsiElement navTarget = target.getNavigationElement();
        if (navTarget instanceof Navigatable && ((Navigatable) navTarget).canNavigate()) {
            ((Navigatable) navTarget).navigate(true);
        }
    }

    @Nullable
    private static PsiElement findXmlToJavaTarget(@NotNull PsiElement element, @NotNull Project project) {
        XmlAttributeValue idValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class);
        if (idValue == null) return null;
        PsiElement parent = idValue.getParent();
        if (!(parent instanceof com.intellij.psi.xml.XmlAttribute)) return null;
        com.intellij.psi.xml.XmlAttribute attribute = (com.intellij.psi.xml.XmlAttribute) parent;
        if (!"id".equals(attribute.getName())) return null;
        PsiElement grandParent = attribute.getParent();
        if (!(grandParent instanceof XmlTag)) return null;
        XmlTag tag = (XmlTag) grandParent;
        if (!MyBatisMapperUtils.isStatementTag(tag)) return null;
        PsiFile containingFile = element.getContainingFile();
        if (!(containingFile instanceof XmlFile)) return null;
        XmlFile xmlFile = (XmlFile) containingFile;
        PsiClass mapperClass = MyBatisMapperUtils.findMapperInterface(xmlFile, project);
        if (mapperClass == null) return null;
        PsiMethod targetMethod = MyBatisMapperUtils.findMethodByName(mapperClass, idValue.getValue());
        if (targetMethod == null) return null;
        return targetMethod.getNameIdentifier() != null ? targetMethod.getNameIdentifier() : targetMethod;
    }

    @NotNull
    private static JumpResult findJavaToXmlTarget(@NotNull PsiElement element, @NotNull Project project) {
        // 1. 优先：Service 调用处的方法名标识符（userMapper.selectUser 的 selectUser）
        PsiMethod callMethod = resolveMethodFromCallSite(element);
        if (callMethod != null) {
            PsiClass callClass = callMethod.getContainingClass();
            if (callClass != null && callClass.isInterface()
                    && MyBatisMapperUtils.isMapperInterface(callClass, project)) {
                List<XmlFile> xmlFiles = MyBatisMapperUtils.findMapperXmlFiles(callClass, project);
                for (XmlFile xmlFile : xmlFiles) {
                    XmlTag statement = MyBatisMapperUtils.findStatementById(xmlFile, callMethod.getName());
                    if (statement != null) {
                        return JumpResult.found(statement);
                    }
                }
                return JumpResult.consumed();
            }
        }

        // 2. Mapper 接口方法声明处
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return JumpResult.notFound();
        PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
        if (psiClass == null || !psiClass.isInterface()) return JumpResult.notFound();

        // 首先判断是否是 Mapper 接口（非 Mapper 接口直接放行，让 IDEA 处理 GotoImplementation）
        if (!MyBatisMapperUtils.isMapperInterface(psiClass, project)) {
            return JumpResult.notFound();
        }

        // 查找匹配的 XML 文件（通过 namespace 缓存，不局限于文件名）
        List<XmlFile> xmlFiles = MyBatisMapperUtils.findMapperXmlFiles(psiClass, project);

        // 找到 XML 文件，查找对应 id 的语句
        for (XmlFile xmlFile : xmlFiles) {
            XmlTag statement = MyBatisMapperUtils.findStatementById(xmlFile, method.getName());
            if (statement != null) {
                return JumpResult.found(statement);
            }
        }

        // 没找到 XML 语句：如果方法有 @Select/@Update 等注解 SQL，静默消费事件（已处理，无需 IDEA 弹 No implementations）
        // 如果既没 XML 也没注解，也静默消费事件（不弹错误框，用户可能正在开发中）
        return JumpResult.consumed();
    }

    /**
     * 从 Service 调用处解析目标方法：光标在 userMapper.selectUser(...) 的 selectUser 标识符上时，
     * 通过 PsiMethodCallExpression.resolveMethod() 获取被调用的方法
     */
    @Nullable
    private static PsiMethod resolveMethodFromCallSite(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) {
            return null;
        }
        PsiElement parent = element.getParent();
        if (!(parent instanceof com.intellij.psi.PsiReferenceExpression)) {
            return null;
        }
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(
                element, PsiMethodCallExpression.class, false);
        if (call == null) {
            return null;
        }
        return call.resolveMethod();
    }

    /**
     * 跳转结果：找到目标、未找到（静默，不消费事件）、或已处理（静默消费事件）
     */
    public static class JumpResult {
        @Nullable public final PsiElement target;
        public final boolean consumed;

        private JumpResult(@Nullable PsiElement target, boolean consumed) {
            this.target = target;
            this.consumed = consumed;
        }

        static JumpResult found(@NotNull PsiElement target) {
            return new JumpResult(target, true);
        }

        static JumpResult notFound() {
            return new JumpResult(null, false);
        }

        /**
         * 已处理但未找到目标：消费事件阻止 IDEA 弹 No implementations，但不弹窗不跳转
         */
        static JumpResult consumed() {
            return new JumpResult(null, true);
        }

        public boolean isFound() { return target != null; }
        public boolean shouldConsume() { return consumed; }
    }
}
