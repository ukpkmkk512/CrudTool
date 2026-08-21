package com.crudtool.constant;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

/**
 * @Description: 插件图标常量
 */
public interface RestIcons {
    Icon STATEMENT_LINE_CLIPBOARD_CONTROLLER_ICON = IconLoader.getIcon("/icons/clipBoard_controller.svg", RestIcons.class);

    // MyBatis Mapper 接口方法 与 XML 语句之间的双向跳转图标
    Icon STATEMENT_LINE_MAPPER_JUMP_ICON = IconLoader.getIcon("/icons/mapperJump.svg", RestIcons.class);
}
