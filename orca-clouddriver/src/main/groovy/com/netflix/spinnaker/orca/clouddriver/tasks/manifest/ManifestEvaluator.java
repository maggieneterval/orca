/*
 * Copyright 2019 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.manifest;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Collections.emptyList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.netflix.spinnaker.kork.annotations.NonnullByDefault;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.kork.core.RetrySupport;
import com.netflix.spinnaker.orca.ExecutionStatus;
import com.netflix.spinnaker.orca.TaskResult;
import com.netflix.spinnaker.orca.clouddriver.KatoService;
import com.netflix.spinnaker.orca.clouddriver.OortService;
import com.netflix.spinnaker.orca.clouddriver.model.TaskId;
import com.netflix.spinnaker.orca.clouddriver.tasks.manifest.ManifestContext.BindArtifact;
import com.netflix.spinnaker.orca.clouddriver.utils.CloudProviderAware;
import com.netflix.spinnaker.orca.pipeline.expressions.PipelineExpressionEvaluator;
import com.netflix.spinnaker.orca.pipeline.model.Stage;
import com.netflix.spinnaker.orca.pipeline.util.ArtifactUtils;
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import retrofit.client.Response;

/** This class handles resolving a list of manifests and associated artifacts. */
@Component
@NonnullByDefault
public class ManifestEvaluator implements CloudProviderAware {
  private static final ThreadLocal<Yaml> yamlParser =
      ThreadLocal.withInitial(() -> new Yaml(new SafeConstructor()));

  private final ArtifactUtils artifactUtils;
  private final ContextParameterProcessor contextParameterProcessor;
  private final KatoService katoService;
  private final ObjectMapper objectMapper;
  private final OortService oortService;
  private final RetrySupport retrySupport;

  @Autowired
  public ManifestEvaluator(
      ArtifactUtils artifactUtils,
      ContextParameterProcessor contextParameterProcessor,
      KatoService katoService,
      ObjectMapper objectMapper,
      OortService oortService,
      RetrySupport retrySupport) {
    this.artifactUtils = artifactUtils;
    this.contextParameterProcessor = contextParameterProcessor;
    this.katoService = katoService;
    this.objectMapper = objectMapper;
    this.oortService = oortService;
    this.retrySupport = retrySupport;
  }

  @RequiredArgsConstructor
  @Getter
  public static class Result {
    private final ImmutableList<Map<Object, Object>> manifests;
    private final ImmutableList<Artifact> requiredArtifacts;
    private final ImmutableList<Artifact> optionalArtifacts;
  }

  /**
   * Resolves manifests and associated artifacts as a {@link Result}. Handles determining the input
   * source of a manifest, downloading the manifest if it is an artifact, optionally processing it
   * for SpEL, and composing the required and optional artifacts associated with the manifest.
   *
   * @param stage The stage to consider when resolving manifests and artifacts.
   * @param context The stage-specific manifest context to consider when resolving manifests and
   *     artifacts.
   * @return The result of the manifest and artifact resolution {@link Result}.
   */
  public Result evaluate(Stage stage, ManifestContext context) {
    return new Result(
        getManifests(stage, context),
        getRequiredArtifacts(stage, context),
        getOptionalArtifacts(stage));
  }

  private ImmutableList<Map<Object, Object>> getManifests(Stage stage, ManifestContext context) {
    if (ManifestContext.Source.Artifact.equals(context.getSource())) {
      return getManifestsFromArtifact(stage, context);
    }
    return getManifestsFromText(context);
  }

  private ImmutableList<Map<Object, Object>> getManifestsFromText(ManifestContext context) {
    List<Map<Object, Object>> textManifests =
        Optional.ofNullable(context.getManifests())
            .orElseThrow(() -> new IllegalArgumentException("No text manifest was specified."));
    return ImmutableList.copyOf(textManifests);
  }

  private ImmutableList<Map<Object, Object>> getManifestsFromArtifact(
      Stage stage, ManifestContext context) {
    Artifact manifestArtifact = getManifestArtifact(stage, context);

    Iterable<Object> rawManifests =
        retrySupport.retry(
            fetchAndParseManifestYaml(manifestArtifact), 10, Duration.ofMillis(200), true);

    ImmutableList<Map<Object, Object>> unevaluatedManifests =
        StreamSupport.stream(rawManifests.spliterator(), false)
            .map(this::coerceManifestToList)
            .flatMap(Collection::stream)
            .collect(toImmutableList());

    if (context.isSkipExpressionEvaluation()) {
      return unevaluatedManifests;
    }

    return getSpelEvaluatedManifests(unevaluatedManifests, stage);
  }

  private ImmutableList<Map<Object, Object>> coerceManifestToList(Object manifest) {
    if (manifest instanceof List) {
      return ImmutableList.copyOf(
          objectMapper.convertValue(manifest, new TypeReference<List<Map<Object, Object>>>() {}));
    }
    Map<Object, Object> singleManifest =
        (Map<Object, Object>) objectMapper.convertValue(manifest, Map.class);
    return ImmutableList.of(singleManifest);
  }

  private Supplier<Iterable<Object>> fetchAndParseManifestYaml(Artifact manifestArtifact) {
    return () -> {
      Response manifestText = oortService.fetchArtifact(manifestArtifact);
      try (InputStream body = manifestText.getBody().in()) {
        return yamlParser.get().loadAll(body);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
  }

  private Artifact getManifestArtifact(Stage stage, ManifestContext context) {
    Artifact manifestArtifact =
        Optional.ofNullable(
                artifactUtils.getBoundArtifactForStage(
                    stage, context.getManifestArtifactId(), context.getManifestArtifact()))
            .orElseThrow(() -> new IllegalArgumentException("No manifest artifact was specified."));

    // Once the legacy artifacts feature is removed, all trigger expected artifacts will be
    // required to define an account up front.
    if (context.getManifestArtifactAccount() != null) {
      manifestArtifact.setArtifactAccount(context.getManifestArtifactAccount());
    }

    if (manifestArtifact.getArtifactAccount() == null) {
      throw new IllegalArgumentException("No manifest artifact account was specified.");
    }

    return manifestArtifact;
  }

  private ImmutableList<Map<Object, Object>> getSpelEvaluatedManifests(
      ImmutableList<Map<Object, Object>> unevaluatedManifests, Stage stage) {
    Map<String, Object> manifestWrapper = new HashMap<>();
    manifestWrapper.put("manifests", unevaluatedManifests);

    manifestWrapper =
        contextParameterProcessor.process(
            manifestWrapper, contextParameterProcessor.buildExecutionContext(stage), true);

    if (manifestWrapper.containsKey(PipelineExpressionEvaluator.SUMMARY)) {
      throw new IllegalStateException(
          String.format(
              "Failure evaluating manifest expressions: %s",
              manifestWrapper.get(PipelineExpressionEvaluator.SUMMARY)));
    }

    List<Map<Object, Object>> evaluatedManifests =
        (List<Map<Object, Object>>) manifestWrapper.get("manifests");
    return ImmutableList.copyOf(evaluatedManifests);
  }

  private ImmutableList<Artifact> getRequiredArtifacts(Stage stage, ManifestContext context) {
    Stream<Artifact> requiredArtifactsFromId =
        Optional.ofNullable(context.getRequiredArtifactIds()).orElse(emptyList()).stream()
            .map(artifactId -> resolveRequiredArtifactById(stage, artifactId));

    Stream<Artifact> requiredArtifacts =
        Optional.ofNullable(context.getRequiredArtifacts()).orElse(emptyList()).stream()
            .map(artifact -> resolveRequiredArtifact(stage, artifact));

    return Streams.concat(requiredArtifactsFromId, requiredArtifacts).collect(toImmutableList());
  }

  private Artifact resolveRequiredArtifactById(Stage stage, String artifactId) {
    return Optional.ofNullable(artifactUtils.getBoundArtifactForId(stage, artifactId))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "No artifact with id %s could be found in the pipeline context.",
                        artifactId)));
  }

  private Artifact resolveRequiredArtifact(Stage stage, BindArtifact artifact) {
    return Optional.ofNullable(
            artifactUtils.getBoundArtifactForStage(
                stage, artifact.getExpectedArtifactId(), artifact.getArtifact()))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "No artifact with id %s could be found in the pipeline context.",
                        artifact.getExpectedArtifactId())));
  }

  private ImmutableList<Artifact> getOptionalArtifacts(Stage stage) {
    return ImmutableList.copyOf(artifactUtils.getArtifacts(stage));
  }

  // todo(mneterval): remove this task-specific logic from this class
  public TaskResult buildTaskResult(String taskName, Stage stage, Map<String, Object> task) {
    Map<String, Map> operation =
        new ImmutableMap.Builder<String, Map>().put(taskName, task).build();

    TaskId taskId =
        katoService
            .requestOperations(getCloudProvider(stage), Collections.singletonList(operation))
            .toBlocking()
            .first();

    Map<String, Object> outputs =
        new ImmutableMap.Builder<String, Object>()
            .put("kato.result.expected", true)
            .put("kato.last.task.id", taskId)
            .put("deploy.account.name", getCredentials(stage))
            .build();

    return TaskResult.builder(ExecutionStatus.SUCCEEDED).context(outputs).build();
  }
}
