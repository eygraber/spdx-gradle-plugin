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
package org.spdx.sbom.gradle.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource2;
import org.apache.maven.model.resolution.ModelResolver;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;

public class GradleMavenResolver implements ModelResolver {
  private final DependencyHandler dependencies;
  private final ConfigurationContainer configurations;

  public GradleMavenResolver(
      DependencyHandler dependencies, ConfigurationContainer configurations) {
    this.dependencies = dependencies;
    this.configurations = configurations;
  }

  @Override
  public ModelSource2 resolveModel(String groupId, String artifactId, String version) {
    var dep = groupId + ":" + artifactId + ":" + version + "@pom";
    var dependency = dependencies.create(dep);
    var config = configurations.detachedConfiguration(dependency);

    var pomXml = config.getSingleFile();
    return new FileModelSource(pomXml);
  }

  @Override
  public ModelSource2 resolveModel(Parent parent) {
    return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
  }

  @Override
  public ModelSource2 resolveModel(Dependency dep) {
    return resolveModel(dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
  }

  @Override
  public void addRepository(Repository repository) {
    // do nothing, we don't use repositories from here
  }

  @Override
  public void addRepository(Repository repository, boolean replace) {
    // do nothing, we don't use repositories from here
  }

  @Override
  public ModelResolver newCopy() {
    return this;
  }
}
