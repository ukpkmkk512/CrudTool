package com.crudtool.properties;


import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


/**
 * @Description: 项目初始化阶段配置文件读取与解析
 */
public class ConfigReader {

    private static final String PROPERTIES_FILE_NAME = "application.properties";
    private static final String PROPERTIES_BOOTSTRAP_FILE_NAME = "bootstrap.properties";
    private static final String YML_FILE_NAME = "application.yml";
    private static final String YAML_FILE_NAME = "application.yaml";
    //支持 nacos 场景，server.servlet.context-path / spring.mvc.servlet.path 仅存在于 bootstrap.yml
    private static final String YML_BOOTSTRAP_FILE_NAME = "bootstrap.yml";
    private static final String YAML_BOOTSTRAP_FILE_NAME = "bootstrap.yaml";


    /**
     * 读取 properties（application + bootstrap 合并，后者覆盖前者）
     */
    public static Properties readProperties(PsiDirectory moduleDirectory) {
        Properties merged = new Properties();
        Properties p1 = readPropertiesFromFile(moduleDirectory, PROPERTIES_FILE_NAME);
        if (p1 != null && !p1.isEmpty()) {
            merged.putAll(p1);
        }
        Properties p2 = readPropertiesFromFile(moduleDirectory, PROPERTIES_BOOTSTRAP_FILE_NAME);
        if (p2 != null && !p2.isEmpty()) {
            merged.putAll(p2);
        }
        return merged;
    }

    /**
     * 读取 yml/yaml（application + bootstrap 合并，后者覆盖前者）
     */
    public static Map<String, Object> readYmlOrYaml(PsiDirectory moduleDirectory) {
        Map<String, Object> merged = new HashMap<>();
        mergeYml(merged, readYmlFromFile(moduleDirectory, YAML_FILE_NAME));
        mergeYml(merged, readYmlFromFile(moduleDirectory, YML_FILE_NAME));
        mergeYml(merged, readYmlFromFile(moduleDirectory, YAML_BOOTSTRAP_FILE_NAME));
        mergeYml(merged, readYmlFromFile(moduleDirectory, YML_BOOTSTRAP_FILE_NAME));
        return merged;
    }

    private static void mergeYml(Map<String, Object> merged, Map<String, Object> data) {
        if (data != null && !data.isEmpty()) {
            merged.putAll(data);
        }
    }

    private static Properties readPropertiesFromFile(PsiDirectory moduleDirectory, String fileName) {
        Properties properties = new Properties();
        VirtualFile[] files = findFilesByName(moduleDirectory, fileName);
        for (VirtualFile file : files) {
            try (InputStream inputStream = file.getInputStream()) {
                properties.load(inputStream);
                break; // 只加载第一个找到的文件
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return properties;
    }

    private static Map<String, Object> readYmlFromFile(PsiDirectory moduleDirectory, String fileName) {
        Yaml yaml = new Yaml();
        VirtualFile[] files = findFilesByName(moduleDirectory, fileName);
        for (VirtualFile file : files) {
            try (InputStream inputStream = file.getInputStream()) {
                return yaml.load(inputStream);
            } catch (Exception e) {
                e.printStackTrace();
            }
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
