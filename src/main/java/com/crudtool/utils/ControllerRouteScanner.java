package com.crudtool.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.Query;
import com.crudtool.enums.SpringBootClassAnnotation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Description: 全工程 Controller 路由扫描器
 *
 * 扫描所有 @Controller / @RestController 类，对每个带 Restful 注解的方法计算完整 URL。
 *
 * 扫描策略：
 *   1. AnnotatedElementsSearch——基于注解 FQN 索引（需要读锁内调用）
 *   2. 按类名后缀 "Controller" 遍历 PsiShortNamesCache 做兜底（覆盖 AnnotatedElementsSearch
 *      因依赖未解析返回空的极端场景）
 *   3. 空结果不缓存，避免索引未完成时缓存空列表
 */
public class ControllerRouteScanner {

    private ControllerRouteScanner() {
    }

    public static class RouteItem {
        public final String url;
        public final String className;
        public final String methodName;
        public final PsiMethod method;

        public RouteItem(@NotNull String url, @NotNull String className,
                         @NotNull String methodName, @NotNull PsiMethod method) {
            this.url = url;
            this.className = className;
            this.methodName = methodName;
            this.method = method;
        }

        @Override
        public String toString() {
            return url + "  [" + className + "#" + methodName + "]";
        }
    }

    @NotNull
    public static List<RouteItem> scanAllRoutes(@NotNull Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
            List<RouteItem> routes = computeAllRoutes(project);
            // 仅在 Java 结构变化（增删类/方法、改注解）时失效重算；
            // 编辑方法体等普通修改不触发全量重扫，显著降低 Ctrl+\ 打开后的等待时间
            return CachedValueProvider.Result.create(routes,
                    PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
        });
    }

    @NotNull
    private static List<RouteItem> computeAllRoutes(@NotNull Project project) {
        List<RouteItem> routes = new ArrayList<>();
        Set<PsiClass> visited = new HashSet<>();

        // 策略 1：AnnotatedElementsSearch（基于 FQN 索引，需读锁）
        scanByAnnotationSearch(project, SpringBootClassAnnotation.CONTROLLER.getQualifiedName(), routes, visited);
        scanByAnnotationSearch(project, SpringBootClassAnnotation.RESTCONTROLLER.getQualifiedName(), routes, visited);

        // 策略 2：按类名后缀 "Controller" 遍历兜底
        if (routes.isEmpty()) {
            scanByClassNameSuffix(project, routes, visited);
        }

        return routes;
    }

    private static void scanByAnnotationSearch(@NotNull Project project, @NotNull String annotationFqn,
                                               @NotNull List<RouteItem> routes,
                                               @NotNull Set<PsiClass> visited) {
        PsiClass annotationClass = JavaPsiFacade.getInstance(project).findClass(
                annotationFqn, GlobalSearchScope.allScope(project));
        if (annotationClass == null) {
            return;
        }
        Query<PsiClass> query = AnnotatedElementsSearch.searchPsiClasses(annotationClass,
                GlobalSearchScope.projectScope(project));
        for (PsiClass controllerClass : query) {
            if (!controllerClass.isValid() || controllerClass.isInterface()) continue;
            if (visited.add(controllerClass)) {
                collectRoutesForClass(controllerClass, project, routes);
            }
        }
    }

    /**
     * 兜底策略：在 PsiShortNamesCache 中查找类名以 "Controller" 结尾的类，
     * 再用 isControllerClass 校验是否真的标注了 Controller 注解。
     *
     * PsiShortNamesCache 按类名索引，getAllClassNames() 返回项目中所有类名，
     * 过滤出以 Controller 结尾的类名，再逐个 getClassesByName 获取 PsiClass。
     */
    private static void scanByClassNameSuffix(@NotNull Project project,
                                              @NotNull List<RouteItem> routes,
                                              @NotNull Set<PsiClass> visited) {
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        String[] allNames = cache.getAllClassNames();
        for (String name : allNames) {
            if (!name.endsWith("Controller")) continue;
            for (PsiClass psiClass : cache.getClassesByName(name, scope)) {
                if (!psiClass.isValid() || psiClass.isInterface()) continue;
                if (!AnnotationParserUtils.isControllerClass(psiClass)) continue;
                if (visited.add(psiClass)) {
                    collectRoutesForClass(psiClass, project, routes);
                }
            }
        }
    }

    private static void collectRoutesForClass(@NotNull PsiClass controllerClass,
                                              @NotNull Project project,
                                              @NotNull List<RouteItem> routes) {
        // 配置前缀按类计算一次（原来每个方法算两次，是主要耗时点之一）
        String serverPath = ControllerClassScanUtils.extractSpringProperties(
                controllerClass, project, "server.servlet.context-path");
        String mvcPath = ControllerClassScanUtils.extractSpringProperties(
                controllerClass, project, "spring.mvc.servlet.path");
        String shortName = controllerClass.getName() != null
                ? controllerClass.getName() : controllerClass.getQualifiedName();

        for (PsiMethod method : controllerClass.getMethods()) {
            PsiAnnotation restfulAnnotation = AnnotationParserUtils.findRestfulAnnotation(method);
            if (restfulAnnotation == null) continue;
            String fullUrl = ControllerClassScanUtils.buildControllerUrl(
                    controllerClass, method, serverPath, mvcPath);
            if (fullUrl == null || fullUrl.isEmpty()) {
                String classPath = ControllerClassScanUtils.controllerPsiClassPath(controllerClass);
                fullUrl = (classPath != null && !classPath.isEmpty()) ? classPath : "(default)";
            }
            routes.add(new RouteItem(fullUrl, shortName, method.getName(), method));
        }
    }
}
