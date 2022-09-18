package io.github.guqing.plugin;

import groovyjarjarasm.asm.Opcodes;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;

@Slf4j
public class PluginComponentsIndexTask extends DefaultTask {
    private static final String CLASS_SUFFIX = ".class";
    private static final String FILEPATH = "META-INF/plugin-components.idx";

    public static final String TASK_NAME = "generatePluginComponentsIdx";

    @InputFiles
    ConfigurableFileCollection classesDirs = getProject().getObjects().fileCollection();

    @TaskAction
    public void generate() throws IOException {
        log.info("Generating plugin components index file...");

        String buildPath = classesDirs.getAsPath();
        Set<String> componentsIdxFileLines = new LinkedHashSet<>();
        componentsIdxFileLines.add("# Generated by Halo");
        for (File file : classesDirs.getAsFileTree()) {
            if (!file.getName().endsWith(CLASS_SUFFIX)) {
                continue;
            }
            String path = file.getPath();
            String codeReferenceName = toCodeReferenceName(buildPath, path);

            ClassReader classReader = new ClassReader(new FileInputStream(file));
            FilterComponentClassVisitor filterComponentClassVisitor =
                new FilterComponentClassVisitor(Opcodes.ASM9);
            classReader.accept(filterComponentClassVisitor, Opcodes.ASM9);
            if (filterComponentClassVisitor.isComponentClass()) {
                componentsIdxFileLines.add(codeReferenceName);
            }
        }
        // write to file
        Path componentsIdxPath = Paths.get(buildPath).resolve(FILEPATH);
        if (!Files.exists(componentsIdxPath.getParent())) {
            Files.createDirectories(componentsIdxPath.getParent());
        }
        if (!Files.exists(componentsIdxPath)) {
            Files.createFile(componentsIdxPath);
        }
        Files.write(componentsIdxPath, componentsIdxFileLines, StandardCharsets.UTF_8);
    }

    private String toCodeReferenceName(String buildOutputDir, String classFileName) {
        Assert.notNull(buildOutputDir, "The buildOutputDir must not be null");
        Assert.notNull(classFileName, "The classFileName must not be null");
        String relativePath = StringUtils.removeStart(classFileName, buildOutputDir);

        String referencePath = relativePath.replace(File.separator, ".");
        StringBuilder sb = new StringBuilder(referencePath);
        for (int i = 0; i < sb.length(); i++) {
            if (i == 0 && sb.charAt(i) == '.') {
                sb.deleteCharAt(i);
                continue;
            }
            if (sb.charAt(i) == '$') {
                sb.setCharAt(i, '.');
            }
        }
        int suffixIndex = sb.lastIndexOf(CLASS_SUFFIX);
        if (suffixIndex != -1) {
            sb.delete(suffixIndex, suffixIndex + CLASS_SUFFIX.length());
        }
        return sb.toString();
    }

    public ConfigurableFileCollection getClassesDirs() {
        return classesDirs;
    }
}
