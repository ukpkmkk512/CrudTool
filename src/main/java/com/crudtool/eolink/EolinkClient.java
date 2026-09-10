package com.crudtool.eolink;

import com.crudtool.settings.EolinkSettings;
import com.crudtool.utils.JsonUtils;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Eolink 内部 API 客户端（协议与网页端一致，参照 apidocx 开源实现）：
 * - POST /userCenter/common/sso/login 账号密码登录，响应 data.jwt 作为后续请求的 Authorization 头
 * - GET /api/common/User/getUserInfo 获取当前空间 spaceKey
 * - 其余接口走 application/x-www-form-urlencoded，定位参数为 spaceKey + projectHashKey
 * - 响应 statusCode 属于 {"000000","400500"} 视为成功；"200001" 表示需重新登录（自动重登重试一次）
 */
public class EolinkClient {

    private static final int TIMEOUT_MS = 30000;

    private final String baseUrl;
    private final String account;
    private final String password;
    private final String projectHashKey;

    private String jwt;// 登录令牌，懒加载
    private String spaceKey;// 空间标识，登录后由 getUserInfo 返回

    public EolinkClient(@NotNull Project project) {
        EolinkSettings settings = EolinkSettings.getInstance(project);
        EolinkSettings.State state = settings.getState();
        this.baseUrl = normalizeBaseUrl(state.baseUrl);
        this.account = state.account;
        this.password = settings.getPassword();
        this.projectHashKey = state.projectHashKey;
    }

    // 工作空间前端域名(*.w.eolink.com)不提供 API，统一归一到内部网关
    private static String normalizeBaseUrl(String s) {
        if (s == null || s.isBlank()) {
            return "https://apis.eolink.com";
        }
        s = s.replaceAll("/+$", "");
        if (s.matches("https?://[^/]+\\.[we]\\.eolink\\.com")) {
            s = s.replaceFirst("https?://[^/]+", "https://apis.eolink.com");
        }
        return s;
    }

    // ------------------------------------------------------------------ 分组

    /** 获取项目分组列表（扁平结构，含 parentGroupID/groupDepth），返回 [{groupID, parentGroupID, groupName, groupDepth}] */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getGroupList() throws EolinkException {
        Map<String, Object> resp = postFormWithRetry("/api/apiManagementPro/ApiGroup/getApiGroupData",
                "spaceKey=" + enc(getSpaceKey())
                        + "&projectHashKey=" + enc(projectHashKey));
        Object list = resp.get("apiGroupData");
        List<Map<String, Object>> groups = list instanceof List ? (List<Map<String, Object>>) list : List.of();
        return new java.util.ArrayList<>(groups);
    }

    /** 创建分组（parentGroupId="0" 表示顶级），返回 groupID */
    public long createGroup(@NotNull String groupName, @NotNull String parentGroupId) throws EolinkException {
        Map<String, Object> resp = postFormWithRetry("/api/generalFunction/Group/addGroup",
                "spaceKey=" + enc(getSpaceKey())
                        + "&projectHashKey=" + enc(projectHashKey)
                        + "&groupName=" + enc(groupName)
                        + "&parentGroupID=" + enc(parentGroupId)
                        + "&module=2");
        Long groupID = toLong(resp.get("groupID"));
        if (groupID != null) {
            return groupID;
        }
        throw new EolinkException("创建分组未返回 groupID: " + JsonUtils.stringify(resp));
    }

    // ------------------------------------------------------------------ 接口

    /** 获取分组下接口列表，返回 [{apiID, apiName, apiURI, apiRequestType, ...}] */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getApiList(long groupId) throws EolinkException {
        Map<String, Object> resp = postFormWithRetry("/api/apiManagementPro/Api/getApiListByCondition",
                "spaceKey=" + enc(getSpaceKey())
                        + "&projectHashKey=" + enc(projectHashKey)
                        + "&groupID=" + groupId
                        + "&page=1&pageSize=1000");
        Object list = resp.get("apiList");
        return list instanceof List ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * 保存接口（apiId 为 null 走 addApi，否则 editApi），返回 apiID。
     * fields 中简单类型转字符串，Map/List 序列化为 JSON 字符串。
     */
    public long saveApi(@NotNull Map<String, Object> fields, @Nullable Long apiId) throws EolinkException {
        StringBuilder form = new StringBuilder();
        form.append("spaceKey=").append(enc(getSpaceKey()))
                .append("&projectHashKey=").append(enc(projectHashKey));
        if (apiId != null) {
            form.append("&apiID=").append(apiId);
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            String s = (v instanceof Map || v instanceof List) ? JsonUtils.stringify(v) : String.valueOf(v);
            form.append('&').append(e.getKey()).append('=').append(enc(s));
        }
        Map<String, Object> resp = postFormWithRetry(
                apiId == null ? "/api/apiManagementPro/Api/addApi" : "/api/apiManagementPro/Api/editApi",
                form.toString());
        Long apiID = toLong(resp.get("apiID"));
        if (apiID != null) {
            return apiID;
        }
        throw new EolinkException("保存接口未返回 apiID: " + JsonUtils.stringify(resp));
    }

    // ------------------------------------------------------------------ 认证

    /** 测试连接：登录并获取用户信息，返回空间 spaceKey */
    @NotNull
    public String test() throws EolinkException {
        this.jwt = null;
        this.spaceKey = null;
        return getSpaceKey();
    }

    @NotNull
    private synchronized String getSpaceKey() throws EolinkException {
        if (spaceKey != null) {
            return spaceKey;
        }
        Map<String, Object> resp = doRequest("GET", baseUrl + "/api/common/User/getUserInfo",
                null, "application/x-www-form-urlencoded", getJwt());
        checkStatus("/api/common/User/getUserInfo", resp);
        Object userInfo = resp.get("userInfo");
        if (userInfo instanceof Map) {
            Object key = ((Map<?, ?>) userInfo).get("spaceKey");
            if (key != null && !String.valueOf(key).isBlank()) {
                spaceKey = String.valueOf(key);
                return spaceKey;
            }
        }
        throw new EolinkException("获取空间标识失败: " + JsonUtils.stringify(resp));
    }

    @NotNull
    private synchronized String getJwt() throws EolinkException {
        if (jwt != null) {
            return jwt;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", account);
        body.put("password", password);
        body.put("client", 1);// 桌面端
        body.put("type", 1);// 账号密码登录
        body.put("appType", 0);
        Map<String, Object> resp = doRequest("POST", baseUrl + "/userCenter/common/sso/login",
                JsonUtils.stringify(body), "application/json", null);
        // 成功时 code 为数字 0 或字符串 "0"
        if (!"0".equals(String.valueOf(resp.get("code")))) {
            throw new EolinkException("Eolink 登录失败: " + abbrev(JsonUtils.stringify(resp)));
        }
        Object data = resp.get("data");
        if (data instanceof Map) {
            Object token = ((Map<?, ?>) data).get("jwt");
            if (token != null && !String.valueOf(token).isBlank()) {
                jwt = String.valueOf(token);
                return jwt;
            }
        }
        throw new EolinkException("Eolink 登录未返回 jwt: " + abbrev(JsonUtils.stringify(resp)));
    }

    /** 表单 POST；遇 200001（登录失效）自动重登并重试一次 */
    private Map<String, Object> postFormWithRetry(@NotNull String path, @NotNull String form) throws EolinkException {
        Map<String, Object> resp = postForm(path, form);
        if ("200001".equals(String.valueOf(resp.get("statusCode")))) {
            this.jwt = null;
            resp = postForm(path, form);
        }
        checkStatus(path, resp);
        return resp;
    }

    private Map<String, Object> postForm(@NotNull String path, @NotNull String form) throws EolinkException {
        return doRequest("POST", baseUrl + path, form, "application/x-www-form-urlencoded", getJwt());
    }

    private static void checkStatus(@NotNull String path, @NotNull Map<String, Object> resp) throws EolinkException {
        String statusCode = String.valueOf(resp.get("statusCode"));
        if (!"000000".equals(statusCode) && !"400500".equals(statusCode)) {
            Object errorMsg = resp.get("errorMsg");
            throw new EolinkException(path + " 返回错误 [" + statusCode + "]: "
                    + (errorMsg != null ? String.valueOf(errorMsg) : JsonUtils.stringify(resp)));
        }
    }

    // ------------------------------------------------------------------ http

    private Map<String, Object> doRequest(@NotNull String method, @NotNull String url, @Nullable String payload,
                                          @NotNull String contentType, @Nullable String authorization) throws EolinkException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Accept", "application/json");
            if (authorization != null) {
                conn.setRequestProperty("Authorization", authorization);
            }
            if (payload != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String respText = is != null ? readAll(is) : "";
            if (status >= 400) {
                throw new EolinkException("Eolink 请求失败 (HTTP " + status + "): " + abbrev(respText));
            }
            return JsonUtils.parseObject(respText);
        } catch (EolinkException e) {
            throw e;
        } catch (Exception e) {
            throw new EolinkException("Eolink 请求异常: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static String abbrev(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }

    /** Eolink 响应中的数字字段可能是字符串，统一转 Long */
    @Nullable
    private static Long toLong(@Nullable Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Eolink 调用异常 */
    public static class EolinkException extends Exception {
        public EolinkException(String message) {
            super(message);
        }

        public EolinkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
