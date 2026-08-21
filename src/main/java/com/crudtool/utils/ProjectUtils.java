package com.crudtool.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;

/**
 * @Description: 工程相关工具（仅保留业务文件判定，用于过滤三方源码）
 */
public class ProjectUtils {

    private ProjectUtils() {
    }

    /**
     * 判断元素是否属于业务源码（排除三方包、非 java/xml 文件）
     * 用于过滤所有 Provider 监听，避免对依赖 jar 中的类生效
     * 注意：.xml 用于支持 MyBatis Mapper XML 跳转
     */
    public static boolean isBizElement(PsiElement element) {
        if (element == null || element.getContainingFile() == null) {
            return false;
        }
        VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
        if (virtualFile == null) {
            return false;
        }
        String name = virtualFile.getName();
        if (!name.endsWith(".java") && !name.endsWith(".xml")) {
            return false;
        }
        Project project = element.getProject();
        ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);
        if (projectFileIndex.isInLibrary(virtualFile)) {
            return false;
        }
        return projectFileIndex.isInSourceContent(virtualFile);
    }
}
