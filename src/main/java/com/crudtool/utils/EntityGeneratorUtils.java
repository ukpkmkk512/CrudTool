package com.crudtool.utils;

import com.intellij.database.types.DasBuiltinTypeClass;
import com.intellij.database.types.DasType;
import com.intellij.database.types.DasTypeCategory;
import com.intellij.database.types.DasTypeClass;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据库表 → Java 实体类源码生成工具
 */
public class EntityGeneratorUtils {

    /** 列信息 */
    public static class ColumnInfo {
        public String fieldName;// 字段名
        public String javaType;// Java 类型
        public String comment;// 列注释
    }

    private EntityGeneratorUtils() {
    }

    /**
     * 表名 → 类名（下划线转大驼峰，如 bank_card → BankCard）
     */
    public static String toClassName(String tableName) {
        return toCamel(tableName, true);
    }

    /**
     * 列名 → 字段名（下划线转小驼峰，如 card_number → cardNumber）
     */
    public static String toFieldName(String columnName) {
        return toCamel(columnName, false);
    }

    private static String toCamel(String name, boolean capitalFirst) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = capitalFirst;
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        return sb.toString();
    }

    /**
     * 数据库类型 → Java 类型（按类型类别 + 类型名映射）
     */
    public static String toJavaType(DasType dasType) {
        if (dasType == null) {
            return "String";
        }
        DasTypeClass typeClass = dasType.getTypeClass();
        if (!(typeClass instanceof DasBuiltinTypeClass)) {
            return "String";
        }
        DasTypeCategory category = ((DasBuiltinTypeClass) typeClass).getCategory();
        String name = typeClass.getName() == null ? "" : typeClass.getName().toUpperCase(Locale.ROOT);
        if (category == DasTypeCategory.INTEGER) {
            if (name.contains("BIG")) {
                return "Long";
            }
            if (name.contains("SMALL")) {
                return "Short";
            }
            if (name.contains("TINY")) {
                return "Byte";
            }
            return "Integer";
        }
        if (category == DasTypeCategory.REAL) {
            if (name.contains("DECIMAL") || name.contains("NUMERIC") || name.contains("NUMBER")
                    || name.contains("MONEY")) {
                return "BigDecimal";
            }
            if (name.contains("DOUBLE")) {
                return "Double";
            }
            if (name.contains("FLOAT") || name.equals("REAL")) {
                return "Float";
            }
            return "Double";
        }
        if (category == DasTypeCategory.BOOLEAN) {
            return "Boolean";
        }
        if (category == DasTypeCategory.DATE) {
            return "LocalDate";
        }
        if (category == DasTypeCategory.TIME) {
            return "LocalTime";
        }
        if (category == DasTypeCategory.TIMESTAMP || category == DasTypeCategory.DATE_TIME) {
            return "LocalDateTime";
        }
        if (category == DasTypeCategory.BYTES) {
            return "byte[]";
        }
        return "String";
    }

    /**
     * 生成实体类源码
     *
     * @param className    类名
     * @param tableComment 表注释（作为类注释，可为空）
     * @param columns      列信息
     * @param data         是否加 @Data
     * @param allArgs      是否加 @AllArgsConstructor
     * @param noArgs       是否加 @NoArgsConstructor
     */
    public static String generateSource(String className, String tableComment, List<ColumnInfo> columns,
                                        boolean data, boolean allArgs, boolean noArgs) {
        StringBuilder sb = new StringBuilder();

        // import 区：按字段类型与勾选的注解收集
        Set<String> imports = new LinkedHashSet<>();
        for (ColumnInfo column : columns) {
            switch (column.javaType) {
                case "BigDecimal":
                    imports.add("java.math.BigDecimal");
                    break;
                case "LocalDate":
                    imports.add("java.time.LocalDate");
                    break;
                case "LocalTime":
                    imports.add("java.time.LocalTime");
                    break;
                case "LocalDateTime":
                    imports.add("java.time.LocalDateTime");
                    break;
                default:
                    break;
            }
        }
        if (data) {
            imports.add("lombok.Data");
        }
        if (allArgs) {
            imports.add("lombok.AllArgsConstructor");
        }
        if (noArgs) {
            imports.add("lombok.NoArgsConstructor");
        }
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        if (!imports.isEmpty()) {
            sb.append("\n");
        }

        // 类注释
        if (tableComment != null && !tableComment.isBlank()) {
            sb.append("/**\n * ").append(tableComment.trim()).append("\n */\n");
        }

        // 类注解
        if (data) {
            sb.append("@Data\n");
        }
        if (allArgs) {
            sb.append("@AllArgsConstructor\n");
        }
        if (noArgs) {
            sb.append("@NoArgsConstructor\n");
        }

        sb.append("public class ").append(className).append(" {\n");
        for (ColumnInfo column : columns) {
            sb.append("\n    private ").append(column.javaType).append(" ").append(column.fieldName);
            if (column.comment != null && !column.comment.isBlank()) {
                sb.append(";// ").append(column.comment.trim());
            }
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
