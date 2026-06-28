package net.neoforged.moddevgradle.mcpforge.internal;

import javax.inject.Inject;
import net.neoforged.moddevgradle.legacyforge.internal.MinecraftMappings;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;

/**
 * This disambiguation rule will prefer NAMED over SRG when both are present.
 */
public class MappingsDisambiguationRule implements AttributeDisambiguationRule<MinecraftMappings> {
    private final MinecraftMappings named;

    @Inject
    public MappingsDisambiguationRule(MinecraftMappings named) {
        this.named = named;
    }

    @Override
    public void execute(MultipleCandidatesDetails<MinecraftMappings> details) {
        var consumerValue = details.getConsumerValue();
        if (consumerValue == null) {
            if (details.getCandidateValues().contains(named)) {
                details.closestMatch(named);
            }
        }
    }
}
