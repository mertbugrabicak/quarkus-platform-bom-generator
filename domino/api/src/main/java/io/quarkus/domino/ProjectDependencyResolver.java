package io.quarkus.domino;

import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.scm.GitScmLocator;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmLocator;
import com.redhat.hacbs.recipies.scm.TagInfo;
import io.quarkus.bom.decomposer.BomDecomposerException;
import io.quarkus.bom.decomposer.ReleaseId;
import io.quarkus.bom.decomposer.ReleaseIdDetector;
import io.quarkus.bom.decomposer.ReleaseIdFactory;
import io.quarkus.bom.decomposer.ReleaseIdResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

public class ProjectDependencyResolver {

    private static final String SCM_LOCATOR_STATS_PROP = "scm-locator-stats";

    private static boolean isScmLocatorStats() {
        if (!System.getProperties().containsKey(SCM_LOCATOR_STATS_PROP)) {
            return false;
        }
        var s = System.getProperty(SCM_LOCATOR_STATS_PROP);
        return s == null || Boolean.parseBoolean(s);
    }

    public static class Builder {

        private MavenArtifactResolver resolver;
        private Function<ArtifactCoords, List<Dependency>> artifactConstraintsProvider;
        private MessageWriter log;
        private ProjectDependencyConfig depConfig;
        private Path logOutputFile;
        private boolean appendOutput;
        private List<DependencyTreeVisitor> visitors = List.of();

        private Builder() {
        }

        private Builder(ProjectDependencyConfig config) {
            this.depConfig = config;
        }

        public Builder addDependencyTreeVisitor(DependencyTreeVisitor visitor) {
            switch (visitors.size()) {
                case 0:
                    visitors = List.of(visitor);
                    break;
                case 1:
                    visitors = List.of(visitors.get(0), visitor);
                    break;
                case 2:
                    visitors = new ArrayList<>(visitors);
                default:
                    visitors.add(visitor);
            }
            return this;
        }

        public Builder setArtifactResolver(MavenArtifactResolver artifactResolver) {
            resolver = artifactResolver;
            return this;
        }

        public Builder setArtifactConstraintsProvider(Function<ArtifactCoords, List<Dependency>> constraintsProvider) {
            artifactConstraintsProvider = constraintsProvider;
            return this;
        }

        public Builder setMessageWriter(MessageWriter msgWriter) {
            log = msgWriter;
            return this;
        }

        public Builder setLogOutputFile(Path file) {
            this.logOutputFile = file;
            return this;
        }

        public Builder setAppendOutput(boolean appendOutput) {
            this.appendOutput = appendOutput;
            return this;
        }

        public Builder setDependencyConfig(ProjectDependencyConfig depConfig) {
            this.depConfig = depConfig;
            return this;
        }

        public ProjectDependencyResolver build() {
            return new ProjectDependencyResolver(this);
        }

        private MavenArtifactResolver getInitializedResolver() {
            if (resolver == null) {
                try {
                    if (depConfig == null || depConfig.getProjectDir() == null) {
                        return MavenArtifactResolver.builder().setWorkspaceDiscovery(false).build();
                    }
                    return MavenArtifactResolver.builder()
                            .setCurrentProject(depConfig.getProjectDir().toString())
                            .setEffectiveModelBuilder(true)
                            .setPreferPomsFromWorkspace(true)
                            .build();
                } catch (BootstrapMavenException e) {
                    throw new IllegalStateException("Failed to initialize the Maven artifact resolver", e);
                }
            }
            return resolver;
        }

        private MessageWriter getInitializedLog() {
            return log == null ? MessageWriter.info() : log;
        }
    }

    private static class DepVisit implements DependencyTreeVisitor.DependencyVisit {

        final ArtifactCoords coords;
        final boolean managed;

        private DepVisit(ArtifactCoords coords, boolean managed) {
            this.coords = coords;
            this.managed = managed;
        }

        @Override
        public ArtifactCoords getCoords() {
            return coords;
        }

        @Override
        public boolean isManaged() {
            return managed;
        }

    }

    private static ArtifactCoordsPattern toPattern(ArtifactCoords c) {
        final ArtifactCoordsPattern.Builder pattern = ArtifactCoordsPattern.builder();
        pattern.groupIdPattern(c.getGroupId());
        pattern.artifactIdPattern(c.getArtifactId());
        if (c.getClassifier() != null && !c.getClassifier().isEmpty()) {
            pattern.classifierPattern(c.getClassifier());
        }
        if (c.getType() != null && !c.getType().isEmpty()) {
            pattern.typePattern(c.getType());
        }
        pattern.versionPattern(c.getVersion());
        return pattern.build();
    }

    private static List<ArtifactCoordsPattern> toPatterns(Collection<ArtifactCoords> coords) {
        if (coords.isEmpty()) {
            return List.of();
        }
        final List<ArtifactCoordsPattern> result = new ArrayList<>(coords.size());
        for (ArtifactCoords c : coords) {
            result.add(toPattern(c));
        }
        return result;
    }

    public static Builder builder() {
        return new ProjectDependencyResolver.Builder();
    }

    private final MavenArtifactResolver resolver;
    private final ProjectDependencyConfig config;
    private MessageWriter log;
    private final List<ArtifactCoordsPattern> excludeSet;
    private final List<ArtifactCoordsPattern> includeSet;
    private final List<DependencyTreeVisitor> treeVisitors;

    private PrintStream fileOutput;
    private MessageWriter outputWriter;
    private final Path logOutputFile;
    private final boolean appendOutput;

    /*
     * Whether to include test JARs
     */
    private boolean includeTestJars;

    private Function<ArtifactCoords, List<Dependency>> artifactConstraintsProvider;
    private Set<ArtifactCoords> targetBomConstraints;
    private List<Dependency> targetBomManagedDeps;
    private final Map<ArtifactCoords, List<RemoteRepository>> allDepsToBuild = new HashMap<>();
    private final Set<ArtifactCoords> nonManagedVisited = new HashSet<>();
    private final Set<ArtifactCoords> skippedDeps = new HashSet<>();
    private final Set<ArtifactCoords> remainingDeps = new HashSet<>();

    private final Map<ArtifactCoords, ArtifactDependency> artifactDeps = new HashMap<>();
    private final Map<ReleaseId, ReleaseRepo> releaseRepos = new HashMap<>();
    private final Map<ArtifactCoords, Map<String, String>> effectivePomProps = new HashMap<>();

    private final Map<Set<ReleaseId>, List<ReleaseId>> circularRepoDeps = new HashMap<>();

    private Map<ArtifactCoords, DependencyNode> preResolvedRootArtifacts = Map.of();
    private ReleaseId projectReleaseId;

    private ProjectDependencyResolver(Builder builder) {
        this.resolver = builder.getInitializedResolver();
        this.log = builder.getInitializedLog();
        this.artifactConstraintsProvider = builder.artifactConstraintsProvider;
        this.logOutputFile = builder.logOutputFile;
        this.appendOutput = builder.appendOutput;
        this.config = Objects.requireNonNull(builder.depConfig);
        excludeSet = toPatterns(config.getExcludePatterns());
        includeSet = new ArrayList<>(config.getIncludeArtifacts().size() + config.getIncludePatterns().size());
        config.getIncludePatterns().forEach(p -> includeSet.add(toPattern(p)));
        config.getIncludeArtifacts().forEach(c -> includeSet.add(toPattern(c)));
        if (config.isLogTrees()) {
            treeVisitors = new ArrayList<>(builder.visitors.size() + 1);
            treeVisitors.add(new LoggingDependencyTreeVisitor(getOutput(), true));
        } else {
            treeVisitors = builder.visitors;
        }
    }

    public ProjectDependencyConfig getConfig() {
        return config;
    }

    public Collection<ReleaseRepo> getReleaseRepos() {
        buildModel();
        initReleaseRepos();
        detectCircularRepoDeps();
        return new ArrayList<>(releaseRepos.values());
    }

    public Collection<ReleaseRepo> getSortedReleaseRepos() {
        return sortReleaseRepos(getReleaseRepos());
    }

    public void consumeSorted(Consumer<Collection<ReleaseRepo>> consumer) {
        consumer.accept(getSortedReleaseRepos());
    }

    public <T> T applyToSorted(Function<Collection<ReleaseRepo>, T> func) {
        return func.apply(getSortedReleaseRepos());
    }

    public void log() {

        final boolean logCodeRepos = config.isLogCodeRepos() || config.isLogCodeRepoTree();

        try {
            buildModel();
            int codeReposTotal = 0;
            if (config.isLogArtifactsToBuild() && !allDepsToBuild.isEmpty()) {
                logComment("Artifacts to be built from source from "
                        + (config.getProjectBom() == null ? "" : config.getProjectBom().toCompactCoords()) + ":");
                if (logCodeRepos) {
                    initReleaseRepos();
                    detectCircularRepoDeps();
                    codeReposTotal = releaseRepos.size();

                    final List<ReleaseRepo> sorted = sortReleaseRepos(releaseRepos.values());
                    for (ReleaseRepo e : sorted) {
                        logComment("repo-url " + e.id().origin());
                        logComment("tag " + e.id().version().asString());
                        for (String s : toSortedStrings(e.artifacts.keySet(), config.isLogModulesToBuild())) {
                            log(s);
                        }
                    }

                    if (!circularRepoDeps.isEmpty()) {
                        logComment("ERROR: The following circular dependency chains were detected among releases:");
                        final Iterator<List<ReleaseId>> chains = circularRepoDeps.values().iterator();
                        int i = 0;
                        while (chains.hasNext()) {
                            logComment("  Chain #" + ++i + ":");
                            chains.next().forEach(id -> logComment("    " + id));
                            logComment("");
                        }
                    }
                    if (config.isLogCodeRepoTree()) {
                        logComment("");
                        logComment("Code repository dependency graph");
                        for (ReleaseRepo r : releaseRepos.values()) {
                            if (r.isRoot()) {
                                logReleaseRepoDep(r, 0);
                            }
                        }
                        logComment("");
                    }

                } else {
                    for (String s : toSortedStrings(allDepsToBuild.keySet(), config.isLogModulesToBuild())) {
                        log(s);
                    }
                }
            }

            if (config.isLogNonManagedVisitied() && !nonManagedVisited.isEmpty()) {
                logComment("Non-managed dependencies visited walking dependency trees:");
                final List<String> sorted = toSortedStrings(nonManagedVisited, config.isLogModulesToBuild());
                for (int i = 0; i < sorted.size(); ++i) {
                    logComment((i + 1) + ") " + sorted.get(i));
                }
            }

            if (config.isLogRemaining()) {
                logComment("Remaining artifacts include:");
                final List<String> sorted = toSortedStrings(remainingDeps, config.isLogModulesToBuild());
                for (int i = 0; i < sorted.size(); ++i) {
                    logComment((i + 1) + ") " + sorted.get(i));
                }
            }

            if (config.isLogSummary()) {
                final StringBuilder sb = new StringBuilder().append("Selecting ");
                if (config.getLevel() < 0) {
                    sb.append("all the");
                } else {
                    sb.append(config.getLevel()).append(" level(s) of");
                }
                if (config.isIncludeNonManaged()) {
                    sb.append(" managed and non-managed");
                } else {
                    sb.append(" managed (stopping at the first non-managed one)");
                }
                sb.append(" dependencies of supported extensions");
                if (config.getProjectBom() != null) {
                    sb.append(" from ").append(config.getProjectBom().toCompactCoords());
                }
                sb.append(" will result in:");
                logComment(sb.toString());

                sb.setLength(0);
                sb.append(allDepsToBuild.size()).append(" artifacts");
                if (codeReposTotal > 0) {
                    sb.append(" from ").append(codeReposTotal).append(" code repositories");
                }
                sb.append(" to build from source");
                logComment(sb.toString());
                if (config.isIncludeNonManaged() && !nonManagedVisited.isEmpty()) {
                    logComment("  * " + nonManagedVisited.size() + " of which is/are not managed by the BOM");
                }
                if (!skippedDeps.isEmpty()) {
                    logComment(skippedDeps.size() + " dependency nodes skipped");
                }
                logComment((allDepsToBuild.size() + skippedDeps.size()) + " dependencies visited in total");
            }
        } finally {
            if (fileOutput != null) {
                log.info("Saving the report in " + logOutputFile.toAbsolutePath());
                fileOutput.close();
            }
        }
    }

    private void buildModel() {
        targetBomManagedDeps = getBomConstraints(config.getProjectBom());
        targetBomConstraints = new HashSet<>(targetBomManagedDeps.size());
        for (Dependency d : targetBomManagedDeps) {
            targetBomConstraints.add(toCoords(d.getArtifact()));
        }
        if (artifactConstraintsProvider == null) {
            artifactConstraintsProvider = t -> targetBomManagedDeps;
        }

        for (ArtifactCoords coords : getProjectArtifacts()) {
            if (isIncluded(coords) || !isExcluded(coords)) {
                processRootArtifact(coords, artifactConstraintsProvider.apply(coords));
            }
        }

        for (ArtifactCoords coords : config.getIncludeArtifacts()) {
            if (isIncluded(coords) || !isExcluded(coords)) {
                processRootArtifact(coords, artifactConstraintsProvider.apply(coords));
            }
        }

        if (!config.isIncludeAlreadyBuilt()) {
            removeProductizedDeps();
        }
    }

    private static List<ReleaseRepo> sortReleaseRepos(Collection<ReleaseRepo> releaseRepos) {
        final int codeReposTotal = releaseRepos.size();
        final List<ReleaseRepo> sorted = new ArrayList<>(codeReposTotal);
        final Set<ReleaseId> processedRepos = new HashSet<>(codeReposTotal);
        for (ReleaseRepo r : releaseRepos) {
            if (r.isRoot()) {
                sort(r, processedRepos, sorted);
            }
        }
        return sorted;
    }

    private static void sort(ReleaseRepo repo, Set<ReleaseId> processed, List<ReleaseRepo> sorted) {
        if (!processed.add(repo.id)) {
            return;
        }
        for (ReleaseRepo d : repo.dependencies.values()) {
            sort(d, processed, sorted);
        }
        sorted.add(repo);
    }

    private void removeProductizedDeps() {
        final Set<ArtifactKey> alreadyBuiltKeys = allDepsToBuild.keySet().stream()
                .filter(c -> RhVersionPattern.isRhVersion(c.getVersion()))
                .map(ArtifactCoords::getKey).collect(Collectors.toSet());
        if (!alreadyBuiltKeys.isEmpty()) {
            final Iterator<ArtifactCoords> i = allDepsToBuild.keySet().iterator();
            while (i.hasNext()) {
                final ArtifactCoords coords = i.next();
                if (alreadyBuiltKeys.contains(coords.getKey())) {
                    i.remove();
                    artifactDeps.remove(coords);
                    artifactDeps.values().forEach(d -> {
                        d.removeDependency(coords);
                    });
                }
            }
        }
    }

    protected Iterable<ArtifactCoords> getProjectArtifacts() {
        if (config.getProjectDir() != null) {
            final BuildTool buildTool = BuildTool.forProjectDir(config.getProjectDir());
            Collection<ArtifactCoords> result;
            if (BuildTool.MAVEN.equals(buildTool)) {
                result = MavenProjectReader.resolveModuleDependencies(resolver);
            } else if (BuildTool.GRADLE.equals(buildTool)) {
                preResolvedRootArtifacts = GradleProjectReader.resolveModuleDependencies(config.getProjectDir(),
                        config.isGradleJava8(), config.getGradleJavaHome(), resolver);
                result = preResolvedRootArtifacts.keySet();
                try {
                    final Repository gitRepo = Git.open(config.getProjectDir().toFile()).getRepository();
                    final String repoUrl = gitRepo.getConfig().getString("remote", "origin", "url");
                    projectReleaseId = ReleaseIdFactory.forScmAndTag(repoUrl, gitRepo.getBranch());
                } catch (IOException e) {
                    log.warn("Failed to determine the Git repository URL: ", e.getLocalizedMessage());
                    final ArtifactCoords a = result.iterator().next();
                    projectReleaseId = ReleaseIdFactory.forGav(a.getGroupId(), a.getArtifactId(), a.getVersion());
                }
            } else {
                throw new IllegalStateException("Unrecognized build tool " + buildTool);
            }
            return result;
        }

        if (config.getProjectArtifacts().isEmpty()) {
            final List<ArtifactCoords> result = new ArrayList<>();
            for (ArtifactCoords d : targetBomConstraints) {
                if (d.getGroupId().startsWith(config.getProjectBom().getGroupId()) && d.isJar() && !isExcluded(d)) {
                    result.add(d);
                    log.debug(d.toCompactCoords() + " selected as a top level artifact to build");
                }
            }
            return result;
        }
        return config.getProjectArtifacts();
    }

    private void processRootArtifact(ArtifactCoords rootArtifact, List<Dependency> managedDeps) {
        final DependencyNode root = collectDependencies(rootArtifact, managedDeps);
        if (root == null) {
            // couldn't be resolved
            return;
        }

        final boolean addDependency;
        try {
            addDependency = addDependencyToBuild(rootArtifact, root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process " + rootArtifact, e);
        }
        if (addDependency) {
            var visit = new DepVisit(rootArtifact, targetBomConstraints.contains(rootArtifact));
            for (DependencyTreeVisitor v : treeVisitors) {
                v.enterRootArtifact(visit);
            }

            final ArtifactDependency extDep = getOrCreateArtifactDep(rootArtifact);
            if (!config.isExcludeParentPoms() && config.isLogTrees()) {
                extDep.logBomImportsAndParents();
            }
            for (DependencyNode d : root.getChildren()) {
                if (d.getDependency().isOptional()
                        && !(config.isIncludeOptionalDeps() || isIncluded(toCoords(d.getArtifact())))) {
                    continue;
                }
                processNodes(extDep, d, 1, false);
            }

            for (DependencyTreeVisitor v : treeVisitors) {
                v.leaveRootArtifact(visit);
            }
        } else if (config.isLogRemaining()) {
            for (DependencyNode d : root.getChildren()) {
                processNodes(null, d, 1, true);
            }
        }
    }

    private DependencyNode collectDependencies(ArtifactCoords coords, List<Dependency> managedDeps) {
        DependencyNode root = preResolvedRootArtifacts.get(coords);
        if (root != null) {
            return root;
        }
        try {
            final Artifact a = toAetherArtifact(coords);
            root = resolver.getSystem().collectDependencies(resolver.getSession(), new CollectRequest()
                    .setManagedDependencies(managedDeps)
                    .setRepositories(resolver.getRepositories())
                    .setRoot(new Dependency(a, JavaScopes.RUNTIME)))
                    .getRoot();
            // if the dependencies are not found, make sure the artifact actually exists
            if (root.getChildren().isEmpty()) {
                resolver.resolve(a);
            }
        } catch (Exception e) {
            if (config.isWarnOnResolutionErrors()) {
                log.warn(e.getCause() == null ? e.getLocalizedMessage() : e.getCause().getLocalizedMessage());
                allDepsToBuild.remove(coords);
                return null;
            }
            throw new RuntimeException("Failed to collect dependencies of " + coords.toCompactCoords(), e);
        }
        return root;
    }

    private static DefaultArtifact toAetherArtifact(ArtifactCoords a) {
        return new DefaultArtifact(a.getGroupId(),
                a.getArtifactId(), a.getClassifier(),
                a.getType(), a.getVersion());
    }

    private void initReleaseRepos() {

        final ReleaseIdResolver idResolver = newReleaseIdResolver(resolver, log, config,
                getRhCoordsUpstreamVersions());

        final Map<ArtifactCoords, ReleaseId> artifactReleases = new HashMap<>();
        for (Map.Entry<ArtifactCoords, List<RemoteRepository>> c : allDepsToBuild.entrySet()) {
            final ReleaseId releaseId;
            if (this.preResolvedRootArtifacts.containsKey(c.getKey())) {
                releaseId = projectReleaseId;
            } else {
                try {
                    releaseId = idResolver.releaseId(toAetherArtifact(c.getKey()), c.getValue());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to resolve release id for " + c, e);
                }
            }
            getOrCreateRepo(releaseId).artifacts.put(c.getKey(), c.getValue());
            artifactReleases.put(c.getKey(), releaseId);
        }

        final Iterator<Map.Entry<ReleaseId, ReleaseRepo>> i = releaseRepos.entrySet().iterator();
        while (i.hasNext()) {
            if (i.next().getValue().artifacts.isEmpty()) {
                i.remove();
            }
        }

        for (ArtifactDependency d : artifactDeps.values()) {
            final ArtifactCoords c = d.coords;
            final List<Dependency> directDeps;
            try {
                directDeps = resolver
                        .resolveDescriptor(
                                new DefaultArtifact(c.getGroupId(), c.getArtifactId(), ArtifactCoords.TYPE_POM, c.getVersion()))
                        .getDependencies();
            } catch (BootstrapMavenException e) {
                throw new RuntimeException("Failed to resolve artifact descriptor for " + c, e);
            }
            for (Dependency directDep : directDeps) {
                final Artifact a = directDep.getArtifact();
                final ArtifactDependency dirArt = artifactDeps.get(ArtifactCoords.of(a.getGroupId(), a.getArtifactId(),
                        a.getClassifier(), a.getExtension(), a.getVersion()));
                if (dirArt != null) {
                    d.addDependency(dirArt);
                }
            }
        }

        for (ArtifactDependency d : artifactDeps.values()) {
            final ReleaseRepo repo = getRepo(artifactReleases.get(d.coords));
            for (ArtifactDependency c : d.getAllDependencies()) {
                repo.addRepoDependency(getRepo(artifactReleases.get(c.coords)));
            }
        }
    }

    private Map<ArtifactCoords, String> getRhCoordsUpstreamVersions() {
        if (!config.isIncludeAlreadyBuilt()) {
            // already excluded
            return Map.of();
        }
        final Map<String, List<ArtifactVersion>> upstreamVersions = new HashMap<>();
        final List<ArtifactCoords> rhCoords = new ArrayList<>();
        final List<ArtifactCoords> allCoords = new ArrayList<>(allDepsToBuild.size());
        for (ArtifactCoords c : allDepsToBuild.keySet()) {
            if (RhVersionPattern.isRhVersion(c.getVersion())) {
                rhCoords.add(c);
                if (!allCoords.isEmpty()) {
                    for (ArtifactCoords coords : allCoords) {
                        upstreamVersions.computeIfAbsent(coords.getGroupId(), k -> new ArrayList<>())
                                .add(new DefaultArtifactVersion(coords.getVersion()));
                    }
                    allCoords.clear();
                }
            } else if (rhCoords.isEmpty()) {
                allCoords.add(c);
            } else {
                upstreamVersions.computeIfAbsent(c.getGroupId(), k -> new ArrayList<>())
                        .add(new DefaultArtifactVersion(c.getVersion()));
            }
        }
        if (rhCoords.isEmpty()) {
            return Map.of();
        }
        final Map<ArtifactCoords, String> rhCoordsUpstreamVersions = new HashMap<>(rhCoords.size());
        for (ArtifactCoords c : rhCoords) {
            final List<ArtifactVersion> originalVersions = upstreamVersions.get(c.getGroupId());
            if (originalVersions == null) {
                continue;
            }
            final ArtifactVersion noRhSuffixVersion = new DefaultArtifactVersion(
                    RhVersionPattern.ensureNoRhSuffix(c.getVersion()));
            for (ArtifactVersion v : originalVersions) {
                if (v.equals(noRhSuffixVersion)) {
                    try {
                        resolver.resolve(new DefaultArtifact(c.getGroupId(), c.getArtifactId(), c.getClassifier(), c.getType(),
                                v.toString()));
                        rhCoordsUpstreamVersions.put(c, v.toString());
                    } catch (BootstrapMavenException e) {
                        rhCoordsUpstreamVersions.put(c, noRhSuffixVersion.toString());
                    }
                    break;
                }
            }
        }
        return rhCoordsUpstreamVersions;
    }

    private static ReleaseIdResolver newReleaseIdResolver(MavenArtifactResolver artifactResolver, MessageWriter log,
            ProjectDependencyConfig config, Map<ArtifactCoords, String> versionMapping) {

        if (config.isLegacyScmLocator()) {
            return getLegacyReleaseIdResolver(artifactResolver, log, config.isValidateCodeRepoTags(), versionMapping);
        }

        final List<ReleaseIdDetector> releaseDetectors = ServiceLoader.load(ReleaseIdDetector.class).stream().map(p -> p.get())
                .collect(Collectors.toList());

        final AtomicReference<ReleaseIdResolver> ref = new AtomicReference<>();
        final ScmLocator scmLocator = GitScmLocator.builder()
                .setRecipeRepos(config.getRecipeRepos())
                .setCacheRepoTags(true)
                .setFallback(new ScmLocator() {
                    @Override
                    public TagInfo resolveTagInfo(GAV gav) {

                        var pomArtifact = new DefaultArtifact(gav.getGroupId(), gav.getArtifactId(), ArtifactCoords.TYPE_POM,
                                gav.getVersion());

                        ReleaseId releaseId = null;
                        for (ReleaseIdDetector rd : releaseDetectors) {
                            try {
                                var rid = rd.detectReleaseId(ref.get(), pomArtifact);
                                if (releaseId != null && releaseId.origin().isUrl()
                                        && releaseId.origin().toString().contains("git")) {
                                    releaseId = rid;
                                    break;
                                }
                            } catch (BomDecomposerException e) {
                                log.warn("Failed to determine SCM for " + gav.getGroupId() + ":" + gav.getArtifactId() + ":"
                                        + gav.getVersion() + ": " + e.getLocalizedMessage());
                            }
                        }

                        if (releaseId == null) {
                            try {
                                releaseId = ref.get().defaultReleaseId(pomArtifact);
                            } catch (BomDecomposerException e) {
                                log.warn("Failed to determine SCM for " + gav.getGroupId() + ":" + gav.getArtifactId() + ":"
                                        + gav.getVersion() + " from POM metadata: "
                                        + e.getLocalizedMessage());
                            }
                        }

                        if (releaseId != null && releaseId.origin().isUrl() && releaseId.origin().toString().contains("git")) {
                            log.warn("The SCM recipe database is missing an entry for " + gav.getGroupId() + ":"
                                    + gav.getArtifactId() + ":" + gav.getVersion() + ", " + releaseId
                                    + " will be used as a fallback");
                            return new TagInfo(new RepositoryInfo("git", releaseId.origin().toString()),
                                    releaseId.version().asString(), null);
                        }
                        return null;
                    }
                })
                .build();

        final boolean scmLocatorStats = isScmLocatorStats();
        var hacbsScmLocator = new ReleaseIdDetector() {
            int total;
            int succeeded;

            @Override
            public ReleaseId detectReleaseId(ReleaseIdResolver releaseResolver, Artifact artifact)
                    throws BomDecomposerException {
                final GAV gav = new GAV(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                ++total;
                Exception error = null;
                try {
                    final TagInfo tag = scmLocator.resolveTagInfo(gav);
                    if (tag != null) {
                        ++succeeded;
                        return ReleaseIdFactory.forScmAndTag(tag.getRepoInfo().getUri(), tag.getTag());
                    }
                } catch (Exception e) {
                    error = e;
                } finally {
                    if (scmLocatorStats) {
                        System.out.println("ScmLocator resolved " + succeeded + " out of " + total);
                    }
                }
                var sb = new StringBuilder();
                sb.append("Failed to determine SCM for ").append(artifact);
                if (config.isWarnOnMissingScm()) {
                    if (error != null) {
                        sb.append(": ").append(error.getLocalizedMessage());
                    }
                    log.warn(sb.toString());
                } else {
                    throw new RuntimeException(sb.toString(), error);
                }
                return null;
            }
        };
        final ReleaseIdResolver releaseResolver = new ReleaseIdResolver(artifactResolver, List.of(hacbsScmLocator), log,
                config.isValidateCodeRepoTags(), versionMapping);
        ref.set(releaseResolver);
        return releaseResolver;
    }

    private static ReleaseIdResolver getLegacyReleaseIdResolver(MavenArtifactResolver artifactResolver, MessageWriter log,
            boolean validateCodeRepoTags, Map<ArtifactCoords, String> versionMapping) {
        final List<ReleaseIdDetector> releaseDetectors = new ArrayList<>();
        releaseDetectors.add(
                // Vert.X
                new ReleaseIdDetector() {

                    final Set<String> artifactIdRepos = Set.of("vertx-service-proxy",
                            "vertx-amqp-client",
                            "vertx-health-check",
                            "vertx-camel-bridge",
                            "vertx-redis-client",
                            "vertx-json-schema",
                            "vertx-lang-groovy",
                            "vertx-mail-client",
                            "vertx-http-service-factory",
                            "vertx-tcp-eventbus-bridge",
                            "vertx-dropwizard-metrics",
                            "vertx-consul-client",
                            "vertx-maven-service-factory",
                            "vertx-cassandra-client",
                            "vertx-circuit-breaker",
                            "vertx-jdbc-client",
                            "vertx-reactive-streams",
                            "vertx-rabbitmq-client",
                            "vertx-mongo-client",
                            "vertx-sockjs-service-proxy",
                            "vertx-kafka-client",
                            "vertx-micrometer-metrics",
                            "vertx-service-factory");

                    @Override
                    public ReleaseId detectReleaseId(ReleaseIdResolver releaseResolver, Artifact artifact)
                            throws BomDecomposerException {
                        if (!"io.vertx".equals(artifact.getGroupId())) {
                            return null;
                        }
                        String s = artifact.getArtifactId();
                        if (!s.startsWith("vertx-")) {
                            return releaseResolver.defaultReleaseId(artifact);
                        }
                        if (s.equals("vertx-uri-template")
                                || s.equals("vertx-codegen")
                                || s.equals("vertx-http-proxy")) {
                            return ReleaseIdFactory.forScmAndTag("https://github.com/eclipse-vertx/" + s,
                                    artifact.getVersion());
                        }
                        if (s.equals("vertx-core")) {
                            return ReleaseIdFactory.forScmAndTag("https://github.com/eclipse-vertx/vert.x",
                                    artifact.getVersion());
                        }
                        if (s.startsWith("vertx-tracing")
                                || s.equals("vertx-opentelemetry")
                                || s.equals("vertx-opentracing")
                                || s.equals("vertx-zipkin")) {
                            return ReleaseIdFactory.forScmAndTag("https://github.com/eclipse-vertx/vertx-tracing",
                                    artifact.getVersion());
                        }
                        var defaultReleaseId = releaseResolver.defaultReleaseId(artifact);
                        if (defaultReleaseId.origin().toString().endsWith("vertx-sql-client")) {
                            return defaultReleaseId;
                        }

                        if (s.startsWith("vertx-ext")) {
                            s = "vertx-ext-parent";
                        } else if (artifactIdRepos.contains(s)) {
                            // keep the artifactId
                        } else if (s.startsWith("vertx-lang-kotlin")) {
                            s = "vertx-lang-kotlin";
                        } else if (s.startsWith("vertx-service-discovery")) {
                            s = "vertx-service-discovery";
                        } else if (s.equals("vertx-template-engines")) {
                            s = "vertx-web";
                        } else if (s.equals("vertx-web-sstore-infinispan")) {
                            s = "vertx-infinispan";
                        } else if (s.startsWith("vertx-junit5-rx")) {
                            s = "vertx-rx";
                        } else if (!s.equals("vertx-bridge-common")) {
                            int i = s.indexOf('-', "vertx-".length());
                            if (i > 0) {
                                s = s.substring(0, i);
                            }
                        }

                        return ReleaseIdFactory.forScmAndTag("https://github.com/vert-x3/" + s, artifact.getVersion());
                    }
                });
        releaseDetectors
                .addAll(ServiceLoader.load(ReleaseIdDetector.class).stream().map(p -> p.get()).collect(Collectors.toList()));

        return new ReleaseIdResolver(artifactResolver, releaseDetectors, log, validateCodeRepoTags, versionMapping);
    }

    private void logReleaseRepoDep(ReleaseRepo repo, int depth) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; ++i) {
            sb.append("  ");
        }
        sb.append(repo.id().origin()).append(' ').append(repo.id().version());
        logComment(sb.toString());
        for (ReleaseRepo child : repo.dependencies.values()) {
            logReleaseRepoDep(child, depth + 1);
        }
    }

    private static List<String> toSortedStrings(Collection<ArtifactCoords> coords, boolean asModules) {
        final List<String> list;
        if (asModules) {
            final Set<String> set = new HashSet<>();
            for (ArtifactCoords c : coords) {
                set.add(c.getGroupId() + ":" + c.getArtifactId() + ":" + c.getVersion());
            }
            list = new ArrayList<>(set);
        } else {
            list = new ArrayList<>(coords.size());
            for (ArtifactCoords c : coords) {
                list.add(c.toGACTVString());
            }
        }
        Collections.sort(list);
        return list;
    }

    private MessageWriter getOutput() {
        if (logOutputFile == null) {
            return log;
        }
        if (outputWriter == null) {
            try {
                if (logOutputFile.getParent() != null) {
                    Files.createDirectories(logOutputFile.getParent());
                }
                final OpenOption[] oo = appendOutput
                        ? new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.APPEND }
                        : new OpenOption[] {};
                fileOutput = new PrintStream(Files.newOutputStream(logOutputFile, oo), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to open " + logOutputFile + " for writing", e);
            }
            outputWriter = MessageWriter.info(fileOutput);
        }
        return outputWriter;
    }

    private void logComment(String msg) {
        log("# " + msg);
    }

    private void log(String msg) {
        getOutput().info(msg);
    }

    private void processNodes(ArtifactDependency parent, DependencyNode node, int level, boolean remaining) {
        final ArtifactCoords coords = toCoords(node.getArtifact());
        if (isExcluded(coords)) {
            return;
        }
        ArtifactDependency artDep = null;
        DepVisit visit = null;
        if (remaining) {
            addToRemaining(coords);
        } else if (config.getLevel() < 0 || level <= config.getLevel()) {
            if (addDependencyToBuild(coords, node)) {
                visit = new DepVisit(coords, targetBomConstraints.contains(coords));
                for (DependencyTreeVisitor v : treeVisitors) {
                    v.enterDependency(visit);
                }
                if (parent != null) {
                    artDep = getOrCreateArtifactDep(coords);
                    parent.addDependency(artDep);
                    if (config.isLogTrees()) {
                        artDep.logBomImportsAndParents();
                    }
                }
            } else if (config.isLogRemaining()) {
                remaining = true;
            } else {
                return;
            }
        } else {
            addToSkipped(coords);
            if (config.isLogRemaining()) {
                remaining = true;
                addToRemaining(coords);
            } else {
                return;
            }
        }
        for (DependencyNode child : node.getChildren()) {
            processNodes(artDep, child, level + 1, remaining);
        }

        if (visit != null) {
            for (DependencyTreeVisitor v : treeVisitors) {
                v.leaveDependency(visit);
            }
        }
    }

    private boolean addDependencyToBuild(ArtifactCoords coords, DependencyNode node) {
        if (!addArtifactToBuild(coords, node.getRepositories())) {
            return false;
        }
        if (!config.isExcludeParentPoms() && !isExcludeParentPoms(coords)) {
            addImportedBomsAndParentPomToBuild(coords, node);
        }
        return true;
    }

    private boolean isExcludeParentPoms(ArtifactCoords coords) {
        return preResolvedRootArtifacts.containsKey(coords);
    }

    private boolean addArtifactToBuild(ArtifactCoords coords, List<RemoteRepository> repos) {
        final boolean managed = targetBomConstraints.contains(coords);
        if (!managed) {
            nonManagedVisited.add(coords);
        }

        if (managed || config.isIncludeNonManaged() || isIncluded(coords)
                || !config.isExcludeParentPoms() && coords.getType().equals(ArtifactCoords.TYPE_POM)) {
            allDepsToBuild.put(coords, repos);
            skippedDeps.remove(coords);
            remainingDeps.remove(coords);
            return true;
        }

        addToSkipped(coords);
        if (config.isLogRemaining()) {
            addToRemaining(coords);
        }
        return false;
    }

    private Map<String, String> addImportedBomsAndParentPomToBuild(ArtifactCoords coords, DependencyNode node) {
        final ArtifactCoords pomCoords = coords.getType().equals(ArtifactCoords.TYPE_POM) ? coords
                : ArtifactCoords.pom(coords.getGroupId(), coords.getArtifactId(), coords.getVersion());

        if (allDepsToBuild.containsKey(pomCoords)) {
            return effectivePomProps.getOrDefault(pomCoords, Map.of());
        }
        final Path pomXml;
        try {
            pomXml = resolver.resolve(toAetherArtifact(pomCoords), node.getRepositories()).getArtifact().getFile().toPath();
        } catch (BootstrapMavenException e) {
            if (config.isWarnOnResolutionErrors()) {
                log.warn(e.getCause() == null ? e.getLocalizedMessage() : e.getCause().getLocalizedMessage());
                allDepsToBuild.remove(pomCoords);
                return Map.of();
            }
            throw new IllegalStateException("Failed to resolve " + pomCoords, e);
        }
        final Model model;
        try {
            model = ModelUtils.readModel(pomXml);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + pomXml, e);
        }
        final ArtifactDependency artDep = getOrCreateArtifactDep(coords);
        Map<String, String> parentPomProps = null;
        final Parent parent = model.getParent();
        if (parent != null) {
            String parentVersion = parent.getVersion();
            if (ModelUtils.isUnresolvedVersion(parentVersion)) {
                if (model.getVersion() == null || model.getVersion().equals(parentVersion)) {
                    parentVersion = pomCoords.getVersion();
                } else {
                    log.warn("Failed to resolve the version of" + parent.getGroupId() + ":" + parent.getArtifactId() + ":"
                            + parent.getVersion() + " as a parent of " + pomCoords);
                    parentVersion = null;
                }
            }
            if (parentVersion != null) {
                final ArtifactCoords parentPomCoords = ArtifactCoords.pom(parent.getGroupId(), parent.getArtifactId(),
                        parentVersion);
                if (!isExcluded(parentPomCoords)) {
                    artDep.setParentPom(getOrCreateArtifactDep(parentPomCoords));
                    parentPomProps = addImportedBomsAndParentPomToBuild(parentPomCoords, node);
                    addArtifactToBuild(parentPomCoords, node.getRepositories());
                }
            }
        }

        if (config.isExcludeBomImports()) {
            return Map.of();
        }
        Map<String, String> pomProps = toMap(model.getProperties());
        for (Profile profile : model.getProfiles()) {
            if (profile.getActivation() != null && profile.getActivation().isActiveByDefault()
                    && !profile.getProperties().isEmpty()) {
                addAll(pomProps, profile.getProperties());
            }
        }
        pomProps.put("project.version", pomCoords.getVersion());
        pomProps.put("project.groupId", pomCoords.getGroupId());
        if (parentPomProps != null) {
            final Map<String, String> tmp = new HashMap<>(parentPomProps.size() + pomProps.size());
            tmp.putAll(parentPomProps);
            tmp.putAll(pomProps);
            pomProps = tmp;
        }
        effectivePomProps.put(pomCoords, pomProps);
        addImportedBomsToBuild(artDep, model, pomProps, node);
        return pomProps;
    }

    private void addImportedBomsToBuild(ArtifactDependency pomArtDep, Model model, Map<String, String> effectiveProps,
            DependencyNode node) {
        final DependencyManagement dm = model.getDependencyManagement();
        if (dm == null) {
            return;
        }
        for (org.apache.maven.model.Dependency d : dm.getDependencies()) {
            if ("import".equals(d.getScope()) && ArtifactCoords.TYPE_POM.equals(d.getType())) {
                final String groupId = resolveProperty(d.getGroupId(), d, effectiveProps);
                final String artifactId = resolveProperty(d.getArtifactId(), d, effectiveProps);
                final String version = resolveProperty(d.getVersion(), d, effectiveProps);
                if (groupId == null || version == null || artifactId == null) {
                    continue;
                }
                final ArtifactCoords bomCoords = ArtifactCoords.pom(groupId, artifactId, version);
                if (!isExcluded(bomCoords)) {
                    if (pomArtDep != null) {
                        final ArtifactDependency bomDep = getOrCreateArtifactDep(bomCoords);
                        pomArtDep.addBomImport(bomDep);
                    }
                    addImportedBomsAndParentPomToBuild(bomCoords, node);
                    addArtifactToBuild(bomCoords, node.getRepositories());
                }
            }
        }
    }

    private String resolveProperty(String expr, org.apache.maven.model.Dependency dep, Map<String, String> props) {
        final String value = PropertyResolver.resolvePropertyOrNull(expr, props);
        if (value == null) {
            log.warn("Failed to resolve property " + expr + " from " + dep);
            throw new RuntimeException();
        }
        return value;
    }

    private void addToSkipped(ArtifactCoords coords) {
        if (!allDepsToBuild.containsKey(coords)) {
            skippedDeps.add(coords);
        }
    }

    private void addToRemaining(ArtifactCoords coords) {
        if (!allDepsToBuild.containsKey(coords)) {
            remainingDeps.add(coords);
        }
    }

    private boolean isExcluded(ArtifactCoords coords) {
        for (ArtifactCoordsPattern pattern : excludeSet) {
            boolean matches = pattern.matches(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(),
                    coords.getType(),
                    coords.getVersion());
            if (matches) {
                return true;
            }
        }
        return !includeTestJars && coords.getClassifier().equals("tests");
    }

    private boolean isIncluded(ArtifactCoords coords) {
        for (ArtifactCoordsPattern pattern : includeSet) {
            if (pattern.matches(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getType(),
                    coords.getVersion())) {
                return true;
            }
        }
        return false;
    }

    private List<Dependency> getBomConstraints(ArtifactCoords bomCoords) {
        if (bomCoords == null) {
            return List.of();
        }
        final Artifact bomArtifact = new DefaultArtifact(bomCoords.getGroupId(), bomCoords.getArtifactId(),
                ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_POM, bomCoords.getVersion());
        List<Dependency> managedDeps;
        try {
            managedDeps = resolver.resolveDescriptor(bomArtifact)
                    .getManagedDependencies();
        } catch (BootstrapMavenException e) {
            throw new RuntimeException("Failed to resolve the descriptor of " + bomCoords, e);
        }
        if (managedDeps.isEmpty()) {
            throw new RuntimeException(bomCoords.toCompactCoords()
                    + " does not include any managed dependency or its descriptor could not be read");
        }
        return managedDeps;
    }

    private ArtifactDependency getOrCreateArtifactDep(ArtifactCoords c) {
        return artifactDeps.computeIfAbsent(c, k -> new ArtifactDependency(c));
    }

    private class ArtifactDependency {
        final ArtifactCoords coords;
        final Map<ArtifactCoords, ArtifactDependency> children = new LinkedHashMap<>();
        final Map<ArtifactCoords, ArtifactDependency> bomImports = new LinkedHashMap<>();
        ArtifactDependency parentPom;

        ArtifactDependency(ArtifactCoords coords) {
            this.coords = coords;
        }

        public void addBomImport(ArtifactDependency bomDep) {
            bomImports.put(bomDep.coords, bomDep);
        }

        public void setParentPom(ArtifactDependency parentPom) {
            this.parentPom = parentPom;
        }

        void addDependency(ArtifactDependency d) {
            children.putIfAbsent(d.coords, d);
        }

        Iterable<ArtifactDependency> getAllDependencies() {
            final List<ArtifactDependency> list = new ArrayList<>(children.size() + bomImports.size() + 1);
            if (parentPom != null) {
                list.add(parentPom);
            }
            list.addAll(bomImports.values());
            list.addAll(children.values());
            return list;
        }

        private void removeDependency(ArtifactCoords coords) {
            if (children.remove(coords) != null) {
                return;
            }
            if (bomImports.remove(coords) != null) {
                return;
            }
            if (parentPom != null && parentPom.coords.equals(coords)) {
                parentPom = null;
            }
        }

        private void logBomImportsAndParents() {
            if (parentPom == null && bomImports.isEmpty()) {
                return;
            }
            if (parentPom != null) {
                var visit = new DepVisit(parentPom.coords, false);
                for (DependencyTreeVisitor v : treeVisitors) {
                    v.enterParentPom(visit);
                }
                parentPom.logBomImportsAndParents();
                for (DependencyTreeVisitor v : treeVisitors) {
                    v.leaveParentPom(visit);
                }
            }
            for (ArtifactDependency d : bomImports.values()) {
                var visit = new DepVisit(d.coords, true);
                for (DependencyTreeVisitor v : treeVisitors) {
                    v.enterBomImport(visit);
                }
                d.logBomImportsAndParents();
                for (DependencyTreeVisitor v : treeVisitors) {
                    v.leaveBomImport(visit);
                }
            }
        }
    }

    private ReleaseRepo getOrCreateRepo(ReleaseId id) {
        return releaseRepos.computeIfAbsent(id, k -> new ReleaseRepo(id));
    }

    private ReleaseRepo getRepo(ReleaseId id) {
        return Objects.requireNonNull(releaseRepos.get(id));
    }

    private void detectCircularRepoDeps() {
        for (ReleaseRepo r : releaseRepos.values()) {
            final List<ReleaseId> chain = new ArrayList<>();
            detectCircularRepoDeps(r, chain);
        }
    }

    private void detectCircularRepoDeps(ReleaseRepo r, List<ReleaseId> chain) {
        final int i = chain.indexOf(r.id);
        if (i >= 0) {
            final List<ReleaseId> loop = new ArrayList<>(chain.size() - i + 1);
            for (int j = i; j < chain.size(); ++j) {
                loop.add(chain.get(j));
            }
            loop.add(r.id);
            circularRepoDeps.computeIfAbsent(new HashSet<>(loop), k -> loop);
            return;
        }
        chain.add(r.id);
        for (ReleaseRepo d : r.dependencies.values()) {
            detectCircularRepoDeps(d, chain);
        }
        chain.remove(chain.size() - 1);
    }

    private static Map<String, String> toMap(Properties props) {
        final Map<String, String> map = new HashMap<>(props.size());
        for (Map.Entry<?, ?> e : props.entrySet()) {
            map.put(toString(e.getKey()), toString(e.getValue()));
        }
        return map;
    }

    private static void addAll(Map<String, String> map, Properties props) {
        for (Map.Entry<?, ?> e : props.entrySet()) {
            map.put(toString(e.getKey()), toString(e.getValue()));
        }
    }

    private static String toString(Object o) {
        return o == null ? null : o.toString();
    }

    private static ArtifactCoords toCoords(Artifact a) {
        return ArtifactCoords.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension(), a.getVersion());
    }
}
