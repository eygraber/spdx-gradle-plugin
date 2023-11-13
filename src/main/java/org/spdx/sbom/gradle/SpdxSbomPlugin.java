/*
 * Copyright 2023 The Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spdx.sbom.gradle;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.spdx.sbom.gradle.SpdxSbomExtension.Target;
import org.spdx.sbom.gradle.maven.DependencyResolver;
import org.spdx.sbom.gradle.maven.PomResolver;
import org.spdx.sbom.gradle.project.DocumentInfo;
import org.spdx.sbom.gradle.project.ProjectInfo;
import org.spdx.sbom.gradle.project.ProjectInfoService;
import org.spdx.sbom.gradle.project.ScmInfo;
import org.spdx.sbom.gradle.utils.SpdxKnownLicensesService;

/** A plugin to generate spdx sboms. */
public class SpdxSbomPlugin implements Plugin<Project> {

  public void apply(Project project) {
    Provider<SpdxKnownLicensesService> knownLicenseServiceProvider =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(
                "spdxKnownLicensesService", SpdxKnownLicensesService.class, spec -> {});
    Provider<ProjectInfoService> projectInfoService =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(
                "spdxProjectInfoService",
                ProjectInfoService.class,
                spec ->
                    spec.getParameters()
                        .getAllProjects()
                        .set(
                            project.provider(
                                () ->
                                    ProjectInfo.from(project.getRootProject().getAllprojects()))));
    var extension = project.getExtensions().create("spdxSbom", SpdxSbomExtension.class);
    extension
        .getTargets()
        .configureEach(
            target -> {
              target.getConfigurations().convention(Collections.singleton("runtimeClasspath"));
              target.getDocument().getName().convention(project.getName());
              target.getDocument().getNamespace().convention("https://example.com/UUID");
              target.getScm().getTool().convention("git");
              target.getScm().getRevision().convention("<no-scm-revision>");
              target.getScm().getUri().convention("<no-scm-uri>");
            });
    TaskProvider<Task> aggregate =
        project
            .getTasks()
            .register(
                "spdxSbom",
                t -> {
                  t.setGroup("Spdx sbom tasks");
                  t.setDescription("Run all sbom tasks in this project");
                });
    extension
        .getTargets()
        .all(
            target ->
                createTaskForTarget(
                    project, target, aggregate, knownLicenseServiceProvider, projectInfoService));
  }

  private void createTaskForTarget(
      Project project,
      Target target,
      TaskProvider<Task> aggregate,
      Provider<SpdxKnownLicensesService> knownLicenseServiceProvider,
      Provider<ProjectInfoService> projectInfoService) {
    String name =
        (target.getName().length() <= 1)
            ? target.getName().toUpperCase()
            : target.getName().substring(0, 1).toUpperCase() + target.getName().substring(1);
    TaskProvider<SpdxSbomTask> task =
        project
            .getTasks()
            .register(
                "spdxSbomFor" + name,
                SpdxSbomTask.class,
                t -> {
                  t.setGroup("Spdx sbom tasks");
                  t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("spdx"));
                  t.getProjectPath().set(project.getPath());
                  t.getFilename().set(target.getName() + ".spdx.json");
                  t.getDocumentInfo().set(DocumentInfo.from(target));
                  t.getScmInfo().set(ScmInfo.from(target));
                  t.getProjectInfoService().set(projectInfoService);
                  t.usesService(projectInfoService);
                  t.getSpdxKnownLicensesService().set(knownLicenseServiceProvider);
                  t.usesService(knownLicenseServiceProvider);

                  List<String> configurationNames = target.getConfigurations().get();
                  var rootComponentsProperty =
                      project.getObjects().listProperty(ResolvedComponentResult.class);
                  for (var configurationName : configurationNames) {
                    Provider<ResolvedComponentResult> rootComponent =
                        project
                            .getConfigurations()
                            .getByName(configurationName)
                            .getIncoming()
                            .getResolutionResult()
                            .getRootComponent();

                    rootComponentsProperty.add(rootComponent);
                  }
                  t.getRootComponents().addAll(rootComponentsProperty);

                  Provider<List<ResolvedArtifactResult>> resolvedArtifactsProvider =
                      rootComponentsProperty.map(
                          rootComponents ->
                              DependencyResolver.newDependencyResolver(project.getDependencies())
                                  .resolveDependencies(rootComponents));

                  t.getResolvedArtifacts()
                      .putAll(
                          resolvedArtifactsProvider.map(
                              resolvedArtifactResults ->
                                  resolvedArtifactResults.stream()
                                      .collect(
                                          Collectors.toMap(
                                              ArtifactResult::getId,
                                              ResolvedArtifactResult::getFile))));

                  t.getPoms()
                      .putAll(
                          resolvedArtifactsProvider.map(
                              resolvedArtifactResults ->
                                  PomResolver.newPomResolver(
                                          project.getDependencies(),
                                          project.getConfigurations(),
                                          project.getLogger())
                                      .effectivePoms(resolvedArtifactResults)));

                  t.getMavenRepositories()
                      .set(
                          project.provider(
                              () ->
                                  getAllRepositories(project).entrySet().stream()
                                      .filter(e -> e.getValue() instanceof MavenArtifactRepository)
                                      .collect(
                                          Collectors.toMap(
                                              Entry::getKey,
                                              e ->
                                                  ((MavenArtifactRepository) e.getValue())
                                                      .getUrl()))));
                });
    aggregate.configure(t -> t.dependsOn(task));
  }

  private Map<String, ArtifactRepository> getAllRepositories(Project project) {
    Map<String, ArtifactRepository> projectRepositories = project.getRepositories().getAsMap();

    // nasty cast because of https://github.com/gradle/gradle/issues/17295
    // see https://github.com/spdx/spdx-gradle-plugin/issues/73
    Map<String, ArtifactRepository> settingsRepositories =
        ((GradleInternal) project.getGradle())
            .getSettings()
            .getDependencyResolutionManagement()
            .getRepositories()
            .getAsMap();

    settingsRepositories.putAll(projectRepositories);
    return settingsRepositories;
  }
}
