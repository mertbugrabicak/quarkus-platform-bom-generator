package io.quarkus.bom.decomposer.detector;

import io.quarkus.bom.decomposer.BomDecomposerException;
import io.quarkus.bom.decomposer.ReleaseId;
import io.quarkus.bom.decomposer.ReleaseIdDetector;
import io.quarkus.bom.decomposer.ReleaseIdFactory;
import io.quarkus.bom.decomposer.ReleaseIdResolver;
import io.quarkus.bom.decomposer.ReleaseOrigin;
import org.eclipse.aether.artifact.Artifact;

public class JakartaCdiSpecReleaseIdDetector implements ReleaseIdDetector {

    @Override
    public ReleaseId detectReleaseId(ReleaseIdResolver releaseResolver, Artifact artifact)
            throws BomDecomposerException {
        if (artifact.getGroupId().equals("org.eclipse.ee4j.cdi")
                || artifact.getArtifactId().startsWith("jakarta.enterprise.cdi-")
                        && artifact.getGroupId().equals("jakarta.enterprise")) {
            ReleaseId releaseId = releaseResolver.defaultReleaseId(artifact);
            if (releaseId.origin().toString().startsWith("https://github.com/jakartaee/cdi")) {
                return releaseId;
            }
            return ReleaseIdFactory.create(ReleaseOrigin.Factory.scmConnection("https://github.com/jakartaee/cdi"),
                    releaseId.version());
        }
        return null;
    }

}
