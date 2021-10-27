/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.storageSystem;

import static tech.pegasys.teku.infrastructure.async.SyncAsyncRunner.SYNC_RUNNER;

import tech.pegasys.teku.core.ChainBuilder;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.pow.api.TrackingEth1EventsChannel;
import tech.pegasys.teku.protoarray.ProtoArrayStorageChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.storage.api.FinalizedCheckpointChannel;
import tech.pegasys.teku.storage.api.StubFinalizedCheckpointChannel;
import tech.pegasys.teku.storage.api.TrackingChainHeadChannel;
import tech.pegasys.teku.storage.client.ChainUpdater;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.client.StorageBackedRecentChainData;
import tech.pegasys.teku.storage.server.ChainStorage;
import tech.pegasys.teku.storage.server.Database;
import tech.pegasys.teku.storage.server.DepositStorage;
import tech.pegasys.teku.storage.server.ProtoArrayStorage;
import tech.pegasys.teku.storage.server.StateStorageMode;
import tech.pegasys.teku.storage.store.StoreConfig;

public class StorageSystem implements AutoCloseable {
  private final ChainBuilder chainBuilder;
  private final ChainUpdater chainUpdater;
  private final TrackingEth1EventsChannel eth1EventsChannel = new TrackingEth1EventsChannel();

  private final TrackingChainHeadChannel reorgEventChannel;
  private final StubMetricsSystem metricsSystem;
  private final RecentChainData recentChainData;
  private final StateStorageMode storageMode;
  private final CombinedChainDataClient combinedChainDataClient;
  private final ChainStorage chainStorage;
  private final Database database;
  private final RestartedStorageSupplier restartedSupplier;

  private StorageSystem(
      final StubMetricsSystem metricsSystem,
      final TrackingChainHeadChannel reorgEventChannel,
      final StateStorageMode storageMode,
      final ChainStorage chainStorage,
      final Database database,
      final RecentChainData recentChainData,
      final CombinedChainDataClient combinedChainDataClient,
      final RestartedStorageSupplier restartedSupplier,
      final ChainBuilder chainBuilder,
      final Spec spec) {
    this.metricsSystem = metricsSystem;
    this.chainStorage = chainStorage;
    this.recentChainData = recentChainData;
    this.reorgEventChannel = reorgEventChannel;
    this.storageMode = storageMode;
    this.database = database;
    this.combinedChainDataClient = combinedChainDataClient;
    this.restartedSupplier = restartedSupplier;

    this.chainBuilder = chainBuilder;
    chainUpdater = new ChainUpdater(this.recentChainData, this.chainBuilder, spec);
  }

  static StorageSystem create(
      final Database database,
      final RestartedStorageSupplier restartedSupplier,
      final StateStorageMode storageMode,
      final StoreConfig storeConfig,
      final Spec spec,
      final ChainBuilder chainBuilder) {
    final StubMetricsSystem metricsSystem = new StubMetricsSystem();

    // Create and start storage server
    final ChainStorage chainStorageServer = ChainStorage.create(database, spec);

    // Create recent chain data
    final FinalizedCheckpointChannel finalizedCheckpointChannel =
        new StubFinalizedCheckpointChannel();
    final TrackingChainHeadChannel reorgEventChannel = new TrackingChainHeadChannel();
    final RecentChainData recentChainData =
        StorageBackedRecentChainData.createImmediately(
            SYNC_RUNNER,
            metricsSystem,
            storeConfig,
            chainStorageServer,
            chainStorageServer,
            chainStorageServer,
            ProtoArrayStorageChannel.NO_OP,
            finalizedCheckpointChannel,
            reorgEventChannel,
            spec);

    // Create combined client
    final CombinedChainDataClient combinedChainDataClient =
        new CombinedChainDataClient(recentChainData, chainStorageServer, spec);

    // Return storage system
    return new StorageSystem(
        metricsSystem,
        reorgEventChannel,
        storageMode,
        chainStorageServer,
        database,
        recentChainData,
        combinedChainDataClient,
        restartedSupplier,
        chainBuilder,
        spec);
  }

  public StubMetricsSystem getMetricsSystem() {
    return metricsSystem;
  }

  public RecentChainData recentChainData() {
    return recentChainData;
  }

  public ChainBuilder chainBuilder() {
    return chainBuilder;
  }

  public ChainUpdater chainUpdater() {
    return chainUpdater;
  }

  public DepositStorage createDepositStorage() {
    return DepositStorage.create(eth1EventsChannel, database);
  }

  public ProtoArrayStorage createProtoArrayStorage() {
    return new ProtoArrayStorage(database);
  }

  public Database database() {
    return database;
  }

  public ChainStorage chainStorage() {
    return chainStorage;
  }

  public StorageSystem restarted() {
    return restarted(storageMode);
  }

  public StorageSystem restarted(final StateStorageMode storageMode) {
    try {
      database.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return restartedSupplier.restart(storageMode);
  }

  public CombinedChainDataClient combinedChainDataClient() {
    return combinedChainDataClient;
  }

  public TrackingChainHeadChannel reorgEventChannel() {
    return reorgEventChannel;
  }

  public TrackingEth1EventsChannel eth1EventsChannel() {
    return eth1EventsChannel;
  }

  @Override
  public void close() throws Exception {
    this.database.close();
  }

  public interface RestartedStorageSupplier {
    StorageSystem restart(final StateStorageMode storageMode);
  }
}
