package com.crudtool.utils;

import com.intellij.psi.*;
import com.crudtool.enums.SpringBootClassAnnotation;
import com.crudtool.enums.SpringBootMethodAnnotation;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Description: 注解解析类（仅保留 Controller 复制 URL 所需的最小方法集）
 *
 * 所有按 FQN 查找注解的方法都有短名匹配备选——当 Spring 依赖未解析时，
 * PsiAnnotation.getQualifiedName() 返回 null，getAnnotation(FQN) 也会返回 null，
 * 此时改用注解短名匹配，确保功能仍可用。
 */
public class AnnotationParserUtils {

    /** Restful 方法注解短名集合 */
    private static final Set<String> RESTFUL_SHORT_NAMES = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping"
    ));

    /** Controller 类注解短名集合 */
    private static final Set<String> CONTROLLER_SHORT_NAMES = new HashSet<>(Arrays.asList(
            "Controller", "RestController"
    ));

    private AnnotationParserUtils() {
    }

    /**
     * 在方法上查找 Restful 注解（@RequestMapping / @GetMapping 等）
     *
     * 查找策略：
     *   1. 先按 FQN 查找（method.getAnnotation），速度快，平台有缓存
     *   2. 若 FQN 查不到，按注解短名遍历查找（依赖未解析时的备选）
     */
    public static PsiAnnotation findRestfulAnnotation(PsiMethod method) {
        // 策略 1：按 FQN 查找
        for (String qn : SpringBootMethodAnnotation.allQualifiedNames()) {
            PsiAnnotation annotation = method.getAnnotation(qn);
            if (annotation != null) {
                return annotation;
            }
        }
        // 策略 2：按短名查找（FQN 未解析时的备选）
        PsiModifierList modifierList = method.getModifierList();
        if (modifierList != null) {
            for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                String shortName = getAnnotationShortName(annotation);
                if (shortName != null && RESTFUL_SHORT_NAMES.contains(shortName)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /**
     * 判断当前类是否是 Controller（@Controller / @RestController）
     *
     * 查找策略同 findRestfulAnnotation：先 FQN，后短名
     */
    public static boolean isControllerClass(PsiClass psiClass) {
        if (psiClass == null || !psiClass.isValid()) {
            return false;
        }
        PsiModifierList modifierList = psiClass.getModifierList();
        if (modifierList == null) {
            return false;
        }
        // 策略 1：按 FQN 查找
        if (modifierList.hasAnnotation(SpringBootClassAnnotation.CONTROLLER.getQualifiedName()) ||
                modifierList.hasAnnotation(SpringBootClassAnnotation.RESTCONTROLLER.getQualifiedName())) {
            return true;
        }
        // 策略 2：按短名查找
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            String shortName = getAnnotationShortName(annotation);
            if (shortName != null && CONTROLLER_SHORT_NAMES.contains(shortName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从注解中提取短名
     * eg. "@RestController" → "RestController"
     * eg. "@org.springframework.web.bind.annotation.RestController" → "RestController"
     */
    private static String getAnnotationShortName(@NotNull PsiAnnotation annotation) {
        PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
        return ref != null ? ref.getReferenceName() : null;
    }

    /**
     * 从 Restful 注解中提取 value/path 属性值
     * 支持 String 字面量与引用常量两种形式，自动补 '/'
     */
    public static String getValueFromRestful(PsiAnnotation annotation) {
        PsiAnnotationParameterList parameterList = annotation.getParameterList();
        for (PsiNameValuePair attribute : parameterList.getAttributes()) {
            String attributeName = attribute.getAttributeName();
            if (!"value".equals(attributeName) && !"path".equals(attributeName)) {
                continue;
            }
            String literal = resolveLiteral(attribute.getValue());
            if (literal != null) {
                return literal.startsWith("/") ? literal : "/" + literal;
            }
        }
        return "";
    }

    /**
     * 解析 PsiAnnotationMemberValue 为字符串字面量
     * 处理 PsiLiteralExpression（直接字面量）和 PsiReferenceExpression（引用常量）两种情况
     */
    private static String resolveLiteral(PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression) {
            Object v = ((PsiLiteralExpression) value).getValue();
            return v instanceof String ? (String) v : null;
        }
        if (value instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) value).resolve();
            if (resolved instanceof PsiField) {
                PsiExpression initializer = ((PsiField) resolved).getInitializer();
                if (initializer instanceof PsiLiteralExpression) {
                    Object v = ((PsiLiteralExpression) initializer).getValue();
                    return v instanceof String ? (String) v : null;
                }
            }
        }
        return null;
    }
}
