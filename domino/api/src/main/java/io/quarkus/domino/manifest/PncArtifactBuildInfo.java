package io.quarkus.domino.manifest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class PncArtifactBuildInfo {

    private static volatile ObjectMapper mapper;

    private static ObjectMapper getMapper() {
        if (mapper == null) {
            ObjectMapper om = new ObjectMapper();
            om.enable(SerializationFeature.INDENT_OUTPUT);
            om.enable(JsonParser.Feature.ALLOW_COMMENTS);
            om.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
            om.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
            om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper = om;
        }
        return mapper;
    }

    public static PncArtifactBuildInfo deserialize(Path p) {
        try (BufferedReader reader = Files.newBufferedReader(p)) {
            return getMapper().readValue(reader, PncArtifactBuildInfo.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize " + p, e);
        }
    }

    public static PncArtifactBuildInfo deserialize(InputStream is) {
        try (InputStream stream = is) {
            return getMapper().readValue(stream, PncArtifactBuildInfo.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize PNC build info", e);
        }
    }

    private List<Content> content;
    private Map<String, Object> any;

    public List<Content> getContent() {
        return content;
    }

    public void setContent(List<Content> content) {
        this.content = content;
    }

    public Map<String, Object> getAny() {
        return any;
    }

    @JsonAnySetter
    public void setAny(Map<String, Object> any) {
        this.any = any;
    }

    public static class Content {

        private String id;
        private String identifier;
        private String purl;
        private String artifactQuality;
        private String buildCategory;
        private String md5;
        private String sha1;
        private String sha256;
        private String modificationTime;
        private Build build;

        private Map<String, Object> any;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public String getPurl() {
            return purl;
        }

        public void setPurl(String purl) {
            this.purl = purl;
        }

        public String getArtifactQuality() {
            return artifactQuality;
        }

        public void setArtifactQuality(String artifactQuality) {
            this.artifactQuality = artifactQuality;
        }

        public String getBuildCategory() {
            return buildCategory;
        }

        public void setBuildCategory(String buildCategory) {
            this.buildCategory = buildCategory;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }

        public String getSha1() {
            return sha1;
        }

        public void setSha1(String sha1) {
            this.sha1 = sha1;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public String getModificationTime() {
            return modificationTime;
        }

        public void setModificationTime(String modificationTime) {
            this.modificationTime = modificationTime;
        }

        public Build getBuild() {
            return build;
        }

        public void setBuild(Build build) {
            this.build = build;
        }

        public Map<String, Object> getAny() {
            return any;
        }

        @JsonAnySetter
        public void setAny(Map<String, Object> any) {
            this.any = any;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public static class Build {

        private String id;
        private String startTime;
        private String endTime;
        private Map<String, Object> any;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public Map<String, Object> getAny() {
            return any;
        }

        @JsonAnySetter
        public void setAny(Map<String, Object> any) {
            this.any = any;
        }
    }
}
