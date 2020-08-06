/*
 * Copyright 2020 Google, Inc.
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
 *
 */

package com.netflix.spinnaker.orca.clouddriver.pipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode;
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode.GraphType;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType;
import com.netflix.spinnaker.orca.api.pipeline.models.PipelineExecution;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.utils.OortHelper;
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class DeployManifestStageTest {
  private DeployManifestStage stage;

  @BeforeEach
  void setup() {
    OortHelper oortHelper = new OortHelper();
    stage = new DeployManifestStage(oortHelper);
  }

  @Test
  void success() {
    PipelineExecution pipelineExecution =
        new PipelineExecutionImpl(ExecutionType.PIPELINE, "kubernetes-application");
    Map<String, Object> context = new HashMap<>();
    context.put("key", "value");
    StageExecution stageExecution =
        new StageExecutionImpl(
            pipelineExecution, DeployManifestStage.PIPELINE_CONFIG_TYPE, context);
    TaskNode.Builder taskNodeBuilder = new TaskNode.Builder(GraphType.FULL);
    stage.taskGraph(stageExecution, taskNodeBuilder);
    assertThat(stageExecution.getContext().get("key")).isEqualTo("value");
  }
}
