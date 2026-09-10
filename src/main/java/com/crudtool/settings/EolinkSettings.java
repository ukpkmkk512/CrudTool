package com.crudtool.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Eolink 上传配置（按项目隔离，持久化到各项目的 .idea/workspace.xml）。
 * 认证方式：Eolink 账号密码登录内部 API 获取 JWT（与网页端一致）；
 * 密码不入 workspace.xml，存 IDE PasswordSafe。
 */
@State(name = "CrudToolEolinkSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public class EolinkSettings implements PersistentStateComponent<EolinkSettings.State> {

    private static final String PASSWORD_KEY = "CrudTool.Eolink.Password";

    private State state = new State();

    public static EolinkSettings getInstance(@NotNull Project project) {
        return project.getService(EolinkSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public boolean isConfigured() {
        return !state.baseUrl.isBlank() && !state.account.isBlank()
                && !getPassword().isEmpty() && !state.projectHashKey.isBlank();
    }

    @NotNull
    public String getPassword() {
        Credentials credentials = PasswordSafe.getInstance().get(attributes());
        String password = credentials != null ? credentials.getPasswordAsString() : null;
        return password != null ? password : "";
    }

    public void setPassword(@Nullable String password) {
        PasswordSafe.getInstance().set(attributes(),
                new Credentials(state.account, password != null ? password : ""));
    }

    @NotNull
    private CredentialAttributes attributes() {
        return new CredentialAttributes(PASSWORD_KEY, state.account);
    }

    /** 持久化状态 */
    public static class State {
        public String baseUrl = "https://apis.eolink.com";// 内部 API 网关地址
        public String account = "";// Eolink 登录账号（邮箱/手机号）
        public String projectHashKey = "";// 项目标识（项目 URL 中的 hash 串）
        public String groupName = "CrudTool";// 上传到的接口分组，支持 a/b 层级路径，不存在时逐级创建
    }
}
