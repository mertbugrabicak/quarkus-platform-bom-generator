package io.quarkus.bom.decomposer.detector;

import io.quarkus.bom.decomposer.BomDecomposerException;
import io.quarkus.bom.decomposer.ReleaseId;
import io.quarkus.bom.decomposer.ReleaseIdDetector;
import io.quarkus.bom.decomposer.ReleaseIdFactory;
import io.quarkus.bom.decomposer.ReleaseIdResolver;
import org.eclipse.aether.artifact.Artifact;

public class WildFlyCommonReleaseIdDetector implements ReleaseIdDetector {

    @Override
    public ReleaseId detectReleaseId(ReleaseIdResolver releaseResolver, Artifact artifact)
            throws BomDecomposerException {
        if (!artifact.getGroupId().equals("org.wildfly.common")) {
            return null;
        }
        return ReleaseIdFactory.forScmAndTag("https://github.com/wildfly/wildfly-common", artifact.getVersion());
    }
}
