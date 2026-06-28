package net.neoforged.moddevgradle.mcpforge.internal;

import java.util.List;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;

final class Lwjgl2Natives {
    static final List<String> APPLE_NATIVE_REPLACEMENT_DEPENDENCIES = List.of(
            "org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209",
            "org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209",
            "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209-mmachina.2:natives-osx@jar",
            "ca.weblite:java-objc-bridge:1.1.0-mmachina.1",
            "com.mojang:text2speech:1.11.3");

    private Lwjgl2Natives() {}

    static void configureRuntime(Configuration configuration, DependencyFactory dependencyFactory, String minecraftVersion) {
        configureRuntime(configuration, dependencyFactory, minecraftVersion, System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static void configureRuntime(Configuration configuration, DependencyFactory dependencyFactory, String minecraftVersion, String osName, String osArch) {
        if (!shouldUseAppleNativeReplacement(minecraftVersion, osName, osArch)) {
            return;
        }

        replaceMojangLwjgl2Dependencies(configuration);
        for (var notation : appleNativeReplacementDependencies()) {
            var dependency = dependencyFactory.create(notation);
            ((ExternalModuleDependency) dependency).setTransitive(false);
            configuration.getDependencies().add(dependency);
        }
    }

    static void configure(Configuration nativeLibraries, DependencyFactory dependencyFactory, String minecraftVersion) {
        configure(nativeLibraries, dependencyFactory, minecraftVersion, System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static void configure(Configuration nativeLibraries, DependencyFactory dependencyFactory, String minecraftVersion, String osName, String osArch) {
        if (shouldUseAppleNativeReplacement(minecraftVersion, osName, osArch)) {
            nativeLibraries.getDependencies().add(dependencyFactory.create("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209-mmachina.2:natives-osx@jar"));
        }
    }

    static List<String> appleNativeReplacementDependencies() {
        return APPLE_NATIVE_REPLACEMENT_DEPENDENCIES;
    }

    static boolean shouldUseAppleNativeReplacement(String minecraftVersion, String osName, String osArch) {
        return "1.12.2".equals(minecraftVersion)
                && osName.startsWith("Mac OS X")
                && ("aarch64".equals(osArch) || "arm64".equals(osArch));
    }

    private static void replaceMojangLwjgl2Dependencies(Configuration configuration) {
        configuration.getDependencies().configureEach(dependency -> {
            if (dependency instanceof ModuleDependency moduleDependency) {
                moduleDependency.exclude(java.util.Map.of("group", "org.lwjgl.lwjgl", "module", "lwjgl"));
                moduleDependency.exclude(java.util.Map.of("group", "org.lwjgl.lwjgl", "module", "lwjgl_util"));
                moduleDependency.exclude(java.util.Map.of("group", "org.lwjgl.lwjgl", "module", "lwjgl-platform"));
                moduleDependency.exclude(java.util.Map.of("group", "ca.weblite", "module", "java-objc-bridge"));
                moduleDependency.exclude(java.util.Map.of("group", "com.mojang", "module", "text2speech"));
            }
        });
    }
}
