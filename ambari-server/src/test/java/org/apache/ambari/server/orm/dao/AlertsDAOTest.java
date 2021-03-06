/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.orm.dao;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.ambari.server.controller.AlertHistoryRequest;
import org.apache.ambari.server.controller.internal.AlertHistoryResourceProvider;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.events.listeners.AlertMaintenanceModeListener;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.OrmTestHelper;
import org.apache.ambari.server.orm.entities.AlertCurrentEntity;
import org.apache.ambari.server.orm.entities.AlertDefinitionEntity;
import org.apache.ambari.server.orm.entities.AlertHistoryEntity;
import org.apache.ambari.server.state.AlertState;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentFactory;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostFactory;
import org.apache.ambari.server.state.ServiceFactory;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.alert.Scope;
import org.apache.ambari.server.state.alert.SourceType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.eventbus.EventBus;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;

/**
 * Tests {@link AlertsDAO}.
 */
public class AlertsDAOTest {

  final static String HOSTNAME = "c6401.ambari.apache.org";
  final static Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

  private Clusters m_clusters;
  private Long m_clusterId;
  private Injector m_injector;
  private OrmTestHelper m_helper;
  private AlertsDAO m_dao;
  private AlertDefinitionDAO m_definitionDao;

  private ServiceFactory m_serviceFactory;
  private ServiceComponentFactory m_componentFactory;
  private ServiceComponentHostFactory m_schFactory;
  private AmbariEventPublisher m_eventPublisher;

  /**
   *
   */
  @Before
  public void setup() throws Exception {
    m_injector = Guice.createInjector(new InMemoryDefaultTestModule());
    m_injector.getInstance(GuiceJpaInitializer.class);
    m_helper = m_injector.getInstance(OrmTestHelper.class);
    m_clusterId = m_helper.createCluster();
    m_dao = m_injector.getInstance(AlertsDAO.class);
    m_definitionDao = m_injector.getInstance(AlertDefinitionDAO.class);
    m_serviceFactory = m_injector.getInstance(ServiceFactory.class);
    m_componentFactory = m_injector.getInstance(ServiceComponentFactory.class);
    m_schFactory = m_injector.getInstance(ServiceComponentHostFactory.class);
    m_eventPublisher = m_injector.getInstance(AmbariEventPublisher.class);
    m_clusters = m_injector.getInstance(Clusters.class);

    // register a listener
    EventBus synchronizedBus = new EventBus();
    synchronizedBus.register(m_injector.getInstance(AlertMaintenanceModeListener.class));

    // !!! need a synchronous op for testing
    Field field = AmbariEventPublisher.class.getDeclaredField("m_eventBus");
    field.setAccessible(true);
    field.set(m_eventPublisher, synchronizedBus);

    // create 5 definitions
    for (int i = 0; i < 5; i++) {
      AlertDefinitionEntity definition = new AlertDefinitionEntity();
      definition.setDefinitionName("Alert Definition " + i);
      definition.setServiceName("Service " + i);
      definition.setComponentName(null);
      definition.setClusterId(m_clusterId);
      definition.setHash(UUID.randomUUID().toString());
      definition.setScheduleInterval(Integer.valueOf(60));
      definition.setScope(Scope.SERVICE);
      definition.setSource("{\"type\" : \"SCRIPT\"}");
      definition.setSourceType(SourceType.SCRIPT);
      m_definitionDao.create(definition);
    }

    List<AlertDefinitionEntity> definitions = m_definitionDao.findAll();
    assertNotNull(definitions);
    assertEquals(5, definitions.size());

    // create 10 historical alerts for each definition, 8 OK and 2 CRIT
    calendar.clear();
    calendar.set(2014, Calendar.JANUARY, 1);

    for (AlertDefinitionEntity definition : definitions) {
      for (int i = 0; i < 10; i++) {
        AlertHistoryEntity history = new AlertHistoryEntity();
        history.setServiceName(definition.getServiceName());
        history.setClusterId(m_clusterId);
        history.setAlertDefinition(definition);
        history.setAlertLabel(definition.getDefinitionName() + " " + i);
        history.setAlertText(definition.getDefinitionName() + " " + i);
        history.setAlertTimestamp(calendar.getTimeInMillis());
        history.setHostName("h1");

        history.setAlertState(AlertState.OK);
        if (i == 0 || i == 5) {
          history.setAlertState(AlertState.CRITICAL);
        }

        // increase the days for each
        calendar.add(Calendar.DATE, 1);

        m_dao.create(history);
      }
    }

    // for each definition, create a current alert
    for (AlertDefinitionEntity definition : definitions) {
      List<AlertHistoryEntity> alerts = m_dao.findAll();
      AlertHistoryEntity history = null;
      for (AlertHistoryEntity alert : alerts) {
        if (definition.equals(alert.getAlertDefinition())) {
          history = alert;
        }
      }

      assertNotNull(history);

      AlertCurrentEntity current = new AlertCurrentEntity();
      current.setAlertHistory(history);
      current.setLatestTimestamp(new Date().getTime());
      current.setOriginalTimestamp(new Date().getTime() - 10800000);
      current.setMaintenanceState(MaintenanceState.OFF);
      m_dao.create(current);
    }
  }

  /**
   *
   */
  @After
  public void teardown() {
    m_injector.getInstance(PersistService.class).stop();
    m_injector = null;
  }


  /**
   *
   */
  @Test
  public void testFindAll() {
    List<AlertHistoryEntity> alerts = m_dao.findAll(m_clusterId);
    assertNotNull(alerts);
    assertEquals(50, alerts.size());
  }

  /**
   *
   */
  @Test
  public void testFindAllCurrent() {
    List<AlertCurrentEntity> currentAlerts = m_dao.findCurrent();
    assertNotNull(currentAlerts);
    assertEquals(5, currentAlerts.size());
  }

  /**
   *
   */
  @Test
  public void testFindCurrentByService() {
    List<AlertCurrentEntity> currentAlerts = m_dao.findCurrent();
    AlertCurrentEntity current = currentAlerts.get(0);
    AlertHistoryEntity history = current.getAlertHistory();

    assertNotNull(history);

    currentAlerts = m_dao.findCurrentByService(m_clusterId,
        history.getServiceName());

    assertNotNull(currentAlerts);
    assertEquals(1, currentAlerts.size());

    currentAlerts = m_dao.findCurrentByService(m_clusterId, "foo");

    assertNotNull(currentAlerts);
    assertEquals(0, currentAlerts.size());
  }

  /**
   * Test looking up current by a host name.
   */
  @Test
  public void testFindCurrentByHost() {
    // create a host
    AlertDefinitionEntity hostDef = new AlertDefinitionEntity();
    hostDef.setDefinitionName("Host Alert Definition ");
    hostDef.setServiceName("HostService");
    hostDef.setComponentName(null);
    hostDef.setClusterId(m_clusterId);
    hostDef.setHash(UUID.randomUUID().toString());
    hostDef.setScheduleInterval(Integer.valueOf(60));
    hostDef.setScope(Scope.HOST);
    hostDef.setSource("{\"type\" : \"SCRIPT\"}");
    hostDef.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(hostDef);

    // history for the definition
    AlertHistoryEntity history = new AlertHistoryEntity();
    history.setServiceName(hostDef.getServiceName());
    history.setClusterId(m_clusterId);
    history.setAlertDefinition(hostDef);
    history.setAlertLabel(hostDef.getDefinitionName());
    history.setAlertText(hostDef.getDefinitionName());
    history.setAlertTimestamp(Long.valueOf(1L));
    history.setHostName("h2");
    history.setAlertState(AlertState.OK);

    // current for the history
    AlertCurrentEntity current = new AlertCurrentEntity();
    current.setOriginalTimestamp(1L);
    current.setLatestTimestamp(2L);
    current.setAlertHistory(history);
    m_dao.create(current);

    List<AlertCurrentEntity> currentAlerts = m_dao.findCurrentByHost(
        m_clusterId, history.getHostName());

    assertNotNull(currentAlerts);
    assertEquals(1, currentAlerts.size());

    currentAlerts = m_dao.findCurrentByHost(m_clusterId, "foo");

    assertNotNull(currentAlerts);
    assertEquals(0, currentAlerts.size());
  }

  /**
   *
   */
  @Test
  public void testFindByState() {
    List<AlertState> allStates = new ArrayList<AlertState>();
    allStates.add(AlertState.OK);
    allStates.add(AlertState.WARNING);
    allStates.add(AlertState.CRITICAL);

    List<AlertHistoryEntity> history = m_dao.findAll(m_clusterId, allStates);
    assertNotNull(history);
    assertEquals(50, history.size());

    history = m_dao.findAll(m_clusterId,
        Collections.singletonList(AlertState.OK));
    assertNotNull(history);
    assertEquals(40, history.size());

    history = m_dao.findAll(m_clusterId,
        Collections.singletonList(AlertState.CRITICAL));
    assertNotNull(history);
    assertEquals(10, history.size());

    history = m_dao.findAll(m_clusterId,
        Collections.singletonList(AlertState.WARNING));
    assertNotNull(history);
    assertEquals(0, history.size());
  }

  /**
   *
   */
  @Test
  public void testFindByDate() {
    calendar.clear();
    calendar.set(2014, Calendar.JANUARY, 1);

    // on or after 1/1/2014
    List<AlertHistoryEntity> history = m_dao.findAll(m_clusterId,
        calendar.getTime(), null);

    assertNotNull(history);
    assertEquals(50, history.size());

    // on or before 1/1/2014
    history = m_dao.findAll(m_clusterId, null, calendar.getTime());
    assertNotNull(history);
    assertEquals(1, history.size());

    // between 1/5 and 1/10
    calendar.set(2014, Calendar.JANUARY, 5);
    Date startDate = calendar.getTime();

    calendar.set(2014, Calendar.JANUARY, 10);
    Date endDate = calendar.getTime();

    history = m_dao.findAll(m_clusterId, startDate, endDate);
    assertNotNull(history);
    assertEquals(6, history.size());

    // after 3/1
    calendar.set(2014, Calendar.MARCH, 5);
    history = m_dao.findAll(m_clusterId, calendar.getTime(), null);
    assertNotNull(history);
    assertEquals(0, history.size());

    history = m_dao.findAll(m_clusterId, endDate, startDate);
    assertNotNull(history);
    assertEquals(0, history.size());
  }

  @Test
  public void testFindCurrentByHostAndName() throws Exception {
    AlertCurrentEntity entity = m_dao.findCurrentByHostAndName(
        m_clusterId.longValue(), "h2", "Alert Definition 1");
    assertNull(entity);

    entity = m_dao.findCurrentByHostAndName(m_clusterId.longValue(), "h1",
        "Alert Definition 1");

    assertNotNull(entity);
    assertNotNull(entity.getAlertHistory());
    assertNotNull(entity.getAlertHistory().getAlertDefinition());
  }

  /**
   *
   */
  @Test
  public void testFindCurrentSummary() throws Exception {
    AlertSummaryDTO summary = m_dao.findCurrentCounts(m_clusterId.longValue(),
        null, null);
    assertEquals(5, summary.getOkCount());

    AlertHistoryEntity h1 = m_dao.findCurrentByCluster(m_clusterId.longValue()).get(
        2).getAlertHistory();
    AlertHistoryEntity h2 = m_dao.findCurrentByCluster(m_clusterId.longValue()).get(
        3).getAlertHistory();
    AlertHistoryEntity h3 = m_dao.findCurrentByCluster(m_clusterId.longValue()).get(
        4).getAlertHistory();
    h1.setAlertState(AlertState.WARNING);
    m_dao.merge(h1);
    h2.setAlertState(AlertState.CRITICAL);
    m_dao.merge(h2);
    h3.setAlertState(AlertState.UNKNOWN);
    m_dao.merge(h3);

    int ok = 0;
    int warn = 0;
    int crit = 0;
    int unk = 0;

    for (AlertCurrentEntity h : m_dao.findCurrentByCluster(m_clusterId.longValue())) {
      switch (h.getAlertHistory().getAlertState()) {
        case CRITICAL:
          crit++;
          break;
        case OK:
          ok++;
          break;
        case UNKNOWN:
          unk++;
          break;
        default:
          warn++;
          break;
      }

    }

    summary = m_dao.findCurrentCounts(m_clusterId.longValue(), null, null);
    // !!! db-to-db compare
    assertEquals(ok, summary.getOkCount());
    assertEquals(warn, summary.getWarningCount());
    assertEquals(crit, summary.getCriticalCount());
    assertEquals(unk, summary.getCriticalCount());

    // !!! expected
    assertEquals(2, summary.getOkCount());
    assertEquals(1, summary.getWarningCount());
    assertEquals(1, summary.getCriticalCount());
    assertEquals(1, summary.getCriticalCount());

    summary = m_dao.findCurrentCounts(m_clusterId.longValue(), "Service 0",
        null);
    assertEquals(1, summary.getOkCount());
    assertEquals(0, summary.getWarningCount());
    assertEquals(0, summary.getCriticalCount());
    assertEquals(0, summary.getCriticalCount());

    summary = m_dao.findCurrentCounts(m_clusterId.longValue(), null, "h1");
    assertEquals(2, summary.getOkCount());
    assertEquals(1, summary.getWarningCount());
    assertEquals(1, summary.getCriticalCount());
    assertEquals(1, summary.getCriticalCount());

    summary = m_dao.findCurrentCounts(m_clusterId.longValue(), "foo", null);
    assertEquals(0, summary.getOkCount());
    assertEquals(0, summary.getWarningCount());
    assertEquals(0, summary.getCriticalCount());
    assertEquals(0, summary.getCriticalCount());
  }

  @Test
  public void testFindAggregates() throws Exception {
    // definition
    AlertDefinitionEntity definition = new AlertDefinitionEntity();
    definition.setDefinitionName("many_per_cluster");
    definition.setServiceName("ServiceName");
    definition.setComponentName(null);
    definition.setClusterId(m_clusterId);
    definition.setHash(UUID.randomUUID().toString());
    definition.setScheduleInterval(Integer.valueOf(60));
    definition.setScope(Scope.SERVICE);
    definition.setSource("{\"type\" : \"SCRIPT\"}");
    definition.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(definition);

    // history record #1 and current
    AlertHistoryEntity history = new AlertHistoryEntity();
    history.setAlertDefinition(definition);
    history.setAlertInstance(null);
    history.setAlertLabel("");
    history.setAlertState(AlertState.OK);
    history.setAlertText("");
    history.setAlertTimestamp(Long.valueOf(1L));
    history.setClusterId(m_clusterId);
    history.setComponentName("");
    history.setHostName("h1");
    history.setServiceName("ServiceName");

    AlertCurrentEntity current = new AlertCurrentEntity();
    current.setAlertHistory(history);
    current.setLatestTimestamp(Long.valueOf(1L));
    current.setOriginalTimestamp(Long.valueOf(1L));
    m_dao.merge(current);

    // history record #2 and current
    history = new AlertHistoryEntity();
    history.setAlertDefinition(definition);
    history.setAlertInstance(null);
    history.setAlertLabel("");
    history.setAlertState(AlertState.OK);
    history.setAlertText("");
    history.setAlertTimestamp(Long.valueOf(1L));
    history.setClusterId(m_clusterId);
    history.setComponentName("");
    history.setHostName("h2");
    history.setServiceName("ServiceName");

    current = new AlertCurrentEntity();
    current.setAlertHistory(history);
    current.setLatestTimestamp(Long.valueOf(1L));
    current.setOriginalTimestamp(Long.valueOf(1L));
    m_dao.merge(current);

    AlertSummaryDTO summary = m_dao.findAggregateCounts(
        m_clusterId.longValue(), "many_per_cluster");
    assertEquals(2, summary.getOkCount());
    assertEquals(0, summary.getWarningCount());
    assertEquals(0, summary.getCriticalCount());
    assertEquals(0, summary.getUnknownCount());

    AlertCurrentEntity c = m_dao.findCurrentByHostAndName(
        m_clusterId.longValue(),
        "h2", "many_per_cluster");
    AlertHistoryEntity h = c.getAlertHistory();
    h.setAlertState(AlertState.CRITICAL);
    m_dao.merge(h);

    summary = m_dao.findAggregateCounts(m_clusterId.longValue(),
        "many_per_cluster");
    assertEquals(2, summary.getOkCount());
    assertEquals(0, summary.getWarningCount());
    assertEquals(1, summary.getCriticalCount());
    assertEquals(0, summary.getUnknownCount());

    summary = m_dao.findAggregateCounts(m_clusterId.longValue(), "foo");
    assertEquals(0, summary.getOkCount());
    assertEquals(0, summary.getWarningCount());
    assertEquals(0, summary.getCriticalCount());
    assertEquals(0, summary.getUnknownCount());
  }

  /**
   * Tests <a
   * href="https://bugs.eclipse.org/bugs/show_bug.cgi?id=398067">https:/
   * /bugs.eclipse.org/bugs/show_bug.cgi?id=398067</a> which causes an inner
   * entity to be stale.
   */
  @Test
  public void testJPAInnerEntityStaleness() {
    List<AlertCurrentEntity> currents = m_dao.findCurrent();
    AlertCurrentEntity current = currents.get(0);
    AlertHistoryEntity oldHistory = current.getAlertHistory();

    AlertHistoryEntity newHistory = new AlertHistoryEntity();
    newHistory.setAlertDefinition(oldHistory.getAlertDefinition());
    newHistory.setAlertInstance(oldHistory.getAlertInstance());
    newHistory.setAlertLabel(oldHistory.getAlertLabel());

    if (oldHistory.getAlertState() == AlertState.OK) {
      newHistory.setAlertState(AlertState.CRITICAL);
    } else {
      newHistory.setAlertState(AlertState.OK);
    }

    newHistory.setAlertText("New History");
    newHistory.setClusterId(oldHistory.getClusterId());
    newHistory.setAlertTimestamp(System.currentTimeMillis());
    newHistory.setComponentName(oldHistory.getComponentName());
    newHistory.setHostName(oldHistory.getHostName());
    newHistory.setServiceName(oldHistory.getServiceName());

    m_dao.create(newHistory);

    assertTrue(newHistory.getAlertId().longValue() != oldHistory.getAlertId().longValue());

    current.setAlertHistory(newHistory);
    m_dao.merge(current);

    AlertCurrentEntity newCurrent = m_dao.findCurrentByHostAndName(
        newHistory.getClusterId(),
        newHistory.getHostName(),
        newHistory.getAlertDefinition().getDefinitionName());

    assertEquals(newHistory.getAlertId(),
        newCurrent.getAlertHistory().getAlertId());

    assertEquals(newHistory.getAlertState(),
        newCurrent.getAlertHistory().getAlertState());
  }

  /**
   * Tests that maintenance mode is set correctly on notices.
   *
   * @throws Exception
   */
  @Test
  public void testMaintenanceMode() throws Exception {
    Cluster cluster = initializeNewCluster();

    List<AlertCurrentEntity> currents = m_dao.findCurrent();
    for (AlertCurrentEntity current : currents) {
      m_dao.remove(current);
    }

    // create some definitions
    AlertDefinitionEntity namenode = new AlertDefinitionEntity();
    namenode.setDefinitionName("NAMENODE");
    namenode.setServiceName("HDFS");
    namenode.setComponentName("NAMENODE");
    namenode.setClusterId(cluster.getClusterId());
    namenode.setHash(UUID.randomUUID().toString());
    namenode.setScheduleInterval(Integer.valueOf(60));
    namenode.setScope(Scope.ANY);
    namenode.setSource("{\"type\" : \"SCRIPT\"}");
    namenode.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(namenode);

    AlertDefinitionEntity datanode = new AlertDefinitionEntity();
    datanode.setDefinitionName("DATANODE");
    datanode.setServiceName("HDFS");
    datanode.setComponentName("DATANODE");
    datanode.setClusterId(cluster.getClusterId());
    datanode.setHash(UUID.randomUUID().toString());
    datanode.setScheduleInterval(Integer.valueOf(60));
    datanode.setScope(Scope.HOST);
    datanode.setSource("{\"type\" : \"SCRIPT\"}");
    datanode.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(datanode);

    AlertDefinitionEntity aggregate = new AlertDefinitionEntity();
    aggregate.setDefinitionName("DATANODE_UP");
    aggregate.setServiceName("HDFS");
    aggregate.setComponentName(null);
    aggregate.setClusterId(cluster.getClusterId());
    aggregate.setHash(UUID.randomUUID().toString());
    aggregate.setScheduleInterval(Integer.valueOf(60));
    aggregate.setScope(Scope.SERVICE);
    aggregate.setSource("{\"type\" : \"SCRIPT\"}");
    aggregate.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(aggregate);

    // create some history
    AlertHistoryEntity nnHistory = new AlertHistoryEntity();
    nnHistory.setAlertState(AlertState.OK);
    nnHistory.setServiceName(namenode.getServiceName());
    nnHistory.setComponentName(namenode.getComponentName());
    nnHistory.setClusterId(cluster.getClusterId());
    nnHistory.setAlertDefinition(namenode);
    nnHistory.setAlertLabel(namenode.getDefinitionName());
    nnHistory.setAlertText(namenode.getDefinitionName());
    nnHistory.setAlertTimestamp(calendar.getTimeInMillis());
    nnHistory.setHostName(HOSTNAME);
    m_dao.create(nnHistory);

    AlertCurrentEntity nnCurrent = new AlertCurrentEntity();
    nnCurrent.setAlertHistory(nnHistory);
    nnCurrent.setLatestText(nnHistory.getAlertText());
    nnCurrent.setMaintenanceState(MaintenanceState.OFF);
    nnCurrent.setOriginalTimestamp(System.currentTimeMillis());
    nnCurrent.setLatestTimestamp(System.currentTimeMillis());
    m_dao.create(nnCurrent);

    AlertHistoryEntity dnHistory = new AlertHistoryEntity();
    dnHistory.setAlertState(AlertState.WARNING);
    dnHistory.setServiceName(datanode.getServiceName());
    dnHistory.setComponentName(datanode.getComponentName());
    dnHistory.setClusterId(cluster.getClusterId());
    dnHistory.setAlertDefinition(datanode);
    dnHistory.setAlertLabel(datanode.getDefinitionName());
    dnHistory.setAlertText(datanode.getDefinitionName());
    dnHistory.setAlertTimestamp(calendar.getTimeInMillis());
    dnHistory.setHostName(HOSTNAME);
    m_dao.create(dnHistory);

    AlertCurrentEntity dnCurrent = new AlertCurrentEntity();
    dnCurrent.setAlertHistory(dnHistory);
    dnCurrent.setLatestText(dnHistory.getAlertText());
    dnCurrent.setMaintenanceState(MaintenanceState.OFF);
    dnCurrent.setOriginalTimestamp(System.currentTimeMillis());
    dnCurrent.setLatestTimestamp(System.currentTimeMillis());
    m_dao.create(dnCurrent);

    AlertHistoryEntity aggregateHistory = new AlertHistoryEntity();
    aggregateHistory.setAlertState(AlertState.CRITICAL);
    aggregateHistory.setServiceName(aggregate.getServiceName());
    aggregateHistory.setComponentName(aggregate.getComponentName());
    aggregateHistory.setClusterId(cluster.getClusterId());
    aggregateHistory.setAlertDefinition(aggregate);
    aggregateHistory.setAlertLabel(aggregate.getDefinitionName());
    aggregateHistory.setAlertText(aggregate.getDefinitionName());
    aggregateHistory.setAlertTimestamp(calendar.getTimeInMillis());
    m_dao.create(aggregateHistory);

    AlertCurrentEntity aggregateCurrent = new AlertCurrentEntity();
    aggregateCurrent.setAlertHistory(aggregateHistory);
    aggregateCurrent.setLatestText(aggregateHistory.getAlertText());
    aggregateCurrent.setMaintenanceState(MaintenanceState.OFF);
    aggregateCurrent.setOriginalTimestamp(System.currentTimeMillis());
    aggregateCurrent.setLatestTimestamp(System.currentTimeMillis());
    m_dao.create(aggregateCurrent);

    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());

    for (AlertCurrentEntity current : currents) {
      assertEquals(MaintenanceState.OFF, current.getMaintenanceState());
    }

    // turn on HDFS MM
    Service hdfs = m_clusters.getClusterById(cluster.getClusterId()).getService(
        "HDFS");

    hdfs.setMaintenanceState(MaintenanceState.ON);

    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());
    for (AlertCurrentEntity current : currents) {
      assertEquals(MaintenanceState.ON, current.getMaintenanceState());
    }

    // turn HDFS MM off
    hdfs.setMaintenanceState(MaintenanceState.OFF);

    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());
    for (AlertCurrentEntity current : currents) {
      assertEquals(MaintenanceState.OFF, current.getMaintenanceState());
    }

    // turn on host MM
    Host host = m_clusters.getHost(HOSTNAME);
    host.setMaintenanceState(cluster.getClusterId(), MaintenanceState.ON);

    // only NAMENODE and DATANODE should be in MM; the aggregate should not
    // since the host is in MM
    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());
    for (AlertCurrentEntity current : currents) {
      if (current.getAlertHistory().getComponentName() != null) {
        assertEquals(MaintenanceState.ON, current.getMaintenanceState());
      } else {
        assertEquals(MaintenanceState.OFF, current.getMaintenanceState());
      }
    }

    // turn host MM off
    host.setMaintenanceState(cluster.getClusterId(), MaintenanceState.OFF);

    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());
    for (AlertCurrentEntity current : currents) {
      assertEquals(MaintenanceState.OFF, current.getMaintenanceState());
    }

    // turn a component MM on
    ServiceComponentHost nnComponent = null;
    List<ServiceComponentHost> schs = cluster.getServiceComponentHosts(HOSTNAME);
    for (ServiceComponentHost sch : schs) {
      if ("NAMENODE".equals(sch.getServiceComponentName())) {
        sch.setMaintenanceState(MaintenanceState.ON);
        nnComponent = sch;
      }
    }

    assertNotNull(nnComponent);

    currents = m_dao.findCurrent();
    assertEquals(3, currents.size());
    for (AlertCurrentEntity current : currents) {
      if ("NAMENODE".equals(current.getAlertHistory().getComponentName())) {
        assertEquals(MaintenanceState.ON, current.getMaintenanceState());
      } else {
        assertEquals(MaintenanceState.OFF, current.getMaintenanceState());
      }
    }
  }

  /**
   * Tests that the Ambari {@link Predicate} can be converted and submitted to
   * JPA correctly to return a restricted result set.
   *
   * @throws Exception
   */
  @Test
  public void testAlertHistoryPredicate() throws Exception {
    Cluster cluster = initializeNewCluster();

    // remove any definitions and start over
    List<AlertDefinitionEntity> definitions = m_definitionDao.findAll();
    for (AlertDefinitionEntity definition : definitions) {
      m_definitionDao.remove(definition);
    }

    // create some definitions
    AlertDefinitionEntity namenode = new AlertDefinitionEntity();
    namenode.setDefinitionName("NAMENODE");
    namenode.setServiceName("HDFS");
    namenode.setComponentName("NAMENODE");
    namenode.setClusterId(cluster.getClusterId());
    namenode.setHash(UUID.randomUUID().toString());
    namenode.setScheduleInterval(Integer.valueOf(60));
    namenode.setScope(Scope.ANY);
    namenode.setSource("{\"type\" : \"SCRIPT\"}");
    namenode.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(namenode);

    AlertDefinitionEntity datanode = new AlertDefinitionEntity();
    datanode.setDefinitionName("DATANODE");
    datanode.setServiceName("HDFS");
    datanode.setComponentName("DATANODE");
    datanode.setClusterId(cluster.getClusterId());
    datanode.setHash(UUID.randomUUID().toString());
    datanode.setScheduleInterval(Integer.valueOf(60));
    datanode.setScope(Scope.HOST);
    datanode.setSource("{\"type\" : \"SCRIPT\"}");
    datanode.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(datanode);

    AlertDefinitionEntity aggregate = new AlertDefinitionEntity();
    aggregate.setDefinitionName("YARN_AGGREGATE");
    aggregate.setServiceName("YARN");
    aggregate.setComponentName(null);
    aggregate.setClusterId(cluster.getClusterId());
    aggregate.setHash(UUID.randomUUID().toString());
    aggregate.setScheduleInterval(Integer.valueOf(60));
    aggregate.setScope(Scope.SERVICE);
    aggregate.setSource("{\"type\" : \"SCRIPT\"}");
    aggregate.setSourceType(SourceType.SCRIPT);
    m_definitionDao.create(aggregate);

    // create some history
    AlertHistoryEntity nnHistory = new AlertHistoryEntity();
    nnHistory.setAlertState(AlertState.OK);
    nnHistory.setServiceName(namenode.getServiceName());
    nnHistory.setComponentName(namenode.getComponentName());
    nnHistory.setClusterId(cluster.getClusterId());
    nnHistory.setAlertDefinition(namenode);
    nnHistory.setAlertLabel(namenode.getDefinitionName());
    nnHistory.setAlertText(namenode.getDefinitionName());
    nnHistory.setAlertTimestamp(calendar.getTimeInMillis());
    nnHistory.setHostName(HOSTNAME);
    m_dao.create(nnHistory);

    AlertHistoryEntity dnHistory = new AlertHistoryEntity();
    dnHistory.setAlertState(AlertState.WARNING);
    dnHistory.setServiceName(datanode.getServiceName());
    dnHistory.setComponentName(datanode.getComponentName());
    dnHistory.setClusterId(cluster.getClusterId());
    dnHistory.setAlertDefinition(datanode);
    dnHistory.setAlertLabel(datanode.getDefinitionName());
    dnHistory.setAlertText(datanode.getDefinitionName());
    dnHistory.setAlertTimestamp(calendar.getTimeInMillis());
    dnHistory.setHostName(HOSTNAME);
    m_dao.create(dnHistory);

    AlertHistoryEntity aggregateHistory = new AlertHistoryEntity();
    aggregateHistory.setAlertState(AlertState.CRITICAL);
    aggregateHistory.setServiceName(aggregate.getServiceName());
    aggregateHistory.setComponentName(aggregate.getComponentName());
    aggregateHistory.setClusterId(cluster.getClusterId());
    aggregateHistory.setAlertDefinition(aggregate);
    aggregateHistory.setAlertLabel(aggregate.getDefinitionName());
    aggregateHistory.setAlertText(aggregate.getDefinitionName());
    aggregateHistory.setAlertTimestamp(calendar.getTimeInMillis());
    m_dao.create(aggregateHistory);

    List<AlertHistoryEntity> histories = m_dao.findAll();
    assertEquals(3, histories.size());

    Predicate clusterPredicate = null;
    Predicate hdfsPredicate = null;
    Predicate yarnPredicate = null;
    Predicate clusterAndHdfsPredicate = null;
    Predicate clusterAndHdfsAndCriticalPredicate = null;
    Predicate hdfsAndCriticalOrWarningPredicate = null;
    Predicate alertNamePredicate = null;

    clusterPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_CLUSTER_NAME).equals("c1").toPredicate();

    AlertHistoryRequest request = new AlertHistoryRequest();

    request.Predicate = clusterPredicate;
    histories = m_dao.findAll(request);
    assertEquals(3, histories.size());

    hdfsPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("HDFS").toPredicate();

    yarnPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("YARN").toPredicate();

    clusterAndHdfsPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_CLUSTER_NAME).equals("c1").and().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("HDFS").toPredicate();

    clusterAndHdfsPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_CLUSTER_NAME).equals("c1").and().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("HDFS").toPredicate();

    clusterAndHdfsAndCriticalPredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_CLUSTER_NAME).equals("c1").and().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("HDFS").and().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_STATE).equals(
        AlertState.CRITICAL.name()).toPredicate();

    hdfsAndCriticalOrWarningPredicate = new PredicateBuilder().begin().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_SERVICE_NAME).equals("HDFS").and().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_STATE).equals(
        AlertState.CRITICAL.name()).end().or().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_STATE).equals(
        AlertState.WARNING.name()).toPredicate();

    alertNamePredicate = new PredicateBuilder().property(
        AlertHistoryResourceProvider.ALERT_HISTORY_DEFINITION_NAME).equals(
        "NAMENODE").toPredicate();

    request.Predicate = hdfsPredicate;
    histories = m_dao.findAll(request);
    assertEquals(2, histories.size());

    request.Predicate = yarnPredicate;
    histories = m_dao.findAll(request);
    assertEquals(1, histories.size());

    request.Predicate = clusterAndHdfsPredicate;
    histories = m_dao.findAll(request);
    assertEquals(2, histories.size());

    request.Predicate = clusterAndHdfsAndCriticalPredicate;
    histories = m_dao.findAll(request);
    assertEquals(0, histories.size());

    request.Predicate = hdfsAndCriticalOrWarningPredicate;
    histories = m_dao.findAll(request);
    assertEquals(1, histories.size());

    request.Predicate = alertNamePredicate;
    histories = m_dao.findAll(request);
    assertEquals(1, histories.size());
  }

  private Cluster initializeNewCluster() throws Exception {
    String clusterName = "cluster-" + System.currentTimeMillis();
    m_clusters.addCluster(clusterName);

    Cluster cluster = m_clusters.getCluster(clusterName);
    cluster.setDesiredStackVersion(new StackId("HDP", "2.0.6"));

    addHost();
    m_clusters.mapHostToCluster(HOSTNAME, cluster.getClusterName());

    installHdfsService(cluster);
    return cluster;
  }

  /**
   * @throws Exception
   */
  private void addHost() throws Exception {
    m_clusters.addHost(HOSTNAME);

    Host host = m_clusters.getHost(HOSTNAME);
    Map<String, String> hostAttributes = new HashMap<String, String>();
    hostAttributes.put("os_family", "redhat");
    hostAttributes.put("os_release_version", "6.4");
    host.setHostAttributes(hostAttributes);
    host.setState(HostState.HEALTHY);
    host.persist();
  }

  /**
   * Calls {@link Service#persist()} to mock a service install along with
   * creating a single {@link Host} and {@link ServiceComponentHost}.
   */
  private void installHdfsService(Cluster cluster) throws Exception {
    String serviceName = "HDFS";
    Service service = m_serviceFactory.createNew(cluster, serviceName);
    cluster.addService(service);
    service.persist();
    service = cluster.getService(serviceName);
    Assert.assertNotNull(service);

    ServiceComponent datanode = m_componentFactory.createNew(service,
        "DATANODE");

    service.addServiceComponent(datanode);
    datanode.setDesiredState(State.INSTALLED);
    datanode.persist();

    ServiceComponentHost sch = m_schFactory.createNew(datanode, HOSTNAME);

    datanode.addServiceComponentHost(sch);
    sch.setDesiredState(State.INSTALLED);
    sch.setState(State.INSTALLED);
    sch.setDesiredStackVersion(new StackId("HDP-2.0.6"));
    sch.setStackVersion(new StackId("HDP-2.0.6"));

    sch.persist();

    ServiceComponent namenode = m_componentFactory.createNew(service,
        "NAMENODE");

    service.addServiceComponent(namenode);
    namenode.setDesiredState(State.INSTALLED);
    namenode.persist();

    sch = m_schFactory.createNew(namenode, HOSTNAME);
    namenode.addServiceComponentHost(sch);
    sch.setDesiredState(State.INSTALLED);
    sch.setState(State.INSTALLED);
    sch.setDesiredStackVersion(new StackId("HDP-2.0.6"));
    sch.setStackVersion(new StackId("HDP-2.0.6"));

    sch.persist();
  }
}
