package com.crudtool.action;

import com.crudtool.eolink.EolinkApiBuilder;
import com.crudtool.eolink.EolinkClient;
import com.crudtool.settings.EolinkSettings;
import com.crudtool.utils.AnnotationParserUtils;
import com.crudtool.utils.ControllerClassScanUtils;
import com.crudtool.utils.JsonUtils;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编辑器右键 → Upload to Eolink：
 * 将光标所在的 Controller 接口（URL、请求方式、请求参数、返回结果）上传到 Eolink 文档。
 * 连接配置位于 Settings → Tools → CrudTool（按项目隔离）。
 */
public class UploadToEolinkAction extends AnAction {

    private static final String NOTIFICATION_GROUP = "crud-tool";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        boolean visible = element != null && ReadAction.compute(() -> isControllerMethodElement(element));
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (project == null || element == null) {
            return;
        }
        EolinkSettings.State settings = EolinkSettings.getInstance(project).getState();
        if (!EolinkSettings.getInstance(project).isConfigured()) {
            notify(project, "请先在 Settings → Tools → CrudTool 中配置 Eolink 连接信息", NotificationType.WARNING);
            return;
        }
        // 读锁内收集 PSI 数据
        List<String> debugInfo = new ArrayList<>();
        Map<String, Object> apiBody = ReadAction.compute(() -> {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (method == null) {
                return null;
            }
            PsiClass controllerClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
            if (controllerClass == null || !AnnotationParserUtils.isControllerClass(controllerClass)
                    || AnnotationParserUtils.findRestfulAnnotation(method) == null) {
                return null;
            }
            String serverPath = ControllerClassScanUtils.extractSpringProperties(
                    controllerClass, project, "server.servlet.context-path");
            String mvcPath = ControllerClassScanUtils.extractSpringProperties(
                    controllerClass, project, "spring.mvc.servlet.path");
            // groupID 占位，后台线程解析后回填
            return EolinkApiBuilder.build(method, controllerClass, 0, serverPath, mvcPath, debugInfo);
        });
        if (apiBody == null) {
            return;
        }

        notify(project, "正在上传到 Eolink: " + apiBody.get("apiName"), NotificationType.INFORMATION);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                EolinkClient client = new EolinkClient(project);

                // 解析/创建分组（支持 "父/子" 层级路径，逐级匹配或创建）
                String groupPath = settings.groupName.isBlank() ? "CrudTool" : settings.groupName;
                List<String> createdGroups = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                List<Map<String, Object>> groups = client.getGroupList();
                Long groupId = resolveGroupId(client, groups, groupPath, createdGroups, warnings);
                apiBody.put("groupID", groupId);

                // 匹配已有接口：名称+路径+方式 → 路径+方式 → 名称，三级降级
                List<Map<String, Object>> apiList = client.getApiList(groupId);
                Long apiId = findExistingApiId(apiList, apiBody);

                long savedId = client.saveApi(apiBody, apiId);
                String dumpPath = dumpDebug(apiBody, groups, groupId, createdGroups, apiList, apiId,
                        debugInfo, warnings);
                notify(project, (apiId == null ? "已上传到 Eolink: " : "已更新到 Eolink: ")
                        + apiBody.get("apiName") + " [" + methodName(apiBody.get("apiRequestType")) + "]"
                        + " → " + groupPath + " (groupID=" + groupId
                        + (createdGroups.isEmpty() ? "" : ", 新建分组: " + String.join("/", createdGroups))
                        + ", apiID=" + savedId + ", 说明 " + noteStat(apiBody) + ")", NotificationType.INFORMATION);
                for (String warning : warnings) {
                    notify(project, warning, NotificationType.WARNING);
                }
                notify(project, "Eolink 调试信息已保存: " + dumpPath, NotificationType.INFORMATION);
            } catch (Exception ex) {
                notify(project, "上传 Eolink 失败: " + ex.getMessage(), NotificationType.ERROR);
            }
        });
    }

    /** 按 "父/子" 路径逐级解析分组，缺失的层级自动创建，返回末级分组 ID */
    private static Long resolveGroupId(@NotNull EolinkClient client, @NotNull List<Map<String, Object>> groups,
                                       @NotNull String groupPath, @NotNull List<String> createdGroups,
                                       @NotNull List<String> warnings)
            throws EolinkClient.EolinkException {
        // 顶级分组的 parentGroupID 不同版本可能是 0 或 -1，以已有顶级分组的实际值为准
        String topParentId = "0";
        for (Map<String, Object> group : groups) {
            if (isTopLevel(group)) {
                Object parent = group.get("parentGroupID");
                topParentId = parent != null ? String.valueOf(parent) : "0";
                break;
            }
        }
        String[] segments = groupPath.split("/");
        String parentId = topParentId;
        Long result = null;
        for (String segment : segments) {
            String name = segment.trim();
            if (name.isEmpty()) {
                continue;
            }
            Long found = null;
            int matchCount = 0;
            for (Map<String, Object> group : groups) {
                if (!name.equals(String.valueOf(group.get("groupName")))) {
                    continue;
                }
                String groupParent = String.valueOf(group.get("parentGroupID"));
                boolean parentMatch = result == null ? isTopLevel(group) : parentId.equals(groupParent);
                if (parentMatch) {
                    if (found == null) {
                        found = toLong(group.get("groupID"));
                    }
                    matchCount++;
                }
            }
            if (matchCount > 1) {
                warnings.add("Eolink 分组 '" + name + "' 有 " + matchCount
                        + " 个同名匹配，已使用第一个(groupID=" + found + ")，建议配置完整路径如 父分组/" + name);
            }
            if (found == null && result == null && segments.length == 1) {
                // 单段名称顶级未匹配：整棵树找唯一同名分组复用，多个则提示配置完整路径
                Long uniqueId = null;
                int total = 0;
                for (Map<String, Object> group : groups) {
                    if (name.equals(String.valueOf(group.get("groupName")))) {
                        uniqueId = toLong(group.get("groupID"));
                        total++;
                    }
                }
                if (total == 1) {
                    found = uniqueId;
                } else if (total > 1) {
                    warnings.add("Eolink 中存在 " + total + " 个同名分组 '" + name
                            + "'，已新建顶级分组；如需上传到已有分组请配置完整路径如 父分组/" + name);
                }
            }
            if (found == null) {
                found = client.createGroup(name, parentId);
                createdGroups.add(name);
                // 新分组加入本地列表，供后续层级匹配
                Map<String, Object> created = new java.util.HashMap<>();
                created.put("groupID", found);
                created.put("parentGroupID", parentId);
                created.put("groupName", name);
                groups.add(created);
            }
            result = found;
            parentId = String.valueOf(found);
        }
        return result;
    }

    /** 判断是否为顶级分组：parentGroupID 为 0/-1/空，或 groupDepth 为 1 */
    private static boolean isTopLevel(@NotNull Map<String, Object> group) {
        Object parent = group.get("parentGroupID");
        String parentStr = parent != null ? String.valueOf(parent) : "";
        if ("0".equals(parentStr) || "-1".equals(parentStr) || parentStr.isEmpty() || "null".equals(parentStr)) {
            return true;
        }
        Long depth = toLong(group.get("groupDepth"));
        return depth != null && depth == 1;
    }

    /** 请求方式 int → 展示用字符串 */
    @NotNull
    private static String methodName(@Nullable Object requestType) {
        String[] names = {"POST", "GET", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"};
        Long type = toLong(requestType);
        return type != null && type >= 0 && type < names.length ? names[type.intValue()] : "POST";
    }

    /** 统计参数说明覆盖率（有说明的参数数/总参数数），用于排查说明未上传问题 */
    @NotNull
    private static String noteStat(@NotNull Map<String, Object> apiBody) {
        int[] stat = {0, 0};
        countNotes(apiBody.get("apiUrlParam"), stat);
        countNotes(apiBody.get("apiRestfulParam"), stat);
        countNotes(apiBody.get("apiRequestParam"), stat);
        Object resultParam = apiBody.get("apiResultParam");
        if (resultParam instanceof List && !((List<?>) resultParam).isEmpty()) {
            Object first = ((List<?>) resultParam).get(0);
            if (first instanceof Map) {
                countNotes(((Map<?, ?>) first).get("paramList"), stat);
            }
        }
        return stat[0] + "/" + stat[1];
    }

    private static void countNotes(@Nullable Object params, int[] stat) {
        if (!(params instanceof List)) {
            return;
        }
        for (Object item : (List<?>) params) {
            if (!(item instanceof Map)) {
                continue;
            }
            stat[1]++;
            Object note = ((Map<?, ?>) item).get("paramName");
            if (note != null && !String.valueOf(note).isBlank()) {
                stat[0]++;
            }
            countNotes(((Map<?, ?>) item).get("childList"), stat);
        }
    }

    /** 上传过程调试信息写入临时文件，返回文件路径 */
    private static String dumpDebug(@NotNull Map<String, Object> apiBody, @NotNull List<Map<String, Object>> groups,
                                    @NotNull Long groupId, @NotNull List<String> createdGroups,
                                    @NotNull List<Map<String, Object>> apiList, @Nullable Long matchedApiId,
                                    @NotNull List<String> debugInfo, @NotNull List<String> warnings) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("== 分组列表(groupID/groupName/parentGroupID/groupDepth) ==\n")
                    .append(JsonUtils.stringify(compact(groups, "groupID", "groupName", "parentGroupID", "groupDepth")))
                    .append("\n\n== 分组解析结果 ==\ngroupID=").append(groupId)
                    .append(", 新建分组=").append(createdGroups)
                    .append("\n警告: ").append(warnings)
                    .append("\n\n== 分组内接口列表(apiID/apiName/apiURI/apiRequestType/groupID) ==\n")
                    .append(JsonUtils.stringify(compact(apiList, "apiID", "apiName", "apiURI", "apiRequestType", "groupID")))
                    .append("\n\n== 匹配到的已有接口 apiID ==\n").append(matchedApiId)
                    .append("\n\n== 字段注释解析调试 ==\n");
            for (String line : debugInfo) {
                sb.append(line).append('\n');
            }
            sb.append("\n== 发送的接口数据 ==\n").append(JsonUtils.stringify(apiBody)).append('\n');
            java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                    "crudtool-eolink-debug.txt");
            java.nio.file.Files.writeString(path, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            return path.toString();
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    @NotNull
    private static List<Map<String, Object>> compact(@NotNull List<Map<String, Object>> list, @NotNull String... keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : list) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (String key : keys) {
                if (item.containsKey(key)) {
                    row.put(key, item.get(key));
                }
            }
            out.add(row);
        }
        return out;
    }

    /** 在分组接口列表中匹配已有接口 ID，找不到返回 null */
    @Nullable
    private static Long findExistingApiId(@NotNull List<Map<String, Object>> apis, @NotNull Map<String, Object> apiBody) {
        String name = String.valueOf(apiBody.get("apiName"));
        String uri = String.valueOf(apiBody.get("apiURI"));
        String requestType = String.valueOf(apiBody.get("apiRequestType"));
        Map<String, Object> byAll = null;
        Map<String, Object> byUri = null;
        Map<String, Object> byName = null;
        for (Map<String, Object> api : apis) {
            boolean nameEq = name.equals(String.valueOf(api.get("apiName")));
            boolean uriEq = uri.equals(String.valueOf(api.get("apiURI")));
            boolean typeEq = requestType.equals(String.valueOf(api.get("apiRequestType")));
            if (byAll == null && nameEq && uriEq && typeEq) {
                byAll = api;
            }
            if (byUri == null && uriEq && typeEq) {
                byUri = api;
            }
            if (byName == null && nameEq) {
                byName = api;
            }
        }
        Map<String, Object> found = byAll != null ? byAll : (byUri != null ? byUri : byName);
        return found != null ? toLong(found.get("apiID")) : null;
    }

    /** Eolink 响应中的 ID 可能是字符串，统一转 Long */
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

    /** 元素位于带 Restful 注解的 Controller 方法内（调用方需持有读锁） */
    private boolean isControllerMethodElement(@NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null) {
            return false;
        }
        PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
        return psiClass != null && AnnotationParserUtils.isControllerClass(psiClass)
                && AnnotationParserUtils.findRestfulAnnotation(method) != null;
    }

    private void notify(@NotNull Project project, @NotNull String content, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(content, type)
                .notify(project);
    }
}
