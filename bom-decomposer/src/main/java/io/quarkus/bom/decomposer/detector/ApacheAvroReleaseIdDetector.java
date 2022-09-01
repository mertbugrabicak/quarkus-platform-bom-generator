package io.quarkus.bom.decomposer.detector;

import io.quarkus.bom.decomposer.BomDecomposerException;
import io.quarkus.bom.decomposer.ReleaseId;
import io.quarkus.bom.decomposer.ReleaseIdDetector;
import io.quarkus.bom.decomposer.ReleaseIdFactory;
import io.quarkus.bom.decomposer.ReleaseIdResolver;
import io.quarkus.bom.decomposer.ReleaseVersion;
import org.eclipse.aether.artifact.Artifact;

public class ApacheAvroReleaseIdDetector implements ReleaseIdDetector {

    @Override
    public ReleaseId detectReleaseId(ReleaseIdResolver releaseResolver, Artifact artifact)
            throws BomDecomposerException {
        if (artifact.getGroupId().equals("org.apache.avro")) {
            ReleaseId releaseId = releaseResolver.defaultReleaseId(artifact);
            String version = releaseId.version().asString();
            if (version.startsWith("release-")) {
                return releaseId;
            }
            return ReleaseIdFactory.create(releaseId.origin(), ReleaseVersion.Factory.tag("release-" + version));
        }
        return null;
    }

}
