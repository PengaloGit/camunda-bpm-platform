/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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
package org.camunda.bpm.engine.test.api.history;

import org.assertj.core.api.Assertions;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.api.AbstractAsyncOperationsTest;
import org.hamcrest.CoreMatchers;
import org.hamcrest.collection.IsIn;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author Askar Akhmerov
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
public class HistoryServiceAsyncOperationsTest extends AbstractAsyncOperationsTest {

  protected static final String TEST_REASON = "test reason";

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected TaskService taskService;
  protected List<String> historicProcessInstances;
  protected int defaultBatchJobsPerSeed;
  protected int defaultInvocationsPerBatchJob;


  @Before
  public void initServices() {
    super.initServices();
    taskService = engineRule.getTaskService();

    // save defaults
    ProcessEngineConfigurationImpl configuration = engineRule.getProcessEngineConfiguration();
    defaultBatchJobsPerSeed = configuration.getBatchJobsPerSeed();
    defaultInvocationsPerBatchJob = configuration.getInvocationsPerBatchJob();

    prepareData();
  }

  public void prepareData() {
    testRule.deploy("org/camunda/bpm/engine/test/api/oneTaskProcess.bpmn20.xml");
    startTestProcesses(2);

    for (Task activeTask : taskService.createTaskQuery().list()) {
      taskService.complete(activeTask.getId());
    }

    historicProcessInstances = new ArrayList<>();
    for (HistoricProcessInstance pi : historyService.createHistoricProcessInstanceQuery().list()) {
      historicProcessInstances.add(pi.getId());
    }
  }

  @After
  public void cleanBatch() {
    Batch batch = managementService.createBatchQuery().singleResult();
    if (batch != null) {
      managementService.deleteBatch(batch.getId(), true);
    }

    HistoricBatch historicBatch = historyService.createHistoricBatchQuery().singleResult();
    if (historicBatch != null) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
  }

  @After
  public void restoreEngineSettings() {
    ProcessEngineConfigurationImpl configuration = engineRule.getProcessEngineConfiguration();
    configuration.setBatchJobsPerSeed(defaultBatchJobsPerSeed);
    configuration.setInvocationsPerBatchJob(defaultInvocationsPerBatchJob);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithList() throws Exception {
    //when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(historicProcessInstances, TEST_REASON);

    executeSeedJob(batch);
    List<Exception> exceptions = executeBatchJobs(batch);

    // then
    assertThat(exceptions.size(), is(0));
    assertNoHistoryForTasks();
    assertHistoricBatchExists(testRule);
    assertAllHistoricProcessInstancesAreDeleted();
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithListInDifferentDeployments() throws Exception {
    // given a second deployment
    prepareData();
    ProcessDefinitionQuery definitionQuery = engineRule.getRepositoryService().createProcessDefinitionQuery();
    String firstDeploymentId = definitionQuery.processDefinitionVersion(1).singleResult().getDeploymentId();
    String secondDeploymentId = definitionQuery.processDefinitionVersion(2).singleResult().getDeploymentId();

    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);

    // when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(historicProcessInstances, TEST_REASON);
    executeSeedJob(batch);
    // then batch jobs with different deployment ids exist
    List<Job> batchJobs = managementService.createJobQuery().jobDefinitionId(batch.getBatchJobDefinitionId()).list();
    assertThat(batchJobs.size(), is(2));
    assertThat(batchJobs.get(0).getDeploymentId(), IsIn.isOneOf(firstDeploymentId, secondDeploymentId));
    assertThat(batchJobs.get(1).getDeploymentId(), IsIn.isOneOf(firstDeploymentId, secondDeploymentId));
    assertThat(batchJobs.get(0).getDeploymentId(), is(not(batchJobs.get(1).getDeploymentId())));

    // when the batch jobs for the first deployment are executed
    assertThat(getHistoricProcessInstanceCountByDeploymentId(firstDeploymentId), is(2L));
    getJobIdsByDeployment(batchJobs, firstDeploymentId).forEach(managementService::executeJob);
    // then the historic process instances related to the first deployment should be deleted
    assertThat(getHistoricProcessInstanceCountByDeploymentId(firstDeploymentId), is(0L));
    // and historic process instances related to the second deployment should not be deleted
    assertThat(getHistoricProcessInstanceCountByDeploymentId(secondDeploymentId), is(2L));

    // when the remaining batch jobs are executed
    getJobIdsByDeployment(batchJobs, secondDeploymentId).forEach(managementService::executeJob);
    // then
    assertNoHistoryForTasks();
    assertHistoricBatchExists(testRule);
    assertAllHistoricProcessInstancesAreDeleted();
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithEmptyList() throws Exception {
    //expect
    thrown.expect(ProcessEngineException.class);

    //when
    historyService.deleteHistoricProcessInstancesAsync(new ArrayList<String>(), TEST_REASON);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithFake() throws Exception {
    //given
    ArrayList<String> processInstanceIds = new ArrayList<>();
    processInstanceIds.add(historicProcessInstances.get(0));
    processInstanceIds.add("aFakeId");

    //when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(processInstanceIds, TEST_REASON);
    executeSeedJob(batch);
    List<Exception> exceptions = executeBatchJobs(batch);

    //then
    assertThat(exceptions.size(), is(0));
    assertHistoricBatchExists(testRule);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithQueryAndList() throws Exception {
    //given
    HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(historicProcessInstances.get(0));
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(
        historicProcessInstances.subList(1, historicProcessInstances.size()), query, TEST_REASON);
    executeSeedJob(batch);

    //when
    List<Exception> exceptions = executeBatchJobs(batch);

    // then
    assertThat(exceptions.size(), is(0));
    assertNoHistoryForTasks();
    assertHistoricBatchExists(testRule);
    assertAllHistoricProcessInstancesAreDeleted();
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithQuery() throws Exception {
    //given
    HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
        .processInstanceIds(new HashSet<>(historicProcessInstances));
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(query, TEST_REASON);
    executeSeedJob(batch);

    //when
    List<Exception> exceptions = executeBatchJobs(batch);

    // then
    assertThat(exceptions.size(), is(0));
    assertNoHistoryForTasks();
    assertHistoricBatchExists(testRule);
    assertAllHistoricProcessInstancesAreDeleted();
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithEmptyQuery() throws Exception {
    //expect
    thrown.expect(ProcessEngineException.class);
    //given
    HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery().unfinished();
    //when
    historyService.deleteHistoricProcessInstancesAsync(query, TEST_REASON);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithNonExistingIDAsQuery() throws Exception {
    //given
    ArrayList<String> processInstanceIds = new ArrayList<>();
    processInstanceIds.add(historicProcessInstances.get(0));
    processInstanceIds.add("aFakeId");
    HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
        .processInstanceIds(new HashSet<>(processInstanceIds));

    //when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(query, TEST_REASON);
    executeSeedJob(batch);
    executeBatchJobs(batch);

    //then
    assertHistoricBatchExists(testRule);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithoutDeleteReason() throws Exception {
    //when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(historicProcessInstances, null);
    executeSeedJob(batch);
    List<Exception> exceptions = executeBatchJobs(batch);

    //then
    assertThat(exceptions.size(), is(0));
    assertNoHistoryForTasks();
    assertHistoricBatchExists(testRule);
    assertAllHistoricProcessInstancesAreDeleted();
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithNullList() throws Exception {
    thrown.expect(ProcessEngineException.class);
    historyService.deleteHistoricProcessInstancesAsync((List<String>) null, TEST_REASON);
  }

  @Test
  public void testDeleteHistoryProcessInstancesAsyncWithNullQuery() throws Exception {
    thrown.expect(ProcessEngineException.class);
    historyService.deleteHistoricProcessInstancesAsync((HistoricProcessInstanceQuery) null, TEST_REASON);
  }

  @Test
  public void shouldSetInvocationsPerBatchType() {
    // given
    engineRule.getProcessEngineConfiguration()
        .getInvocationsPerBatchJobByBatchType()
        .put(Batch.TYPE_HISTORIC_PROCESS_INSTANCE_DELETION, 42);

    //when
    Batch batch = historyService.deleteHistoricProcessInstancesAsync(historicProcessInstances, TEST_REASON);

    // then
    Assertions.assertThat(batch.getInvocationsPerBatchJob()).isEqualTo(42);

    // clear
    engineRule.getProcessEngineConfiguration()
        .setInvocationsPerBatchJobByBatchType(new HashMap<>());
  }

  protected long getHistoricProcessInstanceCountByDeploymentId(String deploymentId) {
    // fetch process definitions of the deployment
    Set<String> processDefinitionIds = engineRule.getRepositoryService().createProcessDefinitionQuery()
        .deploymentId(deploymentId).list().stream()
        .map(ProcessDefinition::getId)
        .collect(Collectors.toSet());
    // return historic instances of the deployed definitions
    return historyService.createHistoricProcessInstanceQuery().list().stream()
        .filter(hpi -> processDefinitionIds.contains(hpi.getProcessDefinitionId()))
        .map(HistoricProcessInstance::getId)
        .count();
  }

  protected void assertNoHistoryForTasks() {
    if (!testRule.isHistoryLevelNone()) {
      Assert.assertThat(historyService.createHistoricTaskInstanceQuery().count(), CoreMatchers.is(0L));
    }
  }

  protected void assertAllHistoricProcessInstancesAreDeleted() {
    assertThat(historyService.createHistoricProcessInstanceQuery().count(), is(0L));
  }

}
