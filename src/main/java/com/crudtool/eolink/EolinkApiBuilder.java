package com.crudtool.eolink;

import com.crudtool.utils.AnnotationParserUtils;
import com.crudtool.utils.ControllerClassScanUtils;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 Controller 方法解析为 Eolink 内部 API（addApi/editApi）的表单字段：
 * URL、请求方式、Query/Restful/请求体参数树、返回结果参数树。
 * 调用方需持有读锁。spaceKey/projectHashKey 由客户端补充。
 */
public class EolinkApiBuilder {

    // Eolink 参数类型：0=string 3=int 8=boolean 12=array 13=object 14=number
    private static final String T_STRING = "0", T_INT = "3", T_BOOLEAN = "8",
            T_ARRAY = "12", T_OBJECT = "13", T_NUMBER = "14";

    // paramNotNull：0=必填 1=非必填（Eolink 内部约定，值与语义相反）
    private static final String REQUIRED = "0", OPTIONAL = "1";

    private static final int MAX_DEPTH = 5;

    private EolinkApiBuilder() {
    }

    /** 构建完整表单字段（不含 spaceKey/projectHashKey，由客户端补充），字段集与网页端保存请求完全对齐 */
    @NotNull
    public static Map<String, Object> build(@NotNull PsiMethod method, @NotNull PsiClass controllerClass,
                                            long groupId, @NotNull String serverPath, @NotNull String mvcPath,
                                            @NotNull List<String> debug) {
        List<Map<String, Object>> urlParams = new ArrayList<>();
        List<Map<String, Object>> restfulParams = new ArrayList<>();
        List<Map<String, Object>> requestParams = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Map<String, String> paramDocs = paramDocs(method);
        debug.add("paramDocs=" + paramDocs);

        boolean hasRequestBody = false;
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            PsiType type = parameter.getType();
            String name = parameter.getName();
            if (hasAnnotation(parameter, "PathVariable")) {
                restfulParams.add(paramItem(name, type, REQUIRED, paramDocs.get(name), new HashSet<>(), debug));
            } else if (hasAnnotation(parameter, "RequestBody")) {
                hasRequestBody = true;
                PsiClass paramClass = resolveClass(type);
                if (paramClass != null) {
                    visited.clear();
                    visited.add(fqn(paramClass));
                    requestParams.addAll(classFields(paramClass, substitutorOf(type), 1, visited, debug));
                }
            } else {
                // @RequestParam 或无注解：Query 参数（POJO 展开其字段）
                PsiClass paramClass = resolveClass(type);
                if (paramClass != null && !isSimpleClass(paramClass)) {
                    visited.clear();
                    visited.add(fqn(paramClass));
                    urlParams.addAll(classFields(paramClass, substitutorOf(type), 1, visited, debug));
                } else {
                    urlParams.add(paramItem(name, type, OPTIONAL, paramDocs.get(name), new HashSet<>(), debug));
                }
            }
        }

        // 返回结果参数树
        PsiType returnType = method.getReturnType();
        List<Map<String, Object>> resultParams = new ArrayList<>();
        int resultJsonType = 0;
        if (returnType != null) {
            PsiType elementType = collectionElementType(returnType);
            boolean isArray = elementType != null || returnType instanceof PsiArrayType;
            PsiType target = elementType != null ? elementType
                    : (returnType instanceof PsiArrayType ? ((PsiArrayType) returnType).getComponentType() : returnType);
            if (isArray) {
                resultJsonType = 1;
            }
            PsiClass returnClass = resolveClass(target);
            if (returnClass != null && !isSimpleClass(returnClass) && !isMap(returnClass)) {
                debug.add("返回类型类: " + returnClass.getQualifiedName() + " 文件: "
                        + (returnClass.getContainingFile() != null ? returnClass.getContainingFile().getName() : "?")
                        + (returnClass instanceof PsiCompiledElement ? "(编译类)" : "(源码)"));
                visited.clear();
                visited.add(fqn(returnClass));
                resultParams.addAll(classFields(returnClass, substitutorOf(target), 1, visited, debug));
            }
        }
        Map<String, Object> resultItem = new LinkedHashMap<>();
        resultItem.put("responseCode", "200");
        resultItem.put("responseName", "成功");
        resultItem.put("responseType", 0);
        resultItem.put("paramJsonType", 0);
        resultItem.put("paramList", resultParams);
        resultItem.put("raw", "");
        resultItem.put("binary", "");
        resultItem.put("isDefault", 1);

        // 以下字段集与当前网页端 addApi/editApi 请求逐项对齐
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupID", groupId);
        body.put("apiManagerConnID", -1);
        body.put("apiType", "http");
        body.put("apiRequestType", httpMethod(method));
        body.put("apiHeader", List.of());
        body.put("apiRestfulParam", restfulParams);
        body.put("apiUrlParam", urlParams);
        body.put("responseHeader", List.of());
        body.put("apiRequestParam", requestParams);
        body.put("apiResultParam", List.of(resultItem));
        body.put("structureID", List.of());
        body.put("globalStructureID", List.of());
        body.put("resultParamType", 0);
        body.put("resultParamJsonType", resultJsonType);
        if (hasRequestBody) {
            body.put("apiRequestParamType", 2);// 请求体类型 JSON
            body.put("apiRequestParamJsonType", 0);// JSON 子类型：对象
        }
        body.put("apiName", apiName(method));
        body.put("apiURI", ControllerClassScanUtils.buildControllerUrl(controllerClass, method, serverPath, mvcPath));
        body.put("apiProtocol", 0);
        body.put("apiStatus", 0);
        body.put("apiTag", List.of());
        Map<String, Object> customInfo = new LinkedHashMap<>();
        customInfo.put("messageEncoding", "utf-8");
        customInfo.put("apiRequestCustomField", "formData");
        customInfo.put("apiResponseCustomField", "fs20Json");
        body.put("customInfo", customInfo);
        Map<String, Object> apiAuth = new LinkedHashMap<>();
        apiAuth.put("status", "0");
        body.put("apiAuth", apiAuth);
        body.put("beforeInject", "");
        body.put("afterInject", "");
        body.put("fileID", "");
        String note = firstDocLine(method);
        body.put("apiNote", note != null ? note : "");
        body.put("apiNoteRaw", note != null ? note : "");
        body.put("apiNoteType", 0);
        body.put("apiRequestRaw", "");
        body.put("apiRequestBinary", "");
        body.put("afterScriptList", List.of());
        body.put("beforeScriptList", List.of());
        body.put("afterScriptMode", 2);
        body.put("beforeScriptMode", 2);
        body.put("apiSuccessMock", "");
        body.put("apiFailureMock", "");
        body.put("apiFailureStatusCode", 200);
        body.put("apiSuccessStatusCode", 200);
        body.put("apiFailureContentType", "text/html; charset=UTF-8");
        body.put("apiSuccessContentType", "text/html; charset=UTF-8");
        body.put("updateDesc", "");
        body.put("noticeType", 1);
        return body;
    }

    // ------------------------------------------------------------- 参数树

    /** 类字段 → 参数项列表（递归，含 childList） */
    @NotNull
    private static List<Map<String, Object>> classFields(@NotNull PsiClass psiClass, @NotNull PsiSubstitutor substitutor,
                                                         int depth, @NotNull Set<String> visited,
                                                         @NotNull List<String> debug) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (depth > MAX_DEPTH) {
            return items;
        }
        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            PsiType type = substitutor.substitute(field.getType());
            String note = fieldComment(field);
            if (debug.size() < 100) {
                debug.add(debugField(field, note));
            }
            Map<String, Object> item = paramItem(field.getName(), type, OPTIONAL, note, visited, debug);
            items.add(item);
        }
        // 父类字段
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            items.addAll(0, classFields(superClass, PsiSubstitutor.EMPTY, depth + 1, visited, debug));
        }
        return items;
    }

    /** 字段调试信息：名称、提取到的说明、来源文件、末尾子元素快照 */
    @NotNull
    private static String debugField(@NotNull PsiField field, @Nullable String note) {
        StringBuilder sb = new StringBuilder(field.getName()).append(" note=").append(note);
        if (field.getContainingFile() != null) {
            sb.append(" file=").append(field.getContainingFile().getName())
                    .append(field instanceof PsiCompiledElement ? "(编译类)" : "(源码)");
        }
        PsiElement child = field.getLastChild();
        for (int i = 0; i < 3 && child != null; i++) {
            String text = child.getText().replace("\n", "\\n").replace("\r", "\\r");
            sb.append(" | ").append(child.getClass().getSimpleName()).append(':')
                    .append(text, 0, Math.min(30, text.length()));
            child = child.getPrevSibling();
        }
        return sb.toString();
    }

    /** 单个参数项（含嵌套 childList），字段与网页端参数对象结构对齐 */
    @NotNull
    private static Map<String, Object> paramItem(@NotNull String name, @Nullable PsiType type, @NotNull String notNull,
                                                 @Nullable String note, @NotNull Set<String> visited,
                                                 @NotNull List<String> debug) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("paramKey", name);
        item.put("paramType", paramType(type));
        item.put("paramNotNull", notNull);
        item.put("paramName", note != null ? note : "");// 说明
        item.put("paramMock", "");
        item.put("paramNote", "");
        item.put("paramLimit", "");
        item.put("paramValue", "");
        item.put("minLength", "");
        item.put("maxLength", "");
        item.put("minValue", "");
        item.put("maxValue", "");
        item.put("paramValueDictList", List.of());
        item.put("paramValueList", List.of());
        item.put("default", 0);

        // 嵌套子参数
        PsiType elementType = collectionElementType(type);
        if (elementType != null) {
            PsiClass childClass = resolveClass(elementType);
            if (childClass != null && !isSimpleClass(childClass) && visited.add(fqn(childClass))) {
                List<Map<String, Object>> children = classFields(childClass, substitutorOf(elementType), 1, visited, debug);
                visited.remove(fqn(childClass));
                if (!children.isEmpty()) {
                    item.put("childList", children);
                }
            }
        } else {
            PsiClass childClass = resolveClass(type);
            if (childClass != null && !isSimpleClass(childClass) && visited.add(fqn(childClass))) {
                List<Map<String, Object>> children = classFields(childClass, substitutorOf(type), 1, visited, debug);
                visited.remove(fqn(childClass));
                if (!children.isEmpty()) {
                    item.put("childList", children);
                }
            }
        }
        return item;
    }

    // ------------------------------------------------------------- 类型映射

    @NotNull
    private static String paramType(@Nullable PsiType type) {
        if (type == null) {
            return T_STRING;
        }
        if (type instanceof PsiPrimitiveType) {
            String name = type.getPresentableText();
            if ("boolean".equals(name)) return T_BOOLEAN;
            if ("float".equals(name) || "double".equals(name)) return T_NUMBER;
            if ("void".equals(name)) return T_STRING;
            return T_INT;
        }
        String simple = simpleName(type);
        if (isString(simple)) return T_STRING;
        if (isInt(simple)) return T_INT;
        if (isFloat(simple)) return T_NUMBER;
        if (isBoolean(simple)) return T_BOOLEAN;
        if (isDate(simple) || isDateTime(simple)) return T_STRING;// 日期时间按字符串
        if ("MultipartFile".equals(simple)) return T_STRING;
        if (collectionElementType(type) != null || type instanceof PsiArrayType) return T_ARRAY;
        PsiClass psiClass = resolveClass(type);
        if (psiClass != null && !isSimpleClass(psiClass)) return T_OBJECT;
        return T_STRING;
    }

    @Nullable
    private static PsiType collectionElementType(@Nullable PsiType type) {
        if (!(type instanceof PsiClassType)) {
            return null;
        }
        PsiClass psiClass = ((PsiClassType) type).resolve();
        if (psiClass == null) {
            return null;
        }
        String qn = psiClass.getQualifiedName();
        if (qn == null || !(qn.equals("java.util.Collection") || qn.equals("java.util.List")
                || qn.equals("java.util.Set") || qn.equals("java.util.ArrayList")
                || qn.equals("java.util.LinkedList") || qn.equals("java.util.HashSet")
                || qn.equals("java.util.LinkedHashSet") || qn.equals("java.util.TreeSet")
                || InheritanceUtil.isInheritor(psiClass, "java.util.Collection"))) {
            return null;
        }
        PsiType[] params = ((PsiClassType) type).getParameters();
        return params.length > 0 ? params[0] : null;
    }

    @Nullable
    private static PsiClass resolveClass(@Nullable PsiType type) {
        if (type instanceof PsiClassType) {
            return ((PsiClassType) type).resolve();
        }
        return null;
    }

    @NotNull
    private static PsiSubstitutor substitutorOf(@Nullable PsiType type) {
        if (type instanceof PsiClassType) {
            JavaResolveResult result = ((PsiClassType) type).resolveGenerics();
            if (result.getSubstitutor() != null) {
                return result.getSubstitutor();
            }
        }
        return PsiSubstitutor.EMPTY;
    }

    private static boolean isSimpleClass(@NotNull PsiClass psiClass) {
        String qn = psiClass.getQualifiedName();
        if (qn == null) {
            return true;// 无法解析的类按简单类型处理
        }
        return qn.startsWith("java.lang.") || qn.startsWith("java.math.")
                || qn.startsWith("java.time.") || qn.equals("java.util.Date");
    }

    private static boolean isMap(@NotNull PsiClass psiClass) {
        String qn = psiClass.getQualifiedName();
        return qn != null && (qn.equals("java.util.Map") || InheritanceUtil.isInheritor(psiClass, "java.util.Map"));
    }

    private static String simpleName(@NotNull PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            return type.getPresentableText();
        }
        if (type instanceof PsiArrayType) {
            return "Array";
        }
        String text = type.getPresentableText();
        int lt = text.indexOf('<');
        String raw = lt > 0 ? text.substring(0, lt) : text;
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private static String fqn(@NotNull PsiClass psiClass) {
        return psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();
    }

    private static boolean isString(String s) {
        return "String".equals(s) || "Character".equals(s) || "char".equals(s);
    }

    private static boolean isInt(String s) {
        return "int".equals(s) || "Integer".equals(s) || "long".equals(s) || "Long".equals(s)
                || "short".equals(s) || "Short".equals(s) || "byte".equals(s) || "Byte".equals(s)
                || "BigInteger".equals(s);
    }

    private static boolean isFloat(String s) {
        return "float".equals(s) || "Float".equals(s) || "double".equals(s) || "Double".equals(s)
                || "BigDecimal".equals(s);
    }

    private static boolean isBoolean(String s) {
        return "boolean".equals(s) || "Boolean".equals(s);
    }

    private static boolean isDate(String s) {
        return "Date".equals(s) || "LocalDate".equals(s);
    }

    private static boolean isDateTime(String s) {
        return "LocalDateTime".equals(s) || "LocalTime".equals(s) || "Timestamp".equals(s)
                || "ZonedDateTime".equals(s) || "OffsetDateTime".equals(s);
    }

    // ------------------------------------------------------------- 注解/注释

    private static boolean hasAnnotation(@NotNull PsiParameter parameter, @NotNull String shortName) {
        for (PsiAnnotation annotation : parameter.getModifierList().getAnnotations()) {
            PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
            if (ref != null && shortName.equals(ref.getReferenceName())) {
                return true;
            }
        }
        return false;
    }

    /** 请求方式：post=0 get=1 put=2 delete=3 head=4 options=5 patch=6；RequestMapping 取 method 属性，默认 post */
    private static int httpMethod(@NotNull PsiMethod method) {
        PsiAnnotation annotation = AnnotationParserUtils.findRestfulAnnotation(method);
        if (annotation == null) {
            return 0;
        }
        PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
        String shortName = ref != null ? ref.getReferenceName() : "RequestMapping";
        switch (shortName) {
            case "GetMapping": return 1;
            case "PostMapping": return 0;
            case "PutMapping": return 2;
            case "DeleteMapping": return 3;
            case "PatchMapping": return 6;
            default: {
                // @RequestMapping：读 method 属性
                for (com.intellij.psi.PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if ("method".equals(pair.getAttributeName()) && pair.getValue() != null) {
                        String text = pair.getValue().getText().toUpperCase();
                        if (text.contains("GET")) return 1;
                        if (text.contains("PUT")) return 2;
                        if (text.contains("DELETE")) return 3;
                        if (text.contains("PATCH")) return 6;
                    }
                }
                return 0;
            }
        }
    }

    /** 接口名称：优先 javadoc 首行，否则方法名 */
    @NotNull
    private static String apiName(@NotNull PsiMethod method) {
        String doc = firstDocLine(method);
        return doc != null ? doc : method.getName();
    }

    @Nullable
    private static String firstDocLine(@NotNull PsiElement element) {
        PsiElement child = element.getFirstChild();
        while (child != null) {
            if (child instanceof PsiDocComment) {
                String text = ((PsiDocComment) child).getText();
                for (String line : text.split("\n")) {
                    String trimmed = line.trim()
                            .replaceFirst("^/\\*+", "").replaceFirst("\\*+/$", "")
                            .replaceFirst("^\\*", "").trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("@")) {
                        return trimmed;
                    }
                }
                return null;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    /** 字段说明：javadoc 首行 → Swagger 注解 → 行尾 // 注释 → 上一行 // 注释 */
    @Nullable
    private static String fieldComment(@NotNull PsiField field) {
        String doc = firstDocLine(field);
        if (doc != null) {
            return doc;
        }
        String swagger = swaggerDescription(field);
        if (swagger != null) {
            return swagger;
        }
        // 行尾注释可能被解析进字段元素内部（作为字段的最后一个子元素）
        for (PsiElement child = field.getLastChild(); child != null; child = child.getPrevSibling()) {
            if (child instanceof PsiWhiteSpace && !child.getText().contains("\n")) {
                continue;
            }
            if (child instanceof PsiComment) {
                String comment = lineCommentText((PsiComment) child);
                if (comment != null) {
                    return comment;
                }
            }
            break;
        }
        // 行尾注释：字段后同行第一个 // 注释（空白跨行则属于下一个字段，不取）
        PsiElement sibling = field.getNextSibling();
        while (sibling instanceof PsiWhiteSpace) {
            if (sibling.getText().contains("\n")) {
                sibling = null;
                break;
            }
            sibling = sibling.getNextSibling();
        }
        if (sibling instanceof PsiComment) {
            String comment = lineCommentText((PsiComment) sibling);
            if (comment != null) {
                return comment;
            }
        }
        // 上一行注释：字段前紧邻一行的 // 注释（与字段之间只隔一个换行）
        PsiElement prev = field.getPrevSibling();
        if (prev instanceof PsiWhiteSpace) {
            String ws = prev.getText();
            int firstNewline = ws.indexOf('\n');
            if (firstNewline >= 0 && ws.indexOf('\n', firstNewline + 1) < 0) {
                PsiElement before = prev.getPrevSibling();
                if (before instanceof PsiComment) {
                    return lineCommentText((PsiComment) before);
                }
            }
        }
        return null;
    }

    @Nullable
    private static String lineCommentText(@NotNull PsiComment comment) {
        String text = comment.getText();
        if (!text.startsWith("//")) {
            return null;
        }
        String value = text.substring(2).trim();
        return value.isEmpty() ? null : value;
    }

    /** Swagger 注解描述：@Schema(description) / @ApiModelProperty(value) / @ApiParam(value) */
    @Nullable
    private static String swaggerDescription(@NotNull PsiModifierListOwner owner) {
        PsiModifierList modifierList = owner.getModifierList();
        if (modifierList == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
            String shortName = ref != null ? ref.getReferenceName() : null;
            if (!"Schema".equals(shortName) && !"ApiModelProperty".equals(shortName) && !"ApiParam".equals(shortName)) {
                continue;
            }
            String desc = annotationAttr(annotation, "description");
            if (desc == null) {
                desc = annotationAttr(annotation, "value");
            }
            if (desc != null && !desc.isBlank()) {
                return desc;
            }
        }
        return null;
    }

    @Nullable
    private static String annotationAttr(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(name);
        if (value instanceof PsiLiteralExpression) {
            Object v = ((PsiLiteralExpression) value).getValue();
            return v instanceof String ? (String) v : null;
        }
        return null;
    }

    /** 方法 javadoc 中 @param 标签的参数说明：参数名 → 描述 */
    @NotNull
    private static Map<String, String> paramDocs(@NotNull PsiMethod method) {
        Map<String, String> docs = new HashMap<>();
        PsiDocComment doc = method.getDocComment();
        if (doc == null) {
            return docs;
        }
        for (PsiDocTag tag : doc.findTagsByName("param")) {
            PsiElement[] elements = tag.getDataElements();
            if (elements.length < 2) {
                continue;
            }
            StringBuilder desc = new StringBuilder();
            for (int i = 1; i < elements.length; i++) {
                desc.append(elements[i].getText());
            }
            String text = desc.toString().trim();
            if (!text.isEmpty()) {
                docs.put(elements[0].getText(), text);
            }
        }
        return docs;
    }
}
