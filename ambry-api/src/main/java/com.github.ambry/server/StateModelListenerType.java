/**
 * Copyright 2019 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.server;

/**
 * The type of partition state model listener.
 * The state model listeners implement {@link com.github.ambry.clustermap.PartitionStateChangeListener} in different
 * components (i.e. StorageManager, ReplicationManager etc) and take actions when state transition occurs.
 */
public enum StateModelListenerType {
  StorageManagerListener, ReplicationManagerListener, StatsManagerListener, CloudToStoreReplicationManagerListener
}
