package io.github.guqing.plugin;

import io.github.guqing.plugin.docker.AbstractDockerRemoteApiTask;
import io.github.guqing.plugin.docker.DockerClientConfiguration;
import io.github.guqing.plugin.docker.DockerClientService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.Set;

import static io.github.guqing.plugin.HaloPluginExtension.DEFAULT_BOOT_JAR;

/**
 * @author guqing
 * @since 2.0.0
 */
@Slf4j
public class PluginDevelopment implements Plugin<Project> {
    public static final String HALO_SERVER_DEPENDENCY_CONFIGURATION_NAME = "haloServer";
    public static final String GROUP = "halo server";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        log.info("Halo plugin development gradle plugin run...");

        HaloPluginExtension haloPluginExt = project.getExtensions()
                .create(HaloPluginExtension.EXTENSION_NAME, HaloPluginExtension.class, project);
        // populate plugin manifest info
        File manifestFile = getPluginManifest(project);
        haloPluginExt.setManifestFile(manifestFile);

        PluginManifest pluginManifest = YamlUtils.read(manifestFile, PluginManifest.class);
        haloPluginExt.setRequire(pluginManifest.getSpec().getRequire());
        haloPluginExt.setHaloBootJar(project.getDependencies()
                .create(String.format(DEFAULT_BOOT_JAR, haloPluginExt.getRequire())));

        if (StringUtils.isBlank(pluginManifest.getMetadata().getName())) {
            throw new IllegalStateException("Plugin name must not be blank.");
        }

        haloPluginExt.setVersion((String) project.getVersion());
        System.setProperty("halo.plugin.name", pluginManifest.getMetadata().getName());

        project.getTasks()
                .register(PluginComponentsIndexTask.TASK_NAME, PluginComponentsIndexTask.class, it -> {
                    it.setGroup(GROUP);
                    FileCollection files =
                            project.getExtensions().getByType(SourceSetContainer.class).getByName("main")
                                    .getOutput().getClassesDirs();
                    it.classesDirs.from(files);
                });
        project.getTasks().getByName("assemble").dependsOn(PluginComponentsIndexTask.TASK_NAME);

        project.getTasks()
                .register(PluginAutoVersionTask.TASK_NAME, PluginAutoVersionTask.class, it -> {
                    it.setDescription("Auto populate plugin version to manifest file.");
                    it.setGroup(GROUP);
                    it.manifest.set(manifestFile);
                });
        project.getTasks().getByName("assemble").dependsOn(PluginAutoVersionTask.TASK_NAME);

        project.getTasks()
                .register(InstallDefaultThemeTask.TASK_NAME, InstallDefaultThemeTask.class, it -> {
                    it.setDescription("Install default theme for halo server locally.");
                    it.themeUrl.set(haloPluginExt.getThemeUrl());
                    it.setGroup(GROUP);
                });

        project.getTasks().register(InstallHaloTask.TASK_NAME, InstallHaloTask.class, it -> {
            it.setDescription("Install Halo server executable jar locally.");
            it.setGroup(GROUP);
            Configuration configuration =
                    project.getConfigurations().create(HALO_SERVER_DEPENDENCY_CONFIGURATION_NAME);
            it.configurationProperty.set(configuration);
            it.serverBootJar.set(haloPluginExt.getHaloBootJar());
            it.serverRepository.set(haloPluginExt.getServerRepository());
        });

        project.getTasks().register(HaloServerTask.TASK_NAME, HaloServerTask.class, it -> {
            it.setDescription("Run Halo server locally with the plugin being developed");
            it.setGroup(GROUP);
            it.pluginEnvProperty.set(haloPluginExt);
            it.haloHome.set(haloPluginExt.getWorkDir());
            it.manifest.set(haloPluginExt.getManifestFile());
            it.dependsOn(InstallHaloTask.TASK_NAME);
            it.dependsOn(InstallDefaultThemeTask.TASK_NAME);
        });

        DockerClientConfiguration dockerExtension = project.getExtensions()
                .create("dockerExtension", DockerClientConfiguration.class);
        dockerExtension.setUrl("unix:///var/run/docker.sock");

        final Provider<DockerClientService> serviceProvider = project.getGradle()
                .getSharedServices().registerIfAbsent("docker",
                        DockerClientService.class, pBuildServiceSpec -> pBuildServiceSpec.parameters(parameters -> {
                            parameters.getUrl().set(dockerExtension.getUrl());
                            parameters.getCertPath().set(dockerExtension.getCertPath());
                            parameters.getApiVersion().set(dockerExtension.getApiVersion());
                        }));

        project.getTasks().withType(AbstractDockerRemoteApiTask.class)
                .configureEach(task -> task.getDockerClientService().set(serviceProvider));

        project.getTasks().create("runHalo", DockerRunTask.class, it -> {
            it.setGroup(GROUP);
            it.setDescription("Run Halo server in docker container.");
            it.platform.set("linux/amd64");
            it.image.set("halohub/halo:2.0.0");
        });
    }

    private File getPluginManifest(Project project) {
        SourceSetContainer sourceSetContainer =
                (SourceSetContainer) project.getProperties().get("sourceSets");
        File mainResourceDir = sourceSetContainer.stream()
                .filter(set -> "main".equals(set.getName()))
                .map(SourceSet::getResources)
                .map(SourceDirectorySet::getSrcDirs)
                .flatMap(Set::stream)
                .findFirst()
                .orElseThrow();

        for (String filename : HaloPluginExtension.MANIFEST) {
            File manifestFile = new File(mainResourceDir, filename);
            if (manifestFile.exists()) {
                return manifestFile;
            }
        }
        throw new IllegalStateException(
                "The plugin manifest file [plugin.yaml] not found in " + mainResourceDir);
    }
}
