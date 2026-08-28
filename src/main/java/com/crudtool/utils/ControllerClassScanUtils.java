package com.crudtool.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.crudtool.properties.ConfigReader;
import com.crudtool.properties.ServerParser;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static com.crudtool.enums.SpringBootMethodAnnotation.REQUEST_MAPPING;

/**
 * @Description: Controller 接口 URL 计算工具类（已剥离扫描/缓存逻辑，按需计算）
 */
public class ControllerClassScanUtils {

    private static final String SPRINGBOOT_SERVER_PATH = "server.servlet.context-path";
    private static final String SPRINGMVC_PATH = "spring.mvc.servlet.path";

    private ControllerClassScanUtils() {
    }

    /**
     * 计算 Controller 方法的完整 URL：
     *   server.servlet.context-path + spring.mvc.servlet.path + 类级 @RequestMapping path + 方法级 Restful path
     *
     * @param psiClass 方法所属的 Controller 类
     * @param project  当前工程
     * @param method   Controller 方法
     * @return 完整 URL，若方法没有 Restful 注解则返回空串；任何一段缺失都视作空串
     */
    @NotNull
    public static String buildControllerUrl(PsiClass psiClass, Project project, PsiMethod method) {
        String serverPath = extractSpringProperties(psiClass, project, SPRINGBOOT_SERVER_PATH);
        String mvcPath = extractSpringProperties(psiClass, project, SPRINGMVC_PATH);
        return buildControllerUrl(psiClass, method, serverPath, mvcPath);
    }

    /**
     * 使用预先计算好的配置前缀拼接 URL，避免同一类的每个方法重复解析配置文件
     */
    @NotNull
    public static String buildControllerUrl(PsiClass psiClass, PsiMethod method,
                                            String serverPath, String mvcPath) {
        String classPath = controllerPsiClassPath(psiClass);
        String methodPath = controllerMethodPath(method);
        return normalizePath(serverPath + mvcPath + classPath + methodPath);
    }

    /**
     * 归一化路径：连续的 '/' 折叠为单个。
     * 类级 @RequestMapping 带尾斜杠（如 "/api/szl/healthcoin/"）时直接拼接会产生 "//"，
     * 而 Spring 匹配时把重复斜杠视为同一路由，实际请求路径是单斜杠，
     * 不归一化会导致复制出的路由与真实路由不一致、按真实路由搜索不到。
     */
    private static String normalizePath(String url) {
        return url.replaceAll("/{2,}", "/");
    }

    /**
     * 提取 Controller 类上 @RequestMapping 的路径
     * 先按 FQN 查找，失败后按短名 "RequestMapping" 查找（依赖未解析时的备选）
     */
    public static String controllerPsiClassPath(PsiClass psiClass) {
        PsiAnnotation annotation = psiClass.getAnnotation(REQUEST_MAPPING.getQualifiedName());
        if (annotation == null) {
            // FQN 未解析时，按短名查找
            PsiModifierList modifierList = psiClass.getModifierList();
            if (modifierList != null) {
                for (PsiAnnotation ann : modifierList.getAnnotations()) {
                    PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                    if (ref != null && "RequestMapping".equals(ref.getReferenceName())) {
                        annotation = ann;
                        break;
                    }
                }
            }
        }
        return annotation != null ? AnnotationParserUtils.getValueFromRestful(annotation) : "";
    }

    /**
     * 提取 Controller 方法上 Restful 注解的路径
     */
    public static String controllerMethodPath(PsiMethod method) {
        PsiAnnotation annotation = AnnotationParserUtils.findRestfulAnnotation(method);
        return annotation != null ? AnnotationParserUtils.getValueFromRestful(annotation) : "";
    }

    /**
     * 解析配置项
     * eg. server.servlet.context-path=/hello
     * eg. spring.mvc.servlet.path=/world
     */
    public static String extractSpringProperties(PsiClass psiClass, Project project, String configKey) {
        Optional<com.intellij.psi.PsiDirectory> moduleDir = ServerParser.getServiceModuleResourcesDirectory(psiClass, project);
        if (!moduleDir.isPresent()) {
            return "";
        }
        // 先查 properties，未命中再查 yml/yaml
        Properties properties = ConfigReader.readProperties(moduleDir.get());
        if (properties != null && properties.containsKey(configKey)) {
            return properties.getProperty(configKey);
        }
        Map<String, Object> yml = ConfigReader.readYmlOrYaml(moduleDir.get());
        if (yml != null) {
            String value = extractValueFromYml(yml, configKey);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    // 从 YAML Map 中按点分路径提取嵌套键值
    private static String extractValueFromYml(Map<String, Object> yml, String configKey) {
        String[] keys = configKey.split("\\.");
        Object value = yml;
        for (String key : keys) {
            if (value instanceof Map) {
                value = ((Map<?, ?>) value).get(key);
            } else {
                return null;
            }
        }
        return value != null ? value.toString() : null;
    }
}
