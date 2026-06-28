package net.neoforged.moddevgradle.mcpforge.internal;

import net.neoforged.moddevgradle.internal.utils.VersionCapabilitiesInternal;

public final class LegacyForgeArtifacts {
    private LegacyForgeArtifacts() {}

    static String userdevClassifier(VersionCapabilitiesInternal versionCapabilities) {
        if ("1.12.2".equals(versionCapabilities.minecraftVersion())) {
            return "userdev3";
        }
        return "userdev";
    }

    static String userdevNotation(String groupId, String version, VersionCapabilitiesInternal versionCapabilities) {
        return groupId + ":forge:" + version + ":" + userdevClassifier(versionCapabilities);
    }

    static String userdevJarName(String moduleName, String version, VersionCapabilitiesInternal versionCapabilities) {
        return moduleName + "-" + version + "-" + userdevClassifier(versionCapabilities) + ".jar";
    }
}
