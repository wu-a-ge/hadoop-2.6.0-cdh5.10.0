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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueState;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.security.AccessType;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerAppUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.server.utils.Lock;
import org.apache.hadoop.yarn.server.utils.Lock.NoLock;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

@Private
@Unstable
public class LeafQueue extends AbstractCSQueue {
  private static final Log LOG = LogFactory.getLog(LeafQueue.class);

  private float absoluteUsedCapacity = 0.0f;
  private int userLimit;
  private float userLimitFactor;

  protected int maxApplications;
  protected int maxApplicationsPerUser;
  
  private float maxAMResourcePerQueuePercent;
  private int maxActiveApplications; // Based on absolute max capacity
  private int maxActiveAppsUsingAbsCap; // Based on absolute capacity
  private int maxActiveApplicationsPerUser;
  
  private int nodeLocalityDelay;
  /**
   * 按APPID排序就是按提交的先后顺序排序,FIFO
   */
  Set<FiCaSchedulerApp> activeApplications;
  Map<ApplicationAttemptId, FiCaSchedulerApp> applicationAttemptMap = 
      new HashMap<ApplicationAttemptId, FiCaSchedulerApp>();
  /**
   * 按APPID排序就是按提交的先后顺序排序,FIFO
   */
  Set<FiCaSchedulerApp> pendingApplications;
  
  private final float minimumAllocationFactor;

  private Map<String, User> users = new HashMap<String, User>();

  private final RecordFactory recordFactory = 
    RecordFactoryProvider.getRecordFactory(null);

  private CapacitySchedulerContext scheduler;
  
  private final ActiveUsersManager activeUsersManager;

  // cache last cluster resource to compute actual capacity
  private Resource lastClusterResource = Resources.none();
  
  private final QueueHeadroomInfo queueHeadroomInfo = new QueueHeadroomInfo();
  
  public LeafQueue(CapacitySchedulerContext cs, 
      String queueName, CSQueue parent, CSQueue old) throws IOException {
    super(cs, queueName, parent, old);
    this.scheduler = cs;

    this.activeUsersManager = new ActiveUsersManager(metrics);
    this.minimumAllocationFactor = 
        Resources.ratio(resourceCalculator, 
            Resources.subtract(maximumAllocation, minimumAllocation), 
            maximumAllocation);

    float capacity = getCapacityFromConf();
    float absoluteCapacity = parent.getAbsoluteCapacity() * capacity;

    float maximumCapacity = 
        (float)cs.getConfiguration().getMaximumCapacity(getQueuePath()) / 100;
    float absoluteMaxCapacity = 
        CSQueueUtils.computeAbsoluteMaximumCapacity(maximumCapacity, parent);

    int userLimit = cs.getConfiguration().getUserLimit(getQueuePath());
    float userLimitFactor = 
      cs.getConfiguration().getUserLimitFactor(getQueuePath());
    //表示队列最大可提交APP，include pending or running,超过此值直接拒绝
    int maxApplications =
        cs.getConfiguration().getMaximumApplicationsPerQueue(getQueuePath());
    if (maxApplications < 0) {
      int maxSystemApps = cs.getConfiguration().getMaximumSystemApplications();
      maxApplications = (int)(maxSystemApps * absoluteCapacity);
    }
    //表示每个用户最大可提交APP，include pending or running,超过此值直接拒绝
    maxApplicationsPerUser = 
      (int)(maxApplications * (userLimit / 100.0f) * userLimitFactor);

    float maxAMResourcePerQueuePercent = cs.getConfiguration()
        .getMaximumApplicationMasterResourcePerQueuePercent(getQueuePath());
    //队列最大可运行的APP
    int maxActiveApplications = //页面 Max Schedulable Applications
        CSQueueUtils.computeMaxActiveApplications(
            resourceCalculator,
            cs.getClusterResource(), this.minimumAllocation,
            maxAMResourcePerQueuePercent, absoluteMaxCapacity);
    this.maxActiveAppsUsingAbsCap = //
            CSQueueUtils.computeMaxActiveApplications(
                resourceCalculator,
                cs.getClusterResource(), this.minimumAllocation,
                maxAMResourcePerQueuePercent, absoluteCapacity);
    //队列中用户最大可运行的APP
    int maxActiveApplicationsPerUser = //页面  Max Schedulable Applications Per User
        CSQueueUtils.computeMaxActiveApplicationsPerUser(
            maxActiveAppsUsingAbsCap, userLimit, userLimitFactor);

    QueueState state = cs.getConfiguration().getState(getQueuePath());

    Map<AccessType, AccessControlList> acls = 
      cs.getConfiguration().getAcls(getQueuePath());

    setupQueueConfigs(cs.getClusterResource(), capacity, absoluteCapacity,
        maximumCapacity, absoluteMaxCapacity, userLimit, userLimitFactor,
        maxApplications, maxAMResourcePerQueuePercent, maxApplicationsPerUser,
        maxActiveApplications, maxActiveApplicationsPerUser, state, acls, cs
            .getConfiguration().getNodeLocalityDelay(), accessibleLabels,
        defaultLabelExpression, this.capacitiyByNodeLabels,
        this.maxCapacityByNodeLabels,
        cs.getConfiguration().getReservationContinueLook());

    if(LOG.isDebugEnabled()) {
      LOG.debug("LeafQueue:" + " name=" + queueName
        + ", fullname=" + getQueuePath());
    }

    Comparator<FiCaSchedulerApp> applicationComparator =
        cs.getApplicationComparator();
    this.pendingApplications = 
        new TreeSet<FiCaSchedulerApp>(applicationComparator);
    this.activeApplications = new TreeSet<FiCaSchedulerApp>(applicationComparator);
  }
  
  // externalizing in method, to allow overriding
  protected float getCapacityFromConf() {
    return (float)scheduler.getConfiguration().getCapacity(getQueuePath()) / 100;
  }

  protected synchronized void setupQueueConfigs(
      Resource clusterResource,
      float capacity, float absoluteCapacity, 
      float maximumCapacity, float absoluteMaxCapacity,
      int userLimit, float userLimitFactor,
      int maxApplications, float maxAMResourcePerQueuePercent,
      int maxApplicationsPerUser, int maxActiveApplications,
      int maxActiveApplicationsPerUser, QueueState state,
      Map<AccessType, AccessControlList> acls, int nodeLocalityDelay,
      Set<String> labels, String defaultLabelExpression,
      Map<String, Float> capacitieByLabel,
      Map<String, Float> maximumCapacitiesByLabel, 
      boolean revervationContinueLooking) throws IOException {
    super.setupQueueConfigs(clusterResource, capacity, absoluteCapacity,
        maximumCapacity, absoluteMaxCapacity, state, acls, labels,
        defaultLabelExpression, capacitieByLabel, maximumCapacitiesByLabel,
        revervationContinueLooking);
    // Sanity check
    CSQueueUtils.checkMaxCapacity(getQueueName(), capacity, maximumCapacity);
    float absCapacity = getParent().getAbsoluteCapacity() * capacity;
    CSQueueUtils.checkAbsoluteCapacity(getQueueName(), absCapacity,
        absoluteMaxCapacity);

    this.absoluteCapacity = absCapacity;

    this.userLimit = userLimit;
    this.userLimitFactor = userLimitFactor;

    this.maxApplications = maxApplications;
    this.maxAMResourcePerQueuePercent = maxAMResourcePerQueuePercent;
    this.maxApplicationsPerUser = maxApplicationsPerUser;

    this.maxActiveApplications = maxActiveApplications;
    this.maxActiveApplicationsPerUser = maxActiveApplicationsPerUser;

    if (!SchedulerUtils.checkQueueLabelExpression(this.accessibleLabels,
        this.defaultLabelExpression)) {
      throw new IOException("Invalid default label expression of "
          + " queue="
          + getQueueName()
          + " doesn't have permission to access all labels "
          + "in default label expression. labelExpression of resource request="
          + (this.defaultLabelExpression == null ? ""
              : this.defaultLabelExpression)
          + ". Queue labels="
          + (getAccessibleNodeLabels() == null ? "" : StringUtils.join(
              getAccessibleNodeLabels().iterator(), ',')));
    }
    
    this.nodeLocalityDelay = nodeLocalityDelay;

    StringBuilder aclsString = new StringBuilder();
    for (Map.Entry<AccessType, AccessControlList> e : acls.entrySet()) {
      aclsString.append(e.getKey() + ":" + e.getValue().getAclString());
    }

    StringBuilder labelStrBuilder = new StringBuilder(); 
    if (labels != null) {
      for (String s : labels) {
        labelStrBuilder.append(s);
        labelStrBuilder.append(",");
      }
    }

    LOG.info("Initializing " + queueName + "\n" +
        "capacity = " + capacity +
        " [= (float) configuredCapacity / 100 ]" + "\n" + 
        "asboluteCapacity = " + absoluteCapacity +
        " [= parentAbsoluteCapacity * capacity ]" + "\n" +
        "maxCapacity = " + maximumCapacity +
        " [= configuredMaxCapacity ]" + "\n" +
        "absoluteMaxCapacity = " + absoluteMaxCapacity +
        " [= 1.0 maximumCapacity undefined, " +
        "(parentAbsoluteMaxCapacity * maximumCapacity) / 100 otherwise ]" + 
        "\n" +
        "userLimit = " + userLimit +
        " [= configuredUserLimit ]" + "\n" +
        "userLimitFactor = " + userLimitFactor +
        " [= configuredUserLimitFactor ]" + "\n" +
        "maxApplications = " + maxApplications +
        " [= configuredMaximumSystemApplicationsPerQueue or" + 
        " (int)(configuredMaximumSystemApplications * absoluteCapacity)]" + 
        "\n" +
        "maxApplicationsPerUser = " + maxApplicationsPerUser +
        " [= (int)(maxApplications * (userLimit / 100.0f) * " +
        "userLimitFactor) ]" + "\n" +
        "maxActiveApplications = " + maxActiveApplications +
        " [= max(" + 
        "(int)ceil((clusterResourceMemory / minimumAllocation) * " + 
        "maxAMResourcePerQueuePercent * absoluteMaxCapacity)," + 
        "1) ]" + "\n" +
        "maxActiveAppsUsingAbsCap = " + maxActiveAppsUsingAbsCap +
        " [= max(" + 
        "(int)ceil((clusterResourceMemory / minimumAllocation) *" + 
        "maxAMResourcePercent * absoluteCapacity)," + 
        "1) ]" + "\n" +
        "maxActiveApplicationsPerUser = " + maxActiveApplicationsPerUser +
        " [= max(" +
        "(int)(maxActiveApplications * (userLimit / 100.0f) * " +
        "userLimitFactor)," +
        "1) ]" + "\n" +
        "usedCapacity = " + usedCapacity +
        " [= usedResourcesMemory / " +
        "(clusterResourceMemory * absoluteCapacity)]" + "\n" +
        "absoluteUsedCapacity = " + absoluteUsedCapacity +
        " [= usedResourcesMemory / clusterResourceMemory]" + "\n" +
        "maxAMResourcePerQueuePercent = " + maxAMResourcePerQueuePercent +
        " [= configuredMaximumAMResourcePercent ]" + "\n" +
        "minimumAllocationFactor = " + minimumAllocationFactor +
        " [= (float)(maximumAllocationMemory - minimumAllocationMemory) / " +
        "maximumAllocationMemory ]" + "\n" +
        "numContainers = " + numContainers +
        " [= currentNumContainers ]" + "\n" +
        "state = " + state +
        " [= configuredState ]" + "\n" +
        "acls = " + aclsString +
        " [= configuredAcls ]" + "\n" + 
        "nodeLocalityDelay = " + nodeLocalityDelay + "\n" +
        "labels=" + labelStrBuilder.toString() + "\n" +
        "nodeLocalityDelay = " +  nodeLocalityDelay + "\n" +
        "reservationsContinueLooking = " +
        reservationsContinueLooking + "\n");
  }

  @Override
  public String getQueuePath() {
    return getParent().getQueuePath() + "." + getQueueName();
  }

  /**
   * Used only by tests.
   */
  @Private
  public float getMinimumAllocationFactor() {
    return minimumAllocationFactor;
  }
  
  /**
   * Used only by tests.
   */
  @Private
  public float getMaxAMResourcePerQueuePercent() {
    return maxAMResourcePerQueuePercent;
  }

  public int getMaxApplications() {
    return maxApplications;
  }

  public synchronized int getMaxApplicationsPerUser() {
    return maxApplicationsPerUser;
  }

  public synchronized int getMaximumActiveApplications() {
    return maxActiveApplications;
  }

  public synchronized int getMaximumActiveApplicationsPerUser() {
    return maxActiveApplicationsPerUser;
  }

  @Override
  public ActiveUsersManager getActiveUsersManager() {
    return activeUsersManager;
  }

  @Override
  public List<CSQueue> getChildQueues() {
    return null;
  }
  
  /**
   * Set user limit - used only for testing.
   * @param userLimit new user limit
   */
  synchronized void setUserLimit(int userLimit) {
    this.userLimit = userLimit;
  }

  /**
   * Set user limit factor - used only for testing.
   * @param userLimitFactor new user limit factor
   */
  synchronized void setUserLimitFactor(float userLimitFactor) {
    this.userLimitFactor = userLimitFactor;
  }

  @Override
  public synchronized int getNumApplications() {
    return getNumPendingApplications() + getNumActiveApplications();
  }

  public synchronized int getNumPendingApplications() {
    return pendingApplications.size();
  }

  public synchronized int getNumActiveApplications() {
    return activeApplications.size();
  }

  @Private
  public synchronized int getNumApplications(String user) {
    return getUser(user).getTotalApplications();
  }

  @Private
  public synchronized int getNumPendingApplications(String user) {
    return getUser(user).getPendingApplications();
  }

  @Private
  public synchronized int getNumActiveApplications(String user) {
    return getUser(user).getActiveApplications();
  }
  
  public synchronized int getNumContainers() {
    return numContainers;
  }

  @Override
  public synchronized QueueState getState() {
    return state;
  }

  @Private
  public synchronized int getUserLimit() {
    return userLimit;
  }

  @Private
  public synchronized float getUserLimitFactor() {
    return userLimitFactor;
  }

  @Override
  public synchronized QueueInfo getQueueInfo(
      boolean includeChildQueues, boolean recursive) {
    QueueInfo queueInfo = getQueueInfo();
    return queueInfo;
  }

  @Override
  public synchronized List<QueueUserACLInfo> 
  getQueueUserAclInfo(UserGroupInformation user) {
    QueueUserACLInfo userAclInfo = 
      recordFactory.newRecordInstance(QueueUserACLInfo.class);
    List<QueueACL> operations = new ArrayList<QueueACL>();
    for (QueueACL operation : QueueACL.values()) {
      if (hasAccess(operation, user)) {
        operations.add(operation);
      }
    }

    userAclInfo.setQueueName(getQueueName());
    userAclInfo.setUserAcls(operations);
    return Collections.singletonList(userAclInfo);
  }

  @Private
  public int getNodeLocalityDelay() {
    return nodeLocalityDelay;
  }
  
  public String toString() {
    return queueName + ": " + 
        "capacity=" + capacity + ", " + 
        "absoluteCapacity=" + absoluteCapacity + ", " + 
        "usedResources=" + usedResources +  ", " +
        "usedCapacity=" + getUsedCapacity() + ", " + 
        "absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + ", " +
        "numApps=" + getNumApplications() + ", " + 
        "numContainers=" + getNumContainers();  
  }
  
  @VisibleForTesting
  public synchronized void setNodeLabelManager(RMNodeLabelsManager mgr) {
    this.labelManager = mgr;
  }

  @VisibleForTesting
  public synchronized User getUser(String userName) {
    User user = users.get(userName);
    if (user == null) {
      user = new User();
      users.put(userName, user);
    }
    return user;
  }

  /**
   * @return an ArrayList of UserInfo objects who are active in this queue
   */
  public synchronized ArrayList<UserInfo> getUsers() {
    ArrayList<UserInfo> usersToReturn = new ArrayList<UserInfo>();
    for (Map.Entry<String, User> entry: users.entrySet()) {
      usersToReturn.add(new UserInfo(entry.getKey(), Resources.clone(
        entry.getValue().consumed), entry.getValue().getActiveApplications(),
        entry.getValue().getPendingApplications()));
    }
    return usersToReturn;
  }

  @Override
  public synchronized void reinitialize(
      CSQueue newlyParsedQueue, Resource clusterResource) 
  throws IOException {
    // Sanity check
    if (!(newlyParsedQueue instanceof LeafQueue) || 
        !newlyParsedQueue.getQueuePath().equals(getQueuePath())) {
      throw new IOException("Trying to reinitialize " + getQueuePath() + 
          " from " + newlyParsedQueue.getQueuePath());
    }

    LeafQueue newlyParsedLeafQueue = (LeafQueue)newlyParsedQueue;
    setupQueueConfigs(
        clusterResource,
        newlyParsedLeafQueue.capacity, newlyParsedLeafQueue.absoluteCapacity, 
        newlyParsedLeafQueue.maximumCapacity, 
        newlyParsedLeafQueue.absoluteMaxCapacity, 
        newlyParsedLeafQueue.userLimit, newlyParsedLeafQueue.userLimitFactor, 
        newlyParsedLeafQueue.maxApplications,
        newlyParsedLeafQueue.maxAMResourcePerQueuePercent,
        newlyParsedLeafQueue.getMaxApplicationsPerUser(),
        newlyParsedLeafQueue.getMaximumActiveApplications(), 
        newlyParsedLeafQueue.getMaximumActiveApplicationsPerUser(),
        newlyParsedLeafQueue.state, newlyParsedLeafQueue.acls,
        newlyParsedLeafQueue.getNodeLocalityDelay(),
        newlyParsedLeafQueue.accessibleLabels,
        newlyParsedLeafQueue.defaultLabelExpression,
        newlyParsedLeafQueue.capacitiyByNodeLabels,
        newlyParsedLeafQueue.maxCapacityByNodeLabels,
        newlyParsedLeafQueue.reservationsContinueLooking);

    // queue metrics are updated, more resource may be available
    // activate the pending applications if possible
    activateApplications();
  }

  @Override
  public void submitApplicationAttempt(FiCaSchedulerApp application,
      String userName) {
    // Careful! Locking order is important!
    synchronized (this) {
      User user = getUser(userName);
      // Add the attempt to our data-structures
      addApplicationAttempt(application, user);
    }

    // We don't want to update metrics for move app
    if (application.isPending()) {
      metrics.submitAppAttempt(userName);
    }
    getParent().submitApplicationAttempt(application, userName);
  }

  @Override
  public void submitApplication(ApplicationId applicationId, String userName,
      String queue)  throws AccessControlException {
    // Careful! Locking order is important!

    // Check queue ACLs
    UserGroupInformation userUgi = UserGroupInformation.createRemoteUser(userName);
    if (!hasAccess(QueueACL.SUBMIT_APPLICATIONS, userUgi)
        && !hasAccess(QueueACL.ADMINISTER_QUEUE, userUgi)) {
      throw new AccessControlException("User " + userName + " cannot submit" +
          " applications to queue " + getQueuePath());
    }

    User user = null;
    synchronized (this) {

      // Check if the queue is accepting jobs
      if (getState() != QueueState.RUNNING) {
        String msg = "Queue " + getQueuePath() +
        " is STOPPED. Cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }

      // Check submission limits for queues
      if (getNumApplications() >= getMaxApplications()) {
        String msg = "Queue " + getQueuePath() + 
        " already has " + getNumApplications() + " applications," +
        " cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }

      // Check submission limits for the user on this queue
      user = getUser(userName);
      if (user.getTotalApplications() >= getMaxApplicationsPerUser()) {
        String msg = "Queue " + getQueuePath() + 
        " already has " + user.getTotalApplications() + 
        " applications from user " + userName + 
        " cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }
    }

    // Inform the parent queue
    try {
      getParent().submitApplication(applicationId, userName, queue);
    } catch (AccessControlException ace) {
      LOG.info("Failed to submit application to parent-queue: " + 
          getParent().getQueuePath(), ace);
      throw ace;
    }

  }

  private synchronized void activateApplications() {
    for (Iterator<FiCaSchedulerApp> i=pendingApplications.iterator(); 
         i.hasNext(); ) {
      FiCaSchedulerApp application = i.next();
      
      // Check queue limit
      if (getNumActiveApplications() >= getMaximumActiveApplications()) {
        break;
      }
      
      // Check user limit
      User user = getUser(application.getUser());
      if (user.getActiveApplications() < getMaximumActiveApplicationsPerUser()) {
        user.activateApplication();
        activeApplications.add(application);
        i.remove();
        LOG.info("Application " + application.getApplicationId() +
            " from user: " + application.getUser() + 
            " activated in queue: " + getQueueName());
      }
    }
  }
  
  private synchronized void addApplicationAttempt(FiCaSchedulerApp application,
      User user) {
    // Accept 
    user.submitApplication();
    pendingApplications.add(application);
    applicationAttemptMap.put(application.getApplicationAttemptId(), application);

    // Activate applications
    activateApplications();
    
    LOG.info("Application added -" +
        " appId: " + application.getApplicationId() +
        " user: " + user + "," + " leaf-queue: " + getQueueName() +
        " #user-pending-applications: " + user.getPendingApplications() +
        " #user-active-applications: " + user.getActiveApplications() +
        " #queue-pending-applications: " + getNumPendingApplications() +
        " #queue-active-applications: " + getNumActiveApplications()
        );
  }

  @Override
  public void finishApplication(ApplicationId application, String user) {
    // Inform the activeUsersManager
    activeUsersManager.deactivateApplication(user, application);
    // Inform the parent queue
    getParent().finishApplication(application, user);
  }

  @Override
  public void finishApplicationAttempt(FiCaSchedulerApp application, String queue) {
    // Careful! Locking order is important!
    synchronized (this) {
      removeApplicationAttempt(application, getUser(application.getUser()));
    }
    getParent().finishApplicationAttempt(application, queue);
  }

  public synchronized void removeApplicationAttempt(
      FiCaSchedulerApp application, User user) {
    boolean wasActive = activeApplications.remove(application);
    if (!wasActive) {
      pendingApplications.remove(application);
    }
    applicationAttemptMap.remove(application.getApplicationAttemptId());

    user.finishApplication(wasActive);
    if (user.getTotalApplications() == 0) {
      users.remove(application.getUser());
    }

    // Check if we can activate more applications
    activateApplications();

    LOG.info("Application removed -" +
        " appId: " + application.getApplicationId() + 
        " user: " + application.getUser() + 
        " queue: " + getQueueName() +
        " #user-pending-applications: " + user.getPendingApplications() +
        " #user-active-applications: " + user.getActiveApplications() +
        " #queue-pending-applications: " + getNumPendingApplications() +
        " #queue-active-applications: " + getNumActiveApplications()
        );
  }

  private synchronized FiCaSchedulerApp getApplication(
      ApplicationAttemptId applicationAttemptId) {
    return applicationAttemptMap.get(applicationAttemptId);
  }

  private static final CSAssignment NULL_ASSIGNMENT =
      new CSAssignment(Resources.createResource(0, 0), NodeType.NODE_LOCAL);
  
  private static final CSAssignment SKIP_ASSIGNMENT = new CSAssignment(true);
  
  private static Set<String> getRequestLabelSetByExpression(
      String labelExpression) {
    Set<String> labels = new HashSet<String>();
    if (null == labelExpression) {
      return labels;
    }
    for (String l : labelExpression.split("&&")) {
      if (l.trim().isEmpty()) {
        continue;
      }
      labels.add(l.trim());
    }
    return labels;
  }

  @Override
  public synchronized CSAssignment assignContainers(Resource clusterResource,
      FiCaSchedulerNode node, boolean needToUnreserve) {

    if(LOG.isDebugEnabled()) {
      LOG.debug("assignContainers: node=" + node.getNodeName()
        + " #applications=" + activeApplications.size());
    }
    //TODO：此地可以优化，如果不可以往节点上分配容器不应该往后走。也可以在这里解决预留资源标签变化的问题，释放预留资源!
    //查看SchedulerUtils.checkNodeLabelExpression的调用点
    // if our queue cannot access this node, just return
    if (!SchedulerUtils.checkQueueAccessToNode(accessibleLabels,
        labelManager.getLabelsOnNode(node.getNodeID()))) {
      return NULL_ASSIGNMENT;
    }
    //优先为保留容器分配资源
    // Check for reserved resources
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      FiCaSchedulerApp application = 
          getApplication(reservedContainer.getApplicationAttemptId());
      synchronized (application) {
        return assignReservedContainer(application, node, reservedContainer,
            clusterResource);
      }
    }
    
    //如果某个APP申请到资源就退出此方法，否则继续循环直到有APP可分配或全部不可分配
    //子队列内部，所有程序FIFO，按提交顺序排序
    // Try to assign containers to applications in order
    for (FiCaSchedulerApp application : activeApplications) {
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("pre-assignContainers for application "
        + application.getApplicationId());
        application.showRequests();
      }
      //加锁很重要，调度容器和资源申请是并发的，有同步问题
      synchronized (application) {
        // Check if this resource is on the blacklist
    	//此APP已经将此NODE加入了黑名单，不在此节点上进行调度容器
        if (SchedulerAppUtils.isBlacklisted(application, node, LOG)) {
          continue;
        }
        
        // Schedule in priority order
        //按优先级选取资源请求进行调度
        for (Priority priority : application.getPriorities()) {
          //同等的优先级下,资源名和资源容量大小标识一个资源请求(可能包含多个容器),会同时按主机名（data-local），机架名(rack-local)及off-switch（*表示的任意名）封装三种资源请求由AppMaster提交上来
         //（参考RMContainerRequestor.addContainerReq方法）以便调度器能从多维度选择调度
          //data-local和rack-local不一定有相应的资源请求，但是off-switch资源请求是必须要存在的，不然可能无法调度!
          //Map同时提交data-local,rack-local,off-switch三种资源请求，Reduce只有rack-local,off-switch两种资源请求
          //所以这里使用ANY资源名获取资源来验证资源可调度性
          //默认没有配置机架感知的情况下，肯定都有DEFAULT_RACK
          ResourceRequest anyRequest =
              application.getResourceRequest(priority, ResourceRequest.ANY);
          if (null == anyRequest) {
            continue;
          }
          
          // Required resource
          Resource required = anyRequest.getCapability();

          // Do we need containers at this 'priority'?
          if (application.getTotalRequiredResources(priority) <= 0) {
            continue;
          }
          if (!this.reservationsContinueLooking) {
            if (!needContainers(application, priority, required)) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("doesn't need containers based on reservation algo!");
              }
              continue;
            }
          }
          //标签表达式虽然可以用&&分隔，但是计算资源时只取第一个，没意义
          Set<String> requestedNodeLabels =
              getRequestLabelSetByExpression(anyRequest
                  .getNodeLabelExpression());

          // Compute user-limit & set headroom
          // Note: We compute both user-limit & headroom with the highest 
          //       priority request as the target. 
          //       This works since we never assign lower priority requests
          //       before all higher priority ones are serviced.
          Resource userLimit = 
              computeUserLimitAndSetHeadroom(application, clusterResource, 
                  required, requestedNodeLabels);          
          
          // Check queue max-capacity limit
          if (!canAssignToThisQueue(clusterResource, required,
              labelManager.getLabelsOnNode(node.getNodeID()), application, true)) {
            return NULL_ASSIGNMENT;
          }

          // Check user limit
          if (!assignToUser(clusterResource, application.getUser(), userLimit,
              application, true, requestedNodeLabels)) {
            break;
          }
          //每个优先级有一个调度机会，如果造成非本地化的调度会跳过，最大跳过次数为NM节点数
          // Inform the application it is about to get a scheduling opportunity
          application.addSchedulingOpportunity(priority);
          
          // Try to schedule
          CSAssignment assignment =  
            assignContainersOnNode(clusterResource, node, application, priority, 
                null, needToUnreserve);

          // Did the application skip this node?
          //对于跳过的节点还不能计数调度机会，一般是资源申请者有这种要求才会跳过
          if (assignment.getSkipped()) {
            // Don't count 'skipped nodes' as a scheduling opportunity!
            application.subtractSchedulingOpportunity(priority);
            continue;
          }
          
          // Did we schedule or reserve a container?
          Resource assigned = assignment.getResource();
          if (Resources.greaterThan(
              resourceCalculator, clusterResource, assigned, Resources.none())) {

            // Book-keeping 
            // Note: Update headroom to account for current allocation too...
            allocateResource(clusterResource, application, assigned,
                labelManager.getLabelsOnNode(node.getNodeID()));
            
            // Don't reset scheduling opportunities for non-local assignments
            // otherwise the app will be delayed for each non-local assignment.
            // This helps apps with many off-cluster requests schedule faster.
            if (assignment.getType() != NodeType.OFF_SWITCH) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Resetting scheduling opportunities");
              }
              application.resetSchedulingOpportunities(priority);
            }
            
            // Done
            return assignment;
          } else {
            // Do not assign out of order w.r.t priorities
            break;
          }
        }
      }

      if(LOG.isDebugEnabled()) {
        LOG.debug("post-assignContainers for application "
          + application.getApplicationId());
      }
      application.showRequests();
    }
  
    return NULL_ASSIGNMENT;

  }

  private synchronized CSAssignment 
  assignReservedContainer(FiCaSchedulerApp application, 
      FiCaSchedulerNode node, RMContainer rmContainer, Resource clusterResource) {
    // Do we still need this reservation?
    Priority priority = rmContainer.getReservedPriority();
    if (application.getTotalRequiredResources(priority) == 0) {
      // Release
      return new CSAssignment(application, rmContainer);
    }

    // Try to assign if we have sufficient resources
    assignContainersOnNode(clusterResource, node, application, priority, 
        rmContainer, false);
    
    // Doesn't matter... since it's already charged for at time of reservation
    // "re-reservation" is *free*
    return new CSAssignment(Resources.none(), NodeType.NODE_LOCAL);
  }
  
  protected Resource getHeadroom(User user, Resource queueMaxCap,
      Resource clusterResource, FiCaSchedulerApp application, Resource required) {
    return getHeadroom(user, queueMaxCap, clusterResource,
	  computeUserLimit(application, clusterResource, required, user, null));
  }
  
  private Resource getHeadroom(User user, Resource queueMaxCap,
      Resource clusterResource, Resource userLimit) {
    /** 
     * Headroom is:
     *    min(
     *        min(userLimit, queueMaxCap) - userConsumed,
     *        queueMaxCap - queueUsedResources
     *       )
     * 
     * ( which can be expressed as, 
     *  min (userLimit - userConsumed, queuMaxCap - userConsumed, 
     *    queueMaxCap - queueUsedResources)
     *  )
     *
     * given that queueUsedResources >= userConsumed, this simplifies to
     *
     * >> min (userlimit - userConsumed,   queueMaxCap - queueUsedResources) << 
     *
     */
    Resource headroom = 
      Resources.min(resourceCalculator, clusterResource,
        Resources.subtract(userLimit, user.getTotalConsumedResources()),
        Resources.subtract(queueMaxCap, usedResources)
        );
    return headroom;
  }
  /**
   * 判断当前队列的标签使用容量是否超过标签容量最大值，无标签则判断是否超过队列的最大容量
   * @author fulaihua 2018年6月20日 下午5:16:54
   * @param clusterResource
   * @param required
   * @param nodeLabels
   * @param application
   * @param checkReservations
   * @return
   */
  synchronized boolean canAssignToThisQueue(Resource clusterResource,
      Resource required, Set<String> nodeLabels, FiCaSchedulerApp application, 
      boolean checkReservations) {
    // Get label of this queue can access, it's (nodeLabel AND queueLabel)
    Set<String> labelCanAccess;
    if (null == nodeLabels || nodeLabels.isEmpty()) {
      labelCanAccess = new HashSet<String>();
      // Any queue can always access any node without label
      labelCanAccess.add(RMNodeLabelsManager.NO_LABEL);
    } else {
      labelCanAccess = new HashSet<String>(Sets.intersection(accessibleLabels, nodeLabels));
    }
    //如果队列有多个标签在节点上，把当前申请的资源全部标签都加一遍！！！目的自然而明
    boolean canAssign = true;
    for (String label : labelCanAccess) {
      if (!usedResourcesByNodeLabels.containsKey(label)) {
        usedResourcesByNodeLabels.put(label, Resources.createResource(0));
      }
      
      Resource potentialTotalCapacity =
          Resources.add(usedResourcesByNodeLabels.get(label), required);
      
      float potentialNewCapacity =
          Resources.divide(resourceCalculator, clusterResource,
              potentialTotalCapacity,
              labelManager.getResourceByLabel(label, clusterResource));
      // if enabled, check to see if could we potentially use this node instead
      // of a reserved node if the application has reserved containers
      // TODO, now only consider reservation cases when the node has no label
      if (this.reservationsContinueLooking && checkReservations
          && label.equals(RMNodeLabelsManager.NO_LABEL)) {
        float potentialNewWithoutReservedCapacity = Resources.divide(
            resourceCalculator,
            clusterResource,
            Resources.subtract(potentialTotalCapacity,
               application.getCurrentReservation()),
            labelManager.getResourceByLabel(label, clusterResource));

        if (potentialNewWithoutReservedCapacity <= absoluteMaxCapacity) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("try to use reserved: "
                + getQueueName()
                + " usedResources: "
                + usedResources
                + " clusterResources: "
                + clusterResource
                + " reservedResources: "
                + application.getCurrentReservation()
                + " currentCapacity "
                + Resources.divide(resourceCalculator, clusterResource,
                    usedResources, clusterResource) + " required " + required
                + " potentialNewWithoutReservedCapacity: "
                + potentialNewWithoutReservedCapacity + " ( " + " max-capacity: "
                + absoluteMaxCapacity + ")");
          }
          // we could potentially use this node instead of reserved node
          return true;
        }
      }
      
      // Otherwise, if any of the label of this node beyond queue limit, we
      // cannot allocate on this node. Consider a small epsilon here.
      if (potentialNewCapacity > getAbsoluteMaximumCapacityByNodeLabel(label) + 1e-4) {
        canAssign = false;
        break;
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug(getQueueName()
            + "Check assign to queue, label=" + label
            + " usedResources: " + usedResourcesByNodeLabels.get(label)
            + " clusterResources: " + clusterResource
            + " currentCapacity "
            + Resources.divide(resourceCalculator, clusterResource,
                usedResourcesByNodeLabels.get(label),
                labelManager.getResourceByLabel(label, clusterResource))
            + " potentialNewCapacity: " + potentialNewCapacity + " ( "
            + " max-capacity: " + absoluteMaxCapacity + ")");
      }
    }
    
    return canAssign;
  }

  @Lock({LeafQueue.class, FiCaSchedulerApp.class})
  Resource computeUserLimitAndSetHeadroom(FiCaSchedulerApp application,
      Resource clusterResource, Resource required, Set<String> requestedLabels) {
    String user = application.getUser();
    User queueUser = getUser(user);

    // Compute user limit respect requested labels,
    // TODO, need consider headroom respect labels also
    Resource userLimit =
        computeUserLimit(application, clusterResource, required,
            queueUser, requestedLabels);

    //Max avail capacity needs to take into account usage by ancestor-siblings
    //which are greater than their base capacity, so we are interested in "max avail"
    //capacity
    float absoluteMaxAvailCapacity = CSQueueUtils.getAbsoluteMaxAvailCapacity(
      resourceCalculator, clusterResource, this);
    //(集群资源*算出来的绝对最大可用容量)=当前队列最大可用容量
    Resource queueMaxCap =                        // Queue Max-Capacity
        Resources.multiplyAndNormalizeDown(
            resourceCalculator, 
            clusterResource, 
            absoluteMaxAvailCapacity,
            minimumAllocation);
	
    synchronized (queueHeadroomInfo) {
      queueHeadroomInfo.setQueueMaxCap(queueMaxCap);
      queueHeadroomInfo.setClusterResource(clusterResource);
    }
    
    Resource headroom =
        getHeadroom(queueUser, queueMaxCap, clusterResource, userLimit);
    
    if (LOG.isDebugEnabled()) {
      LOG.debug("Headroom calculation for user " + user + ": " + 
          " userLimit=" + userLimit + 
          " queueMaxCap=" + queueMaxCap + 
          " consumed=" + queueUser.getTotalConsumedResources() + 
          " headroom=" + headroom);
    }
    
    CapacityHeadroomProvider headroomProvider = new CapacityHeadroomProvider(
      queueUser, this, application, required, queueHeadroomInfo);
    
    application.setHeadroomProvider(headroomProvider);

    metrics.setAvailableResourcesToUser(user, headroom);
    
    return userLimit;
  }
  
  @Lock(NoLock.class)
  private Resource computeUserLimit(FiCaSchedulerApp application,
      Resource clusterResource, Resource required, User user,
      Set<String> requestedLabels) {
    // What is our current capacity? 
    // * It is equal to the max(required, queue-capacity) if
    //   we're running below capacity. The 'max' ensures that jobs in queues
    //   with miniscule capacity (< 1 slot) make progress
    // * If we're running over capacity, then its
    //   (usedResources + required) (which extra resources we are allocating)
    Resource queueCapacity = Resource.newInstance(0, 0);
    if (requestedLabels != null && !requestedLabels.isEmpty()) {
      // if we have multiple labels to request, we will choose to use the first
      // label
      //如果有标签，队列容量只计算打了指定标签的所有机器，判断队列和用户使用的资源情况也是相应的用标签使用量和标签最大量来判断
      String firstLabel = requestedLabels.iterator().next();
      queueCapacity =
          Resources
              .max(resourceCalculator, clusterResource, queueCapacity,
                  Resources.multiplyAndNormalizeUp(resourceCalculator,
                      labelManager.getResourceByLabel(firstLabel,
                          clusterResource),//标签的资源，多个机器打上了此标签！
                      getAbsoluteCapacityByNodeLabel(firstLabel),
                      minimumAllocation));
    } else {
      //没有标签，算的是没有打标签的所有机器,，判断队列和用户使用的资源情况也是相应的用无标签的使用量和无标签最大量来判断
      // else there's no label on request, just to use absolute capacity as
      // capacity for nodes without label
      queueCapacity =
          Resources.multiplyAndNormalizeUp(resourceCalculator, labelManager
                .getResourceByLabel(CommonNodeLabelsManager.NO_LABEL, clusterResource),
              absoluteCapacity, minimumAllocation);
    }
    //请求资源可能超过队列容量
    // Allow progress for queues with miniscule capacity
    queueCapacity =
        Resources.max(
            resourceCalculator, clusterResource, 
            queueCapacity, 
            required);
    //可能使用资源超过队列容量，这是允许的！
    Resource currentCapacity =
        Resources.lessThan(resourceCalculator, clusterResource, 
            usedResources, queueCapacity) ?
            queueCapacity : Resources.add(usedResources, required);
    
    // Never allow a single user to take more than the 
    // queue's configured capacity * user-limit-factor.
    // Also, the queue's configured capacity should be higher than 
    // queue-hard-limit * ulMin
    
    final int activeUsers = activeUsersManager.getNumActiveUsers();  
    		
    Resource limit =
        Resources.roundUp(
            resourceCalculator, 
            Resources.min(
                resourceCalculator, clusterResource,   
                Resources.max(
                    resourceCalculator, clusterResource, 
                    Resources.divideAndCeil(
                        resourceCalculator, currentCapacity, activeUsers),//每个用户平均资源,单个用户可以用完整个资源
                    Resources.divideAndCeil(
                        resourceCalculator, 
                        Resources.multiplyAndRoundDown(
                            currentCapacity, userLimit), 
                        100)//每个用户百分比资源
                    ), //取两者的最大值
                Resources.multiplyAndRoundDown(queueCapacity, userLimitFactor)
                ), //TODO:为什么这里取最小呢?用户无法使用资源大于队列容量了？？队列容量*factor，表示此用户可以使用超过队列的容量多少！
            minimumAllocation);

    if (LOG.isDebugEnabled()) {
      String userName = application.getUser();
      LOG.debug("User limit computation for " + userName + 
          " in queue " + getQueueName() +
          " userLimit=" + userLimit +
          " userLimitFactor=" + userLimitFactor +
          " required: " + required + 
          " consumed: " + user.getTotalConsumedResources() + 
          " limit: " + limit +
          " queueCapacity: " + queueCapacity + 
          " qconsumed: " + usedResources +
          " currentCapacity: " + currentCapacity +
          " activeUsers: " + activeUsers +
          " clusterCapacity: " + clusterResource
      );
    }

    return limit;
  }
  /**
   * 判断用户使用的资源是否超过用户限制，如果有标签就取第一个标签，否则使用空标签
   * @author fulaihua 2018年6月28日 下午2:06:45
   * @param clusterResource
   * @param userName
   * @param limit
   * @param application
   * @param checkReservations
   * @param requestLabels
   * @return 
   */
  @Private
  protected synchronized boolean assignToUser(Resource clusterResource,
      String userName, Resource limit, FiCaSchedulerApp application,
      boolean checkReservations, Set<String> requestLabels) {
    User user = getUser(userName);
    
    String label = CommonNodeLabelsManager.NO_LABEL;
    if (requestLabels != null && !requestLabels.isEmpty()) {
      label = requestLabels.iterator().next();
    }

    // Note: We aren't considering the current request since there is a fixed
    // overhead of the AM, but it's a > check, not a >= check, so...
    if (Resources
        .greaterThan(resourceCalculator, clusterResource,
            user.getConsumedResourceByLabel(label),
            limit)) {
      // if enabled, check to see if could we potentially use this node instead
      // of a reserved node if the application has reserved containers
      if (this.reservationsContinueLooking && checkReservations) {
        if (Resources.lessThanOrEqual(
            resourceCalculator,
            clusterResource,
            Resources.subtract(user.getTotalConsumedResources(),
                application.getCurrentReservation()), limit)) {

          if (LOG.isDebugEnabled()) {
            LOG.debug("User " + userName + " in queue " + getQueueName()
                + " will exceed limit based on reservations - " + " consumed: "
                + user.getTotalConsumedResources() + " reserved: "
                + application.getCurrentReservation() + " limit: " + limit);
          }
          return true;
        }
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("User " + userName + " in queue " + getQueueName()
            + " will exceed limit - " + " consumed: "
            + user.getTotalConsumedResources() + " limit: " + limit);
      }
      return false;
    }
    return true;
  }
  /**
   * 根据当前APP的饥饿程度来判断是否需要分配容器
   * @author fulaihua 2018年1月26日 上午11:06:37
   * @param application
   * @param priority
   * @param required
   * @return
   */
  boolean needContainers(FiCaSchedulerApp application, Priority priority,
      Resource required) {
    int requiredContainers = application.getTotalRequiredResources(priority);//此优先级总共多少容器
    int reservedContainers = application.getNumReservedContainers(priority);//此优先级保留了多少容器
    int starvation = 0;
    if (reservedContainers > 0) {
      float nodeFactor = 
          Resources.ratio(
              resourceCalculator, required, getMaximumAllocation()
              );
      
      // Use percentage of node required to bias against large containers...
      // Protect against corner case where you need the whole node with
      // Math.min(nodeFactor, minimumAllocationFactor)
      starvation = 
          (int)((application.getReReservations(priority) / (float)reservedContainers) * 
                (1.0f - (Math.min(nodeFactor, getMinimumAllocationFactor())))
               );
      
      if (LOG.isDebugEnabled()) {
        LOG.debug("needsContainers:" +
            " app.#re-reserve=" + application.getReReservations(priority) + 
            " reserved=" + reservedContainers + 
            " nodeFactor=" + nodeFactor + 
            " minAllocFactor=" + getMinimumAllocationFactor() +
            " starvation=" + starvation);
      }
    }
    return (((starvation + requiredContainers) - reservedContainers) > 0);
  }

  /**
   * LOCAL，RACK，ANY这三种资源名按顺序尝试调度
   * @author fulaihua 2018年1月25日 下午2:48:43
   * @param clusterResource
   * @param node
   * @param application
   * @param priority
   * @param reservedContainer
   * @param needToUnreserve
   * @return
   */
  private CSAssignment assignContainersOnNode(Resource clusterResource,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve) {
    Resource assigned = Resources.none();

    // Data-local
    ResourceRequest nodeLocalResourceRequest =
        application.getResourceRequest(priority, node.getNodeName());
    if (nodeLocalResourceRequest != null) {
      assigned = 
          assignNodeLocalContainers(clusterResource, nodeLocalResourceRequest, 
              node, application, priority, reservedContainer, needToUnreserve); 
      if (Resources.greaterThan(resourceCalculator, clusterResource, 
          assigned, Resources.none())) {
        return new CSAssignment(assigned, NodeType.NODE_LOCAL);
      }
    }

    // Rack-local
    ResourceRequest rackLocalResourceRequest =
        application.getResourceRequest(priority, node.getRackName());
    if (rackLocalResourceRequest != null) {
      if (!rackLocalResourceRequest.getRelaxLocality()) {
        return SKIP_ASSIGNMENT;
      }
      
      assigned = 
          assignRackLocalContainers(clusterResource, rackLocalResourceRequest, 
              node, application, priority, reservedContainer, needToUnreserve);
      if (Resources.greaterThan(resourceCalculator, clusterResource, 
          assigned, Resources.none())) {
        return new CSAssignment(assigned, NodeType.RACK_LOCAL);
      }
    }
    
    // Off-switch
    ResourceRequest offSwitchResourceRequest =
        application.getResourceRequest(priority, ResourceRequest.ANY);
    if (offSwitchResourceRequest != null) {
      if (!offSwitchResourceRequest.getRelaxLocality()) {
        return SKIP_ASSIGNMENT;
      }

      return new CSAssignment(
          assignOffSwitchContainers(clusterResource, offSwitchResourceRequest,
              node, application, priority, reservedContainer, needToUnreserve), 
              NodeType.OFF_SWITCH);
    }
    
    return SKIP_ASSIGNMENT;
  }

  @Private
  protected boolean findNodeToUnreserve(Resource clusterResource,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      Resource capability) {
    // need to unreserve some other container first
    NodeId idToUnreserve = application.getNodeIdToUnreserve(priority, capability);
    if (idToUnreserve == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("checked to see if could unreserve for app but nothing "
            + "reserved that matches for this app");
      }
      return false;
    }
    FiCaSchedulerNode nodeToUnreserve = scheduler.getNode(idToUnreserve);
    if (nodeToUnreserve == null) {
      LOG.error("node to unreserve doesn't exist, nodeid: " + idToUnreserve);
      return false;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("unreserving for app: " + application.getApplicationId()
        + " on nodeId: " + idToUnreserve
        + " in order to replace reserved application and place it on node: "
        + node.getNodeID() + " needing: " + capability);
    }

    // headroom
    //为什么将保留的添加进净空?
    Resources.addTo(application.getHeadroom(), nodeToUnreserve
        .getReservedContainer().getReservedResource());

    // Make sure to not have completedContainers sort the queues here since
    // we are already inside an iterator loop for the queues and this would
    // cause an concurrent modification exception.
    completedContainer(clusterResource, application, nodeToUnreserve,
        nodeToUnreserve.getReservedContainer(),
        SchedulerUtils.createAbnormalContainerStatus(nodeToUnreserve
            .getReservedContainer().getContainerId(),
            SchedulerUtils.UNRESERVED_CONTAINER),
        RMContainerEventType.RELEASED, null, false);
    return true;
  }

  @Private
  protected boolean checkLimitsToReserve(Resource clusterResource,
      FiCaSchedulerApp application, Resource capability,
      boolean needToUnreserve) {
    if (needToUnreserve) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("we needed to unreserve to be able to allocate");
      }
      return false;
    }

    // we can't reserve if we got here based on the limit
    // checks assuming we could unreserve!!!
    Resource userLimit = computeUserLimitAndSetHeadroom(application,
        clusterResource, capability, null);

    // Check queue max-capacity limit,
    // TODO: Consider reservation on labels
    if (!canAssignToThisQueue(clusterResource, capability, null, application, false)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("was going to reserve but hit queue limit");
      }
      return false;
    }

    // Check user limit
    if (!assignToUser(clusterResource, application.getUser(), userLimit,
        application, false, null)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("was going to reserve but hit user limit");
      }
      return false;
    }
    return true;
  }


  private Resource assignNodeLocalContainers(Resource clusterResource,
      ResourceRequest nodeLocalResourceRequest, FiCaSchedulerNode node,
      FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve) {
    if (canAssign(application, priority, node, NodeType.NODE_LOCAL, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          nodeLocalResourceRequest, NodeType.NODE_LOCAL, reservedContainer,
          needToUnreserve);
    }
    
    return Resources.none();
  }

  private Resource assignRackLocalContainers(
      Resource clusterResource, ResourceRequest rackLocalResourceRequest,  
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve) {
    if (canAssign(application, priority, node, NodeType.RACK_LOCAL, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          rackLocalResourceRequest, NodeType.RACK_LOCAL, reservedContainer,
          needToUnreserve);
    }
    
    return Resources.none();
  }

  private Resource assignOffSwitchContainers(
      Resource clusterResource, ResourceRequest offSwitchResourceRequest,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority, 
      RMContainer reservedContainer, boolean needToUnreserve) {
    if (canAssign(application, priority, node, NodeType.OFF_SWITCH, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          offSwitchResourceRequest, NodeType.OFF_SWITCH, reservedContainer,
          needToUnreserve);
    }
    
    return Resources.none();
  }
  /**
   * 判断调度机会，针对非本地调度，如果跳过的次数超过节点数那么也会调度，否则再等等
   * @author fulaihua 2018年6月20日 下午9:56:02
   * @param application
   * @param priority
   * @param node
   * @param type
   * @param reservedContainer
   * @return
   */
  boolean canAssign(FiCaSchedulerApp application, Priority priority, 
      FiCaSchedulerNode node, NodeType type, RMContainer reservedContainer) {

    // Clearly we need containers for this application...
    if (type == NodeType.OFF_SWITCH) {
      if (reservedContainer != null) {
        return true;
      }

      // 'Delay' off-switch
      ResourceRequest offSwitchRequest = 
          application.getResourceRequest(priority, ResourceRequest.ANY);
      long missedOpportunities = application.getSchedulingOpportunities(priority);
      long requiredContainers = offSwitchRequest.getNumContainers(); 
      
      float localityWaitFactor = 
        application.getLocalityWaitFactor(priority, 
            scheduler.getNumClusterNodes());
      
      return ((requiredContainers * localityWaitFactor) < missedOpportunities);
    }

    // Check if we need containers on this rack 
    ResourceRequest rackLocalRequest = 
      application.getResourceRequest(priority, node.getRackName());
    if (rackLocalRequest == null || rackLocalRequest.getNumContainers() <= 0) {
      return false;
    }
      
    // If we are here, we do need containers on this rack for RACK_LOCAL req
    if (type == NodeType.RACK_LOCAL) {
      // 'Delay' rack-local just a little bit...
      //延迟调度，它最多可以跳过的调度机会是NM节点数
      long missedOpportunities = application.getSchedulingOpportunities(priority);
      return (
          Math.min(scheduler.getNumClusterNodes(), getNodeLocalityDelay()) < 
          missedOpportunities
          );
    }

    // Check if we need containers on this host
    if (type == NodeType.NODE_LOCAL) {
      // Now check if we need containers on this host...
      ResourceRequest nodeLocalRequest = 
        application.getResourceRequest(priority, node.getNodeName());
      if (nodeLocalRequest != null) {
        return nodeLocalRequest.getNumContainers() > 0;
      }
    }

    return false;
  }
  
  private Container getContainer(RMContainer rmContainer, 
      FiCaSchedulerApp application, FiCaSchedulerNode node, 
      Resource capability, Priority priority) {
    return (rmContainer != null) ? rmContainer.getContainer() :
      createContainer(application, node, capability, priority);
  }

  Container createContainer(FiCaSchedulerApp application, FiCaSchedulerNode node, 
      Resource capability, Priority priority) {
  
    NodeId nodeId = node.getRMNode().getNodeID();
    ContainerId containerId = BuilderUtils.newContainerId(application
        .getApplicationAttemptId(), application.getNewContainerId());
  
    // Create the container
    Container container =
        BuilderUtils.newContainer(containerId, nodeId, node.getRMNode()
          .getHttpAddress(), capability, priority, null);
  
    return container;
  }


  private Resource assignContainer(Resource clusterResource, FiCaSchedulerNode node, 
      FiCaSchedulerApp application, Priority priority, 
      ResourceRequest request, NodeType type, RMContainer rmContainer,
      boolean needToUnreserve) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("assignContainers: node=" + node.getNodeName()
        + " application=" + application.getApplicationId()
        + " priority=" + priority.getPriority()
        + " request=" + request + " type=" + type
        + " needToUnreserve= " + needToUnreserve);
    }
    //TODO:这里又判断一次，在子队列入口处就应该判断解决的问题放到了这里，可以优化掉！
    // check if the resource request can access the label
    if (!SchedulerUtils.checkNodeLabelExpression(
        labelManager.getLabelsOnNode(node.getNodeID()),
        request.getNodeLabelExpression())) {
      // this is a reserved container, but we cannot allocate it now according
      // to label not match. This can be caused by node label changed
      // We should un-reserve this container.
    	//标签变化，必须强制释放资源，不然永远无法调用.
      if (rmContainer != null) {
        unreserve(application, priority, node, rmContainer);
      }
      return Resources.none();
    }
    
    Resource capability = request.getCapability();
    Resource available = node.getAvailableResource();
    Resource totalResource = node.getTotalResource();
    //TODO：有必要总资源和需求资源进行比较吗？直接使用可获得资源和需求资源比不可以?不明白！
    //而且调用此方法是有问题的
    if (!Resources.fitsIn(capability, totalResource)) {
      LOG.warn("Node : " + node.getNodeID()
          + " does not have sufficient resource for request : " + request
          + " node total capability : " + node.getTotalResource());
      return Resources.none();
    }
    assert Resources.greaterThan(
        resourceCalculator, clusterResource, available, Resources.none());

    // Create the container if necessary
    Container container = 
        getContainer(rmContainer, application, node, capability, priority);
  
    // something went wrong getting/creating the container 
    if (container == null) {
      LOG.warn("Couldn't get container for allocation!");
      return Resources.none();
    }

    // default to true since if reservation continue look feature isn't on
    // needContainers is checked earlier and we wouldn't have gotten this far
    boolean canAllocContainer = true;
    if (this.reservationsContinueLooking) {
      // based on reservations can we allocate/reserve more or do we need
      // to unreserve one first
      canAllocContainer = needContainers(application, priority, capability);
      if (LOG.isDebugEnabled()) {
        LOG.debug("can alloc container is: " + canAllocContainer);
      }
    }
    //从可用资源中算出可以分配多少个容器，也即是否至少可以分配一个！
    // Can we allocate a container on this node?
    int availableContainers = 
        resourceCalculator.computeAvailableContainers(available, capability);
    if (availableContainers > 0) {
      // Allocate...

      // Did we previously reserve containers at this 'priority'?
      //这是一个保留容器， 有资源可分配，释放保留容器开始分配
      if (rmContainer != null) {
        unreserve(application, priority, node, rmContainer);
      } else if (this.reservationsContinueLooking
          && (!canAllocContainer || needToUnreserve)) {
        // need to unreserve some other container first
    	//虽然有足够的空间但是还是不能分配，说明保留的容器太多了，需要释放保留的容器
        boolean res = findNodeToUnreserve(clusterResource, node, application,
            priority, capability);
        if (!res) {
          return Resources.none();
        }
      } else {
        // we got here by possibly ignoring queue capacity limits. If the
        // parameter needToUnreserve is true it means we ignored one of those
        // limits in the chance we could unreserve. If we are here we aren't
        // trying to unreserve so we can't allocate anymore due to that parent
        // limit.
        if (needToUnreserve) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("we needed to unreserve to be able to allocate, skipping");
          }
          return Resources.none();
        }
      }

      // Inform the application
      RMContainer allocatedContainer = 
          application.allocate(type, node, priority, request, container);

      // Does the application need this resource?
      if (allocatedContainer == null) {
        return Resources.none();
      }

      // Inform the node
      node.allocateContainer(allocatedContainer);

      LOG.info("assignedContainer" +
          " application attempt=" + application.getApplicationAttemptId() +
          " container=" + container + 
          " queue=" + this + 
          " clusterResource=" + clusterResource);

      return container.getResource();
    } else {
      // if we are allowed to allocate but this node doesn't have space, reserve it or
      // if this was an already a reserved container, reserve it again
      //资源不足或已经有保留的容器，需要保留当前容器
      if ((canAllocContainer) || (rmContainer != null)) {

        if (reservationsContinueLooking) {
          // we got here by possibly ignoring parent queue capacity limits. If
          // the parameter needToUnreserve is true it means we ignored one of
          // those limits in the chance we could unreserve. If we are here
          // we aren't trying to unreserve so we can't allocate
          // anymore due to that parent limit
          //查看是否达到队列或用户资源上线，如果达到也不会保留容器.
          boolean res = checkLimitsToReserve(clusterResource, application, capability, 
              needToUnreserve);
          if (!res) {
            return Resources.none();
          }
        }

        // Reserve by 'charging' in advance...
        reserve(application, priority, node, rmContainer, container);

        LOG.info("Reserved container " + 
            " application=" + application.getApplicationId() + 
            " resource=" + request.getCapability() + 
            " queue=" + this.toString() + 
            " usedCapacity=" + getUsedCapacity() + 
            " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + 
            " used=" + usedResources +
            " cluster=" + clusterResource);

        return request.getCapability();
      }
      return Resources.none();
    }
  }

  private void reserve(FiCaSchedulerApp application, Priority priority, 
      FiCaSchedulerNode node, RMContainer rmContainer, Container container) {
    // Update reserved metrics if this is the first reservation
    if (rmContainer == null) {
      getMetrics().reserveResource(
          application.getUser(), container.getResource());
    }

    // Inform the application 
    rmContainer = application.reserve(node, priority, rmContainer, container);
    
    // Update the node
    node.reserveResource(application, priority, rmContainer);
  }

  private boolean unreserve(FiCaSchedulerApp application, Priority priority,
      FiCaSchedulerNode node, RMContainer rmContainer) {
    // Done with the reservation?
    if (application.unreserve(node, priority)) {
      node.unreserveResource(application);

      // Update reserved metrics
      getMetrics().unreserveResource(application.getUser(),
          rmContainer.getContainer().getResource());
      return true;
    }
    return false;
  }

  @Override
  public void completedContainer(Resource clusterResource, 
      FiCaSchedulerApp application, FiCaSchedulerNode node, RMContainer rmContainer, 
      ContainerStatus containerStatus, RMContainerEventType event, CSQueue childQueue,
      boolean sortQueues) {
    if (application != null) {

      boolean removed = false;

      // Careful! Locking order is important!
      synchronized (this) {

        Container container = rmContainer.getContainer();

        // Inform the application & the node
        // Note: It's safe to assume that all state changes to RMContainer
        // happen under scheduler's lock... 
        // So, this is, in effect, a transaction across application & node
        if (rmContainer.getState() == RMContainerState.RESERVED) {
          removed = unreserve(application, rmContainer.getReservedPriority(),
              node, rmContainer);
        } else {
          removed =
            application.containerCompleted(rmContainer, containerStatus, event);
          node.releaseContainer(container);
        }

        // Book-keeping
        if (removed) {
          releaseResource(clusterResource, application,
              container.getResource(),
              labelManager.getLabelsOnNode(node.getNodeID()));
          LOG.info("completedContainer" +
              " container=" + container +
              " queue=" + this +
              " cluster=" + clusterResource);
        }
      }

      if (removed) {
        // Inform the parent queue _outside_ of the leaf-queue lock
        getParent().completedContainer(clusterResource, application, node,
          rmContainer, null, event, this, sortQueues);
      }
    }
  }

  synchronized void allocateResource(Resource clusterResource,
      SchedulerApplicationAttempt application, Resource resource,
      Set<String> nodeLabels) {
    super.allocateResource(clusterResource, resource, nodeLabels);
    
    // Update user metrics
    String userName = application.getUser();
    User user = getUser(userName);
    user.assignContainer(resource, nodeLabels);
    // Note this is a bit unconventional since it gets the object and modifies
    // it here, rather then using set routine
    Resources.subtractFrom(application.getHeadroom(), resource); // headroom
    metrics.setAvailableResourcesToUser(userName, application.getHeadroom());
    
    if (LOG.isDebugEnabled()) {
      LOG.info(getQueueName() + 
          " user=" + userName + 
          " used=" + usedResources + " numContainers=" + numContainers +
          " headroom = " + application.getHeadroom() +
          " user-resources=" + user.getTotalConsumedResources()
          );
    }
  }

  synchronized void releaseResource(Resource clusterResource, 
      FiCaSchedulerApp application, Resource resource, Set<String> nodeLabels) {
    super.releaseResource(clusterResource, resource, nodeLabels);
    
    // Update user metrics
    String userName = application.getUser();
    User user = getUser(userName);
    user.releaseContainer(resource, nodeLabels);
    metrics.setAvailableResourcesToUser(userName, application.getHeadroom());
      
    LOG.info(getQueueName() + 
        " used=" + usedResources + " numContainers=" + numContainers + 
        " user=" + userName + " user-resources=" + user.getTotalConsumedResources());
  }

  @Override
  public synchronized void updateClusterResource(Resource clusterResource) {
    lastClusterResource = clusterResource;
    
    // Update queue properties
    maxActiveApplications = 
        CSQueueUtils.computeMaxActiveApplications(
            resourceCalculator,
            clusterResource, minimumAllocation, 
            maxAMResourcePerQueuePercent, absoluteMaxCapacity);
    maxActiveAppsUsingAbsCap = 
        CSQueueUtils.computeMaxActiveApplications(
            resourceCalculator,
            clusterResource, minimumAllocation, 
            maxAMResourcePerQueuePercent, absoluteCapacity);
    maxActiveApplicationsPerUser = 
        CSQueueUtils.computeMaxActiveApplicationsPerUser(
            maxActiveAppsUsingAbsCap, userLimit, userLimitFactor);
    
    // Update metrics
    CSQueueUtils.updateQueueStatistics(
        resourceCalculator, this, getParent(), clusterResource, 
        minimumAllocation);

    // queue metrics are updated, more resource may be available
    // activate the pending applications if possible
    activateApplications();

    // Update application properties
    for (FiCaSchedulerApp application : activeApplications) {
      synchronized (application) {
        computeUserLimitAndSetHeadroom(application, clusterResource, 
            Resources.none(), null);
      }
    }
  }

  @VisibleForTesting
  public static class User {
    Resource consumed = Resources.createResource(0, 0);
    Map<String, Resource> consumedByLabel = new HashMap<String, Resource>();
    int pendingApplications = 0;
    int activeApplications = 0;

    public Resource getTotalConsumedResources() {
      return consumed;
    }
    
    public Resource getConsumedResourceByLabel(String label) {
      Resource r = consumedByLabel.get(label);
      if (null != r) {
        return r;
      }
      return Resources.none();
    }

    public int getPendingApplications() {
      return pendingApplications;
    }

    public int getActiveApplications() {
      return activeApplications;
    }

    public int getTotalApplications() {
      return getPendingApplications() + getActiveApplications();
    }
    
    public synchronized void submitApplication() {
      ++pendingApplications;
    }
    
    public synchronized void activateApplication() {
      --pendingApplications;
      ++activeApplications;
    }

    public synchronized void finishApplication(boolean wasActive) {
      if (wasActive) {
        --activeApplications;
      }
      else {
        --pendingApplications;
      }
    }
    /***
     * 记录用户使用的总资源和用户在每个标签下使用的资源
     * @author fulaihua 2018年6月27日 下午9:41:32
     * @param resource
     * @param nodeLabels
     */
    public synchronized void assignContainer(Resource resource,
        Set<String> nodeLabels) {
      Resources.addTo(consumed, resource);
      
      if (nodeLabels == null || nodeLabels.isEmpty()) {
        if (!consumedByLabel.containsKey(RMNodeLabelsManager.NO_LABEL)) {
          consumedByLabel.put(RMNodeLabelsManager.NO_LABEL,
              Resources.createResource(0));
        }
        Resources.addTo(consumedByLabel.get(RMNodeLabelsManager.NO_LABEL),
            resource);
      } else {
        for (String label : nodeLabels) {
          if (!consumedByLabel.containsKey(label)) {
            consumedByLabel.put(label, Resources.createResource(0));
          }
          Resources.addTo(consumedByLabel.get(label), resource);
        }
      }
    }

    public synchronized void releaseContainer(Resource resource, Set<String> nodeLabels) {
      Resources.subtractFrom(consumed, resource);
      
      // Update usedResources by labels
      if (nodeLabels == null || nodeLabels.isEmpty()) {
        if (!consumedByLabel.containsKey(RMNodeLabelsManager.NO_LABEL)) {
          consumedByLabel.put(RMNodeLabelsManager.NO_LABEL,
              Resources.createResource(0));
        }
        Resources.subtractFrom(
            consumedByLabel.get(RMNodeLabelsManager.NO_LABEL), resource);
      } else {
        for (String label : nodeLabels) {
          if (!consumedByLabel.containsKey(label)) {
            consumedByLabel.put(label, Resources.createResource(0));
          }
          Resources.subtractFrom(consumedByLabel.get(label), resource);
        }
      }
    }  
  }

  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApplicationAttempt attempt, RMContainer rmContainer) {
    if (rmContainer.getState().equals(RMContainerState.COMPLETED)) {
      return;
    }
    // Careful! Locking order is important! 
    synchronized (this) {
      allocateResource(clusterResource, attempt, rmContainer.getContainer()
          .getResource(), labelManager.getLabelsOnNode(rmContainer
          .getContainer().getNodeId()));
    }
    getParent().recoverContainer(clusterResource, attempt, rmContainer);
  }

  /**
   * Obtain (read-only) collection of active applications.
   */
  public Set<FiCaSchedulerApp> getApplications() {
    // need to access the list of apps from the preemption monitor
    return activeApplications;
  }

  // return a single Resource capturing the overal amount of pending resources
  public Resource getTotalResourcePending() {
    Resource ret = BuilderUtils.newResource(0, 0);
    for (FiCaSchedulerApp f : activeApplications) {
      Resources.addTo(ret, f.getTotalPendingRequests());
    }
    return ret;
  }

  @Override
  public void collectSchedulerApplications(
      Collection<ApplicationAttemptId> apps) {
    for (FiCaSchedulerApp pendingApp : pendingApplications) {
      apps.add(pendingApp.getApplicationAttemptId());
    }
    for (FiCaSchedulerApp app : activeApplications) {
      apps.add(app.getApplicationAttemptId());
    }
  }

  @Override
  public void attachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      allocateResource(clusterResource, application, rmContainer.getContainer()
          .getResource(), labelManager.getLabelsOnNode(rmContainer
          .getContainer().getNodeId()));
      LOG.info("movedContainer" + " container=" + rmContainer.getContainer()
          + " resource=" + rmContainer.getContainer().getResource()
          + " queueMoveIn=" + this + " usedCapacity=" + getUsedCapacity()
          + " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + " used="
          + usedResources + " cluster=" + clusterResource);
      // Inform the parent queue
      getParent().attachContainer(clusterResource, application, rmContainer);
    }
  }

  @Override
  public void detachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      releaseResource(clusterResource, application, rmContainer.getContainer()
          .getResource(), labelManager.getLabelsOnNode(rmContainer.getContainer()
          .getNodeId()));
      LOG.info("movedContainer" + " container=" + rmContainer.getContainer()
          + " resource=" + rmContainer.getContainer().getResource()
          + " queueMoveOut=" + this + " usedCapacity=" + getUsedCapacity()
          + " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + " used="
          + usedResources + " cluster=" + clusterResource);
      // Inform the parent queue
      getParent().detachContainer(clusterResource, application, rmContainer);
    }
  }

  @Override
  public float getAbsActualCapacity() {
    if (Resources.lessThanOrEqual(resourceCalculator, lastClusterResource,
        lastClusterResource, Resources.none())) {
      return absoluteCapacity;
    }

    Resource resourceRespectLabels =
        labelManager == null ? lastClusterResource : labelManager
            .getQueueResource(queueName, accessibleLabels, lastClusterResource);
    float absActualCapacity =
        Resources.divide(resourceCalculator, lastClusterResource,
            resourceRespectLabels, lastClusterResource);
    
    return absActualCapacity > absoluteCapacity ? absoluteCapacity
        : absActualCapacity;
  }
  
  public void setCapacity(float capacity) {
    this.capacity = capacity;
  }

  public void setAbsoluteCapacity(float absoluteCapacity) {
    this.absoluteCapacity = absoluteCapacity;
  }

  public void setMaxApplications(int maxApplications) {
    this.maxApplications = maxApplications;
  }
  
  /*
   * Holds shared values used by all applications in
   * the queue to calculate headroom on demand
   */
  static class QueueHeadroomInfo {
    private Resource queueMaxCap;
    private Resource clusterResource;
    
    public void setQueueMaxCap(Resource queueMaxCap) {
      this.queueMaxCap = queueMaxCap;
    }
    
    public Resource getQueueMaxCap() {
      return queueMaxCap;
    }
    
    public void setClusterResource(Resource clusterResource) {
      this.clusterResource = clusterResource;
    }
    
    public Resource getClusterResource() {
      return clusterResource;
    }
  }
}
