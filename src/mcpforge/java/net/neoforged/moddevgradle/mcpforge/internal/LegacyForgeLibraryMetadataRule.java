package net.neoforged.moddevgradle.mcpforge.internal;

import java.util.ArrayList;
import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.DirectDependenciesMetadata;
import org.gradle.api.artifacts.DirectDependencyMetadata;

/**
 * Normalizes old Forge library metadata that predates today's Maven Central coordinates or only
 * exists as jar-only artifacts on Forge's Maven. Applies to all 1.12.2 Forge versions (2847+).
 */
@CacheableRule
public class LegacyForgeLibraryMetadataRule implements ComponentMetadataRule {
    @Override
    public void execute(ComponentMetadataContext context) {
        var id = context.getDetails().getId();
        if (!id.getVersion().startsWith("1.12.2-")) {
            return;
        }

        context.getDetails().allVariants(variant -> variant.withDependencies(dependencies -> {
            removeMatching(dependencies, dependency -> {
                var group = dependency.getGroup();
                var name = dependency.getName();
                return group.equals("org.scala-lang.plugins")
                        || group.equals("org.scala-lang") && name.equals("scala-actors-migration_2.11");
            });

            replaceDependency(dependencies, "org.scala-lang:scala-parser-combinators_2.11:1.0.1",
                    "org.scala-lang.modules:scala-parser-combinators_2.11:1.0.1");
            replaceDependency(dependencies, "org.scala-lang:scala-swing_2.11:1.0.1",
                    "org.scala-lang.modules:scala-swing_2.11:1.0.1");
            replaceDependency(dependencies, "org.scala-lang:scala-xml_2.11:1.0.2",
                    "org.scala-lang.modules:scala-xml_2.11:1.0.2");
        }));
    }

    private static void replaceDependency(DirectDependenciesMetadata dependencies,
            String oldNotation,
            String newNotation) {
        var oldParts = oldNotation.split(":");
        removeMatching(dependencies, dependency -> dependency.getGroup().equals(oldParts[0])
                && dependency.getName().equals(oldParts[1])
                && dependency.getVersionConstraint().getRequiredVersion().equals(oldParts[2]));
        dependencies.add(newNotation);
    }

    private static void removeMatching(DirectDependenciesMetadata dependencies,
            java.util.function.Predicate<DirectDependencyMetadata> predicate) {
        var toRemove = new ArrayList<DirectDependencyMetadata>();
        for (var dependency : dependencies) {
            if (predicate.test(dependency)) {
                toRemove.add(dependency);
            }
        }
        dependencies.removeAll(toRemove);
    }
}
