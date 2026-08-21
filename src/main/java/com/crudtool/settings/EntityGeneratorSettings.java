package com.crudtool.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 实体类生成设置（按项目隔离，持久化到各项目的 .idea/workspace.xml）
 */
@State(name = "CrudToolEntityGeneratorSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public class EntityGeneratorSettings implements PersistentStateComponent<EntityGeneratorSettings.State> {

    private State state = new State();

    public static EntityGeneratorSettings getInstance(@NotNull Project project) {
        return project.getService(EntityGeneratorSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public boolean isData() {
        return state.data;
    }

    public void setData(boolean data) {
        state.data = data;
    }

    public boolean isAllArgsConstructor() {
        return state.allArgsConstructor;
    }

    public void setAllArgsConstructor(boolean allArgsConstructor) {
        state.allArgsConstructor = allArgsConstructor;
    }

    public boolean isNoArgsConstructor() {
        return state.noArgsConstructor;
    }

    public void setNoArgsConstructor(boolean noArgsConstructor) {
        state.noArgsConstructor = noArgsConstructor;
    }

    /** 持久化状态 */
    public static class State {
        public boolean data = true;// @Data
        public boolean allArgsConstructor = false;// @AllArgsConstructor
        public boolean noArgsConstructor = false;// @NoArgsConstructor
    }
}
