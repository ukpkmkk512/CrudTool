package com.crudtool.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: MyBatis Mapper 接口与 XML 之间的双向导航工具
 *
 * 匹配规则（与 MybatisX / Free-Mybatis-Tool 保持一致）：
 *   1. mapper XML 的 &lt;mapper namespace="FQN"&gt; 等于 Java 接口的全限定名
 *   2. &lt;select&gt;/&lt;insert&gt;/&lt;update&gt;/&lt;delete&gt; 的 id 属性 等于 Java 接口方法名
 *
 * Mapper 接口识别（三重策略）：
 *   - 显式标注 @org.apache.ibatis.annotations.Mapper 的接口
 *   - 由 @org.mybatis.spring.annotation.MapperScan 扫描包下的接口
 *   - 接口名以 "Mapper" 结尾（命名约定兜底）
 */
public class MyBatisMapperUtils {

    /** org.apache.ibatis.annotations.Mapper */
    public static final String MAPPER_ANNOTATION_QN = "org.apache.ibatis.annotations.Mapper";

    /** org.mybatis.spring.annotation.MapperScan */
    public static final String MAPPER_SCAN_ANNOTATION_QN = "org.mybatis.spring.annotation.MapperScan";

    /** MyBatis 语句标签集合 */
    private static final Set<String> STATEMENT_TAGS = new HashSet<>(Arrays.asList(
            "select", "insert", "update", "delete"
    ));

    /** MyBatis 方法注解（纯注解 SQL，无 XML 语句） */
    private static final Set<String> STATEMENT_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete",
            "org.apache.ibatis.annotations.SelectProvider",
            "org.apache.ibatis.annotations.InsertProvider",
            "org.apache.ibatis.annotations.UpdateProvider",
            "org.apache.ibatis.annotations.DeleteProvider"
    ));

    private MyBatisMapperUtils() {
    }

    /**
     * 判断是否是 MyBatis 语句标签（select/insert/update/delete）
     */
    public static boolean isStatementTag(@Nullable XmlTag tag) {
        return tag != null && STATEMENT_TAGS.contains(tag.getName());
    }

    /**
     * 取 XML 文件的 &lt;mapper&gt; 根标签；非 mapper 文件返回 null
     */
    @Nullable
    public static XmlTag getMapperRootTag(@NotNull XmlFile xmlFile) {
        XmlDocument document = xmlFile.getDocument();
        if (document == null) {
            return null;
        }
        XmlTag root = document.getRootTag();
        if (root == null || !"mapper".equals(root.getName())) {
            return null;
        }
        return root;
    }

    /**
     * 判断一个 PsiClass 是否是 MyBatis Mapper 接口（三重识别策略）
     */
    public static boolean isMapperInterface(@Nullable PsiClass psiClass, @NotNull Project project) {
        if (psiClass == null || !psiClass.isValid() || !psiClass.isInterface()) {
            return false;
        }
        // 1. 显式 @Mapper 注解（短名+FQN双重匹配，兼容依赖未解析情况）
        if (hasAnnotation(psiClass, MAPPER_ANNOTATION_QN)) {
            return true;
        }
        // 2. @MapperScan 扫描包匹配
        String qn = psiClass.getQualifiedName();
        if (qn != null) {
            String pkg = qn.lastIndexOf('.') >= 0 ? qn.substring(0, qn.lastIndexOf('.')) : "";
            for (String prefix : getMapperScanPackages(project)) {
                if (matchesPackage(pkg, prefix)) {
                    return true;
                }
            }
        }
        // 3. 命名约定兜底：接口名以 Mapper 结尾
        String name = psiClass.getName();
        return name != null && name.endsWith("Mapper");
    }

    /**
     * 检查 PsiClass 是否有指定注解（FQN 和短名双重匹配）
     */
    private static boolean hasAnnotation(@NotNull PsiClass psiClass, @NotNull String fqn) {
        if (psiClass.getAnnotation(fqn) != null) {
            return true;
        }
        // 短名匹配（依赖未解析时也能识别）
        String shortName = fqn.substring(fqn.lastIndexOf('.') + 1);
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String annShortName = annotation.getQualifiedName();
            if (annShortName != null && annShortName.endsWith("." + shortName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断方法是否使用了纯注解 SQL（@Select/@Update/@Insert/@Delete/@*Provider）
     */
    public static boolean hasStatementAnnotation(@Nullable PsiMethod method) {
        if (method == null) {
            return false;
        }
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String qn = annotation.getQualifiedName();
            if (qn == null) continue;
            // FQN 精确匹配
            if (STATEMENT_ANNOTATIONS.contains(qn)) {
                return true;
            }
            // 短名匹配（依赖未解析时兼容）
            for (String annFqn : STATEMENT_ANNOTATIONS) {
                String shortName = annFqn.substring(annFqn.lastIndexOf('.') + 1);
                if (qn.endsWith("." + shortName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断 pkg 是否落在扫描包 prefix 下（含本身与子包）
     */
    private static boolean matchesPackage(@NotNull String pkg, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    /**
     * 获取当前工程所有 @MapperScan 声明的扫描包集合（带 PSI 修改级缓存）
     */
    @NotNull
    private static Set<String> getMapperScanPackages(@NotNull Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
            Set<String> packages = computeMapperScanPackages(project);
            return CachedValueProvider.Result.create(
                    Collections.unmodifiableSet(packages),
                    project.getService(PsiModificationTracker.class)
            );
        });
    }

    /**
     * 扫描工程源码中所有 @MapperScan 注解，提取其 basePackages/value/basePackageClasses
     */
    @NotNull
    private static Set<String> computeMapperScanPackages(@NotNull Project project) {
        Set<String> packages = new HashSet<>();
        PsiClass mapperScanAnnotation = JavaPsiFacade.getInstance(project).findClass(
                MAPPER_SCAN_ANNOTATION_QN,
                GlobalSearchScope.allScope(project)
        );
        if (mapperScanAnnotation == null) {
            return packages;
        }
        Query<PsiClass> annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
                mapperScanAnnotation,
                GlobalSearchScope.projectScope(project)
        );
        for (PsiClass configClass : annotatedClasses) {
            PsiAnnotation annotation = configClass.getAnnotation(MAPPER_SCAN_ANNOTATION_QN);
            if (annotation == null) {
                continue;
            }
            extractBasePackages(annotation, packages);
        }
        return packages;
    }

    /**
     * 从单个 @MapperScan 注解中提取扫描包
     */
    private static void extractBasePackages(@NotNull PsiAnnotation annotation, @NotNull Set<String> packages) {
        collectStringValues(annotation, "value", packages);
        collectStringValues(annotation, "basePackages", packages);
        collectClassPackages(annotation, "basePackageClasses", packages);
    }

    private static void collectStringValues(@NotNull PsiAnnotation annotation, @NotNull String attrName,
                                            @NotNull Set<String> packages) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attrName);
        if (value == null) {
            return;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue v : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                String s = resolveStringLiteral(v);
                if (s != null) {
                    packages.add(s);
                }
            }
        } else {
            String s = resolveStringLiteral(value);
            if (s != null) {
                packages.add(s);
            }
        }
    }

    @Nullable
    private static String resolveStringLiteral(@Nullable PsiAnnotationMemberValue value) {
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

    private static void collectClassPackages(@NotNull PsiAnnotation annotation, @NotNull String attrName,
                                             @NotNull Set<String> packages) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attrName);
        if (value == null) {
            return;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue v : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                addClassPackage(v, packages);
            }
        } else {
            addClassPackage(value, packages);
        }
    }

    private static void addClassPackage(@Nullable PsiAnnotationMemberValue value, @NotNull Set<String> packages) {
        if (!(value instanceof PsiReferenceExpression)) {
            return;
        }
        PsiElement resolved = ((PsiReferenceExpression) value).resolve();
        if (!(resolved instanceof PsiClass)) {
            return;
        }
        String qn = ((PsiClass) resolved).getQualifiedName();
        if (qn == null) {
            return;
        }
        int idx = qn.lastIndexOf('.');
        if (idx > 0) {
            packages.add(qn.substring(0, idx));
        }
    }

    /**
     * 获取全工程 mapper XML 的 namespace 映射缓存（namespace FQN → XmlFile 列表）
     * 缓存随 PSI 变更自动失效
     */
    @NotNull
    private static Map<String, List<XmlFile>> getNamespaceCache(@NotNull Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
            Map<String, List<XmlFile>> cache = buildNamespaceCache(project);
            return CachedValueProvider.Result.create(cache,
                    project.getService(PsiModificationTracker.class));
        });
    }

    /**
     * 构建 namespace 缓存：遍历所有 XML 文件，收集 namespace 匹配的 mapper 文件
     */
    @NotNull
    private static Map<String, List<XmlFile>> buildNamespaceCache(@NotNull Project project) {
        Map<String, List<XmlFile>> cache = new HashMap<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        // 获取全工程所有 XML 文件（限制在项目源码范围）
        Collection<VirtualFile> xmlFiles = FilenameIndex.getAllFilesByExt(project, "xml",
                GlobalSearchScope.projectScope(project));
        for (VirtualFile vFile : xmlFiles) {
            PsiFile file = psiManager.findFile(vFile);
            if (!(file instanceof XmlFile)) continue;
            XmlFile xmlFile = (XmlFile) file;
            XmlTag root = getMapperRootTag(xmlFile);
            if (root == null) continue;
            String namespace = root.getAttributeValue("namespace");
            if (namespace == null || namespace.isEmpty()) continue;
            cache.computeIfAbsent(namespace, k -> new ArrayList<>()).add(xmlFile);
        }
        return cache;
    }

    /**
     * 给定一个 Mapper 接口，找到所有 namespace 匹配的 mapper XML 文件
     * 策略：优先使用 namespace 缓存（全量扫描结果），不依赖文件名匹配
     */
    @NotNull
    public static List<XmlFile> findMapperXmlFiles(@NotNull PsiClass mapperClass, @NotNull Project project) {
        String fqn = mapperClass.getQualifiedName();
        if (fqn == null) {
            return Collections.emptyList();
        }
        // 从缓存中直接获取 namespace 匹配的 XML 文件
        List<XmlFile> cached = getNamespaceCache(project).get(fqn);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 缓存未命中时，尝试按文件名查找兜底（兼容缓存未构建完成的情况）
        String simpleName = mapperClass.getName();
        if (simpleName == null) {
            return Collections.emptyList();
        }
        String expectedFileName = simpleName + ".xml";
        PsiFile[] files = FilenameIndex.getFilesByName(project, expectedFileName,
                GlobalSearchScope.projectScope(project));
        List<XmlFile> result = new ArrayList<>();
        for (PsiFile file : files) {
            if (!(file instanceof XmlFile)) continue;
            XmlFile xmlFile = (XmlFile) file;
            XmlTag root = getMapperRootTag(xmlFile);
            if (root == null) continue;
            String namespace = root.getAttributeValue("namespace");
            if (fqn.equals(namespace)) {
                result.add(xmlFile);
            }
        }
        return result;
    }

    /**
     * 在 mapper XML 中按 id 查找语句标签（递归查找所有子标签，支持嵌套在 &lt;resultMap&gt; 等之外的语句）
     */
    @Nullable
    public static XmlTag findStatementById(@NotNull XmlFile xmlFile, @NotNull String id) {
        XmlTag root = getMapperRootTag(xmlFile);
        if (root == null) {
            return null;
        }
        return findStatementInTag(root, id);
    }

    /**
     * 递归查找指定 id 的语句标签
     */
    @Nullable
    private static XmlTag findStatementInTag(@NotNull XmlTag tag, @NotNull String id) {
        for (XmlTag subTag : tag.getSubTags()) {
            if (isStatementTag(subTag)) {
                String tagId = subTag.getAttributeValue("id");
                if (id.equals(tagId)) {
                    return subTag;
                }
            }
            // 递归查找（虽然 MyBatis 语句标签通常是直接子标签，但以防万一有嵌套）
            XmlTag found = findStatementInTag(subTag, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 根据 XML 文件的 namespace 查找对应的 Mapper Java 接口
     */
    @Nullable
    public static PsiClass findMapperInterface(@NotNull XmlFile xmlFile, @NotNull Project project) {
        XmlTag root = getMapperRootTag(xmlFile);
        if (root == null) {
            return null;
        }
        String namespace = root.getAttributeValue("namespace");
        if (namespace == null || namespace.isEmpty()) {
            return null;
        }
        return JavaPsiFacade.getInstance(project).findClass(namespace,
                GlobalSearchScope.projectScope(project));
    }

    /**
     * 在 PsiClass 中按方法名查找方法（首个匹配，含父接口继承方法）
     */
    @Nullable
    public static PsiMethod findMethodByName(@NotNull PsiClass psiClass, @NotNull String methodName) {
        for (PsiMethod method : psiClass.getAllMethods()) {
            if (methodName.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    /**
     * 在 mapper XML 中按 id 递归查找 <resultMap> 标签
     */
    @Nullable
    public static XmlTag findResultMapById(@NotNull XmlFile xmlFile, @NotNull String id) {
        XmlTag root = getMapperRootTag(xmlFile);
        if (root == null) {
            return null;
        }
        return findTagInTree(root, "resultMap", id);
    }

    /**
     * 递归查找指定标签名且 id 属性匹配的子标签
     */
    @Nullable
    private static XmlTag findTagInTree(@NotNull XmlTag tag, @NotNull String tagName, @NotNull String id) {
        for (XmlTag subTag : tag.getSubTags()) {
            if (tagName.equals(subTag.getName()) && id.equals(subTag.getAttributeValue("id"))) {
                return subTag;
            }
            XmlTag found = findTagInTree(subTag, tagName, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 取语句标签的 id 属性值元素，用于作为 gutter 锚点
     */
    @Nullable
    public static com.intellij.psi.xml.XmlAttribute getIdAttribute(@NotNull XmlTag statementTag) {
        return statementTag.getAttribute("id");
    }
}
