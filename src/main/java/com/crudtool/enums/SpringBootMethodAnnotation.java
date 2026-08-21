package com.crudtool.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: Spring Boot 方法级 Restful 注解枚举
 */
public enum SpringBootMethodAnnotation {
    REQUEST_MAPPING("org.springframework.web.bind.annotation.RequestMapping", null),
    GET_MAPPING("org.springframework.web.bind.annotation.GetMapping", "GET"),
    POST_MAPPING("org.springframework.web.bind.annotation.PostMapping", "POST"),
    PUT_MAPPING("org.springframework.web.bind.annotation.PutMapping", "PUT"),
    DELETE_MAPPING("org.springframework.web.bind.annotation.DeleteMapping", "DELETE"),
    PATCH_MAPPING("org.springframework.web.bind.annotation.PatchMapping", "PATCH");

    private final String qualifiedName;
    private final String methodName;

    // 一次构建，O(1) 查询，避免 values() 线性扫描
    private static final Map<String, SpringBootMethodAnnotation> QN_INDEX = new HashMap<>();
    private static final List<String> ALL_QUALIFIED_NAMES;

    static {
        for (SpringBootMethodAnnotation a : values()) {
            QN_INDEX.put(a.qualifiedName, a);
        }
        ALL_QUALIFIED_NAMES = Collections.unmodifiableList(
                Arrays.asList(
                        REQUEST_MAPPING.qualifiedName,
                        GET_MAPPING.qualifiedName,
                        POST_MAPPING.qualifiedName,
                        PUT_MAPPING.qualifiedName,
                        DELETE_MAPPING.qualifiedName,
                        PATCH_MAPPING.qualifiedName
                )
        );
    }

    SpringBootMethodAnnotation(String qualifiedName, String methodName) {
        this.qualifiedName = qualifiedName;
        this.methodName = methodName;
    }

    public static SpringBootMethodAnnotation getByQualifiedName(String qualifiedName) {
        return qualifiedName == null ? null : QN_INDEX.get(qualifiedName);
    }

    public String methodName() {
        return this.methodName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    /**
     * 全部注解全限定名（不可变列表，复用避免每次新建）
     * 用于 findRestfulAnnotation 中按注解名逐个 hasAnnotation 查找
     */
    public static List<String> allQualifiedNames() {
        return ALL_QUALIFIED_NAMES;
    }
}
