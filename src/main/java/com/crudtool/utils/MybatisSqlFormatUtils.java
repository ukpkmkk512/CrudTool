package com.crudtool.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.sql.SqlFileType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis mapper XML 语句标签内 SQL 的格式化工具。
 *
 * 核心思路（用户建议）：
 *   1. 把 &lt;if&gt; 等 MyBatis 动态标签替换为 SQL 块注释占位符（剥离标签）
 *   2. 把 #{} / ${} 占位符替换为合法 SQL 标识符
 *   3. 得到一段完整、合法的 SQL，用 IDEA 内置 CodeStyleManager 格式化
 *   4. 还原标签和占位符
 *   5. 校验：剥离标签和空白后的字符序列前后必须一致，否则放弃格式化
 */
public class MybatisSqlFormatUtils {

    private static final Logger LOG = Logger.getInstance(MybatisSqlFormatUtils.class);

    /** MyBatis 参数占位符 #{} / ${} */
    private static final Pattern PLACEHOLDER = Pattern.compile("[#$]\\{[^}]*\\}");

    /** MyBatis 动态标签：<if ...>, </if>, <where>, <trim .../>, <choose> 等 */
    private static final Pattern MYBATIS_TAG =
            Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>]*?)?/?>");

    /** CDATA 区段 <![CDATA[ ... ]]> */
    private static final Pattern CDATA_SECTION =
            Pattern.compile("<!\\[CDATA\\[.*?]]>", Pattern.DOTALL);

    private static final String[] SQL_START_KEYWORDS = {
            "select", "insert", "update", "delete", "with"
    };

    private MybatisSqlFormatUtils() {
    }

    /**
     * 格式化一个 MyBatis 语句标签的完整 inner content。
     *
     * @param project       当前项目
     * @param rawContent    标签内原始文本（含动态标签、空白、换行）
     * @param baseIndent    SQL 基准缩进（空格数），通常是语句标签缩进 + 4
     * @return 格式化后的文本；非 SQL、格式化失败或校验不通过时返回 null
     */
    public static String formatStatement(Project project, String rawContent, int baseIndent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }

        // 1. 保留 CDATA 区段（里面可能有 < > & 等 XML 特殊字符，不参与 SQL 解析）
        List<String> cdataBlocks = new ArrayList<>();
        String working = preserveBlocks(rawContent, CDATA_SECTION, cdataBlocks, "MCDATA");

        // 2. 替换 #{} / ${} 为合法标识符
        List<String> params = new ArrayList<>();
        working = replacePattern(working, PLACEHOLDER, params, "MBP", "");

        // 3. 替换 MyBatis 动态标签为 SQL 块注释占位符
        //    用块注释是因为 SQL 格式化器会保留注释，不会把它当语法元素移动
        List<String> tags = new ArrayList<>();
        working = replacePattern(working, MYBATIS_TAG, tags, "/*MTAG", "*/");

        // 4. 检查是否确实包含 SQL（跳过前导块注释和空白）
        String trimmed = working.trim();
        while (trimmed.startsWith("/*")) {
            int end = trimmed.indexOf("*/");
            if (end < 0) {
                break;
            }
            trimmed = trimmed.substring(end + 2).trim();
        }
        if (!startsWithSqlKeyword(trimmed)) {
            return null;
        }

        // 5. 用 IDEA 内置 SQL 格式化引擎格式化完整 SQL（trim 去掉首尾空白，避免累积空行）
        String formatted;
        try {
            PsiFile sqlFile = PsiFileFactory.getInstance(project)
                    .createFileFromText("temp_mapper.sql", SqlFileType.INSTANCE, working.trim());
            CodeStyleManager.getInstance(project).reformat(sqlFile);
            formatted = sqlFile.getText();
        } catch (Exception e) {
            LOG.warn("SQL reformat failed", e);
            return null;
        }

        // 6. 校验：格式化前后，剥离标签/注释/占位符/空白后的字符序列必须一致
        if (!verifyIntegrity(rawContent, formatted, cdataBlocks.size(), params.size())) {
            LOG.info("SQL integrity check failed, skipping format. Original length="
                    + rawContent.length() + ", formatted length=" + formatted.length());
            return null;
        }

        // 7. 还原 #{} / ${}
        for (int i = 0; i < params.size(); i++) {
            formatted = formatted.replace("MBP" + i, params.get(i));
        }

        // 8. 还原 MyBatis 标签，并修正缩进
        formatted = restoreTags(formatted, tags, baseIndent);

        // 9. 还原 CDATA
        for (int i = 0; i < cdataBlocks.size(); i++) {
            formatted = formatted.replace("MCDATA" + i, cdataBlocks.get(i));
        }

        // 10. 统一按基准缩进重新缩进每一行
        formatted = reindent(formatted, baseIndent);

        return formatted;
    }

    /**
     * 用正则替换匹配项为占位符，记录原始内容
     */
    private static String replacePattern(String input, Pattern pattern,
                                         List<String> originals,
                                         String prefix, String suffix) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            originals.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + (originals.size() - 1) + suffix));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String preserveBlocks(String input, Pattern pattern,
                                         List<String> blocks, String prefix) {
        return replacePattern(input, pattern, blocks, prefix, "");
    }

    /**
     * 还原 MyBatis 标签。
     * 格式化后每个 /*MTAGn* / 通常独占一行，将其替换为原始标签（暂不加缩进，
     * 缩进由 {@link #reindent} 统一添加）。
     */
    private static String restoreTags(String formatted, List<String> tags, int baseIndent) {
        for (int i = 0; i < tags.size(); i++) {
            String marker = "/*MTAG" + i + "*/";
            // 尝试匹配独占一行的标记（含行首空白和换行）
            Pattern linePattern = Pattern.compile(
                    "(?m)^([ \\t]*)" + Pattern.quote(marker) + "[ \\t]*\\r?\\n?");
            Matcher m = linePattern.matcher(formatted);
            if (m.find()) {
                formatted = m.replaceFirst(Matcher.quoteReplacement(tags.get(i) + "\n"));
            } else {
                formatted = formatted.replace(marker, tags.get(i));
            }
        }
        return formatted;
    }

    /**
     * 按基准缩进重新缩进所有行。
     * 在每行现有缩进前追加 baseIndent，保留 SQL 格式化器产生的相对缩进。
     * 第一行前加换行，末尾去除尾部空行（由调用方拼接闭合标签缩进）。
     */
    private static String reindent(String text, int baseIndent) {
        String indent = " ".repeat(baseIndent);
        // 去除尾部空白行，避免每次格式化累积空行
        String stripped = text.replaceAll("[ \\t]*\\R+$", "");
        String[] lines = stripped.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 0) {
                sb.append('\n').append(indent);
            } else if (lines[i].isBlank()) {
                // 空行不加缩进
            } else {
                sb.append(indent);
            }
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 校验格式化前后内容完整性。
     *
     * 原始文本含 MyBatis 标签和 #{}，格式化后文本含 /*MTAGn* / 注释和 MBPn 标识符，
     * 两边的表现形式不同，需要分别规范化后再比较。
     */
    private static boolean verifyIntegrity(String original, String formatted,
                                           int cdataCount, int paramCount) {
        // 原始侧：去 CDATA 区段（含内容，已单独保留）、去 MyBatis 标签、
        //         #{} / ${} 统一为 ?、去空白、转小写
        String normOrig = original;
        normOrig = CDATA_SECTION.matcher(normOrig).replaceAll(" ");
        normOrig = MYBATIS_TAG.matcher(normOrig).replaceAll(" ");
        normOrig = PLACEHOLDER.matcher(normOrig).replaceAll("?");
        normOrig = normOrig.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        // 格式化侧：去 /*MTAGn*/ 注释、去 MCDATAn 占位符、
        //           MBPn 统一为 ?、去空白、转小写
        String normFmt = formatted;
        normFmt = normFmt.replaceAll("(?i)/\\*MTAG\\d+\\*/", " ");
        for (int i = 0; i < cdataCount; i++) {
            normFmt = normFmt.replace("MCDATA" + i, " ");
        }
        for (int i = 0; i < paramCount; i++) {
            normFmt = normFmt.replace("MBP" + i, "?");
        }
        normFmt = normFmt.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        return normOrig.equals(normFmt);
    }

    private static boolean startsWithSqlKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : SQL_START_KEYWORDS) {
            if (lower.startsWith(kw)) {
                return true;
            }
        }
        return false;
    }
}
