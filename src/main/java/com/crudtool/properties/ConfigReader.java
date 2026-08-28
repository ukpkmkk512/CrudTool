package com.crudtool.properties;


import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @Description: 项目初始化阶段配置文件读取与解析（按 resources 目录缓存解析结果，
 * 配置文件修改后通过 modificationStamp 自动失效）
 */
public class ConfigReader {

    private static final String PROPERTIES_FILE_NAME = "application.properties";
    private static final String PROPERTIES_BOOTSTRAP_FILE_NAME = "bootstrap.properties";
    private static final String YML_FILE_NAME = "application.yml";
    private static final String YAML_FILE_NAME = "application.yaml";
    //支持 nacos 场景，server.servlet.context-path / spring.mvc.servlet.path 仅存在于 bootstrap.yml
    private static final String YML_BOOTSTRAP_FILE_NAME = "bootstrap.yml";
    private static final String YAML_BOOTSTRAP_FILE_NAME = "bootstrap.yaml";

    private static final String[] CONFIG_FILE_NAMES = {
            PROPERTIES_FILE_NAME, PROPERTIES_BOOTSTRAP_FILE_NAME,
            YML_FILE_NAME, YAML_FILE_NAME, YML_BOOTSTRAP_FILE_NAME, YAML_BOOTSTRAP_FILE_NAME
    };

    /** resources 目录路径 → 缓存的解析结果 */
    private static final ConcurrentHashMap<String, CachedConfigs> CACHE = new ConcurrentHashMap<>();

    private static final class CachedConfigs {
        final long stamp;
        final Properties properties;
        final Map<String, Object> yml;

        CachedConfigs(long stamp, Properties properties, Map<String, Object> yml) {
            this.stamp = stamp;
            this.properties = properties;
            this.yml = yml;
        }
    }

    /**
     * 读取 properties（application + bootstrap 合并，后者覆盖前者）
     */
    public static Properties readProperties(PsiDirectory moduleDirectory) {
        CachedConfigs configs = getConfigs(moduleDirectory);
        return configs != null ? configs.properties : new Properties();
    }

    /**
     * 读取 yml/yaml（application + bootstrap 合并，后者覆盖前者）
     */
    public static Map<String, Object> readYmlOrYaml(PsiDirectory moduleDirectory) {
        CachedConfigs configs = getConfigs(moduleDirectory);
        return configs != null ? configs.yml : new HashMap<>();
    }

    /**
     * 获取（或计算并缓存）该 resources 目录下的配置解析结果。
     * stamp 由所有命中的配置文件的 modificationStamp 合成，文件被修改/新增/删除后自动失效。
     */
    private static CachedConfigs getConfigs(PsiDirectory moduleDirectory) {
        if (moduleDirectory == null || moduleDirectory.getVirtualFile() == null) {
            return null;
        }
        String key = moduleDirectory.getVirtualFile().getPath();

        // 先定位 6 个配置文件并计算 stamp
        Map<String, VirtualFile> found = new HashMap<>();
        long stamp = 0;
        for (String fileName : CONFIG_FILE_NAMES) {
            VirtualFile[] files = findFilesByName(moduleDirectory, fileName);
            if (files.length > 0) {
                VirtualFile vf = files[0];
                found.put(fileName, vf);
                stamp = stamp * 31 + vf.getModificationStamp() + fileName.hashCode();
            }
        }

        CachedConfigs cached = CACHE.get(key);
        if (cached != null && cached.stamp == stamp) {
            return cached;
        }

        Properties merged = new Properties();
        for (String name : new String[]{PROPERTIES_FILE_NAME, PROPERTIES_BOOTSTRAP_FILE_NAME}) {
            VirtualFile vf = found.get(name);
            if (vf != null) {
                Properties p = loadProperties(vf);
                if (p != null && !p.isEmpty()) {
                    merged.putAll(p);
                }
            }
        }

        Map<String, Object> ymlMerged = new HashMap<>();
        for (String name : new String[]{YAML_FILE_NAME, YML_FILE_NAME, YAML_BOOTSTRAP_FILE_NAME, YML_BOOTSTRAP_FILE_NAME}) {
            VirtualFile vf = found.get(name);
            if (vf != null) {
                Map<String, Object> data = loadYml(vf);
                if (data != null && !data.isEmpty()) {
                    ymlMerged.putAll(data);
                }
            }
        }

        CachedConfigs result = new CachedConfigs(stamp, merged, ymlMerged);
        CACHE.put(key, result);
        return result;
    }

    private static Properties loadProperties(VirtualFile file) {
        Properties properties = new Properties();
        try (InputStream inputStream = file.getInputStream()) {
            properties.load(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return properties;
    }

    private static Map<String, Object> loadYml(VirtualFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return new Yaml().load(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static VirtualFile[] findFilesByName(PsiDirectory directory, String fileName) {
        if (directory == null || directory.getVirtualFile() == null) {
            return new VirtualFile[0];
        }
        return findFilesByNameRecursively(directory.getVirtualFile(), fileName);
    }

    private static VirtualFile[] findFilesByNameRecursively(VirtualFile directory, String fileName) {
        if (!directory.isDirectory()) {
            return new VirtualFile[0];
        }
        java.util.List<VirtualFile> found = new java.util.ArrayList<>();
        for (VirtualFile child : directory.getChildren()) {
            if (child.isDirectory()) {
                VirtualFile[] sub = findFilesByNameRecursively(child, fileName);
                if (sub.length > 0) {
                    found.addAll(java.util.Arrays.asList(sub));
                }
            } else if (fileName.equals(child.getName())) {
                found.add(child);
            }
        }
        return found.toArray(new VirtualFile[0]);
    }
}
