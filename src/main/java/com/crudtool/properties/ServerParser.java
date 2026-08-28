package com.crudtool.properties;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Description:
 */
public class ServerParser {

    /** 模块根路径 → resources 目录相对路径（空串表示未找到），避免重复遍历目录树 */
    private static final ConcurrentHashMap<String, String> RESOURCES_DIR_CACHE = new ConcurrentHashMap<>();

    /** 递归搜索时跳过的构建/缓存目录 */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "target", "build", "out", "bin", ".idea", ".gradle", ".mvn", "node_modules");

    public static Optional<PsiDirectory> getServiceModuleResourcesDirectory(PsiClass psiClass, Project project) {
        // 获取模块的根目录
        PsiDirectory moduleRootDirectory = getModuleRootDirectory(psiClass, project);
        if (moduleRootDirectory == null) {
            return Optional.empty();
        }

        // 在模块根目录下查找 resources 目录（带缓存）
        return findResourcesWithCache(moduleRootDirectory, project);
    }

    private static Optional<PsiDirectory> findResourcesWithCache(PsiDirectory moduleRootDirectory, Project project) {
        VirtualFile rootVf = moduleRootDirectory.getVirtualFile();
        String rootPath = rootVf.getPath();
        String cached = RESOURCES_DIR_CACHE.get(rootPath);
        if (cached != null) {
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            VirtualFile vf = rootVf.findFileByRelativePath(cached);
            if (vf != null && vf.isValid() && vf.isDirectory()) {
                return Optional.ofNullable(PsiManager.getInstance(project).findDirectory(vf));
            }
            RESOURCES_DIR_CACHE.remove(rootPath);
        }
        PsiDirectory resources = findResourcesDirectory(moduleRootDirectory);
        if (resources == null) {
            RESOURCES_DIR_CACHE.put(rootPath, "");
            return Optional.empty();
        }
        String resPath = resources.getVirtualFile().getPath();
        String rel = resPath.startsWith(rootPath + "/")
                ? resPath.substring(rootPath.length() + 1) : resources.getVirtualFile().getName();
        RESOURCES_DIR_CACHE.put(rootPath, rel);
        return Optional.of(resources);
    }

    private static PsiDirectory getModuleRootDirectory(PsiClass feignClientClass, Project project) {
        PsiDirectory currentDirectory = feignClientClass.getContainingFile().getContainingDirectory();

        // 向上查找，直到找到包含 src 目录的模块根目录，或达到项目根目录
        while (currentDirectory != null) {
            VirtualFile parentDir = currentDirectory.getVirtualFile().getParent();
            if (parentDir == null || parentDir.equals(project.getBaseDir())) {
                break; // 已经达到项目根目录，停止查找
            }

            VirtualFile srcFolder = parentDir.findChild("src");
            if (srcFolder != null && srcFolder.isDirectory()) {
                return PsiManager.getInstance(project).findDirectory(parentDir);
            }

            currentDirectory = currentDirectory.getParent();
        }
        return null;
    }

    private static PsiDirectory findResourcesDirectory(PsiDirectory directory) {
        if (directory == null) {
            return null;
        }
        // 标准 Maven/Gradle 布局直接命中，避免遍历整棵模块目录树（含 target/build 等巨大目录）
        VirtualFile standard = directory.getVirtualFile().findFileByRelativePath("src/main/resources");
        if (standard != null && standard.isDirectory()) {
            return PsiManager.getInstance(directory.getProject()).findDirectory(standard);
        }
        return findResourcesDirectoryRecursively(directory);
    }

    private static PsiDirectory findResourcesDirectoryRecursively(PsiDirectory directory) {
        if (directory == null) {
            return null;
        }

        VirtualFile[] children = directory.getVirtualFile().getChildren();

        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                if (SKIP_DIRS.contains(child.getName())) {
                    continue;
                }
                // 如果找到 resources 目录，返回对应的 PsiDirectory
                if ("resources".equals(child.getName())) {
                    return PsiManager.getInstance(directory.getProject()).findDirectory(child);
                }
                // 查找子目录，在递归调用前进行 null 检查
                PsiDirectory childDirectory = PsiManager.getInstance(directory.getProject()).findDirectory(child);
                if (childDirectory != null) {
                    PsiDirectory result = findResourcesDirectoryRecursively(childDirectory);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }


}
