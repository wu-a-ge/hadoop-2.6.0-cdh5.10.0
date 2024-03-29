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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueState;
import org.apache.hadoop.yarn.api.records.QueueStatistics;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.security.AccessType;
import org.apache.hadoop.yarn.security.PrivilegedEntity;
import org.apache.hadoop.yarn.security.PrivilegedEntity.EntityType;
import org.apache.hadoop.yarn.security.YarnAuthorizationProvider;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

public abstract class AbstractCSQueue implements CSQueue {
  
  CSQueue parent;
  final String queueName;
  /**
   * root 容量100
   */
  float capacity;
  /**
   * 所有队列默认最大容量都是100
   */
  float maximumCapacity;
  /**
   * root 绝对容量100
   */
  float absoluteCapacity;
  /**
   *所有队列默认最大绝对容量都是100
   */
  float absoluteMaxCapacity;
  float absoluteUsedCapacity = 0.0f;

  float usedCapacity = 0.0f;
  volatile int numContainers;
  
  final Resource minimumAllocation;
  final Resource maximumAllocation;
  QueueState state;
  final QueueMetrics metrics;
  protected final PrivilegedEntity queueEntity;

  final ResourceCalculator resourceCalculator;
  Set<String> accessibleLabels;
  RMNodeLabelsManager labelManager;
  String defaultLabelExpression;
  Resource usedResources = Resources.createResource(0, 0);
  /**
   * parent.getAbsoluteCapacityByNodeLabels(label)*queue.label_capacity
   */
  Map<String, Float> absoluteCapacityByNodeLabels;
  /**
   * 每个队列标签的容量必须指定，且ROOT队列每个标签都必须指定为100，否则全是0
   */
  Map<String, Float> capacitiyByNodeLabels;
  Map<String, Resource> usedResourcesByNodeLabels = new HashMap<String, Resource>();
  /**
   * 每个队列标签的最大绝对容量默认都是100
   */
  Map<String, Float> absoluteMaxCapacityByNodeLabels;
  /**
   * 每个队列标签的最大容量默认都是100
   */
  Map<String, Float> maxCapacityByNodeLabels;
  
  Map<AccessType, AccessControlList> acls = 
      new HashMap<AccessType, AccessControlList>();
  boolean reservationsContinueLooking;
  
  private final RecordFactory recordFactory = 
      RecordFactoryProvider.getRecordFactory(null);
  protected YarnAuthorizationProvider authorizer = null;

  public AbstractCSQueue(CapacitySchedulerContext cs, 
      String queueName, CSQueue parent, CSQueue old) throws IOException {
    this.minimumAllocation = cs.getMinimumResourceCapability();
    this.maximumAllocation = cs.getMaximumResourceCapability();
    this.labelManager = cs.getRMContext().getNodeLabelManager();
    this.parent = parent;
    this.queueName = queueName;
    this.resourceCalculator = cs.getResourceCalculator();
    
    // must be called after parent and queueName is set
    this.metrics = old != null ? old.getMetrics() :
        QueueMetrics.forQueue(getQueuePath(), parent,
            cs.getConfiguration().getEnableUserMetrics(),
            cs.getConf());
    //root queue 可以访问所有标签,集合只有一个元素(*号)
    // get labels
    this.accessibleLabels = cs.getConfiguration().getAccessibleNodeLabels(getQueuePath());
   
    this.defaultLabelExpression = cs.getConfiguration()
        .getDefaultNodeLabelExpression(getQueuePath());

    // inherit from parent if labels not set
    if (this.accessibleLabels == null && parent != null) {
      this.accessibleLabels = parent.getAccessibleNodeLabels();
    }
    SchedulerUtils.checkIfLabelInClusterNodeLabels(labelManager,
        this.accessibleLabels);
    
    // inherit from parent if labels not set
    if (this.defaultLabelExpression == null && parent != null
        && this.accessibleLabels.containsAll(parent.getAccessibleNodeLabels())) {
      this.defaultLabelExpression = parent.getDefaultNodeLabelExpression();
    }
    // set capacity by labels
    capacitiyByNodeLabels =
        cs.getConfiguration().getNodeLabelCapacities(getQueuePath(), accessibleLabels,
            labelManager);
    // set maximum capacity by labels
    maxCapacityByNodeLabels =
        cs.getConfiguration().getMaximumNodeLabelCapacities(getQueuePath(),
            accessibleLabels, labelManager);
    queueEntity = new PrivilegedEntity(EntityType.QUEUE, getQueuePath());
    authorizer = YarnAuthorizationProvider.getInstance(cs.getConf());
  }
  
  @Override
  public synchronized float getCapacity() {
    return capacity;
  }

  @Override
  public synchronized float getAbsoluteCapacity() {
    return absoluteCapacity;
  }

  @Override
  public float getAbsoluteMaximumCapacity() {
    return absoluteMaxCapacity;
  }

  @Override
  public synchronized float getAbsoluteUsedCapacity() {
    return absoluteUsedCapacity;
  }

  @Override
  public float getMaximumCapacity() {
    return maximumCapacity;
  }

  @Override
  public synchronized float getUsedCapacity() {
    return usedCapacity;
  }

  @Override
  public synchronized Resource getUsedResources() {
    return usedResources;
  }

  public synchronized int getNumContainers() {
    return numContainers;
  }

  @Override
  public synchronized QueueState getState() {
    return state;
  }
  
  @Override
  public QueueMetrics getMetrics() {
    return metrics;
  }
  
  @Override
  public String getQueueName() {
    return queueName;
  }

  public PrivilegedEntity getPrivilegedEntity() {
    return queueEntity;
  }

  @Override
  public synchronized CSQueue getParent() {
    return parent;
  }

  @Override
  public synchronized void setParent(CSQueue newParentQueue) {
    this.parent = (ParentQueue)newParentQueue;
  }
  
  public Set<String> getAccessibleNodeLabels() {
    return accessibleLabels;
  }

  @Override
  public boolean hasAccess(QueueACL acl, UserGroupInformation user) {
    return authorizer.checkPermission(SchedulerUtils.toAccessType(acl),
      queueEntity, user);
  }

  @Override
  public synchronized void setUsedCapacity(float usedCapacity) {
    this.usedCapacity = usedCapacity;
  }
  
  @Override
  public synchronized void setAbsoluteUsedCapacity(float absUsedCapacity) {
    this.absoluteUsedCapacity = absUsedCapacity;
  }

  /**
   * Set maximum capacity - used only for testing.
   * @param maximumCapacity new max capacity
   */
  synchronized void setMaxCapacity(float maximumCapacity) {
    // Sanity check
    CSQueueUtils.checkMaxCapacity(getQueueName(), capacity, maximumCapacity);
    float absMaxCapacity =
        CSQueueUtils.computeAbsoluteMaximumCapacity(maximumCapacity, parent);
    CSQueueUtils.checkAbsoluteCapacity(getQueueName(), absoluteCapacity,
        absMaxCapacity);
    
    this.maximumCapacity = maximumCapacity;
    this.absoluteMaxCapacity = absMaxCapacity;
  }

  @Override
  public float getAbsActualCapacity() {
    // for now, simply return actual capacity = guaranteed capacity for parent
    // queue
    return absoluteCapacity;
  }

  @Override
  public String getDefaultNodeLabelExpression() {
    return defaultLabelExpression;
  }
  
  synchronized void setupQueueConfigs(Resource clusterResource, float capacity,
      float absoluteCapacity, float maximumCapacity, float absoluteMaxCapacity,
      QueueState state, Map<AccessType, AccessControlList> acls,
      Set<String> labels, String defaultLabelExpression,
      Map<String, Float> nodeLabelCapacities,
      Map<String, Float> maximumNodeLabelCapacities,
      boolean reservationContinueLooking)
      throws IOException {
    // Sanity check
    CSQueueUtils.checkMaxCapacity(getQueueName(), capacity, maximumCapacity);
    CSQueueUtils.checkAbsoluteCapacity(getQueueName(), absoluteCapacity,
        absoluteMaxCapacity);

    this.capacity = capacity;
    this.absoluteCapacity = absoluteCapacity;

    this.maximumCapacity = maximumCapacity;
    this.absoluteMaxCapacity = absoluteMaxCapacity;

    this.state = state;

    this.acls = acls;
    
    // set labels
    this.accessibleLabels = labels;
    
    // set label expression
    this.defaultLabelExpression = defaultLabelExpression;
    
    // copy node label capacity
    this.capacitiyByNodeLabels = new HashMap<String, Float>(nodeLabelCapacities);
    this.maxCapacityByNodeLabels =
        new HashMap<String, Float>(maximumNodeLabelCapacities);

    // Update metrics
    CSQueueUtils.updateQueueStatistics(
        resourceCalculator, this, parent, clusterResource, minimumAllocation);
    
    // Check if labels of this queue is a subset of parent queue, only do this
    // when we not root
    if (parent != null && parent.getParent() != null) {
      if (parent.getAccessibleNodeLabels() != null
          && !parent.getAccessibleNodeLabels().contains(RMNodeLabelsManager.ANY)) {
        // if parent isn't "*", child shouldn't be "*" too
        if (this.getAccessibleNodeLabels().contains(RMNodeLabelsManager.ANY)) {
          throw new IOException("Parent's accessible queue is not ANY(*), "
              + "but child's accessible queue is *");
        } else {
          Set<String> diff =
              Sets.difference(this.getAccessibleNodeLabels(),
                  parent.getAccessibleNodeLabels());
          if (!diff.isEmpty()) {
            throw new IOException("Some labels of child queue is not a subset "
                + "of parent queue, these labels=["
                + StringUtils.join(diff, ",") + "]");
          }
        }
      }
    }
    // calculate absolute capacity by each node label
    this.absoluteCapacityByNodeLabels =
        CSQueueUtils.computeAbsoluteCapacityByNodeLabels(
            this.capacitiyByNodeLabels, parent);
    // calculate maximum capacity by each node label
    this.absoluteMaxCapacityByNodeLabels =
        CSQueueUtils.computeAbsoluteMaxCapacityByNodeLabels(
            maximumNodeLabelCapacities, parent);
    //TODO:BUG,没有使用最大集合
    // check absoluteMaximumNodeLabelCapacities is valid
    CSQueueUtils.checkAbsoluteCapacitiesByLabel(getQueueName(),
        absoluteCapacityByNodeLabels, absoluteCapacityByNodeLabels);
    
    this.reservationsContinueLooking = reservationContinueLooking;
  }
  
  protected QueueInfo getQueueInfo() {
    QueueInfo queueInfo = recordFactory.newRecordInstance(QueueInfo.class);
    queueInfo.setQueueName(queueName);
    queueInfo.setAccessibleNodeLabels(accessibleLabels);
    queueInfo.setCapacity(capacity);
    queueInfo.setMaximumCapacity(maximumCapacity);
    queueInfo.setQueueState(state);
    queueInfo.setDefaultNodeLabelExpression(defaultLabelExpression);
    queueInfo.setCurrentCapacity(getUsedCapacity());
    queueInfo.setQueueStatistics(getQueueStatistics());
    return queueInfo;
  }

  public QueueStatistics getQueueStatistics() {
    QueueStatistics stats =
        recordFactory.newRecordInstance(QueueStatistics.class);
    stats.setNumAppsSubmitted(getMetrics().getAppsSubmitted());
    stats.setNumAppsRunning(getMetrics().getAppsRunning());
    stats.setNumAppsPending(getMetrics().getAppsPending());
    stats.setNumAppsCompleted(getMetrics().getAppsCompleted());
    stats.setNumAppsKilled(getMetrics().getAppsKilled());
    stats.setNumAppsFailed(getMetrics().getAppsFailed());
    stats.setNumActiveUsers(getMetrics().getActiveUsers());
    stats.setAvailableMemoryMB(getMetrics().getAvailableMB());
    stats.setAllocatedMemoryMB(getMetrics().getAllocatedMB());
    stats.setPendingMemoryMB(getMetrics().getPendingMB());
    stats.setReservedMemoryMB(getMetrics().getReservedMB());
    stats.setAvailableVCores(getMetrics().getAvailableVirtualCores());
    stats.setAllocatedVCores(getMetrics().getAllocatedVirtualCores());
    stats.setPendingVCores(getMetrics().getPendingVirtualCores());
    stats.setReservedVCores(getMetrics().getReservedVirtualCores());
    stats.setPendingContainers(getMetrics().getPendingContainers());
    stats.setAllocatedContainers(getMetrics().getAllocatedContainers());
    stats.setReservedContainers(getMetrics().getReservedContainers());
    return stats;
  }
  
  @Private
  public Resource getMaximumAllocation() {
    return maximumAllocation;
  }
  
  @Private
  public Resource getMinimumAllocation() {
    return minimumAllocation;
  }
  /**
   * 记录队列在每个标签下已经使用的资源和总共使用的资源
   * @author fulaihua 2018年6月20日 下午4:17:44
   * @param clusterResource
   * @param resource
   * @param nodeLabels
   */
  synchronized void allocateResource(Resource clusterResource, 
      Resource resource, Set<String> nodeLabels) {
    Resources.addTo(usedResources, resource);
    
    // Update usedResources by labels
    if (nodeLabels == null || nodeLabels.isEmpty()) {
      if (!usedResourcesByNodeLabels.containsKey(RMNodeLabelsManager.NO_LABEL)) {
        usedResourcesByNodeLabels.put(RMNodeLabelsManager.NO_LABEL,
            Resources.createResource(0));
      }
      Resources.addTo(usedResourcesByNodeLabels.get(RMNodeLabelsManager.NO_LABEL),
          resource);
    } else {
      for (String label : Sets.intersection(accessibleLabels, nodeLabels)) {
        if (!usedResourcesByNodeLabels.containsKey(label)) {
          usedResourcesByNodeLabels.put(label, Resources.createResource(0));
        }
        Resources.addTo(usedResourcesByNodeLabels.get(label), resource);
      }
    }

    ++numContainers;
    CSQueueUtils.updateQueueStatistics(resourceCalculator, this, getParent(),
        clusterResource, minimumAllocation);
  }
  
  protected synchronized void releaseResource(Resource clusterResource,
      Resource resource, Set<String> nodeLabels) {
    // Update queue metrics
    Resources.subtractFrom(usedResources, resource);

    // Update usedResources by labels
    if (null == nodeLabels || nodeLabels.isEmpty()) {
      if (!usedResourcesByNodeLabels.containsKey(RMNodeLabelsManager.NO_LABEL)) {
        usedResourcesByNodeLabels.put(RMNodeLabelsManager.NO_LABEL,
            Resources.createResource(0));
      }
      Resources.subtractFrom(
          usedResourcesByNodeLabels.get(RMNodeLabelsManager.NO_LABEL), resource);
    } else {
      for (String label : Sets.intersection(accessibleLabels, nodeLabels)) {
        if (!usedResourcesByNodeLabels.containsKey(label)) {
          usedResourcesByNodeLabels.put(label, Resources.createResource(0));
        }
        Resources.subtractFrom(usedResourcesByNodeLabels.get(label), resource);
      }
    }

    CSQueueUtils.updateQueueStatistics(resourceCalculator, this, getParent(),
        clusterResource, minimumAllocation);
    --numContainers;
  }
  
  @Private
  public float getCapacityByNodeLabel(String label) {
    if (StringUtils.equals(label, RMNodeLabelsManager.NO_LABEL)) {
      if (null == parent) {
        return 1f;
      }
      return getCapacity();
    }
    
    if (!capacitiyByNodeLabels.containsKey(label)) {
      return 0f;
    } else {
      return capacitiyByNodeLabels.get(label);
    }
  }
  
  @Private
  public float getAbsoluteCapacityByNodeLabel(String label) {
    if (StringUtils.equals(label, RMNodeLabelsManager.NO_LABEL)) {
      if (null == parent) {
        return 1f; 
      }
      return getAbsoluteCapacity();
    }
    
    if (!absoluteCapacityByNodeLabels.containsKey(label)) {
      return 0f;
    } else {
      return absoluteCapacityByNodeLabels.get(label);
    }
  }
  
  @Private
  public float getAbsoluteMaximumCapacityByNodeLabel(String label) {
    if (StringUtils.equals(label, RMNodeLabelsManager.NO_LABEL)) {
      return getAbsoluteMaximumCapacity();
    }
    if (!absoluteMaxCapacityByNodeLabels.containsKey(label)) {
      return 0f;
    } else {
      return absoluteMaxCapacityByNodeLabels.get(label);
    }
  }
  
  @Private
  public boolean getReservationContinueLooking() {
    return reservationsContinueLooking;
  }
  
  @Private
  public Map<AccessType, AccessControlList> getACLs() {
    return acls;
  }
}
