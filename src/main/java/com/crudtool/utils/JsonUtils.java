package com.crudtool.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析/序列化工具（无第三方依赖）。
 * 解析结果类型：Map&lt;String,Object&gt; / List&lt;Object&gt; / String / Double / Long / Boolean / null
 */
public class JsonUtils {

    private JsonUtils() {
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObj();
            }
            if (c == '[') {
                return parseArr();
            }
            if (c == '"') {
                return parseStr();
            }
            if (c == 't' || c == 'f') {
                boolean t = c == 't';
                i += t ? 4 : 5;
                return t;
            }
            if (c == 'n') {
                i += 4;
                return null;
            }
            return parseNum();
        }

        Map<String, Object> parseObj() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (i < s.length()) {
                skipWs();
                String key = parseStr();
                skipWs();
                if (peek() == ':') {
                    i++;
                }
                map.put(key, parseValue());
                skipWs();
                char c = peek();
                i++;
                if (c == '}') {
                    break;
                }
            }
            return map;
        }

        List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (i < s.length()) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                i++;
                if (c == ']') {
                    break;
                }
            }
            return list;
        }

        String parseStr() {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNum() {
            int start = i;
            boolean isFloat = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    isFloat = true;
                }
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                    break;
                }
                i++;
            }
            String num = s.substring(start, i);
            try {
                if (isFloat) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException e2) {
                    return num;
                }
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }

    // ------------------------------------------------------------ stringify

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            writeString(String.valueOf(value), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
