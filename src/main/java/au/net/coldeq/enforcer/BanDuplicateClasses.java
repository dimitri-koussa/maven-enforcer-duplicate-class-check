package au.net.coldeq.enforcer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.lang.String.format;
import static java.util.Collections.emptySet;

/**
 * Based of the class of the same name from the maven enforcer plugin extras project.
 */
public class BanDuplicateClasses implements EnforcerRule {

    private static final Set<String> ALLOWED_ARTIFACT_TYPES = new HashSet<String>() {{
        add("jar");
        add("test-jar");
    }};

    private Log log;

    /**
     * Simple param. This rule will fail if the value is true.
     */
    private Set<String> ignoredArtifacts = emptySet();

    @Override
    public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
        this.log = helper.getLog();
        MavenProject project = getMavenProject(helper);
        Set<Artifact> artifacts = project.getArtifacts();
        int numberOfArtifacts = artifacts.size();
        int numberProcessed = 0;
        Map<Artifact, Set<String>> artifactToClasses = new HashMap<Artifact, Set<String>>();



        for (Artifact artifact : artifacts) {

            String currentArtifact = format("%s:%s", artifact.getGroupId(), artifact.getArtifactId());
            if (ignoredArtifacts.contains(currentArtifact)) {
                log.info("Ignoring " + currentArtifact);
                continue;
            }

            HashSet<String> classesFoundInArtifact = new HashSet<String>();
            artifactToClasses.put(artifact, classesFoundInArtifact);

            File file = artifact.getFile();
            if (file == null) {
                log.info("OK, file is null. WTF! " + artifact.toString() + ". Ignoring...");
            } else if (!file.exists()) {
                log.info("OK, file does not exist. WTF! " + file.getAbsolutePath() + ". Ignoring...");
            } else if (file.isDirectory()) {
                log.info("File is a directory. why?" + file.getAbsolutePath() + ". Ignoring...");
            } else if (shouldIgnoreArtifactType(artifact)) {
                log.info("File is a..." + artifact.getType() + ": " + file.getAbsolutePath() + ". Ignoring...");
            } else {
                log.debug((numberProcessed + 1) + " / " + numberOfArtifacts + "\tSearching for duplicate classes in: " + file.getAbsolutePath());
                classesFoundInArtifact.addAll(findClassesInJarFile(file));
            }
        }

        Map<String, Set<Artifact>> classesToArtifact = invert(artifactToClasses);
        Map<String, Set<Artifact>> duplicates = filterForClassesFoundInMoreThanOneArtifact(classesToArtifact);
        if (duplicates.isEmpty()) {
            log.info("No duplicates found");
        } else {
            assertThatAllDuplicatesAreOfClassesThatMatchToTheByte(duplicates);
        }
    }

    private boolean shouldIgnoreArtifactType(Artifact artifact) {
        return !ALLOWED_ARTIFACT_TYPES.contains(artifact.getType());
    }

    private void assertThatAllDuplicatesAreOfClassesThatMatchToTheByte(Map<String, Set<Artifact>> duplicates) throws EnforcerRuleException {
        int errorCount = 0;
        for (Map.Entry<String, Set<Artifact>> entry : duplicates.entrySet()) {
            String className = entry.getKey();
            Iterator<Artifact> iter = entry.getValue().iterator();

            Artifact artifactA = iter.next();
            byte[] artifactABytes = readBytesForClassFromArtifact(className, artifactA);


            while (iter.hasNext()) {
                Artifact artifactB = iter.next();
                byte[] artifactBBytes = readBytesForClassFromArtifact(className, artifactB);

                if (areByteArraysDifferent(artifactABytes, artifactBBytes)) {
                    errorCount++;

                    log.error(String.format("Multiple differing copies of %s found in %s:%s:%s and %s:%s:%s",
                            StringUtils.replace(className.substring(0, className.indexOf(".class")), "/", "."),
                            artifactA.getGroupId(),
                            artifactA.getArtifactId(),
                            artifactA.getVersion(),
                            artifactB.getGroupId(),
                            artifactB.getArtifactId(),
                            artifactB.getVersion())
                    );
                    break;
                }
            }
        }

        if (errorCount > 0) {
            throw new EnforcerRuleException("Duplicate classes found on classpath. " + errorCount + " instances detected.");
        }
    }

    private boolean areByteArraysDifferent(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return true;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return true;
            }
        }
        return false;
    }

    private byte[] readBytesForClassFromArtifact(String className, Artifact artifact) throws EnforcerRuleException {
        try {
            JarFile jar = new JarFile(artifact.getFile());
            try {
                for (JarEntry entry : Collections.<JarEntry>list(jar.entries())) {
                    if (className.equals(entry.getName())) {
                        return convertInputStreamToByteArray(jar.getInputStream(entry));
                    }
                }
                throw new RuntimeException(String.format("Expected to find %s in artifact: %s:%s:%s", className, artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
            } finally {
                try {
                    jar.close();
                } catch (IOException e) {
                }
            }
        } catch (IOException e) {
            throw new EnforcerRuleException("Unable to process dependency " + artifact.getFile().getAbsolutePath() + " due to " + e.getMessage(), e);
        }
    }

    private byte[] convertInputStreamToByteArray(InputStream inputStream) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];

            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            buffer.flush();

            return buffer.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Set<Artifact>> filterForClassesFoundInMoreThanOneArtifact(Map<String, Set<Artifact>> classesToArtifact) {
        Map<String, Set<Artifact>> result = new HashMap<String, Set<Artifact>>();
        for (Map.Entry<String, Set<Artifact>> entry : classesToArtifact.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Set<Artifact>> invert(Map<Artifact, Set<String>> artifactToClasses) {
        Map<String, Set<Artifact>> classesToArtifact = new HashMap<String, Set<Artifact>>();
        for (Map.Entry<Artifact, Set<String>> classesInArtifact : artifactToClasses.entrySet()) {
            for (String className : classesInArtifact.getValue()) {
                if (!classesToArtifact.containsKey(className)) {
                    classesToArtifact.put(className, new HashSet<Artifact>());
                }
                classesToArtifact.get(className).add(classesInArtifact.getKey());
            }
        }
        return classesToArtifact;
    }

    private Set<String> findClassesInJarFile(File file) throws EnforcerRuleException {
        try {
            JarFile jar = new JarFile(file);
            try {
                Set<String> classes = new HashSet<String>();
                for (JarEntry entry : Collections.<JarEntry>list(jar.entries())) {
                    String name = entry.getName();
                    if (name != null && name.matches(".*\\.class") && !name.contains("module-info.class")) {
                        classes.add(entry.getName());
                    }
                }
                return classes;
            } finally {
                try {
                    jar.close();
                } catch (IOException e) {
                }
            }
        } catch (IOException e) {
            throw new EnforcerRuleException("Unable to process dependency " + file.getAbsolutePath() + " due to " + e.getMessage(), e);
        }
    }

    private MavenProject getMavenProject(EnforcerRuleHelper helper) {
        try {
            return (MavenProject) helper.evaluate("${project}");
        } catch (ExpressionEvaluationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCacheable() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResultValid(EnforcerRule enforcerRule) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCacheId() {
        return "Does not matter as not cacheable";
    }
}
