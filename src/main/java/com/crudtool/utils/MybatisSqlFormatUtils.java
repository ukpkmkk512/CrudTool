package com.crudtool.utils;

import com.github.vertical_blank.sqlformatter.SqlFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis mapper XML 中 SQL 片段的格式化工具。
 *
 * 格式化流程：
 *   1. 将 #{...} / ${...} 占位符替换为安全 token（SQL 解析器不认识它们，直接格式化会报错）
 *   2. 解码 XML 实体（&amp;gt; 等），使 SQL 关键字可被正确识别
 *   3. 调用 vertical-blank sql-formatter 格式化（关键字大写、子句换行、缩进）
 *   4. 还原占位符，并按 XML 缩进规则重新缩进各行
 *
 * 注意：格式化器不理解 MyBatis 动态标签，调用方需先把文本节点与子标签分离，
 * 只对纯文本 SQL 段调用本工具。
 */
public class MybatisSqlFormatUtils {

    /** MyBatis 占位符：#{xxx} 或 ${xxx}（含 jdbcType、mode 等附加属性） */
    private static final Pattern PLACEHOLDER = Pattern.compile("[#$]\\{[^}]*}");

    /** 占位符 token 使用不会与 SQL 冲突的字符 */
    private static final char TOKEN_PREFIX = '@';

    /** 视为 SQL 起始关键字的单词（用于判断文本段是否值得格式化） */
    private static final String[] SQL_START_KEYWORDS = {
            "select", "insert", "update", "delete", "with", "from", "where",
            "set", "values", "and", "or", "on", "join", "order", "group",
            "having", "limit", "offset", "union", "into"
    };

    private MybatisSqlFormatUtils() {
    }

    /**
     * 格式化一段 MyBatis SQL 文本片段。
     *
     * @param sqlFragment 语句标签内的纯文本 SQL 段（不含动态子标签）
     * @param indentUnit  XML 单级缩进字符串（通常为 4 空格）
     * @param baseLevel   该文本段所在标签的嵌套层级（决定整体缩进基准）
     * @return 格式化后的多行文本（行首含缩进，不含行尾换行）；若无实质内容则原样返回
     */
    public static String format(String sqlFragment, String indentUnit, int baseLevel) {
        if (sqlFragment == null) {
            return sqlFragment;
        }
        String trimmed = sqlFragment.trim();
        if (trimmed.isEmpty()) {
            return sqlFragment;
        }

        // 1. 占位符 → token
        List<String> placeholders = new ArrayList<>();
        String masked = maskPlaceholders(trimmed, placeholders);

        // 2. 解码 XML 实体
        masked = decodeXmlEntities(masked);

        // 3. 格式化
        String formatted;
        try {
            formatted = SqlFormatter.format(masked);
        } catch (RuntimeException e) {
            // 解析失败时保守返回原文，避免破坏用户代码
            return sqlFragment;
        }
        if (formatted == null || formatted.isBlank()) {
            return sqlFragment;
        }

        // 4. 还原占位符
        formatted = restorePlaceholders(formatted, placeholders);

        // 5. 按 XML 层级重新缩进
        return reindent(formatted, indentUnit, baseLevel);
    }

    /**
     * 判断文本段是否包含 SQL 内容（首个单词是 SQL 关键字），
     * 用于跳过纯空白或无意义文本，避免不必要的改动。
     */
    public static boolean looksLikeSql(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        // 提取首个单词（占位符开头的段也视为 SQL，如 "#{ids} = ..."）
        Matcher m = Pattern.compile("[#$]\\{|[A-Za-z]+").matcher(t);
        if (!m.find()) {
            return false;
        }
        String first = m.group();
        if (first.startsWith("#") || first.startsWith("$")) {
            return true;
        }
        String lower = first.toLowerCase();
        for (String kw : SQL_START_KEYWORDS) {
            if (kw.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 #{...}/${...} 替换为 @0、@1... 形式的 token，原值收集到 placeholders
     */
    private static String maskPlaceholders(String sql, List<String> placeholders) {
        Matcher m = PLACEHOLDER.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            placeholders.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(TOKEN_PREFIX + String.valueOf(placeholders.size() - 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 将 token 还原为原始占位符
     */
    private static String restorePlaceholders(String formatted, List<String> placeholders) {
        String result = formatted;
        for (int i = placeholders.size() - 1; i >= 0; i--) {
            // 从大到小替换，避免 @1 误匹配 @10 的前缀
            result = result.replace(TOKEN_PREFIX + String.valueOf(i), placeholders.get(i));
        }
        return result;
    }

    /**
     * 解码 mapper XML 文本中常见的 XML 实体
     */
    private static String decodeXmlEntities(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    /**
     * 将格式化结果按 XML 层级缩进：首行不加缩进（由调用方拼接），后续行统一缩进
     */
    private static String reindent(String formatted, String indentUnit, int baseLevel) {
        String baseIndent = indentUnit.repeat(Math.max(baseLevel, 0));
        String[] lines = formatted.split("\\R");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String content = line.stripTrailing();
            if (content.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append('\n').append(baseIndent);
            }
            sb.append(content.stripLeading());
            first = false;
        }
        return sb.toString();
    }
}
