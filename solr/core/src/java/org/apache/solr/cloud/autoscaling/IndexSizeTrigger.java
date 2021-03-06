/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.ReplicaInfo;
import org.apache.solr.client.solrj.cloud.autoscaling.Suggester;
import org.apache.solr.client.solrj.cloud.autoscaling.TriggerEventType;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.metrics.SolrCoreMetricManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class IndexSizeTrigger extends TriggerBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ABOVE_BYTES_PROP = "aboveBytes";
  public static final String ABOVE_DOCS_PROP = "aboveDocs";
  public static final String ABOVE_OP_PROP = "aboveOp";
  public static final String BELOW_BYTES_PROP = "belowBytes";
  public static final String BELOW_DOCS_PROP = "belowDocs";
  public static final String BELOW_OP_PROP = "belowOp";
  public static final String COLLECTIONS_PROP = "collections";

  public static final String BYTES_SIZE_PROP = "__bytes__";
  public static final String DOCS_SIZE_PROP = "__docs__";
  public static final String ABOVE_SIZE_PROP = "aboveSize";
  public static final String BELOW_SIZE_PROP = "belowSize";
  public static final String VIOLATION_PROP = "violationType";

  public enum Unit { bytes, docs }

  private long aboveBytes, aboveDocs, belowBytes, belowDocs;
  private CollectionParams.CollectionAction aboveOp, belowOp;
  private final Set<String> collections = new HashSet<>();
  private final Map<String, Long> lastEventMap = new ConcurrentHashMap<>();

  public IndexSizeTrigger(String name) {
    super(TriggerEventType.INDEXSIZE, name);
    TriggerUtils.validProperties(validProperties,
        ABOVE_BYTES_PROP, ABOVE_DOCS_PROP, BELOW_BYTES_PROP, BELOW_DOCS_PROP, COLLECTIONS_PROP);
  }

  @Override
  public void configure(SolrResourceLoader loader, SolrCloudManager cloudManager, Map<String, Object> properties) throws TriggerValidationException {
    super.configure(loader, cloudManager, properties);
    String aboveStr = String.valueOf(properties.getOrDefault(ABOVE_BYTES_PROP, Long.MAX_VALUE));
    String belowStr = String.valueOf(properties.getOrDefault(BELOW_BYTES_PROP, -1));
    try {
      aboveBytes = Long.parseLong(aboveStr);
      if (aboveBytes <= 0) {
        throw new Exception("value must be > 0");
      }
    } catch (Exception e) {
      throw new TriggerValidationException(getName(), ABOVE_BYTES_PROP, "invalid value '" + aboveStr + "': " + e.toString());
    }
    try {
      belowBytes = Long.parseLong(belowStr);
      if (belowBytes < 0) {
        belowBytes = -1;
      }
    } catch (Exception e) {
      throw new TriggerValidationException(getName(), BELOW_BYTES_PROP, "invalid value '" + belowStr + "': " + e.toString());
    }
    // below must be at least 2x smaller than above, otherwise splitting a shard
    // would immediately put the shard below the threshold and cause the mergeshards action
    if (belowBytes > 0 && (belowBytes * 2 > aboveBytes)) {
      throw new TriggerValidationException(getName(), BELOW_BYTES_PROP,
          "invalid value " + belowBytes + ", should be less than half of '" + ABOVE_BYTES_PROP + "' value, which is " + aboveBytes);
    }
    // do the same for docs bounds
    aboveStr = String.valueOf(properties.getOrDefault(ABOVE_DOCS_PROP, Long.MAX_VALUE));
    belowStr = String.valueOf(properties.getOrDefault(BELOW_DOCS_PROP, -1));
    try {
      aboveDocs = Long.parseLong(aboveStr);
      if (aboveDocs <= 0) {
        throw new Exception("value must be > 0");
      }
    } catch (Exception e) {
      throw new TriggerValidationException(getName(), ABOVE_DOCS_PROP, "invalid value '" + aboveStr + "': " + e.toString());
    }
    try {
      belowDocs = Long.parseLong(belowStr);
      if (belowDocs < 0) {
        belowDocs = -1;
      }
    } catch (Exception e) {
      throw new TriggerValidationException(getName(), BELOW_DOCS_PROP, "invalid value '" + belowStr + "': " + e.toString());
    }
    // below must be at least 2x smaller than above, otherwise splitting a shard
    // would immediately put the shard below the threshold and cause the mergeshards action
    if (belowDocs > 0 && (belowDocs * 2 > aboveDocs)) {
      throw new TriggerValidationException(getName(), BELOW_DOCS_PROP,
          "invalid value " + belowDocs + ", should be less than half of '" + ABOVE_DOCS_PROP + "' value, which is " + aboveDocs);
    }

    String collectionsString = (String) properties.get(COLLECTIONS_PROP);
    if (collectionsString != null && !collectionsString.isEmpty()) {
      collections.addAll(StrUtils.splitSmart(collectionsString, ','));
    }
    String aboveOpStr = String.valueOf(properties.getOrDefault(ABOVE_OP_PROP, CollectionParams.CollectionAction.SPLITSHARD.toLower()));
    // TODO: this is a placeholder until SOLR-9407 is implemented
    String belowOpStr = String.valueOf(properties.getOrDefault(BELOW_OP_PROP, CollectionParams.CollectionAction.MERGESHARDS.toLower()));
    aboveOp = CollectionParams.CollectionAction.get(aboveOpStr);
    if (aboveOp == null) {
      throw new TriggerValidationException(getName(), ABOVE_OP_PROP, "unrecognized value of " + ABOVE_OP_PROP + ": '" + aboveOpStr + "'");
    }
    belowOp = CollectionParams.CollectionAction.get(belowOpStr);
    if (belowOp == null) {
      throw new TriggerValidationException(getName(), BELOW_OP_PROP, "unrecognized value of " + BELOW_OP_PROP + ": '" + belowOpStr + "'");
    }
  }

  @Override
  protected Map<String, Object> getState() {
    Map<String, Object> state = new HashMap<>();
    state.put("lastEventMap", lastEventMap);
    return state;
  }

  @Override
  protected void setState(Map<String, Object> state) {
    this.lastEventMap.clear();
    Map<String, Long> replicaVsTime = (Map<String, Long>)state.get("lastEventMap");
    if (replicaVsTime != null) {
      this.lastEventMap.putAll(replicaVsTime);
    }
  }

  @Override
  public void restoreState(AutoScaling.Trigger old) {
    assert old.isClosed();
    if (old instanceof IndexSizeTrigger) {
    } else {
      throw new SolrException(SolrException.ErrorCode.INVALID_STATE,
          "Unable to restore state from an unknown type of trigger");
    }
  }

  @Override
  public void run() {
    synchronized(this) {
      if (isClosed) {
        log.warn(getName() + " ran but was already closed");
        return;
      }
    }
    AutoScaling.TriggerEventProcessor processor = processorRef.get();
    if (processor == null) {
      return;
    }

    // replica name / info + size, retrieved from leaders only
    Map<String, ReplicaInfo> currentSizes = new HashMap<>();

    try {
      ClusterState clusterState = cloudManager.getClusterStateProvider().getClusterState();
      for (String node : clusterState.getLiveNodes()) {
        Map<String, ReplicaInfo> metricTags = new HashMap<>();
        // coll, shard, replica
        Map<String, Map<String, List<ReplicaInfo>>> infos = cloudManager.getNodeStateProvider().getReplicaInfo(node, Collections.emptyList());
        infos.forEach((coll, shards) -> {
          if (!collections.isEmpty() && !collections.contains(coll)) {
            return;
          }
          DocCollection docCollection = clusterState.getCollection(coll);

          shards.forEach((sh, replicas) -> {
            // check only the leader of a replica in active shard
            Slice s = docCollection.getSlice(sh);
            if (s.getState() != Slice.State.ACTIVE) {
              return;
            }
            Replica r = s.getLeader();
            // no leader - don't do anything
            if (r == null) {
              return;
            }
            // find ReplicaInfo
            ReplicaInfo info = null;
            for (ReplicaInfo ri : replicas) {
              if (r.getCoreName().equals(ri.getCore())) {
                info = ri;
                break;
              }
            }
            if (info == null) {
              // probably replica is not on this node?
              return;
            }
            // we have to translate to the metrics registry name, which uses "_replica_nN" as suffix
            String replicaName = Utils.parseMetricsReplicaName(coll, info.getCore());
            if (replicaName == null) { // should never happen???
              replicaName = info.getName(); // which is actually coreNode name...
            }
            String registry = SolrCoreMetricManager.createRegistryName(true, coll, sh, replicaName, null);
            String tag = "metrics:" + registry + ":INDEX.sizeInBytes";
            metricTags.put(tag, info);
            tag = "metrics:" + registry + ":SEARCHER.searcher.numDocs";
            metricTags.put(tag, info);
          });
        });
        if (metricTags.isEmpty()) {
          continue;
        }
        Map<String, Object> sizes = cloudManager.getNodeStateProvider().getNodeValues(node, metricTags.keySet());
        sizes.forEach((tag, size) -> {
          final ReplicaInfo info = metricTags.get(tag);
          if (info == null) {
            log.warn("Missing replica info for response tag " + tag);
          } else {
            // verify that it's a Number
            if (!(size instanceof Number)) {
              log.warn("invalid size value - not a number: '" + size + "' is " + size.getClass().getName());
              return;
            }

            ReplicaInfo currentInfo = currentSizes.computeIfAbsent(info.getCore(), k -> (ReplicaInfo)info.clone());
            if (tag.contains("INDEX")) {
              currentInfo.getVariables().put(BYTES_SIZE_PROP, ((Number) size).longValue());
            } else {
              currentInfo.getVariables().put(DOCS_SIZE_PROP, ((Number) size).longValue());
            }
          }
        });
      }
    } catch (IOException e) {
      log.warn("Error running trigger " + getName(), e);
      return;
    }

    long now = cloudManager.getTimeSource().getTimeNs();

    // now check thresholds

    // collection / list(info)
    Map<String, List<ReplicaInfo>> aboveSize = new HashMap<>();
    currentSizes.entrySet().stream()
        .filter(e -> (
            (Long)e.getValue().getVariable(BYTES_SIZE_PROP) > aboveBytes ||
            (Long)e.getValue().getVariable(DOCS_SIZE_PROP) > aboveDocs
            ) && waitForElapsed(e.getKey(), now, lastEventMap))
        .forEach(e -> {
          ReplicaInfo info = e.getValue();
          List<ReplicaInfo> infos = aboveSize.computeIfAbsent(info.getCollection(), c -> new ArrayList<>());
          if (!infos.contains(info)) {
            if ((Long)e.getValue().getVariable(BYTES_SIZE_PROP) > aboveBytes) {
              info.getVariables().put(VIOLATION_PROP, ABOVE_BYTES_PROP);
            } else {
              info.getVariables().put(VIOLATION_PROP, ABOVE_DOCS_PROP);
            }
            infos.add(info);
          }
        });
    // collection / list(info)
    Map<String, List<ReplicaInfo>> belowSize = new HashMap<>();
    currentSizes.entrySet().stream()
        .filter(e -> (
            (Long)e.getValue().getVariable(BYTES_SIZE_PROP) < belowBytes ||
            (Long)e.getValue().getVariable(DOCS_SIZE_PROP) < belowDocs
            ) && waitForElapsed(e.getKey(), now, lastEventMap))
        .forEach(e -> {
          ReplicaInfo info = e.getValue();
          List<ReplicaInfo> infos = belowSize.computeIfAbsent(info.getCollection(), c -> new ArrayList<>());
          if (!infos.contains(info)) {
            if ((Long)e.getValue().getVariable(BYTES_SIZE_PROP) < belowBytes) {
              info.getVariables().put(VIOLATION_PROP, BELOW_BYTES_PROP);
            } else {
              info.getVariables().put(VIOLATION_PROP, BELOW_DOCS_PROP);
            }
            infos.add(info);
          }
        });

    if (aboveSize.isEmpty() && belowSize.isEmpty()) {
      return;
    }

    // find the earliest time when a condition was exceeded
    final AtomicLong eventTime = new AtomicLong(now);

    // calculate ops
    final List<TriggerEvent.Op> ops = new ArrayList<>();
    aboveSize.forEach((coll, replicas) -> {
      replicas.forEach(r -> {
        TriggerEvent.Op op = new TriggerEvent.Op(aboveOp);
        op.addHint(Suggester.Hint.COLL_SHARD, new Pair<>(coll, r.getShard()));
        ops.add(op);
        Long time = lastEventMap.get(r.getCore());
        if (time != null && eventTime.get() > time) {
          eventTime.set(time);
        }
      });
    });
    belowSize.forEach((coll, replicas) -> {
      if (replicas.size() < 2) {
        return;
      }
      // sort by increasing size
      replicas.sort((r1, r2) -> {
        // XXX this is not quite correct - if BYTES_SIZE_PROP decided that replica got here
        // then we should be sorting by BYTES_SIZE_PROP. However, since DOCS and BYTES are
        // loosely correlated it's simpler to sort just by docs (which better reflects the "too small"
        // condition than index size, due to possibly existing deleted docs that still occupy space)
        long delta = (Long) r1.getVariable(DOCS_SIZE_PROP) - (Long) r2.getVariable(DOCS_SIZE_PROP);
        if (delta > 0) {
          return 1;
        } else if (delta < 0) {
          return -1;
        } else {
          return 0;
        }
      });

      // TODO: MERGESHARDS is not implemented yet. For now take the top two smallest shards
      // TODO: but in the future we probably need to get ones with adjacent ranges.

      // TODO: generate as many MERGESHARDS as needed to consume all belowSize shards
      TriggerEvent.Op op = new TriggerEvent.Op(belowOp);
      op.addHint(Suggester.Hint.COLL_SHARD, new Pair(coll, replicas.get(0).getShard()));
      op.addHint(Suggester.Hint.COLL_SHARD, new Pair(coll, replicas.get(1).getShard()));
      ops.add(op);
      Long time = lastEventMap.get(replicas.get(0).getCore());
      if (time != null && eventTime.get() > time) {
        eventTime.set(time);
      }
      time = lastEventMap.get(replicas.get(1).getCore());
      if (time != null && eventTime.get() > time) {
        eventTime.set(time);
      }
    });

    if (ops.isEmpty()) {
      return;
    }
    if (processor.process(new IndexSizeEvent(getName(), eventTime.get(), ops, aboveSize, belowSize))) {
      // update last event times
      aboveSize.forEach((coll, replicas) -> {
        replicas.forEach(r -> lastEventMap.put(r.getCore(), now));
      });
      belowSize.forEach((coll, replicas) -> {
        lastEventMap.put(replicas.get(0).getCore(), now);
        lastEventMap.put(replicas.get(1).getCore(), now);
      });
    }
  }

  private boolean waitForElapsed(String name, long now, Map<String, Long> lastEventMap) {
    Long lastTime = lastEventMap.computeIfAbsent(name, s -> now);
    long elapsed = TimeUnit.SECONDS.convert(now - lastTime, TimeUnit.NANOSECONDS);
    log.trace("name={}, lastTime={}, elapsed={}", name, lastTime, elapsed);
    if (TimeUnit.SECONDS.convert(now - lastTime, TimeUnit.NANOSECONDS) < getWaitForSecond()) {
      return false;
    }
    return true;
  }

  public static class IndexSizeEvent extends TriggerEvent {
    public IndexSizeEvent(String source, long eventTime, List<Op> ops, Map<String, List<ReplicaInfo>> aboveSize,
                          Map<String, List<ReplicaInfo>> belowSize) {
      super(TriggerEventType.INDEXSIZE, source, eventTime, null);
      properties.put(TriggerEvent.REQUESTED_OPS, ops);
      properties.put(ABOVE_SIZE_PROP, aboveSize);
      properties.put(BELOW_SIZE_PROP, belowSize);
    }
  }

}
